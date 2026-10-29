# Server-side TLS — 1.0.7 release record

**Status: implemented and verified offline. NOT yet deployed.** The known-good reference is still
1.0.6 / commit `68e15e8`. This change is an **uncommitted working-tree change** — the user owns git,
so do not cite a SHA for it. The tip of `main` when it was written was `8621e99`.

Artifact: `target/mqtt-broker-connected-system-1.0.7-plugin.jar`.
Verification evidence with real pasted output: `server-side-tls-1.0.7-verification.md`.
Plan: `server-side-tls-1.0.7-plan.md`.

**Scope: server-side TLS only.** The plug-in verifies the broker. **Mutual TLS** — presenting a
client certificate and private key — is explicitly out of scope and stays out of scope in this
release.

---

## 1. What changed

| Area | Change |
|---|---|
| Connected system | Two new fields, `tlsEnabled` (Boolean) and `caCertificate` (Paragraph), inserted after `brokerPort`. **Nine → eleven.** |
| New source | `de.hock.mqtt.TlsSupport` — URI scheme, PEM parsing, trust managers, socket factory. **7 → 8 sources.** |
| Paho call sites | `MqttPublisher.publish` and `MqttSubscriber.subscribe` take the two new values; both build the URI through `TlsSupport.brokerUri`. **Still exactly two Paho call sites.** |
| Options assembly | `MqttPublisher.buildConnectOptions` installs the socket factory and sets `setHttpsHostnameVerificationEnabled(true)`. It now `throws GeneralSecurityException`. |
| Validator | Three new rules. **Four → seven.** |
| Integrations | Both read the two values, pass them through, and add a fixed-message `catch (GeneralSecurityException)` ahead of the existing catch. |
| Tests | **23 → 35** in **nine** classes, plus a non-test fixture factory. |
| Resource bundle | **22 → 24** keys, **16 → 18** field labels. |
| Dependencies | **None added.** All crypto is JDK. `THIRD-PARTY-NOTICES.md` unchanged, confirmed. |
| Version | `1.0.6` → `1.0.7` in `pom.xml` and `appian-plugin.xml`. `<application-version min="26.9.0.0"/>` untouched. |

Deliberately **not** changed: `src/assembly/plugin-assembly.xml` (its `fileSet` picks up the new
source automatically), SDK versions (core 1.9.1, client 1.3.0, both `provided`), Paho 1.2.5, the
plug-in key, all three module keys, and every `§1` hard constraint in the steering file.

---

## 2. The hostname-verification finding — the security crux

### What the brief feared, and why it is not the case

The brief's premise was that Paho uses the supplied `SSLSocketFactory` for `ssl://` but does not
necessarily verify the broker's host name, so TLS would be "encrypted but only authenticated as
*presents a certificate this CA signed*" — a materially weaker guarantee than a checkbox labelled
TLS implies.

**That premise is false for Paho 1.2.5. Hostname verification happens, by default.** The plan
established this and I re-verified every step independently with `javap` before relying on it.

### The evidence

Measured against
`~/.m2/repository/org/eclipse/paho/org.eclipse.paho.client.mqttv3/1.2.5/org.eclipse.paho.client.mqttv3-1.2.5.jar`.

**1. The `ssl` scheme factory propagates the flag.**
`javap -p -c org.eclipse.paho.client.mqttv3.internal.SSLNetworkModuleFactory`:

```
 9:  public java.util.Set<java.lang.String> getSupportedUriSchemes();
17:      10: ldc  #1    // String ssl          <- this class IS the ssl:// factory
70:      55: invokevirtual MqttConnectOptions.getSocketFactory:()Ljavax/net/SocketFactory;
119:    162: invokevirtual MqttConnectOptions.isHttpsHostnameVerificationEnabled:()Z
120:    165: invokevirtual SSLNetworkModule.setHttpsHostnameVerificationEnabled:(Z)V
```

**2. The flag defaults to `true`.** `javap -p -c MqttConnectOptions`, constructor:

```
31: aload_0
32: iconst_1
33: putfield  #251   // Field httpsHostnameVerificationEnabled:Z
```

**3. `SSLNetworkModule.start()` applies endpoint identification before the handshake.**

