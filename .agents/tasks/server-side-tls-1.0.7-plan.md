# Server-side TLS — 1.0.7 implementation plan

Target: Appian 26.9, Java 17, plug-in `de.hock.mqtt`, version `1.0.6` → `1.0.7`.
Baseline: commit `8621e99` (working tree clean). Known-good deployed reference: `68e15e8` (1.0.6).

Scope: **server-side TLS only** — the plug-in verifies the broker. Mutual TLS (client certificate +
private key) is out of scope and stays out of scope in this release.

> **Git:** this plan writes no commits. Everything below is a working-tree change; the user owns
> git. Recovery references use `git show <sha>:<path>`.

---

## 0. Section 1 — the security crux, resolved here with evidence

### 0.1 Finding: Paho 1.2.5 **does** perform hostname verification on `ssl://`, by default

The brief's premise — that `setHttpsHostnameVerificationEnabled` applies only to `wss://` — is
**false for Paho 1.2.5**. Measured against
`~/.m2/repository/org/eclipse/paho/org.eclipse.paho.client.mqttv3/1.2.5/org.eclipse.paho.client.mqttv3-1.2.5.jar`.

**Evidence 1 — the `ssl` scheme factory propagates the flag.**
`javap -p -c org.eclipse.paho.client.mqttv3.internal.SSLNetworkModuleFactory`:

- `getSupportedUriSchemes()` builds a one-element set from `ldc "ssl"` — this class is the `ssl://`
  factory, and `META-INF/services/org.eclipse.paho.client.mqttv3.spi.NetworkModuleFactory` lists it
  beside `TCPNetworkModuleFactory`, which already resolves on a live instance.
- `createNetworkModule(URI, MqttConnectOptions, String)`, offsets 159–165:

```
159: aload         9     // the SSLNetworkModule just constructed
161: aload_2             // MqttConnectOptions
162: invokevirtual MqttConnectOptions.isHttpsHostnameVerificationEnabled:()Z
165: invokevirtual SSLNetworkModule.setHttpsHostnameVerificationEnabled:(Z)V
```

**Evidence 2 — the flag defaults to `true`.**
`javap -p -c org.eclipse.paho.client.mqttv3.MqttConnectOptions`, constructor offsets 31–33:

```
31: aload_0
32: iconst_1
33: putfield  #251  // Field httpsHostnameVerificationEnabled:Z
```

(`SSLNetworkModule`'s own constructor seeds it `false` at offsets 21–23, but the factory overwrites
it with the options value immediately, as Evidence 1 shows.)

**Evidence 3 — `SSLNetworkModule.start()` applies endpoint identification before the handshake.**
`javap -p -c org.eclipse.paho.client.mqttv3.internal.SSLNetworkModule`, `start()`:

```
 35..83:  new SSLParameters; setServerNames([SNIHostName(host)]); socket.setSSLParameters(...)
 90: getfield httpsHostnameVerificationEnabled:Z
 94: ifeq 126                                  // skip when false
 97: new SSLParameters
105: ldc "HTTPS"
108: invokevirtual SSLParameters.setEndpointIdentificationAlgorithm:(Ljava/lang/String;)V
119: invokevirtual SSLSocket.setSSLParameters:(Ljavax/net/ssl/SSLParameters;)V
133: invokevirtual SSLSocket.startHandshake:()V
136..231: if (hostnameVerifier != null && !httpsHostnameVerificationEnabled) -> post-handshake
          HostnameVerifier.verify(host, session); on false: session.invalidate(), socket.close(),
          throw SSLPeerUnverifiedException
Exception table:  97..122 -> catch java/lang/NoSuchMethodError   (silently swallowed)
```

Two consequences worth carrying into the code comments:

- The custom `MqttConnectOptions.setSSLHostnameVerifier` path runs **only when the HTTPS flag is
  false**. Setting both is contradictory. **Do not set a `HostnameVerifier`.**
- The `setEndpointIdentificationAlgorithm` block is wrapped in `catch (NoSuchMethodError)`, so on a
  JRE older than 7 it would be skipped silently. Irrelevant on Java 17, but it is the reason the
  plug-in sets the flag **explicitly** rather than trusting a default it cannot see.

**Evidence 4 — end-to-end, against a real Paho client.** A scratch probe
(`/tmp/paho-probe/Handshake.java`, throwaway, outside the repo) stood up a loopback
`SSLServerSocket` presenting a certificate signed by a throwaway CA, and connected with
`new MqttClient("ssl://localhost:<port>", ...)` plus `options.setSocketFactory(ourFactory)` and
otherwise default options:

```
server cert SAN=localhost
   REAL PAHO (default opts) -> MqttException rc=32109 msg=Connection lost
                               cause=java.io.EOFException        <- TLS succeeded, no MQTT behind it
server cert SAN=wrong.example.com
   REAL PAHO (default opts) -> MqttException rc=0 cause=javax.net.ssl.SSLHandshakeException:
                               (certificate_unknown) No subject alternative DNS name matching
                               localhost found.
```

The wrong-hostname certificate was **rejected** by stock Paho 1.2.5 on `ssl://`. The same probe
with endpoint identification removed accepted it, which is exactly the weakness that would exist if
the flag were off.

**Evidence 5 — the hostname survives Paho's socket-creation pattern.**
`TCPNetworkModule.start()` (inherited by `SSLNetworkModule`) does:

```
71..79:  socket = factory.createSocket()                     // NO-ARG overload
82..95:  socket.connect(new InetSocketAddress(host, port), conTimeout*1000)
```

An unconnected `SSLSocket` has no peer host, which is the classic way hostname verification
silently degrades to an IP-address check. It does not happen here: `InetSocketAddress(String,int)`
retains the original host name, and `SSLSocketImpl.connect` adopts it. The probe printed
`peerHost=localhost` for the socket created exactly this way, and the wrong-SAN handshake failed
naming `localhost` — so verification runs against the host from the broker URI, which is what a
designer typed.

**Conclusion.** In mode 2 and mode 3 the broker is authenticated as *"presents a certificate chain
this trust anchor signed, whose SAN matches the configured host"*. That is the full guarantee, not
the weakened one the brief feared. It must be stated in the README as something **measured**, with
the one caveat that it depends on `httpsHostnameVerificationEnabled` staying true — which the
plug-in now sets explicitly instead of inheriting.

### 0.2 Decision: **no `SSLSocketFactory` wrapper.** Set the flag instead

Implement:

```java
options.setHttpsHostnameVerificationEnabled(true);   // in MqttPublisher.buildConnectOptions
```

unconditionally (the flag is read only by the `ssl`/`wss` factories, so it is inert when TLS is
off), and never call `setSSLHostnameVerifier`.

Reasons a wrapper is rejected, in order of weight:

1. **It is redundant.** Paho already applies `HTTPS` endpoint identification after connect and
   before `startHandshake()` (0.1, Evidence 3 + 4). A wrapper would set the same parameter earlier.
2. **A wrapper is how 1.0.3's TLS would have failed on the first connection — a third, unrecorded
   defect in that code.** `git show d023a76:src/main/java/de/hock/mqtt/protocol/ConnectOptionsFactory.java`
   contains `TlsVersionConstrainingSocketFactory`, which overrides six `createSocket` overloads but
   **not the no-argument `createSocket()`** — the only one Paho uses (0.1, Evidence 5). The
   inherited `javax.net.SocketFactory.createSocket()` throws:

   ```
   javap -c javax.net.SocketFactory  ->  new SocketException("Unconnected sockets not implemented")
                                         initCause(new UnsupportedOperationException())
   ```

   So every TLS connection in 1.0.3 would have died with `SocketException: Unconnected sockets not
   implemented`. Add this to the list of 1.0.3 TLS defects (alongside the never-resolved document
   id and the unusable passphrase) in the release record and in steering §5.
3. **Measured: a wrapper would not even be defeated, so there is nothing to defend against.** Probe
   `/tmp/paho-probe/Probe.java` on Corretto 17.0.20.1 and on JDK 23.0.1: pre-setting
   `endpointIdentificationAlgorithm=HTTPS`, then applying Paho's fresh `SSLParameters` carrying only
   `serverNames`, reads back `eia=HTTPS` and keeps the SNI name — a `null` algorithm in an incoming
   `SSLParameters` does not clear the configured one. The two concerns are independent; neither
   needs the other.

**For the record, had a wrapper been needed**, the complete override surface is seven
`createSocket` overloads plus two cipher-suite methods, enumerated from
`javap javax.net.ssl.SSLSocketFactory` and `javap javax.net.SocketFactory` on Corretto 17:

| Declared on | Signature | Abstract? |
|---|---|---|
| `javax.net.SocketFactory` | `createSocket()` | no — **base throws `SocketException`**; the only one Paho calls |
| `javax.net.SocketFactory` | `createSocket(String, int)` | yes |
| `javax.net.SocketFactory` | `createSocket(String, int, InetAddress, int)` | yes |
| `javax.net.SocketFactory` | `createSocket(InetAddress, int)` | yes |
| `javax.net.SocketFactory` | `createSocket(InetAddress, int, InetAddress, int)` | yes |
| `javax.net.ssl.SSLSocketFactory` | `createSocket(Socket, String, int, boolean)` | yes |
| `javax.net.ssl.SSLSocketFactory` | `createSocket(Socket, InputStream, boolean)` | no |
| `javax.net.ssl.SSLSocketFactory` | `getDefaultCipherSuites()` | yes — must delegate |
| `javax.net.ssl.SSLSocketFactory` | `getSupportedCipherSuites()` | yes — must delegate |

`getDefaultCipherSuites()` / `getSupportedCipherSuites()` must delegate verbatim: Paho reads neither
unless `setSSLProperties` was used (`SSLSocketFactoryFactory.getEnabledCipherSuites`), which this
plug-in never calls, but a non-delegating override would silently narrow negotiation.