```
 35: new javax/ssl/SSLParameters ... 72: setServerNames  ... 83: SSLSocket.setSSLParameters
 91: getfield httpsHostnameVerificationEnabled:Z
 94: ifeq 126                                       // skipped when false
 97: new SSLParameters
106: ldc "HTTPS"
108: invokevirtual SSLParameters.setEndpointIdentificationAlgorithm:(Ljava/lang/String;)V
119: invokevirtual SSLSocket.setSSLParameters
133: invokevirtual SSLSocket.startHandshake:()V
137: getfield hostnameVerifier  ... 144: getfield httpsHostnameVerificationEnabled
170: invokeinterface HostnameVerifier.verify
Exception table: 97..122 -> Class java/lang/NoSuchMethodError   (silently swallowed)
```

**4. Confirmed end to end, offline.** `TlsHostnameVerificationTest`'s two handshake tests stand up a
loopback `SSLServerSocket` presenting a leaf signed by a throwaway CA, and connect with the
plug-in's own `TlsSupport.socketFactory(caPem)`, replicating Paho's exact socket sequence. A SAN of
`localhost` completes the handshake; a SAN of `wrong.example.com` fails with `SSLHandshakeException`.
Proved non-vacuous by giving the mismatch test a matching SAN once and watching it fail.

**5. The host name survives Paho's socket-creation pattern.** `TCPNetworkModule.start()`, inherited
by `SSLNetworkModule`, calls the **no-argument** `createSocket()` and then
`connect(new InetSocketAddress(host, port), timeout)`. An unconnected `SSLSocket` has no peer host,
which is the classic way verification degrades to an IP check — it does not happen here, because
`InetSocketAddress(String,int)` retains the name and `SSLSocketImpl` adopts it. The handshake tests
reproduce this sequence precisely, which is what makes them evidence about *Paho* rather than about
an idealised TLS client.

### What I did about it

**Set the flag explicitly; built no wrapper.**

```java
options.setHttpsHostnameVerificationEnabled(true);   // in MqttPublisher.buildConnectOptions
```

unconditionally — it is inert when TLS is off, since only the `ssl` and `wss` factories read it —
and `setSSLHostnameVerifier` is deliberately left `null`.

Three reasons a wrapper was rejected, in order of weight:

1. **It would be redundant.** Paho already applies `HTTPS` endpoint identification after connect and
   before `startHandshake()`. A wrapper would set the same parameter earlier.
2. **A wrapper is how 1.0.3's TLS would have failed on its first connection — a third, previously
   unrecorded defect in that code.** `ConnectOptionsFactory` contained
   `TlsVersionConstrainingSocketFactory`, overriding six `createSocket` overloads but **not** the
   no-argument one, which is the only one Paho calls. `javap -c javax.net.SocketFactory` shows the
   inherited no-arg implementation:

   ```
   0: new java/net/SocketException
   4: ldc "Unconnected sockets not implemented"
   ```

   So every TLS connection in 1.0.3 would have died with `SocketException: Unconnected sockets not
   implemented`. Add this to the two recorded 1.0.3 TLS defects.
3. The flag is set explicitly rather than inherited precisely because the
   `setEndpointIdentificationAlgorithm` block sits inside `catch (NoSuchMethodError)` — irrelevant
   on Java 17, but a reason not to depend on an unseen default.

### The guarantee, and the residual risks

In modes 2 and 3 the broker is authenticated as *"presents a certificate chain the configured trust
anchor signed, whose subject alternative name matches the configured host"*. That is the full
guarantee, not the weakened one the brief feared.

Residual risks, documented in the README in designer-facing terms:

- Verification matches **the host string as typed**, so configuring a broker by IP address requires
  a certificate carrying that IP in its SAN. Prefer a host name.
- **In mode 2 any CA the Appian server trusts can vouch for the host.** That is the ordinary web PKI
  trust model and the right trade-off for a publicly trusted broker; mode 3 narrows trust to one CA.
- It depends on `httpsHostnameVerificationEnabled` staying true, which the plug-in now sets rather
  than inherits — and a test pins Paho's own default, so a dependency bump that flipped it fails the
  build instead of silently weakening every connection.

### No escape hatch, and no argument for one