**No `ssl://` path bypasses the factory.** `MqttAsyncClient.createNetworkModule` →
`NetworkModuleService.createInstance` → ServiceLoader → `SSLNetworkModuleFactory.createNetworkModule`,
which uses `options.getSocketFactory()` when non-null and only builds its own
`SSLSocketFactoryFactory` when it is null (offsets 54–122). `serverURIs` is never set, so there is
no second URI to dispatch on.

### 0.3 Decision: no "trust all" escape hatch, and no argument for one

Not implemented, not flagged, not test-only. Mode 3 covers self-signed and private-CA brokers
properly by trusting the specific CA, and the tests generate their own CA at run time (§5), so no
test needs to disable verification either. Nothing to report beyond that.

---

## 1. Section 2 — where the new code goes

### 1.1 One new class: `de.hock.mqtt.TlsSupport`. Source count 7 → **8**

`src/main/java/de/hock/mqtt/TlsSupport.java`, root package, beside the two Paho call sites whose
behaviour it configures — the same reasoning that put `ConnectionConfigValidator` there at 1.0.6
(a `tls` package would imply a layer; this is one small utility class).

It is **8, not 9**: no second class is needed because the failure signal is the JDK's own
`java.security.GeneralSecurityException`. A bespoke exception class would add a file, a source-count
change and nothing else — `GeneralSecurityException` is checked, is the supertype of everything
`CertificateFactory`, `KeyStore`, `TrustManagerFactory` and `SSLContext` throw, and both integrations
already have a place to catch it.

`TlsSupport` API (all `static`; the class is `final` with a private constructor, matching
`MqttPublisher` / `MqttSubscriber` / `ConnectionConfigValidator`):

| Member | Visibility | Purpose |
|---|---|---|
| `PEM_CERTIFICATE_HEADER = "-----BEGIN CERTIFICATE-----"` | `static final String`, package-private | shared by the validator's "is this even PEM" check |
| `brokerUri(Boolean tlsEnabled, String brokerHost, int brokerPort)` | `public static String` | `"ssl://h:p"` when `Boolean.TRUE`, else `"tcp://h:p"`; `null` → `tcp://` |
| `isTlsEnabled(Boolean)` | package-private `static boolean` | the single `null`-means-false rule, so URI and socket factory can never disagree |
| `parseCertificates(String pem)` | package-private `static List<X509Certificate>` | `throws GeneralSecurityException`; never echoes input |
| `trustManagers(String caPem)` | package-private `static TrustManager[]` | the test seam that proves trust is *restricted* |
| `socketFactory(String caPem)` | `public static SSLSocketFactory` | `throws GeneralSecurityException` |

**Hard rules inside this class**, each a line of its Javadoc as well as of the code:

- `SSLContext.getInstance("TLS")` — **not** a pinned version. Justification, measured on Corretto
  17.0.20.1: a socket from such a context reports `enabledProtocols=[TLSv1.3, TLSv1.2]`; TLSv1 and
  TLSv1.1 are already off via the JDK's `jdk.tls.disabledAlgorithms` default. Pinning `"TLSv1.2"`,
  as 1.0.3 did, would **exclude TLS 1.3**, and the `setEnabledProtocols` wrapper 1.0.3 added to
  compensate is the broken wrapper of §0.2. `"TLS"` lets the JDK negotiate the best available and
  inherits the platform's own deprecation schedule.
- `KeyStore.getInstance(KeyStore.getDefaultType())` + `load(null, null)` — in memory. (Measured:
  default type is `pkcs12` on Corretto 17. Nothing depends on that.)
- Blank / `null` CA PEM → `TrustManagerFactory.getInstance(getDefaultAlgorithm()).init((KeyStore) null)`,
  which is the **JVM default trust store**. Returning a real `TrustManager[]` rather than `null`
  (which `SSLContext.init` would also accept) is deliberate: it makes mode 2 readable back in a
  test. Measured on Corretto 17: 146 accepted issuers, and it **rejects** a throwaway self-signed
  fixture. Note in the Javadoc that `init((KeyStore) null)` *reads* the JSSE trust-store system
  properties if an Appian administrator set them — a read, never a write, and a feature.
- Non-blank CA PEM → every parsed certificate under its own alias (`"ca-0"`, `"ca-1"`, …), then
  `TrustManagerFactory.init(thatKeyStore)`. Chain support falls out of
  `CertificateFactory.generateCertificates`, which returns all `-----BEGIN CERTIFICATE-----` blocks
  (measured: a two-block PEM parses to 2; a CRLF-padded PEM with leading/trailing whitespace parses
  fine).
- **An empty parse result is an error, not an empty trust store.** Measured:
  `generateCertificates("")` returns **0 certificates without throwing**, while `"123456"` and
  `"not a certificate at all"` throw `CertificateException: No certificate data found`. A
  zero-length result must be rethrown as a `GeneralSecurityException`, or mode 3 would silently
  become "trust nothing" or, worse, be mistaken for mode 2.
- **No message may carry certificate text.** `parseCertificates` catches `CertificateException` and
  throws a `GeneralSecurityException` with a **fixed** sentence and the original as `cause`. The
  integrations never put `e.getMessage()` of a TLS failure into a response (§3.3).
- No filesystem, no `System.setProperty`. The Javadoc must assert this **without writing the
  literal tokens** `System.setProperty`, `javax.net.ssl.trustStore` or `javax.net.ssl.keyStore`, so
  the §4 greps keep their documented single hit in `MqttPublisher`. Phrase it as "never mutates
  JVM-global JSSE settings and never writes a key store to disk".
- The Javadoc must also avoid the token `isExpressionable` (it has no reason to use it).
- Nothing in `TlsSupport` touches Paho. The steering invariant "exactly two Paho call sites" is
  preserved: `MqttConnectOptions.setSocketFactory` is called from `MqttPublisher.buildConnectOptions`,
  which already exists and is already shared.

### 1.2 Where the scheme choice and the options assembly live

- **URI scheme:** `TlsSupport.brokerUri(...)`, called from the two places that build a client:
  `MqttPublisher.publish` and `MqttSubscriber.subscribe` (each currently inlines
  `"tcp://" + brokerHost + ":" + brokerPort`). One helper, two one-line call sites.
- **`SSLContext` / socket factory:** applied in **`MqttPublisher.buildConnectOptions`**, which both
  call sites already use. This is the load-bearing choice: it keeps the steering invariant that the
  configuration→Paho mapping exists in exactly one place, and it makes it impossible for a call
  site to forget TLS, because neither can build options any other way.
- Consequence: `buildConnectOptions` stops being total. It gains
  `throws GeneralSecurityException`. Its two out-of-range *fallbacks* stay as they are — that
  "never throws for out-of-range input" property is unchanged; only malformed crypto material
  throws, and the validator rejects that before the integration gets here.

### 1.3 Signature changes, with the argument-order rule

Three `Boolean`s in a row is how a silent argument swap happens, and no offline test can catch one.
So the two new parameters go **where a `String` separates them from the session `Boolean`s**:

```java
// MqttPublisher
public static void publish(
    String brokerHost, int brokerPort,
    Boolean tlsEnabled, String caCertificatePem,          // <- inserted after brokerPort
    String clientIdentifier, String username, char[] password,
    String topic, byte[] payload, int qos,
    Integer connectionTimeoutSeconds, Integer keepAliveSeconds,
    Boolean cleanSession, Boolean autoReconnectEnabled)
    throws MqttException, GeneralSecurityException

static MqttConnectOptions buildConnectOptions(
    Boolean tlsEnabled, String caCertificatePem,          // <- FIRST, adjacent to a String
    String username, char[] password,
    Integer connectionTimeoutSeconds, Integer keepAliveSeconds,
    Boolean cleanSession, Boolean autoReconnectEnabled)
    throws GeneralSecurityException

// MqttSubscriber
public static List<Map<String, Object>> subscribe(
    String brokerHost, int brokerPort,
    Boolean tlsEnabled, String caCertificatePem,          // <- inserted after brokerPort
    String clientIdentifier, String username, char[] password,
    String topicFilter, int qos, int maxWaitSeconds, int maxMessageCount,
    Integer connectionTimeoutSeconds, Integer keepAliveSeconds,
    Boolean cleanSession, Boolean autoReconnectEnabled)
    throws MqttException, GeneralSecurityException
```

### 1.4 Mode table (the contract the tests pin)

| Mode | `tlsEnabled` | `caCertificate` | URI | `options.getSocketFactory()` | Trust anchors |
|---|---|---|---|---|---|
| 1 | `false` / absent | ignored *(see §4 rule 5)* | `tcp://host:port` | **`null`** | n/a |
| 2 | `true` | blank | `ssl://host:port` | non-null | JVM default trust store (146 issuers on Corretto 17) |
| 3 | `true` | PEM | `ssl://host:port` | non-null | **only** the pasted certificate(s) |

Hostname verification applies in modes 2 and 3 (§0.1). The port is the designer's responsibility:
1883 plain, 8883 TLS by convention, and **the plug-in never changes the configured port** when the
checkbox flips. Say so in the field instruction text and in the README.

---

## 2. Section 3 — the field wiring

### 2.1 The eleven config keys, in display order

The two new fields go **immediately after `brokerPort`**: transport settings belong together, and
the port instruction then sits next to the checkbox that changes which port is right. Descriptor
order is presentation only — state is keyed by name — so reordering cannot disturb a saved
connected system.

```
 1 brokerHost                 Text            (unchanged, isRequired true)
 2 brokerPort                 Integer         (unchanged, isRequired true; instruction text updated)
 3 tlsEnabled                 Boolean         NEW
 4 caCertificate              Paragraph       NEW
 5 clientIdentifier           Text
 6 username                   Text
 7 password                   EncryptedText, masked
 8 connectionTimeoutSeconds   Integer
 9 keepAliveSeconds           Integer
10 cleanSession               Boolean
11 autoReconnectEnabled       Boolean
```

New constants on `MqttConnectedSystemTemplate`, keeping the "the only `public static final String`
constants on this class are the config keys" property the steering §4 bundle check depends on:

```java
/** Field key: enable TLS, switching the broker URI from tcp:// to ssl://. */
public static final String TLS_ENABLED = "tlsEnabled";

/** Field key: PEM-encoded broker CA certificate; blank means the JVM default trust store. */
public static final String CA_CERTIFICATE = "caCertificate";
```

### 2.2 The exact builder calls — no expressionable call anywhere

```java
BooleanPropertyDescriptor.builder()
    .key(TLS_ENABLED)
    .label("Enable TLS")
    .instructionText(
        "Connect over TLS (ssl://) instead of plain TCP. Set the port yourself: 1883 is the"
            + " convention for plain TCP and 8883 for TLS. Enabling this does not change the port.")
    .isRequired(false)
    .build(),
ParagraphPropertyDescriptor.builder()
    .key(CA_CERTIFICATE)
    .label("CA Certificate")
    .instructionText(
        "Optional. Paste the broker's CA certificate as PEM text, starting with"
            + " -----BEGIN CERTIFICATE-----. Leave blank to trust the certificate authorities the"
            + " Appian server already trusts, which is correct for a broker with a publicly"
            + " trusted certificate. A certificate chain with several PEM blocks is accepted.")
    .isRequired(false)
    .build(),
```

- **Verified safe, not assumed.** `javap -p -c` on
  `ParagraphPropertyDescriptor$ParagraphPropertyDescriptorBuilder` in
  `connected-systems-core-1.9.1.jar`: the constructor calls only
  `PropertyDescriptorBuilder.type(SystemType.PARAGRAPH)` and `data.put("paragraphHeight", TALL)`.
  No `isExpressionable` call, and `build()` invokes no `Guard`. `BooleanPropertyDescriptor` was
  already measured non-expressionable at 1.0.5.
- `.height(...)` exists (`ParagraphHeight.SHORT|MEDIUM|TALL`) and defaults to `TALL` — leave it
  alone; `TALL` is right for a PEM block.
- `DisplayHint` offers only `NORMAL` and `EXPRESSION`, so `Paragraph` is the only multi-line input
  available. **Do not** reach for `DocumentPropertyDescriptor`: its builder force-sets the
  expressionable flag and `Guard.checkPropertyIsExpressionable` refuses to build without it, which
  is the exact combination that broke the 1.0.2 → 1.0.3 deploy.
- Neither call touches `isExpressionable`, so
  `grep -c isExpressionable .../MqttConnectedSystemTemplate.java` stays **0**. The class Javadoc
  must keep avoiding the literal token while it gains a sentence about the two new fields.

Also update `brokerPort`'s `instructionText` to
`"Broker TCP port. Typically 1883 for plain TCP and 8883 for TLS."` (placeholder stays `1883`).

### 2.3 Effective defaults follow from measured `StateGenerator` seeding

| Type | `StateGenerator` seeds | Brand-new page renders | Absent value at run time |
|---|---|---|---|
| `BOOLEAN` | `Boolean.FALSE` | **Enable TLS unchecked** | `booleanValue` → `null` → treated `false` |
| `PARAGRAPH` | `""` | **CA Certificate empty** | `textValue` → `""` or `null`; both blank |

So the required default (`tlsEnabled` effectively FALSE, `caCertificate` blank) is what the platform
already produces. **No seeding, no first-render gate, no `setValue` call** — the machinery 1.0.4
removed stays removed. The only configuration that reads `null` rather than `false`/`""` is one
saved by 1.0.6 before the fields existed; `isTlsEnabled(null) == false` handles it, which is also
the upgrade-safety story: **an existing connected system keeps working over `tcp://` untouched.**

### 2.4 Resource bundle: 22 keys → **24**, 16 field labels → **18**

```properties
MqttConnectedSystemTemplate.tlsEnabled.label=Enable TLS
MqttConnectedSystemTemplate.caCertificate.label=CA Certificate
```

Insert them between `brokerPort` and `clientIdentifier` so the file mirrors display order. Update
the file's header comment: "nine constants" → "eleven constants", "22 keys, 16 of them field
labels" → "24 keys, 18 of them field labels". Extend
`MqttConnectedSystemTemplate.description` to mention optional TLS.

The steering §4 bundle check works unchanged once the constants exist (verified clean against the
current tree: 22 keys, no diff).

---

## 3. Section 4 — run-time validation

Design-time validation stays impossible (steering §5 item 7: Appian discards connected system field
errors, measured). Nothing below touches `setErrorAtPath`; `isRequired` remains the only design-time
guard and neither new field uses it.

### 3.1 `ConnectionConfigValidator.firstError` — three new rules, appended after the existing four