Not implemented, not flagged, not test-only. There is no trust-everything `X509TrustManager`
anywhere, and the verification greps confirm it. Mode 3 covers self-signed and private-CA brokers
properly by trusting the specific certificate, and the tests generate their own CA at run time, so
no test needs verification disabled either. There is nothing to report beyond that.

---

## 3. The three connection modes

| Mode | `tlsEnabled` | `caCertificate` | URI | `options.getSocketFactory()` | Trust anchors |
|---|---|---|---|---|---|
| 1 | `false` / absent | must be blank (rule 5) | `tcp://host:port` | **`null`** | n/a — no TLS |
| 2 | `true` | blank | `ssl://host:port` | non-null | JVM default trust store |
| 3 | `true` | PEM | `ssl://host:port` | non-null | **only** the pasted certificate(s) |

- **Mode 1 is the default and is unchanged from 1.0.6.** An existing connected system carries no
  value for either new key, `tlsEnabled` reads `null` → `false`, and it keeps connecting over plain
  TCP. `isTlsEnabled` is the single place that `null`-means-false rule lives, and both the URI and
  the socket factory read it, so they cannot disagree.
- **Mode 2** is correct for a broker with a publicly trusted certificate (HiveMQ Cloud and similar).
  A CA is not forced. `TrustManagerFactory.init((KeyStore) null)` *reads* the JSSE configuration an
  administrator may have set for the Appian server — a read, never a write, and a feature.
- **Mode 3** trusts that CA alone, measured: exactly one accepted issuer, a leaf signed by it
  accepted, an unrelated self-signed certificate rejected.

**The port is the designer's responsibility.** 1883 plain, 8883 TLS by convention, and **the plug-in
never changes the configured port** when the checkbox flips. Said in the field instruction text and
in the README; a `tcp` port left behind is the commonest cause of a failed TLS connection.

---

## 4. How the `SSLContext` is built

`de.hock.mqtt.TlsSupport`, root package beside the two Paho call sites whose behaviour it configures
— the same reasoning that put `ConnectionConfigValidator` there at 1.0.6. A `tls` package would
imply a layer; this is one small utility class. `final`, private constructor, all members static.

| Member | Visibility | Purpose |
|---|---|---|
| `PEM_CERTIFICATE_HEADER` | package-private | shared with the validator's "is this even PEM" check |
| `brokerUri(Boolean, String, int)` | public | `ssl://` when TLS on, else `tcp://` |
| `isTlsEnabled(Boolean)` | package-private | the one `null`-means-false rule |
| `parseCertificates(String)` | package-private | `throws GeneralSecurityException`; never echoes input |
| `trustManagers(String)` | package-private | the seam that proves trust is *restricted* |
| `socketFactory(String)` | public | `throws GeneralSecurityException` |

The chain: `CertificateFactory` over a `ByteArrayInputStream` → `KeyStore.getInstance(getDefaultType())`
opened with `load(null, null)` → `TrustManagerFactory` → `SSLContext.getInstance("TLS")` →
`getSocketFactory()` → `MqttConnectOptions.setSocketFactory`.

**Hard rules observed:**

- **In memory only. No temporary file, ever.** Appian forbids plug-ins manipulating the filesystem;
  the 1.0.3 code wrote keystore files to disk.
- **No `System.setProperty`.** The usual `javax.net.ssl.*` trust-store route mutates JVM-global
  state, which Appian forbids and which would change TLS behaviour for every other plug-in on the
  server. The verification grep returns exactly one hit: a Javadoc sentence asserting the *absence*
  of such a call.
- **Protocol: `SSLContext.getInstance("TLS")`, deliberately not pinned.** The JDK negotiates its
  best available and inherits the platform's own deprecation schedule; a test asserts a produced
  socket offers TLS 1.2 and neither TLS 1.0 nor 1.1. Pinning `"TLSv1.2"` as 1.0.3 did would
  **exclude TLS 1.3**, and the `setEnabledProtocols` wrapper 1.0.3 added to compensate is the broken
  wrapper of §2.
- **Chains supported.** `generateCertificates` reads every `-----BEGIN CERTIFICATE-----` block; each
  is loaded under its own alias (`ca-0`, `ca-1`, …) so none overwrites another.
- **An empty parse result is an error, not an empty trust store.** Measured: an empty input returns
  zero certificates *without throwing*, while a bare number or prose throws. Allowing a zero-length
  result would make mode 3 silently become "trust nothing", or be mistaken for mode 2.
- **Nothing closeable is opened.** The only stream is over a byte array already in memory, so there
  is no OS resource to release.
- **No message carries certificate material.** Failures are `GeneralSecurityException` with fixed
  sentences and the original as cause.

**Where the pieces live, and why.** TLS is applied in `buildConnectOptions` — the method both call
sites already use and have no alternative to — so the configuration→Paho mapping stays in exactly
one place and neither call site can forget TLS. Consequence: that method is no longer total; it
`throws GeneralSecurityException`. Its two out-of-range *session* fallbacks are unchanged and still
never throw. `TlsSupport` holds **no** Paho reference, so the "exactly two Paho call sites"
invariant survives.

**Argument order.** Three `Boolean`s in a row is how a silent argument swap happens and no offline
test can catch one, so the new parameters are placed where a `String` separates `tlsEnabled` from
the session `Boolean`s: `(tlsEnabled, caCertificatePem, username, …)` on `buildConnectOptions`, and
inserted after `brokerPort` on `publish` / `subscribe`.

---

## 5. The field wiring

The eleven keys in display order, with the two new ones after `brokerPort` so transport settings sit
together and the port instruction lands next to the checkbox that changes which port is right:

```
 1 brokerHost                 Text            isRequired true
 2 brokerPort                 Integer         isRequired true; instruction text updated for 8883
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

Descriptor order is presentation only — state is keyed by name — so reordering cannot disturb a saved
connected system.

**Why Paragraph and not Document, verified rather than assumed.** `javap -p -c` on
`ParagraphPropertyDescriptor$ParagraphPropertyDescriptorBuilder` in `connected-systems-core-1.9.1.jar`:
the constructor calls only `PropertyDescriptorBuilder.type(SystemType.PARAGRAPH)` and
`data.put("paragraphHeight", TALL)`, and `build()` invokes **no** `Guard`. It neither forces the
expressionable modifier nor can refuse to build without it. `DocumentPropertyDescriptor` does the
opposite — its builder force-sets the flag and `Guard.checkPropertyIsExpressionable` refuses to build
with it cleared — and a connected system that declares any expressionable descriptor is rejected
whole at conversion time. That exact combination caused the 1.0.2 → 1.0.3 deploy failure. Paragraph
is also the *only* multi-line text input, since `DisplayHint` offers only `NORMAL` and `EXPRESSION`.
So **there is no document-upload field available on a connected system template with this SDK**, and
pasted PEM is not a shortcut but the only option.

Neither new builder call touches `isExpressionable`, so the template's count stays **0** and the
repo-wide count stays **7** (publish 3, subscribe 4). The sufficient proof is
`noConnectedSystemPropertyDescriptorIsExpressionable`, which reads every modifier back through the
SDK accessor across all eleven fields — a grep alone would have shipped the 1.0.2 document fields.

**Effective defaults need no machinery.** `StateGenerator` seeds `BOOLEAN` → `Boolean.FALSE` and
`PARAGRAPH` → `""`, so a brand-new page renders **Enable TLS unchecked** and **CA Certificate
empty** — exactly the required defaults. No seeding, no first-render gate, no `setValue` call; the
machinery 1.0.4 removed stays removed.

---

## 6. The validator rules

`ConnectionConfigValidator.firstError` keeps its established shape — one small class returning the
first problem as a sentence or `null`, called by both integrations before any socket is opened.
Rules 1–4 keep their exact wording and order, so no existing test message changed.

| # | Rule | Message |
|---|---|---|
| 5 | CA supplied while TLS off | "A CA certificate is configured but TLS is disabled on the MQTT Broker connected system. Enable TLS, or clear the CA Certificate field." |
| 6 | TLS on, CA present, not PEM | "…is not a PEM certificate. It must start with `-----BEGIN CERTIFICATE-----` and end with `-----END CERTIFICATE-----`. Paste the certificate text itself, not a document id, a file name or a path." — plus " The configured value looks like an Appian document id." when the trimmed value is only digits |
| 7 | TLS on, header present, will not parse | "…could not be parsed as an X.509 certificate. Check that the whole PEM block was pasted, including the BEGIN and END lines, and that no characters were lost." |

Rule 7 calls `TlsSupport.parseCertificates` and **discards** the caught exception's message rather
than appending it. All three messages are well under the 500-character truncation limit, name the
field by its rendered label, and contain no field content — asserted by tests that scan for any
six-character run of the input.

**Design-time validation is not used and must not be.** Appian discards connected system field
errors (measured on a live instance at 1.0.5), so nothing here touches `setErrorAtPath`.
`isRequired` remains the only design-time guard, and neither new field uses it.

### Rule 5 is a hard error — the judgement call, and its trade-off

A CA pasted while TLS is off is **configured but inert**, which is precisely the failure this
project already condemned in 1.0.3's `maxReconnectAttempts` — a field nothing enforced. Appian gives
no design-time warning channel and `IntegrationResponse.forSuccess` has no warning slot, so the only
way to tell a designer is to fail the call.

**The cost, stated in the README rather than hidden:** a designer who unchecks **Enable TLS** to
test a plain connection must also clear the CA field, and until they do, **both** integrations fail
with `Invalid configuration`. That is a visible, self-explaining, one-field fix.

Alternatives considered and rejected: ignore silently (the inert-configuration trap); ignore and log
(the plug-in has no logging by design); return success with a warning field (changes the output shape
for every caller to carry a message almost nobody reads).

### The integrations

Both read the two new values beside the other connected-system reads, keep the existing guard order
unchanged — blank topic/filter, then `ConnectionConfigValidator.firstError`, then the input-range
check — and pass both through. So the TLS rules run inside the existing `Invalid configuration`
branch, before any broker contact.

Both add a **separate** catch ahead of the existing one, with a fixed message:

```java
} catch (GeneralSecurityException e) {
  return error("Invalid configuration",
      "The TLS configuration of the MQTT Broker connected system could not be applied."
          + " Check the CA Certificate field.");
}
```

No `e.getMessage()`, no `e.toString()`. This guarantees the no-echo rule even if a future JDK starts
quoting input in a crypto exception message; it is defence in depth, since rule 7 already rejected
unparseable PEM.

The existing `catch (MqttException | RuntimeException e)` branch still includes `e.getMessage()`,
which for a TLS failure is Paho's own wording — `No subject alternative DNS name matching <host>
found`, or `unable to find valid certification path to requested target`. That names the host the
designer typed and no certificate material, and it is genuinely the most useful diagnostic in the
release, so both messages are documented in the README as recognisable.

---

## 7. Behaviour notes

1. **Plain `tcp://` is untouched when TLS is off.** The default, and the most important thing not to
   regress. Pinned by `tlsDisabledProducesATcpUriAndNoSocketFactory`, which asserts a **null** socket
   factory even with a valid CA present in state.
2. **An existing 1.0.6 connected system keeps working with no action.** It carries no value for
   either new key; `null` reads as `false`.
3. **A stale CA with TLS off now fails both integrations** until the field is cleared. This is the
   one deliberate user-visible behaviour change in the release (§6).
4. **The configured port is never rewritten** by the TLS flag.
5. **Mode 2 depends on the Appian server's trust store**, which cannot be tested offline. A broker
   whose CA is publicly trusted in general but absent from that store fails in mode 2 and works in
   mode 3 — one of the reasons mode 3 exists.
6. **Nothing is logged, written to disk, or set as a JVM global** — unchanged, and now also true of
   the TLS path.
7. **Parameter-count creep, flagged not fixed.** `publish` is now 14 parameters and `subscribe` 15.
   That is ugly. A configuration-carrier record is exactly the `MqttConnectionConfig` indirection
   1.0.4 deliberately deleted, and reintroducing it in the same release as new cryptographic code
   would put two risky changes in one deploy. Candidate refactor for a release that changes nothing
   else.

---

## 8. Test inventory — 23 → 35 in nine classes

No test needs a broker, an Appian runtime, or a network route off the machine. Two bind an ephemeral
loopback port on `127.0.0.1`.