Existing rules 1–4 (host, port, connect timeout, keep-alive) keep their exact wording and order, so
no existing test message changes. New constant:
`static final String PEM_HEADER = TlsSupport.PEM_CERTIFICATE_HEADER;` (same package, no import, so
the validator's SDK import count stays 1).

Read once: `Boolean tlsEnabled = booleanValue(state, TLS_ENABLED);`
`String caCertificate = textValue(state, CA_CERTIFICATE);`
`boolean hasCa = caCertificate != null && !caCertificate.isBlank();`

**Rule 5 — CA supplied while TLS is off (hard error).**

```
"A CA certificate is configured but TLS is disabled on the MQTT Broker connected system. "
+ "Enable TLS, or clear the CA Certificate field."
```

**Rule 6 — TLS on, CA supplied, value is not PEM at all** (`!caCertificate.contains(PEM_HEADER)`):

```
"The CA Certificate on the MQTT Broker connected system is not a PEM certificate. It must start "
+ "with -----BEGIN CERTIFICATE----- and end with -----END CERTIFICATE-----. Paste the certificate "
+ "text itself, not a document id, a file name or a path."
```

and when the trimmed value consists only of digits (`trimmed.matches("\\d{1,20}")`), append:

```
+ " The configured value looks like an Appian document id."
```

This is the stale-document-id case the 1.0.3 design produced, and the message must **not** repeat
the digits.

**Rule 7 — TLS on, CA has the header, but it will not parse**: call
`TlsSupport.parseCertificates(caCertificate)` in a `try`; on `GeneralSecurityException` (and on an
empty result, which `TlsSupport` already converts to one) return:

```
"The CA Certificate on the MQTT Broker connected system could not be parsed as an X.509 "
+ "certificate. Check that the whole PEM block was pasted, including the BEGIN and END lines, and "
+ "that no characters were lost."
```

The caught exception's message is **discarded**, not appended. All three messages are well under
the 500-character truncation limit, name no field content, and name the field by its rendered label.

### 3.2 Judgement call: rule 5 is a hard error — recommended, and that is what to implement

A CA pasted while TLS is off is **configured but inert**, which is precisely the failure mode this
project already condemned in 1.0.3's `maxReconnectAttempts` (a field nothing enforced). Appian gives
no design-time warning channel and `IntegrationResponse.forSuccess` has no warning slot, so the
only way to tell a designer is to fail the call.

Trade-off, stated honestly in the README: a designer who unchecks **Enable TLS** to test a plain
connection must also clear the CA field, and until they do, **both** integrations fail with
`Invalid configuration`. That is a visible, self-explaining, one-click-to-fix stop.

Alternatives considered and rejected:
- *Ignore silently* — the inert-configuration trap; rejected.
- *Ignore and log* — the plug-in has no logging by design (steering §3); rejected.
- *Return success with a warning field in the result map* — changes the publish/subscribe output
  shape for every caller to carry a message almost nobody will read; rejected.

If the reviewing step disagrees, the smallest alternative is to drop rule 5 and add one sentence to
the README's field table. **Do not implement both.** The plan is: implement rule 5 as a hard error.

### 3.3 The integrations

Both `MqttPublishIntegration.execute` and `MqttSubscribeIntegration.execute`:

1. Read the two new values next to the other connected-system reads:
   `Boolean tlsEnabled = MqttConnectedSystemTemplate.booleanValue(connection, MqttConnectedSystemTemplate.TLS_ENABLED);`
   `String caCertificate = MqttConnectedSystemTemplate.textValue(connection, MqttConnectedSystemTemplate.CA_CERTIFICATE);`
2. Keep the existing guard order unchanged — blank topic / topic filter, then
   `ConnectionConfigValidator.firstError`, then the input-range check. The TLS rules therefore run
   **before any socket is opened**, inside the existing `Invalid configuration` branch.
3. Pass both values through to `publish` / `subscribe`.
4. Add a **separate** catch clause, ahead of the existing one, with a fixed message:

```java
} catch (GeneralSecurityException e) {
  return error(
      "Invalid configuration",
      "The TLS configuration of the MQTT Broker connected system could not be applied."
          + " Check the CA Certificate field.");
}
```

No `e.getMessage()`, no `e.toString()`, no certificate text — this is the clause that guarantees the
"never echo key material" rule even if a future JDK adds input to a crypto exception message. It is
defence in depth: rule 7 already rejected unparseable PEM.

The existing `catch (MqttException | RuntimeException e)` branch keeps including `e.getMessage()`,
which for a TLS failure is Paho's own wording (e.g. `SSLHandshakeException: ... No subject
alternative DNS name matching <host> found`). That names the host, which the designer typed, and no
certificate material — acceptable, and genuinely the most useful diagnostic in the release. Note it
in the README so the message is recognisable.

---

## 4. Section 5 — the test plan: 23 → **35** tests in **nine** classes

No test needs a broker, a network route off the machine, or an Appian runtime. Two tests bind an
**ephemeral loopback port on 127.0.0.1** (§4.3) — that is the only socket activity.

### 4.1 Certificate fixtures with public JDK 17 APIs only

**There is no public JDK API that creates an X.509 certificate.** `CertificateFactory` only parses;
`sun.security.x509.CertAndKeyGen` is not exported from `java.base`. So the fixture builds the
certificate **DER by hand** and signs it with `java.security.Signature` — all public API, no
BouncyCastle, no `--add-exports`, no `keytool` subprocess, no committed key material, nothing
written to disk.

**This is already proven to work.** A throwaway probe (`/tmp/paho-probe/CertGen.java`, outside the
repo) ran on Corretto 17.0.20.1 and produced:

```
caCert subject=CN=Test MQTT CA version=3 basicConstraints=2147483647
leaf SANs=[[2, broker.example.com]]
trust manager type=sun.security.ssl.X509TrustManagerImpl acceptedIssuers=1
ACCEPT leaf signed by the configured CA: OK
REJECT rogue: OK (ValidatorException)
chain PEM parses to 2 certificates
```

New file: `src/test/java/de/hock/mqtt/CertificateFixtures.java` — package `de.hock.mqtt` so it can
reach `TlsSupport`'s package-private seams. **It must not be named `Test*`, `*Test`, `*Tests` or
`*TestCase`**, or Surefire's default includes would try to run it as a test class
(`TestCertificates.java` would match `**/Test*.java` — hence `CertificateFixtures`).

Shape, mirroring the verified probe:

- A ~90-line private DER writer: `tlv(tag, value)` with short/long length forms, plus `seq`, `set`,
  `integer`, `bool`, `nul`, `octets`, `bitString`, `utf8`, `utcTime` (`yyMMddHHmmss'Z'`, UTC), `oid`
  (base-128 subidentifiers), `explicit(n, inner)` for `[n] EXPLICIT`.
- `AlgorithmIdentifier` for `sha256WithRSAEncryption` = `SEQUENCE { OID 1.2.840.113549.1.1.11, NULL }`.
- `Name` = `SEQUENCE OF SET OF SEQUENCE { OID 2.5.4.3, UTF8String cn }`.
- `subjectPublicKeyInfo` is `publicKey.getEncoded()` **verbatim** — an RSA `X509EncodedKeySpec`
  encoding already *is* a `SubjectPublicKeyInfo`.
- Extensions: `basicConstraints` (`2.5.29.19`, critical, `SEQUENCE { BOOLEAN TRUE }` for a CA, empty
  `SEQUENCE` for a leaf), `keyUsage` (`2.5.29.15`, critical, `BIT STRING 0x06` = keyCertSign|cRLSign)
  on the CA only, and `subjectAltName` (`2.5.29.17`, non-critical,
  `SEQUENCE { [2] IA5String dnsName }` — context tag `0x82`) on the leaf.
- Validity: `now - 1h` to `now + 30d`, so nothing can expire mid-suite and no long-dated artefact
  is produced.
- `KeyPairGenerator.getInstance("RSA").initialize(2048)` per fixture; **private keys exist only as
  in-memory `PrivateKey` objects inside the test JVM** and are never serialised, printed or stored.
- Public helpers: `newCa(String cn)`, `newLeaf(String cn, String dnsSan, Ca issuer)`,
  `newSelfSignedCa(String cn, String dnsSan)` (for the unrelated/rogue anchor), `toPem(cert)`
  (`Base64.getMimeEncoder(64, "\n")` wrapped in BEGIN/END lines), and
  `serverSocketFactory(leaf, key, ca)` for §4.3.

**Fallback, if the ASN.1 writer resists.** Do not invent a second scheme and do not commit a
fixture. Fall back in this order: (a) add
`--add-exports java.base/sun.security.x509=ALL-UNNAMED` to a Surefire `argLine` and use
`CertAndKeyGen` — ugly, and it reintroduces a Surefire `<configuration>` block the project removed
at 1.0.4, so record it in the release notes if used; (b) keep only the single-certificate proof:
build the trust manager from one self-signed fixture's PEM and assert it **accepts that exact
certificate** and **rejects an unrelated self-signed one** — which still proves trust is
*restricted*, only without a CA→leaf chain. The probe output above says (a) and (b) should not be
needed.

### 4.2 Updates to the 23 existing tests — no count change

| File | Change |
|---|---|
| `template/MqttConnectedSystemTemplateDescriptorTest.java` | `CONFIG_FIELD_KEYS` → the eleven keys of §2.1 in order; rename `exactlyTheNineExpectedConfigFieldKeysArePresent` → `exactlyTheElevenExpectedConfigFieldKeysArePresent`; in `noConnectedSystemPropertyDescriptorIsExpressionable` change `assertEquals(9, inspected)` → `assertEquals(11, inspected)` and the message to "all eleven". `connectedSystemFieldsUseNoListDocumentOrAppianValueDescriptor` needs no edit and now covers eleven fields. |
| `template/MqttConnectedSystemTemplateNoDesignTimeErrorsTest.java` | its nine-key list → eleven; the Javadoc "nine fields" → "eleven fields". |
| `MqttPublisherConnectOptionsTest.java` | both `buildConnectOptions` calls gain the two leading arguments (`Boolean.FALSE, null`); the test method now declares `throws GeneralSecurityException`. Assertions unchanged. |
| `ConnectionConfigValidatorTest.java` | no edit to the four existing tests — rules 1–4 and their wording are untouched. |
| `MqttSubscriberTest.java`, `integration/MqttSubscribeIntegrationInputsTest.java`, `ProjectStructureTest.java` | no edit. `MqttSubscribeIntegrationInputsTest` drives `inputError`/`qosOf`/`success`, not `subscribe`, so the signature change does not reach it. **Confirm this when implementing**; if any call site breaks, fix it in the same step. |

### 4.3 The twelve new tests

**`template/MqttConnectedSystemTemplateDescriptorTest.java` (+1 → 8 tests)**

1. `theTwoTlsFieldsUseBooleanAndParagraphDescriptorsAndAreNotExpressionable` — resolves
   `tlsEnabled` and `caCertificate` off the rendered root type; asserts
   `instanceof BooleanPropertyDescriptor` / `instanceof ParagraphPropertyDescriptor`, and
   `isExpressionable()` is `false` on both read back through the SDK accessor. This is the
   field-specific guard against someone "upgrading" the CA field to a document upload; the
   all-eleven sweep in the updated test is the general one.

**`TlsSupportTest.java` — new, package `de.hock.mqtt` (+5)**

2. `tlsDisabledProducesATcpUriAndNoSocketFactory` — `brokerUri(Boolean.FALSE, "h", 1883)` and
   `brokerUri(null, "h", 1883)` both equal `"tcp://h:1883"`; `buildConnectOptions(Boolean.FALSE,
   <a real CA PEM>, …).getSocketFactory()` is **`null`**. Mode 1 regression lock: a CA present in
   state must not pull TLS in.
3. `tlsEnabledWithoutACaUsesTheDefaultTrustStoreOverAnSslUri` — `brokerUri(Boolean.TRUE,"h",8883)`
   is `"ssl://h:8883"`; `socketFactory("")` and `socketFactory(null)` are non-null
   `SSLSocketFactory`s; `buildConnectOptions(Boolean.TRUE, "", …).getSocketFactory()` is non-null;
   `trustManagers("")[0]` as an `X509TrustManager` has **more than 20** accepted issuers (146 on
   Corretto 17 — asserted loosely so a JDK update cannot fail the build) and
   `checkServerTrusted({fixtureSelfSigned}, "RSA")` **throws** `CertificateException`. Also assert a
   socket from `socketFactory("")` has `TLSv1.2` enabled and neither `TLSv1` nor `TLSv1.1`, which
   pins the `SSLContext.getInstance("TLS")` decision.
4. `tlsEnabledWithACaTrustsOnlyThatCa` — **the test that proves restriction, not configuration.**
   Build CA + leaf-signed-by-CA + an unrelated self-signed anchor. `trustManagers(caPem)[0]` has
   exactly **1** accepted issuer; `checkServerTrusted({leaf}, "RSA")` succeeds;
   `checkServerTrusted({unrelated}, "RSA")` throws `CertificateException`. Non-null factory is
   asserted too, but it is not the point of the test.
5. `aMultiCertificatePemChainLoadsEveryCertificate` — a PEM of two concatenated blocks:
   `parseCertificates` returns 2 in order, and `trustManagers` over it yields **2** accepted
   issuers (distinct aliases, so neither overwrites the other).
6. `malformedCaCertificateIsRejectedWithoutEchoingItsContent` — `parseCertificates` throws
   `GeneralSecurityException` for `"123456"`, `"not a certificate at all"`, `""` (the measured
   zero-result case), `"   "` and a header-with-broken-base64 block; for each, assert the thrown
   message contains neither the input nor any 6-character substring of it. Same method asserts a
   **CRLF-and-whitespace-padded** valid PEM still parses to 1 certificate (measured: it does), so
   the paste-from-Windows path is covered by the same test rather than a seventh.

**`TlsHostnameVerificationTest.java` — new, package `de.hock.mqtt` (+3)**

7. `pahoAppliesHttpsEndpointIdentificationOnSslByDefaultAndThePluginSetsItExplicitly` — no sockets:
   `new MqttConnectOptions().isHttpsHostnameVerificationEnabled()` is `true` (pins the Paho 1.2.5
   default the finding rests on, so a dependency bump that changed it would fail here), and
   `buildConnectOptions(Boolean.TRUE, "", …).isHttpsHostnameVerificationEnabled()` is `true`, and
   `getSSLHostnameVerifier()` is `null` (the contradictory path is not taken).
8. `aBrokerCertificateMatchingTheConfiguredHostCompletesTheHandshake`
9. `aBrokerCertificateForADifferentHostFailsTheHandshake`

   Both use one private helper that **replicates Paho's exact socket sequence** —
   `factory.createSocket()`, `connect(new InetSocketAddress(host, port), 5000)`, a fresh
   `SSLParameters` with `SNIHostName`, then a fresh `SSLParameters` with
   `setEndpointIdentificationAlgorithm("HTTPS")`, then `startHandshake()` — against an
   `SSLServerSocket` bound to `InetAddress.getByName("127.0.0.1")` on port `0`, presenting a leaf
   signed by the fixture CA. Client side is **`TlsSupport.socketFactory(caPem)`**, so the code under
   test is the plug-in's own factory. Test 8 uses SAN `localhost` and expects a completed
   handshake; test 9 uses SAN `wrong.example.com` and expects an `SSLHandshakeException` (assert the
   message mentions no matching subject alternative name). The accept loop runs on a **daemon**
   thread, the server socket closes in `finally`, each test carries
   `@Timeout(30)` and `so_timeout` on the accepted socket, and **Paho is not instantiated** — that
   keeps the Paho client's non-daemon threads out of the suite (the scratch probe's own
   `MqttClient` is what made it hang).

   These two are the only tests that bind a port. If the environment forbids even a loopback bind,
   report it rather than deleting them: the fallback is to keep test 7 and tests 2–6 and record in
   the README that hostname verification is evidenced by the `javap` trace and the deploy check,
   not by an offline test.

**`ConnectionConfigValidatorTest.java` (+3 → 7)**

10. `aCaCertificateWithTlsDisabledIsReported` — valid host/port, `tlsEnabled` false (and separately
    absent, i.e. never set), a real PEM in `caCertificate`: non-null error naming both "CA
    certificate" and "TLS is disabled"; and the control case — same CA with `tlsEnabled` true →
    `firstError` returns `null`.
11. `anUnparseableCaCertificateIsReported` — TLS on plus (a) `"not a certificate"` → message names
    `-----BEGIN CERTIFICATE-----`; (b) a header-wrapped broken-base64 block → the "could not be
    parsed" message. For both, assert the message contains no substring of the input.
12. `aBareNumberCaCertificateIsReportedAsAProbableDocumentId` — `caCertificate = "279401"`, TLS on:
    the message mentions an Appian document id **and does not contain `"279401"`**. This is the
    stale-document-id case from the 1.0.3 design, and the assertion that the digits are absent is
    what keeps the no-echo rule honest.

**Totals:** 23 + 1 + 5 + 3 + 3 = **35 tests in 9 classes** — inside the 30–36 target.
Config keys covered: 11. Publish input keys: 3. Subscribe input keys: 4. Still nothing exercises a
real publish or subscribe against a broker; that is what the deploy checklist is for.

---

## 5. Section 6 — documentation lockstep, old value → new value

Every figure below was read out of the current tree, not recalled.

### 5.1 `pom.xml`

| Line | Old | New |
|---|---|---|
| `<version>` | `1.0.6` | `1.0.7` |

Nothing else: no new dependency (the JDK supplies all crypto), no Surefire configuration, no
assembly change. **Verify** `src/assembly/plugin-assembly.xml` needs no edit — its `fileSet` over
`src/main/java` picks up `TlsSupport.java` automatically.

### 5.2 `src/main/resources/appian-plugin.xml`

| Item | Old | New |
|---|---|---|
| `<version>` | `1.0.6` | `1.0.7` |
| header comment, template line | `nine-field broker configuration` | `eleven-field broker configuration, optional server-side TLS` |
| `<description>` | (no TLS mention) | add "optionally over TLS" |
| `<application-version min>` | `26.9.0.0` | **unchanged — four-part, do not touch** |
| `<vendor>` | `name="Hock" url="https://itondemand.com"` | unchanged |
| plug-in key, three module keys | `de.hock.mqtt`, `MqttConnectedSystemTemplate`, `MqttPublishIntegration`, `MqttSubscribeIntegration` | unchanged, so 1.0.7 **replaces** 1.0.6 |

### 5.3 `src/main/resources/resources_en_US.properties`

Two new keys (§2.4); header comment `nine constants` → `eleven constants`,
`22 keys, 16 of them field labels` → `24 keys, 18 of them field labels`; description extended.

### 5.4 `README.md`

| Where | Old | New |
|---|---|---|
| line 3 banner | "`1.0.6` adds the Subscribe (READ) integration…" | rewrite for `1.0.7`: server-side TLS, two new fields, three modes, hostname verification **measured**, mTLS still absent |
| "How we got here" (l. 22–24) | "**nine** fields … unchanged at nine fields in `1.0.6`" | append the `1.0.7` sentence; **eleven** fields |
| "What is still deliberately removed" (l. 28–31) | the whole TLS bullet (`tlsEnabled`, `caCertificate`, `clientCertificate`, `clientPrivateKey`, `keyPassphrase`, "plain `tcp://` only") | replace with a **mutual TLS** bullet only: `clientCertificate`, `clientPrivateKey`, `keyPassphrase` and the key-manager wiring stay out; explain that `1.0.3`'s versions of those were two of the three recorded defects and the third (§0.2) is new |
| Facts (l. 62) | `1.0.6` | `1.0.7` |
| Facts (l. 66) | "`1.0.6 > 1.0.5`" | "`1.0.7 > 1.0.6`" |
| Requirements (the broker bullet) | "speaking MQTT 3.1.1 over **plain TCP** — there is no TLS in this build" | "over plain TCP or TLS" |
| Build (l. 117, 131) | `…-1.0.6-plugin.jar` | `…-1.0.7-plugin.jar` |
| Build (l. 122–123) | "**seven `.java` sources**" | "**eight `.java` sources**" |
| Deploy (l. 140) | version `1.0.6` | `1.0.7` |
| Deploy (l. 142) | "renders nine fields" | "renders eleven fields" |
| Configuring… (l. 150) | "The nine fields below" | "The eleven fields below" |
| field table | 9 rows | **11 rows**, `tlsEnabled` and `caCertificate` inserted after `brokerPort`, with effective defaults `false` / blank and the validation column filled from §3.1 |
| validation rules table (§"Design-time validation") | 4 rows | **7 rows** — add rules 5, 6, 7 verbatim |
| "Runtime behavior" bullet | "**Plain `tcp://` only.** No `ssl://`, no `SSLContext`, no socket factory…" | rewrite: `tcp://` when TLS is off, `ssl://` when on; in-memory `SSLContext` handed to Paho via `setSocketFactory`; **still no `System.setProperty`, still no temp keystore, still nothing written to the filesystem** |
| Known limitations (l. 471) | "23 tests in seven classes" + the per-class bullets | "**35 tests in nine classes**" + updated bullets (`DescriptorTest` 8, `NoDesignTimeErrors` 1, `ConnectionConfigValidatorTest` 7, `MqttPublisherConnectOptionsTest` 2, `MqttSubscriberTest` 3, `MqttSubscribeIntegrationInputsTest` 4, `ProjectStructureTest` 2, **`TlsSupportTest` 5**, **`TlsHostnameVerificationTest` 3**), and the note that two of them bind a loopback port |
| Known limitations (l. 477, 483) | "nine expected field keys", "nine fields" | eleven |
| Restoring the full build (l. 533) | "bumped past `1.0.6`" | `1.0.7` |
| Links | — | add `.agents/tasks/server-side-tls-1.0.7.md` as the current release record, and demote the 1.0.6 entry |

**New README section, "TLS", placed after the field table.** It must contain:

1. The two fields, their types, and why **PEM text rather than a document upload** — Appian forbids
   expressionable descriptors on a connected system, `DocumentPropertyDescriptor`'s builder forces
   that flag and `Guard.checkPropertyIsExpressionable` will not let it be cleared, so no
   document-upload field exists on a connected system template with this SDK. 1.0.3 tried and the
   deploy failed.
2. The three modes of §1.4 with **when to use each**: mode 2 for HiveMQ Cloud and anything with a
   publicly trusted certificate (do not paste a CA you do not need); mode 3 for a self-signed or
   private-CA broker — paste the **CA** that signed the broker certificate, not the broker
   certificate itself (though a self-signed broker certificate is its own CA and works).
3. **The port convention and that the plug-in never changes it**: 1883 plain, 8883 TLS; flipping the
   checkbox does not touch `brokerPort`, and the commonest TLS failure is a `tcp` port left behind.
4. **Hostname verification, prominently, and as a measured fact.** Paho 1.2.5 applies
   `SSLParameters.setEndpointIdentificationAlgorithm("HTTPS")` to the `ssl://` socket before the
   handshake whenever `MqttConnectOptions.isHttpsHostnameVerificationEnabled()` is true, which is
   its default and which this plug-in sets explicitly; a certificate whose SAN does not match the
   configured host is rejected. Cite the evidence in one sentence (bytecode trace plus the two
   offline handshake tests) and state the **residual risk honestly**: (a) verification matches the
   host string as typed, so configuring a broker by IP address requires a certificate with that IP
   in its SAN; (b) in mode 2 any publicly trusted CA can vouch for the host — that is the normal web
   PKI trust model, and mode 3 narrows it to one CA when that matters; (c) there is deliberately
   **no option to disable verification**, and none will be added.
5. **Mutual TLS is not supported.** The plug-in presents no client certificate; a broker that
   requires one will fail the handshake. Planned for a later release.
6. The error messages a designer will actually see: the three `Invalid configuration` sentences of
   §3.1, the fixed TLS-application sentence of §3.3, and the recognisable Paho handshake failures
   (`No subject alternative DNS name matching …`, `unable to find valid certification path to
   requested target` = wrong or missing CA).
7. What is still absent and recoverable from `d023a76`: mTLS, LWT, diagnostics, multi-topic
   subscribe — with the warning that `1.0.3`'s TLS code carries three known defects (§6.2) and is a
   cautionary reference, not a basis.

### 5.5 `.kiro/steering/appian-appmarket-compliance.md`

| Where | Old | New |
|---|---|---|
| Scope note | ends at `1.0.6` | add a `1.0.7` paragraph: server-side TLS, nine → **eleven** fields, one new source (`TlsSupport`), still two Paho call sites, no new dependency; mTLS explicitly deferred |
| §1 import census | `36, across 4 files`; "the connected system template 11, each integration 12, and `ConnectionConfigValidator` 1" | **37 across 4 files**: template **12** (it gains `ParagraphPropertyDescriptor`), each integration 12, validator 1. `TlsSupport` imports **no** SDK class, so the file count stays 4 |
| §1 sources-in-jar | "At 1.0.6 that is **7 sources**" + the list | **8 sources**, adding `de/hock/mqtt/TlsSupport.java`; history line "was 7 at 1.0.6, 5 at 1.0.5, 4 at 1.0.4" |
| §1 version table | `<version>` our value `1.0.6` | `1.0.7` (format rule itself unchanged: three-part vs four-part) |
| §1 Paho-call-site paragraph | "At 1.0.6 **two** classes reference Paho" | still two — add that `TlsSupport` deliberately holds no Paho reference, and that `setSocketFactory` is called from `buildConnectOptions` so the single-place invariant holds |
| §1 descriptor table prose | "`ParagraphPropertyDescriptor` is no longer used by this plug-in at all — the nine connected system fields at 1.0.6 are Text, Integer, Boolean and EncryptedText only" | **eleven** fields at 1.0.7: Text, Integer, Boolean, EncryptedText **and Paragraph**, the last holding the pasted CA PEM. Add the measured note that `ParagraphPropertyDescriptorBuilder`'s constructor touches only `type(PARAGRAPH)` and `paragraphHeight=TALL` and its `build()` calls no `Guard` |
| §1 expressionability greps | template `0`, repo-wide `7`, publish `3` / subscribe `4`; "the Javadoc of `MqttSubscriber`, `ConnectionConfigValidator` and `MqttSubscribeIntegration` must avoid it too" | **all counts unchanged**; add `TlsSupport` to the list of Javadocs that must avoid the literal token |
| §2 submission artifact | `…-1.0.6-plugin.jar` | `…-1.0.7-plugin.jar` |
| §2 tests | "Surefire's default include covers all **seven** test classes" | **nine** test classes |
| §3 | the `System.setProperty` bullet's "(Moot at 1.0.6: there is no TLS…)" parenthetical | **replace with the live rule** (text below) |
| §3 Closeable bullet | "At 1.0.6 this covers **both** Paho call sites" | unchanged; add that `TlsSupport` opens nothing closeable — it reads a `String` through a `ByteArrayInputStream` and holds no OS resource |
| §4 | the whole verification block | replace with §7 of this plan |
| §5 preamble | "The current known-good reference is **1.0.6 / commit `68e15e8`**" | keep `68e15e8` as the known-good reference and state plainly that **1.0.7 is not yet deployed**; add the deploy-confirmed line only after the user reports success |
| §5 item 4 | "23 tests in seven classes … 2 in `ProjectStructureTest`, 7 in …" | **35 tests in nine classes** with the new breakdown and one line per new test, including which two bind a loopback port |
| §5 item 5 | the git history list | add a note that the 1.0.7 working-tree change is **uncommitted** — do not invent a SHA |
| §5 | — | **new item 10**, the hostname-verification finding (text below) |
| §8 | audit trail list | add `server-side-tls-1.0.7-plan.md` and `server-side-tls-1.0.7.md` at the top, and mark the latter as "start here for what currently ships" |

**New §3 bullet to add (the reusable constraint the brief asks for):**

> **MQTT over TLS must be built as an in-memory `SSLContext` handed to Paho via
> `MqttConnectOptions.setSocketFactory`.** Parse the PEM with `CertificateFactory`, load it into a
> `KeyStore.getInstance(KeyStore.getDefaultType())` opened with `load(null, null)`, feed a
> `TrustManagerFactory`, then `SSLContext.getInstance("TLS")`, then `getSocketFactory()`. Two
> Appian policies forbid the usual shortcuts, and each forbids a different one: the ban on
> `System.setProperty` and JVM-global mutation rules out the `javax.net.ssl.*` trust-store
> properties (which would also affect every other plug-in on the server), and the ban on direct
> filesystem manipulation rules out writing a temporary key store — the 1.0.3 code did exactly
> that. Never pin an old protocol: `"TLS"` lets the JDK negotiate, and Corretto 17 already offers
> only TLS 1.3 and 1.2.

**New §5 item 10 to add:**

> 10. **ANSWERED (offline, 2026-10-28) — Paho 1.2.5 DOES perform hostname verification on
>     `ssl://`, by default. No socket-factory wrapper is needed.**
>     `SSLNetworkModuleFactory` (whose `getSupportedUriSchemes()` is exactly `{"ssl"}`) calls
>     `SSLNetworkModule.setHttpsHostnameVerificationEnabled(options.isHttpsHostnameVerificationEnabled())`,
>     `MqttConnectOptions`' constructor seeds that field `true`, and `SSLNetworkModule.start()`
>     applies `SSLParameters.setEndpointIdentificationAlgorithm("HTTPS")` to the socket after
>     connect and before `startHandshake()`. An offline handshake test against a loopback
>     `SSLServerSocket` confirms a wrong-SAN certificate is rejected and a matching one accepted.
>     Two corollaries: a custom `setSSLHostnameVerifier` runs **only** when the HTTPS flag is
>     false, so never set both; and `TCPNetworkModule.start()` uses the **no-argument**
>     `SocketFactory.createSocket()`, whose inherited implementation throws
>     `SocketException("Unconnected sockets not implemented")` — which is why 1.0.3's
>     `TlsVersionConstrainingSocketFactory`, overriding six overloads but not that one, would have
>     failed every TLS connection. Do not wrap the socket factory. Set the flag explicitly and
>     leave the verifier null. *Still untested on a real instance: whether the Appian server's own
>     JSSE configuration (a site trust store set through the JSSE system properties) changes mode 2
>     — `TrustManagerFactory.init((KeyStore) null)` reads those properties, which is intended.*

### 5.6 `docs/mqtt-plugin-guide.md`

Only the banner at lines 3–20 changes; the body stays as history (it documents the pre-reduction
build). Edits: "currently ships is **1.0.6**: one connected system with nine fields (…)" → **1.0.7**
with the eleven keys listed; "TLS and certificates, Last Will and Testament, reconnect attempt
tuning and diagnostics are **not present**" → "**Server-side** TLS ships as of 1.0.7 (`tlsEnabled`,
`caCertificate`), but the mutual-TLS fields this guide describes below (`clientCertificate`,
`clientPrivateKey`, `keyPassphrase`) do **not** exist; Last Will, reconnect attempt tuning and
diagnostics remain absent." Add one line under the guide's own **TLS** table (≈ l. 88–94) pointing
at the README: the "Enable TLS" and CA rows now exist in a different shape, the client rows do not.

### 5.7 `info.md`

`1.0.6` → `1.0.7`; add to the history sentence that 1.0.7 adds server-side TLS; the Connected System
bullet gains "optional server-side TLS (enable TLS, CA certificate)"; the sentence "TLS, Last Will
and Testament, multi-topic subscribe, reconnection tuning and diagnostics remain deliberately
absent" → "**Mutual** TLS, Last Will and Testament, multi-topic subscribe, reconnection tuning and
diagnostics remain deliberately absent".

### 5.8 `THIRD-PARTY-NOTICES.md`

**No change, and confirm it in the verification run.** All crypto comes from the JDK; the only
bundled third-party artefact is still Paho 1.2.5 under EDL-1.0. Record the confirmation rather than
silently leaving the file alone.

### 5.9 New release record: `.agents/tasks/server-side-tls-1.0.7.md`

Mirrors `subscribe-integration-1.0.6.md`: what shipped, the two fields and three modes, the
hostname-verification finding with its bytecode evidence and the no-wrapper decision, the three
1.0.3 TLS defects (document id never resolved, passphrase could not unlock a key, **and the
unoverridden no-arg `createSocket()`**), the validator rules with the hard-error judgement and its
trade-off, the test inventory, the full pasted verification output, and the deploy-and-test
checklist of §8.

---

## 6. Implementation Plan

Ordered by dependency. Every item leaves the tree compiling **and** `mvn -o test` green — test
updates are folded into the item that breaks them rather than deferred, which is why the running
test count is given for each.

- [ ] 1. Bump the version in both places. `pom.xml` `<version>` and
      `src/main/resources/appian-plugin.xml` `<plugin-info><version>`: `1.0.6` → `1.0.7`. Leave
      `<application-version min="26.9.0.0"/>` alone (four-part, deliberately).
      Files: `pom.xml`, `src/main/resources/appian-plugin.xml`
      Verify: `mvn -o clean package` succeeds and
      `ls target/*.jar` prints exactly `target/mqtt-broker-connected-system-1.0.7-plugin.jar`.

- [ ] 2. Add the two connected system fields and update the tests that count them.
      In `MqttConnectedSystemTemplate`: the two constants of §2.1, the two builder calls of §2.2
      inserted after `brokerPort`, the updated `brokerPort` instruction text, and a Javadoc
      paragraph on the new fields that still avoids the literal token `isExpressionable`. In the
      bundle: the two labels and the corrected header counts of §2.4. In the tests: the eleven-key
      list and `assertEquals(11, inspected)` per §4.2, plus the one new descriptor test (§4.3 item 1).
      Files: `src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java`,
      `src/main/resources/resources_en_US.properties`,
      `src/test/java/de/hock/mqtt/template/MqttConnectedSystemTemplateDescriptorTest.java`,
      `src/test/java/de/hock/mqtt/template/MqttConnectedSystemTemplateNoDesignTimeErrorsTest.java`
      Verify: `mvn -o test` → `Tests run: 24, Failures: 0, Errors: 0`; and
      `grep -c isExpressionable src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java`
      prints `0`; and the §7 bundle check reports 24 keys with no diff.

- [ ] 3. Write the certificate fixture helper and the TLS core, with its five tests.
      `CertificateFixtures` per §4.1 (DER writer, `newCa`, `newLeaf`, `newSelfSignedCa`, `toPem`,
      `serverSocketFactory`). `TlsSupport` per §1.1 — six members, `SSLContext.getInstance("TLS")`,
      in-memory `KeyStore`, default-trust-store branch, per-alias chain loading, empty-result and
      malformed-PEM rejection with fixed messages, and a Javadoc that avoids the tokens
      `isExpressionable`, `System.setProperty` and the two JSSE property names. Then
      `TlsSupportTest` (§4.3 items 2–6) — note item 2 also asserts `buildConnectOptions`, so write
      that assertion after step 4 or stub it with `TlsSupport` only and extend it in step 4;
      prefer the latter.
      Files: `src/main/java/de/hock/mqtt/TlsSupport.java`,
      `src/test/java/de/hock/mqtt/CertificateFixtures.java`,
      `src/test/java/de/hock/mqtt/TlsSupportTest.java`
      Verify: `mvn -o test` → `Tests run: 29`; the mode-3 test must fail if its
      `checkServerTrusted({unrelated})` assertion is inverted — check that by inverting it once,
      watching it fail, and restoring it.

- [ ] 4. Thread TLS through both Paho call sites and both integrations.
      `MqttPublisher`: the two new parameters on `publish` and `buildConnectOptions` exactly as
      §1.3 orders them, `options.setHttpsHostnameVerificationEnabled(true)`,
      `options.setSocketFactory(TlsSupport.socketFactory(caCertificatePem))` **only when TLS is
      on**, the URI from `TlsSupport.brokerUri(...)`, `throws GeneralSecurityException`, and the
      class Javadoc rewritten (it currently asserts "Transport is plain `tcp://` only … no
      `SSLContext`, no socket factory" — keep its one `System.setProperty` sentence, which is the
      single permitted grep hit). `MqttSubscriber`: same two parameters, the shared
      `buildConnectOptions` call, `brokerUri`, `throws`, and the same Javadoc correction phrased
      **without** the literal token `System.setProperty`. Both integrations: read the two values,
      pass them through, add the fixed-message `catch (GeneralSecurityException e)` clause of §3.3
      ahead of the existing catch. Update the two `buildConnectOptions` calls in
      `MqttPublisherConnectOptionsTest`, and finish `TlsSupportTest` item 2's socket-factory
      assertions.
      Files: `src/main/java/de/hock/mqtt/MqttPublisher.java`,
      `src/main/java/de/hock/mqtt/MqttSubscriber.java`,
      `src/main/java/de/hock/mqtt/integration/MqttPublishIntegration.java`,
      `src/main/java/de/hock/mqtt/integration/MqttSubscribeIntegration.java`,
      `src/test/java/de/hock/mqtt/MqttPublisherConnectOptionsTest.java`,
      `src/test/java/de/hock/mqtt/TlsSupportTest.java`
      Verify: `mvn -o test` → `Tests run: 29`; and
      `grep -rc isExpressionable src/main/java/ | grep -v ':0$'` prints exactly the two integration
      lines, `3` and `4`.

- [ ] 5. Extend the validator with rules 5–7 and add its three tests.
      `ConnectionConfigValidator`: the `PEM_HEADER` constant, the three rules of §3.1 appended
      after the existing four with the exact wording given, the digits-only clause, and a Javadoc
      rule list extended to seven entries plus a sentence recording the hard-error judgement of
      §3.2. Tests per §4.3 items 10–12, each asserting the message carries no substring of the
      field value.
      Files: `src/main/java/de/hock/mqtt/ConnectionConfigValidator.java`,
      `src/test/java/de/hock/mqtt/ConnectionConfigValidatorTest.java`
      Verify: `mvn -o test` → `Tests run: 32`; the four pre-existing validator tests must still
      pass untouched.

- [ ] 6. Add the hostname-verification tests.
      `TlsHostnameVerificationTest` per §4.3 items 7–9: the no-socket options assertions, and the
      two loopback handshake tests sharing one Paho-sequence helper, with `@Timeout(30)`, a daemon
      accept thread, and the server socket closed in `finally`.
      Files: `src/test/java/de/hock/mqtt/TlsHostnameVerificationTest.java`
      Verify: `mvn -o test` → `Tests run: 35, Failures: 0, Errors: 0`, and the whole run still
      finishes in seconds. Confirm the wrong-host test fails for the right reason by reading its
      assertion message once with the expectation inverted.

- [ ] 7. Documentation lockstep. Apply every row of §5.4 to §5.7 — README (including the new TLS
      section with the seven required contents), the steering file (every figure, the new §3 bullet,
      the new §5 item 10, the rewritten §4 block), the guide banner and `info.md`. Confirm
      `THIRD-PARTY-NOTICES.md` needs no change and say so.
      Files: `README.md`, `.kiro/steering/appian-appmarket-compliance.md`,
      `docs/mqtt-plugin-guide.md`, `info.md`
      Verify: `grep -rn '1\.0\.6' README.md info.md docs/mqtt-plugin-guide.md .kiro/steering/appian-appmarket-compliance.md`
      returns only deliberate historical references (release history, the `68e15e8` known-good
      reference, "replaces the installed 1.0.6"), and `grep -rn 'nine fields\|seven .java\|23 tests\|22 keys'`
      across those four files returns nothing outside a history sentence.

- [ ] 8. Write the release record `.agents/tasks/server-side-tls-1.0.7.md` per §5.9, with the real
      pasted output of §7.
      Files: `.agents/tasks/server-side-tls-1.0.7.md`
      Verify: the record's figures match the §7 output literally — 8 sources, 35 tests, 24 bundle
      keys, one jar, `1.0.7`.

- [ ] 9. Run the full verification block of §7 and paste the real output into the release record
      and the final report. Do not claim 26.9 compatibility: state that final confirmation requires
      the user deploying `target/mqtt-broker-connected-system-1.0.7-plugin.jar`.
      Verify: every line of §7 matches its stated expectation; any mismatch is a defect, not a
      documentation update.

---

## 7. Section 7 — the verification block to run at the end

```bash
cd /Users/andreas.hock/Documents/Appian-Projects/MQTT_Component

mvn -o clean package                             # BUILD SUCCESS
mvn -o test                                      # Tests run: 35, Failures: 0, Errors: 0
J=target/mqtt-broker-connected-system-1.0.7-plugin.jar

# --- packaging invariants (a)-(l), against the BUILT jar ---
ls target/*.jar | wc -l                          # 1   (g)
unzip -l $J | grep -c '\.java$'                  # 8   (b)
unzip -l $J | grep '\.java$' | grep -vc 'de/hock/mqtt/'          # 0   (b)
unzip -l $J | awk '{print $NF}' | grep -c '^appian-plugin.xml$'  # 1   (c) bare root
unzip -l $J | grep -c 'META-INF/lib/.*\.jar$'    # 1   (d)
unzip -l $J | grep -c 'META-INF/lib/org.eclipse.paho'            # 1   (d)
unzip -l $J | grep -ci 'connected-systems'       # 0   (d)
unzip -l $J | grep -c 'com/appian/mqtt'          # 0   (a)
unzip -l $J | grep -ci test                      # 0   (k)
unzip -l $J | grep -ci 'backup'                  # 0
unzip -l $J | grep -c 'validation/'              # 0   the validation package is still gone
unzip -l $J | awk '{print $NF}' | grep -c '^THIRD-PARTY-NOTICES.md$'   # 1   (j)
unzip -l $J | grep -c 'de/hock/mqtt/TlsSupport'  # 2   TlsSupport.class and TlsSupport.java
unzip -p $J appian-plugin.xml | grep -E '<version>|application-version|<vendor'
#   <version>1.0.7</version> / min="26.9.0.0" / vendor name="Hock"   (e)(f)
unzip -p $J appian-plugin.xml | grep -c 'key="de.hock.mqtt"'     # 1   (l)
unzip -p $J appian-plugin.xml \
  | grep -cE 'key="Mqtt(ConnectedSystemTemplate|PublishIntegration|SubscribeIntegration)"'
#   3 — module keys unchanged, so 1.0.7 REPLACES 1.0.6   (l)
unzip -p $J appian-plugin.xml | grep -nE 'connected-system-template|integration-template'
#   both <integration-template> elements still NESTED in the one <connected-system-template>  (6)
for c in template.MqttConnectedSystemTemplate MqttPublisher MqttSubscriber TlsSupport \
         ConnectionConfigValidator integration.MqttPublishIntegration \
         integration.MqttSubscribeIntegration; do
  printf '%s ' $c; javap -verbose -cp target/classes de.hock.mqtt.$c | grep 'major version'
done                                             # 61 for all seven   (h)

# --- expressionability: the grep is necessary, the test is what proves it ---
grep -c isExpressionable src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java  # 0
grep -rn isExpressionable src/main/java/ | wc -l                                               # 7
grep -rc isExpressionable src/main/java/ | grep -v ':0$'
#   exactly two lines: integration/MqttPublishIntegration.java:3
#                      integration/MqttSubscribeIntegration.java:4

# --- namespace trap and SDK import census ---
grep -rn 'com\.appian\.mqtt' . | grep -v '/target/' | grep -v '/.agents/' | grep -v '/.kiro/' \
  | grep -v '/.git/'                             # no hits   (a)
grep -rh 'import com.appian.connectedsystems' src/main/java/ | wc -l   # 37
grep -rl 'com\.appian\.connectedsystems' src/main/java | wc -l         # 4
grep -rc 'import com.appian.connectedsystems' src/main/java/
#   template 12, each integration 12, ConnectionConfigValidator 1, the rest 0

# --- code policies, incl. the TLS-specific bans (brief item 4) ---
grep -rn 'System\.out\|System\.err\|printStackTrace\|java\.util\.logging' src/main/java/
#   expect NO hits
grep -rnE 'System\.setProperty|javax\.net\.ssl\.(trustStore|keyStore)|createTempFile|File\.createTemp|java\.io\.File|java\.nio\.file' \
  src/main/java/
#   expect EXACTLY ONE hit: the Javadoc sentence in MqttPublisher asserting the absence of a
#   System.setProperty call. Prose, not a call. Any other hit is a real violation — in particular
#   TlsSupport and MqttSubscriber must phrase the same assurance without these tokens.
grep -rn 'SSLContext\|setSocketFactory\|TrustManagerFactory' src/main/java/ | cut -d: -f1 | sort -u
#   expect only de/hock/mqtt/TlsSupport.java and de/hock/mqtt/MqttPublisher.java
#   (setSocketFactory is called once, from buildConnectOptions)
grep -rniE 'trustAll|X509TrustManager *\(|checkServerTrusted' src/main/java/
#   expect NO hits — there is no permissive trust manager anywhere in main
grep -rn 'setSSLHostnameVerifier\|setSSLProperties' src/main/java/
#   expect NO hits — see the finding in steering §5 item 10

# --- no key material anywhere (brief item 8) ---
grep -rniE 'BEGIN (RSA |EC |)PRIVATE KEY|BEGIN ENCRYPTED PRIVATE KEY' . \
  --exclude-dir=.git --exclude-dir=target     # no hits
find . -path ./.git -prune -o -path ./target -prune -o \
  \( -name '*.jks' -o -name '*.p12' -o -name '*.pfx' -o -name '*.key' -o -name '*.pem' \
     -o -name '*.keystore' -o -name '*.truststore' \) -print     # nothing
unzip -l $J | grep -ciE '\.(jks|p12|pfx|key|pem|keystore|truststore)$'   # 0

# --- resource bundle: no orphans, no missing keys (24 keys, 18 field labels) ---
B=src/main/resources/resources_en_US.properties
sed -nE 's/^([A-Za-z][A-Za-z.]*)=.*/\1/p' $B | sort > /tmp/bundle-actual.txt
{ echo MqttConnectedSystemTemplate.name; echo MqttConnectedSystemTemplate.description
  sed -nE 's/.*public static final String [A-Z_]+ *= *"([A-Za-z]+)".*/MqttConnectedSystemTemplate.\1.label/p' \
    src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java
  echo MqttPublishIntegration.name; echo MqttPublishIntegration.description
  sed -nE 's/.*public static final String INPUT_[A-Z_]+ *= *"([A-Za-z]+)".*/MqttPublishIntegration.\1.label/p' \
    src/main/java/de/hock/mqtt/integration/MqttPublishIntegration.java
  echo MqttSubscribeIntegration.name; echo MqttSubscribeIntegration.description
  sed -nE 's/.*public static final String INPUT_[A-Z_]+ *= *"([A-Za-z]+)".*/MqttSubscribeIntegration.\1.label/p' \
    src/main/java/de/hock/mqtt/integration/MqttSubscribeIntegration.java
} | sort > /tmp/bundle-expected.txt
diff /tmp/bundle-expected.txt /tmp/bundle-actual.txt    # no output
wc -l < /tmp/bundle-actual.txt                          # 24
#   The sed patterns hold only while the ELEVEN config keys are the only `public static final
#   String` constants on the template. TlsSupport's PEM_CERTIFICATE_HEADER is package-private and
#   lives in a different file, so it cannot leak into this check — keep it that way.

# --- plain tcp:// is unchanged when TLS is off (brief item 6) ---
#   Proven by TlsSupportTest.tlsDisabledProducesATcpUriAndNoSocketFactory (asserts the tcp:// URI
#   and a null socket factory even with a CA present in state) and by the untouched
#   MqttPublisherConnectOptionsTest assertions. Re-read that test's output in the mvn -o test log.
git --no-pager diff --stat                        # review: no unintended file touched
git --no-pager status --short                     # the user commits; this plan does not
```

Final statement to make in the report, verbatim in spirit: **nothing above proves 26.9
compatibility.** Final confirmation requires the user deploying
`target/mqtt-broker-connected-system-1.0.7-plugin.jar` to a live instance and running §8.

---

## 8. Deploy-and-test checklist (the deliverable)

1. Copy `target/mqtt-broker-connected-system-1.0.7-plugin.jar` to
   `APPIAN_INSTALL/_admin/plugins/`; confirm **Admin Console → Plug-ins** shows **1.0.7**, and
   `tomcat-stdOut.log` shows `Successfully installed Plug-in` plus the
   `ConnectedSystemTemplateModuleParser` lines.
2. **The regression check that matters most.** Open the **existing** MQTT Broker connected system:
   it must render **eleven** fields with every previously saved value intact, **Enable TLS**
   unchecked and **CA Certificate** empty. Run the existing publish and subscribe integrations
   unchanged over plain `tcp://` — both must still succeed. If this fails, stop; diff against
   `68e15e8`.
3. Mode 2: check **Enable TLS**, leave **CA Certificate** blank, point at a broker with a publicly
   trusted certificate on **8883** (HiveMQ Cloud or similar) — remember to change the port
   yourself. Publish must succeed.
4. Mode 3: paste the CA PEM of a self-signed or private-CA broker on **8883**. Publish must succeed.
5. **The negative case, and the single most important runtime check in this release.** Keep mode 3
   but paste the **wrong** CA (any unrelated certificate PEM). The publish must **FAIL** —
   `unable to find valid certification path to requested target` or similar. A success here means
   trust is not being enforced and the release must not ship.
   Worth a second pass if a hostname is easy to fake locally: point the connected system at a host
   name the broker certificate does **not** cover and confirm the failure names
   `No subject alternative DNS name matching …`. That is hostname verification working.
6. Run the **subscribe** integration over TLS in mode 2 or 3 and confirm it collects messages — the
   second Paho call site uses the same factory but has never run over `ssl://`.
7. Paste a bare number (e.g. `279401`) into **CA Certificate** with TLS on and run either
   integration: expect `Invalid configuration` naming `-----BEGIN CERTIFICATE-----` and an Appian
   document id, **not** a crypto stack trace. Then uncheck **Enable TLS** while leaving a real CA
   pasted and confirm the rule-5 error appears rather than the CA being silently ignored.
8. Report back; only then update steering §5 to name 1.0.7 as the known-good reference and record
   the deploy as confirmed.

---

## 9. Risks, and what I judge impossible

**Nothing in the brief proved impossible.** Two things turned out differently than the brief
assumed, both in the project's favour:

- **Hostname verification already happens** (§0.1), so the wrapper the brief sketched is not needed
  — and building one would have been a net harm, since the one overload Paho uses is the one such
  wrappers forget (§0.2).
- **Runtime certificate generation with public JDK 17 APIs only is possible** (§4.1), measured, so
  no key material, no `--add-exports` and no BouncyCastle are needed. There is no public JDK
  certificate *builder*, which is why the fixture hand-writes DER; that is the whole difficulty.

Ranked risks:

1. **The ASN.1 fixture is the largest implementation risk.** A wrong tag or length silently yields
   `CertificateException` and the failure is opaque. Mitigation: the probe at
   `/tmp/paho-probe/CertGen.java` is known-good on Corretto 17 — reproduce that structure rather
   than inventing one, and build up by asserting `cert.getVersion() == 3`,
   `getBasicConstraints() >= 0`, `getSubjectAlternativeNames()` and `leaf.verify(caPublicKey)`
   before relying on the trust manager. Fallbacks in §4.1.
2. **Maven runs on JDK 23 here while the plug-in targets 17.** `mvn -o -v` reports
   `Java version: 23.0.1` with `--release 17`, so bytecode is major 61 but tests execute on 23. The
   TLS behaviour was probed on **both** 23.0.1 and Corretto 17.0.20.1 with identical results, and
   every assertion in §4.3 is bounded loosely (">20 accepted issuers", "contains TLSv1.2 and not
   TLSv1/TLSv1.1") so neither JDK can fail it. Do not tighten them to exact numbers.
3. **Mode 2 depends on the Appian server's trust store, which cannot be tested offline.**
   `TrustManagerFactory.init((KeyStore) null)` uses the server JVM's `cacerts` (or a JSSE
   trust store an administrator configured). A broker whose CA is publicly trusted but absent from
   that store fails in mode 2 and works in mode 3. Document it; it is also why mode 3 exists.
4. **The `ssl` `NetworkModuleFactory` must resolve through Appian's plug-in classloader.**
   `NetworkModuleService` uses a `ServiceLoader` over
   `META-INF/services/org.eclipse.paho.client.mqttv3.spi.NetworkModuleFactory`, and the `ssl`
   factory is listed in the same file as the `tcp` factory that already resolves on a live instance,
   so this should be free. It is still the one new platform assumption in the release, and the
   symptom would be `IllegalArgumentException` on the `ssl://` URI at connect time. Deploy step 3
   is what clears it.
5. **The hard error of rule 5 is a judgement call that changes behaviour for a stale
   configuration.** It is argued in §3.2 and is the recommendation; the reviewing step may overrule
   it, in which case drop the rule and document the silent-ignore instead. Implement exactly one.
6. **Three-`Boolean` argument lists.** §1.3 orders the parameters so a `String` separates the TLS
   flag from the session flags, because no offline test can catch a swap between two `Boolean`s on
   the `publish` path. Deploy steps 2 and 3 together would expose one.
7. **Parameter-count creep.** `publish` goes to 14 parameters and `subscribe` to 15. That is ugly
   and I am not fixing it here: a configuration-carrier record is exactly the `MqttConnectionConfig`
   indirection 1.0.4 deliberately deleted, and reintroducing it in the same release as new
   cryptographic code would put two risky changes in one deploy. Flag it as a candidate refactor
   for a release that changes nothing else.