**Updated, no count change:** the descriptor test's key list → eleven and `assertEquals(11, inspected)`;
the no-design-time-errors test's key list → eleven; the two `buildConnectOptions` calls in
`MqttPublisherConnectOptionsTest` gained `Boolean.FALSE, null` and a `throws`.
`MqttSubscribeIntegrationInputsTest` needed no edit — confirmed, as the plan predicted, because it
drives `inputError`/`qosOf`/`success` rather than `subscribe`.

**Added (+12):**

- `MqttConnectedSystemTemplateDescriptorTest` (7 → 8) —
  `theTwoTlsFieldsUseBooleanAndParagraphDescriptorsAndAreNotExpressionable`: the field-specific guard
  against anyone "upgrading" the CA field to a document upload.
- `TlsSupportTest` (new, 5) — mode 1 (tcp URI, null factory even with a CA present), mode 2 (ssl URI,
  >20 default issuers, a self-signed fixture rejected, TLS 1.2 on and 1.0/1.1 off), **mode 3 (exactly
  one issuer, a CA-signed leaf accepted, an unrelated certificate REJECTED)**, a two-block chain
  loading both, and malformed input rejected without echoing its content.
- `TlsHostnameVerificationTest` (new, 3) — Paho's default flag is true and the plug-in sets it
  explicitly with a null verifier; two loopback handshakes, matching SAN and mismatched SAN.
- `ConnectionConfigValidatorTest` (4 → 7) — the three TLS rules, each also asserting no echo, and the
  first carrying the mode-2 and mode-3 control cases.
- `CertificateFixtures` (new, not a test) — throwaway CAs, leaves and PEM generated at run time.

**On the fixtures, because it is the largest implementation risk and it worked.** There is no public
JDK API that *creates* an X.509 certificate: `CertificateFactory` only parses, and
`sun.security.x509.CertAndKeyGen` is not exported from `java.base`. So `CertificateFixtures`
hand-writes the certificate DER — a small ASN.1 writer with `tlv`/`seq`/`integer`/`oid`/`utcTime`/
`explicit`, `basicConstraints`, `keyUsage` and a `dNSName` `subjectAltName` — and signs it with
`java.security.Signature`. All public API. **Neither of the plan's fallbacks was needed**: no
`--add-exports`, no BouncyCastle, no `keytool` subprocess, and no reduction to the
single-certificate-only proof. Private keys exist only as in-memory objects, the in-memory server key
store uses a randomly generated single-use passphrase rather than a literal, and nothing is written
to disk. **No key material is committed.**

**One honest note on a test, rather than a silent weakening.** The no-echo assertion scans for any
six-character run of the input, which cannot distinguish a leak from a coincidence: an input of
`"not a certificate at all"` tripped it on `" certi"` purely because the plug-in's fixed message
contains the word "certificate". The **inputs** were changed to share no English with the messages
(`"garbage pasted by mistake"`); the scan was **not** weakened, because a leak of real certificate
material is base64 and digits, which is exactly what the strict scan catches.

---

## 9. Verification summary

Full pasted output with expected-versus-actual for every command:
**`server-side-tls-1.0.7-verification.md`**.

Headline figures, all observed:

| Check | Expected | Actual |
|---|---|---|
| `mvn -o clean package` | BUILD SUCCESS | BUILD SUCCESS |
| `mvn -o test` | 35 / 0 / 0 | **35, Failures: 0, Errors: 0** |
| jars in `target/` | 1 | 1 |
| `.java` in jar | 8 | 8 |
| sources outside `de/hock/mqtt/` | 0 | 0 |
| bare-root `appian-plugin.xml` | 1 | 1 |
| nested jars in `META-INF/lib/` | 1 (Paho only) | 1, Paho 1.2.5 |
| `connected-systems` entries | 0 | 0 |
| manifest `<version>` | `1.0.7` three-part | `1.0.7` |
| `<application-version min>` | `26.9.0.0` four-part | `26.9.0.0` |
| vendor | Hock | Hock |
| module keys | 3, unchanged | 3 |
| bytecode major version | 61 ×7 | 61 ×7 |
| `isExpressionable` template / repo / per file | 0 / 7 / 3+4 | 0 / 7 / 3+4 |
| SDK imports | 37 across 4 files | 37 across 4 files |
| bundle keys / field labels | 24 / 18, no diff | 24 / 18, no diff |
| `System.setProperty` etc. | 1 prose hit only | 1 prose hit in `MqttPublisher` |
| permissive trust manager | none | none |
| key material in repo or jar | none | none |
| `THIRD-PARTY-NOTICES.md` | unchanged | unchanged |

**One expectation did not hold on first run and was fixed.** The TLS-surface grep
(`SSLContext|setSocketFactory|TrustManagerFactory`) was expected to name two files and named
**three**: `package-info.java` matched on the literal token `SSLContext` in its Javadoc prose, not on
any call. I reworded the prose — "the trust material is assembled entirely in memory and handed to
Paho as a socket factory" — rather than loosening the expectation, which is the pattern four other
Javadocs in this project already follow so documented greps stay exact. Weakening the check to "two
or three files" would have made it unable to detect a genuine third call site. After the reword the
grep returns exactly `MqttPublisher.java` and `TlsSupport.java`, and the build was re-run clean.

**Nothing in the verification proves Appian 26.9 compatibility.** Final confirmation requires
deploying the jar.

---

## 10. Deploy-and-test checklist

1. Copy `target/mqtt-broker-connected-system-1.0.7-plugin.jar` to `APPIAN_INSTALL/_admin/plugins/`.
   Confirm **Admin Console → Plug-ins** shows **1.0.7**, and `tomcat-stdOut.log` shows
   `Successfully installed Plug-in` plus the `ConnectedSystemTemplateModuleParser` lines.

2. **The regression check that matters most.** Open the **existing** MQTT Broker connected system: it
   must render **eleven** fields with every previously saved value intact, **Enable TLS** unchecked
   and **CA Certificate** empty. Run the existing publish and subscribe integrations unchanged over
   plain `tcp://` — both must still succeed. If this fails, stop and diff against `68e15e8`.

3. **Mode 2.** Tick **Enable TLS**, leave **CA Certificate** blank, point at a broker with a publicly
   trusted certificate on **8883** — remember to change the port yourself. Publish must succeed. This
   is also what clears the one new platform assumption in the release: that Paho's `ssl`
   `NetworkModuleFactory` resolves through Appian's plug-in classloader. The symptom of failure would
   be an `IllegalArgumentException` on the `ssl://` URI at connect time.

4. **Mode 3.** Paste the CA PEM of a self-signed or private-CA broker on **8883**. Publish must
   succeed.

5. **The negative case, and the single most important runtime check in this release.** Keep mode 3
   but paste the **wrong** CA — any unrelated certificate PEM. The publish must **FAIL**, with
   `unable to find valid certification path to requested target` or similar. **A success here means
   trust is not being enforced and the release must not ship.**
   Worth a second pass if a host name is easy to fake locally: point the connected system at a name
   the broker certificate does **not** cover and confirm the failure names `No subject alternative
   DNS name matching …`. That is hostname verification working.

6. **Subscribe over TLS**, in mode 2 or 3, and confirm it collects messages. The second Paho call
   site uses the same factory but has never run over `ssl://`.

7. **The validation messages.** Paste a bare number (e.g. `279401`) into **CA Certificate** with TLS
   on and run either integration: expect `Invalid configuration` naming
   `-----BEGIN CERTIFICATE-----` and an Appian document id, **not** a crypto stack trace. Then untick
   **Enable TLS** while leaving a real CA pasted and confirm the rule-5 error appears rather than the
   CA being silently ignored.

8. **Report back.** Only then update steering §5 to name 1.0.7 as the known-good reference and record
   the deploy as confirmed.

---

## 11. Nothing proved impossible

Both of the brief's hedged requirements resolved in the project's favour, and neither of the plan's
fallbacks was needed:

- **Hostname verification did not need implementing** — Paho already does it (§2), and the wrapper
  the brief sketched would have been a net harm, since the one overload Paho uses is the one such
  wrappers forget.
- **Run-time certificate generation with public JDK 17 APIs only is possible** (§8), so the mode-3
  restriction proof, the chain test and both handshake tests exist with no committed key material, no
  `--add-exports` and no new dependency.

The only thing I declined to do is in the brief's own terms: no test-only verification escape hatch
was implemented, and on examination there is no argument for one to report.
