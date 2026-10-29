# MQTT Broker Connected System

**Version `1.0.7` adds server-side TLS.** The connected system gains two fields — **Enable TLS**
and **CA Certificate** — taking it from nine to **eleven**, and the broker URI switches from
`tcp://` to `ssl://` when TLS is on. Three connection modes are supported: plain TCP, TLS against
the Appian server's own trusted certificate authorities, and TLS against a single pasted CA. See
[TLS](#tls).

Two things worth reading before you enable it:

- **Hostname verification works, and that is a measured result, not an assumption.** Paho 1.2.5
  applies `HTTPS` endpoint identification to the `ssl://` socket before the handshake, so a broker
  certificate whose subject alternative name does not match the configured host is rejected. The
  evidence and the residual risks are in [Hostname verification](#hostname-verification).
- **Mutual TLS is deliberately not supported.** The plug-in verifies the broker; it presents no
  client certificate of its own. A broker that requires one will fail the handshake.

### How we got here, briefly

`1.0.4` was a deliberate, requested scope reduction from the full `1.0.3` plug-in, after three
successive deploy-time platform-contract failures. It validated four things on a live Appian 26.9
instance: the plug-in installs, the bundled **Eclipse Paho** library loads and is usable at run time,
a **Connected System configuration page renders**, and **one integration can actually publish**.
`1.0.5` added four session fields — `connectionTimeoutSeconds`, `keepAliveSeconds`, `cleanSession`,
`autoReconnectEnabled`, bringing the connected system to **nine** fields, every one of which reaches
`MqttConnectOptions` — plus the design-time validation probe whose negative answer `1.0.6` acts on.
Both deployed and worked. The connected system was unchanged at nine fields in `1.0.6`, which added
the subscribe integration. `1.0.7` takes it to **eleven** with the two server-side TLS fields.

## What is still deliberately removed

- **Mutual TLS** — `clientCertificate`, `clientPrivateKey`, `keyPassphrase` and the key-manager
  wiring. Server-side TLS ships in `1.0.7`; presenting a *client* certificate does not. The `1.0.3`
  versions of those fields are a cautionary reference rather than a basis, because that TLS code
  carried **three** defects, two recorded at the time and one found while planning this release:
  the runtime chain only ever consumed `byte[]` and never resolved an Appian document id into
  bytes, so a configured certificate could never reach the socket factory; a supplied passphrase
  could not actually unlock a private key; and its `TlsVersionConstrainingSocketFactory` overrode
  six `createSocket` overloads but **not** the no-argument one, which is the only one Paho calls —
  so every TLS connection would have died with `SocketException: Unconnected sockets not
  implemented`. It also wrote temporary keystore files to disk, which Appian's policy forbids.
- **Multi-topic subscribe.** `topicFilter` takes exactly **one** filter, not a list. A list input
  would need `ListTypePropertyDescriptor`, whose builder force-sets both the expressionable modifier
  and an `EXPRESSION` display hint in its constructor and then couples the two in its own `build()`
  guard. That is the same family of forced-flag builders whose connected-system equivalent,
  `DocumentPropertyDescriptor`, caused a live deploy failure. Such a descriptor is *very probably*
  legal on an **integration** input — the validator that rejected the forced flags is
  connected-system-specific — but that has never been observed on a real instance and cannot be
  proven offline, and this project has already lost three deploy cycles to descriptor-contract
  surprises. **Workaround:** MQTT wildcards cover most real subscriptions — `sensors/+/temp` matches
  one level, `home/#` matches a whole subtree. Multi-topic is a deliberate follow-up for a later
  version, once this release is proven on a real instance.
- **Last Will and Testament** — `lwtTopic`, `lwtPayload`, `lwtQos`, `lwtRetained`.
- **Reconnect attempt tuning** — `maxReconnectAttempts` and `reconnectIntervalSeconds`. These are
  **not coming back**: Paho 1.2.5's `MqttConnectOptions` exposes no attempt-count setter and no
  reconnect-interval setter (the only related member is `setMaxReconnectDelay(int)`, a cap on Paho's
  internal exponential backoff), so neither value could be honoured. `1.0.3` shipped
  `maxReconnectAttempts` as a configurable field that nothing enforced, which is a trap for
  designers.
- **Diagnostics and redaction** — the whole `diagnostics` package and the `MqttErrorCategory`
  taxonomy.
- The `retained` publish input.

**All of it is recoverable.** The complete `1.0.3` source — 36 Java files plus the pom, README,
`info.md`, `THIRD-PARTY-NOTICES.md`, `docs/` and `.kiro/` — is in git commit `d023a76`
(`git checkout d023a76 -- src pom.xml README.md`) and archived at
git commit `d023a76`. See
[Restoring the full build](#restoring-the-full-build).

## Facts

- **Plug-in key:** `de.hock.mqtt`
- **Version:** `1.0.7` (manifest `<plugin-info><version>`, three-part `x.y.z`)
- **Minimum Appian version:** `26.9.0.0`
- **Vendor:** Hock, <https://itondemand.com>

The plug-in key and all three module keys are unchanged from `1.0.3`, and `1.0.7 > 1.0.6`, so this
build **replaces** the installed plug-in rather than installing alongside it.

## What this plug-in provides

`src/main/resources/appian-plugin.xml` registers one connected system template with two nested
integration templates:

| Module | Manifest key | Designer-facing name | Class |
|---|---|---|---|
| Connected system template | `MqttConnectedSystemTemplate` | MQTT Broker | `de.hock.mqtt.template.MqttConnectedSystemTemplate` |
| Integration template (`WRITE`) | `MqttPublishIntegration` | Publish MQTT Message | `de.hock.mqtt.integration.MqttPublishIntegration` |
| Integration template (`READ`) | `MqttSubscribeIntegration` | Subscribe to MQTT Topics | `de.hock.mqtt.integration.MqttSubscribeIntegration` |

### This plug-in does not contribute a smart service

Worth stating plainly, because it is a common expectation: there is no custom smart service here.
The manifest declares no `<smart-service>` module and there is no smart service class under
`src/main/java`. What you get is a connected system plus one integration template.

The usage model follows from that:

1. Create one **Connected System** object of type **MQTT Broker** and fill in the broker settings.
2. Create an **Integration** object against that connected system, choosing **Publish MQTT
   Message** or **Subscribe to MQTT Topics**.
3. Call that integration object from an expression, or from a process model using Appian's
   **built-in "Call Integration" smart service node**.

That `Call Integration` node is part of the Appian platform, not something this plug-in installs.

## Requirements

- Appian **26.9** (manifest sets `<application-version min="26.9.0.0"/>`)
- **Java 17** to build (`maven.compiler.release` is `17`)
- A reachable **MQTT broker** speaking MQTT 3.1.1 over plain TCP or TLS
- `com.appian:connected-systems-core:1.9.1`, Maven scope `provided` — supplied by the Appian
  runtime, never bundled
- `com.appian:connected-systems-client:1.3.0`, Maven scope `provided` — the `Simple*` template
  facade both templates subclass; also supplied by the Appian runtime, never bundled
- `org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5`, bundled inside the plug-in jar under
  `META-INF/lib/`

## Build

```bash
mvn clean package
```

The deployment artifact is:

```
target/mqtt-broker-connected-system-1.0.7-plugin.jar
```

The `-plugin` suffix comes from the assembly `<id>`; a jar without it is a stale build. Exactly one
jar is produced by design — the default `maven-jar-plugin` execution is bound to phase `none`, so
there is no second archive that also looks deployable. The jar carries the plug-in's **eight
`.java` sources** alongside the compiled classes (an AppMarket requirement), `appian-plugin.xml` at the
archive root, and Paho as a nested jar under `META-INF/lib/`.

## Deploy

Copy the jar into the plug-ins directory of the Appian installation:

```bash
cp target/mqtt-broker-connected-system-1.0.7-plugin.jar APPIAN_INSTALL/_admin/plugins/
```

Hot deployment is governed by the `conf.plugins.poll-interval` custom property (seconds). When it is
set to `0`, hot deployment is off and loading the plug-in requires an application server restart.
See [Appian Suite Plug-ins](https://docs.appian.com/suite/help/26.9/Appian_Plug-ins.html).

Confirm the deployment three ways:

- The **Admin Console → Plug-ins** page lists the plug-in at version `1.0.7`.
- **New > Connected System** in Appian Designer offers the **MQTT Broker** type **and the form
  renders eleven fields**. This is the check the three releases before `1.0.4` failed.
- **New > Integration** against that connected system offers **both** **Publish MQTT Message** and
  **Subscribe to MQTT Topics**.
- `tomcat-stdOut.log` shows a `Successfully installed Plug-in` line plus
  `ConnectedSystemTemplateModuleParser` registration lines.

## Configuring the Connected System

Create a Connected System object and pick the **MQTT Broker** template. The eleven fields below are
the whole configuration surface, listed in the order the template renders them. Keys are the
constants on `de.hock.mqtt.template.MqttConnectedSystemTemplate`; labels are from
`resources_en_US.properties`.

| Label | Key | Type | Required | Effective default | Validation |
|---|---|---|---|---|---|
| Broker Host | `brokerHost` | Text | Yes | — | Required; non-blank after trimming |
| Broker Port | `brokerPort` | Integer | Yes | — | Required; `1`–`65535`. Placeholder suggests `1883` |
| Enable TLS | `tlsEnabled` | Boolean | No | **`false`** | None |
| CA Certificate | `caCertificate` | Paragraph | No | Blank — the Appian server's own trusted CAs | Must be PEM when TLS is on; rejected outright when TLS is off. See [TLS](#tls) |
| Client Identifier | `clientIdentifier` | Text | No | Paho's `generateClientId()` | None |
| Username | `username` | Text | No | Anonymous connection | None |
| Password | `password` | Encrypted text, masked | No | Anonymous connection | None |
| Connection Timeout | `connectionTimeoutSeconds` | Integer | No | **5 seconds** | If supplied, `1`–`300` |
| Keep Alive Interval | `keepAliveSeconds` | Integer | No | **60 seconds** (Paho's own `KEEP_ALIVE_INTERVAL_DEFAULT`) | If supplied, `0`–`65535`; `0` disables MQTT keep-alive |
| Clean Session | `cleanSession` | Boolean | No | **`true`** when no value is stored at all — see the note below | None |
| Automatic Reconnect | `autoReconnectEnabled` | Boolean | No | **`false`** | None |

Password is stored encrypted and is not rendered back after save. Leave both credential fields blank
for an anonymous connection.

**All four session fields are genuinely wired.** Each reaches `MqttConnectOptions` in
`de.hock.mqtt.MqttPublisher.buildConnectOptions` — shared by both integrations, so the
default-from-`null` logic exists in exactly one place — and a test reads every one of them back
through Paho's own getters, so a setting that stops being honoured fails the build. A `null` value is
interpreted at run time as the default in the table; no descriptor state is ever pre-populated.

**Behaviour change in `1.0.6`: an out-of-range session value now fails the integration.** A
`connectionTimeoutSeconds` outside 1–300 or a `keepAliveSeconds` outside 0–65535 returns an
`Invalid configuration` error from whichever integration you called, naming the field and the value.
In `1.0.5` it silently fell back to the default. That fallback still exists inside
`buildConnectOptions` as defence in depth — the method is total and never throws — but
`de.hock.mqtt.ConnectionConfigValidator` now gates it, so on the integration path it is unreachable.
A mistyped keep-alive is worth one clear error rather than a connection that quietly behaves
differently from what the page says.

**`autoReconnectEnabled` has very little room to act — say so out loud.** One publish connects,
sends and disconnects inside a single request, and a subscribe does the same around its collection
window, so there is almost nothing for automatic reconnect to do. It is exposed because it is a legitimate `MqttConnectOptions` setting and more connection
parameters were asked for, not because it changes much. It starts nothing that outlives the request:
the client is disconnected and closed in a `finally` block either way.

**`cleanSession` honestly.** The SDK seeds a Boolean property to `false`, so a **brand-new**
connected system renders Clean Session **unchecked**. The documented `true` default applies only
when the value is genuinely absent — for instance when a connected system saved by `1.0.4`, before
the field existed, is read at run time. Seeding `true` into descriptor state would reintroduce the
first-render machinery that `1.0.4` deliberately removed.

**No connected system field is expressionable.** Every field takes a literal value only. This is an
Appian platform restriction rather than a design choice: a connected system template that declares
an expressionable property descriptor is rejected outright at descriptor-conversion time, and the
configuration form then fails to render at all. Expressions are supported on the **integration
inputs** instead.

**Broker Port is suggested, not seeded.** The field shows `1883` as a placeholder; it is not
pre-filled. Actually seeding a value would need a first-render signal to avoid overwriting what you
type on every re-render, and that machinery is deliberately absent.

**An existing connected system keeps working untouched.** A configuration saved by `1.0.6`, before
the two TLS fields existed, carries no value for either, and an absent `tlsEnabled` is read as
`false` — so it continues to connect over plain `tcp://` exactly as it did.

## TLS

Server-side TLS, added in `1.0.7`. The plug-in verifies the broker. It does **not** present a client
certificate, so mutual TLS is unsupported — see [Mutual TLS](#mutual-tls-is-not-supported).

### The two fields, and why the certificate is pasted text

| Label | Key | Type | Meaning |
|---|---|---|---|
| Enable TLS | `tlsEnabled` | Boolean | Connect over `ssl://` instead of `tcp://`. Off by default |
| CA Certificate | `caCertificate` | Paragraph | Optional PEM-encoded CA certificate. Blank means the Appian server's own trusted CAs |

**You paste PEM text; there is no file upload, and there cannot be one.** A connected system
template may not declare an expressionable property descriptor — Appian rejects the entire
descriptor set at conversion time and the configuration page then fails to render. The SDK's
`DocumentPropertyDescriptor` *force-sets* that flag in its builder constructor and its
`Guard.checkPropertyIsExpressionable` refuses to build with it cleared, so **no document-upload
field exists on a connected system template with this SDK**. `1.0.3` tried it and the deploy failed.
`ParagraphPropertyDescriptor` is the only multi-line text input available, which is why the
certificate arrives as pasted text.

Paste the **whole** PEM block, BEGIN and END lines included. A chain of several concatenated blocks
is accepted, and every certificate in it becomes a trust anchor. Windows line endings and
surrounding whitespace are tolerated.

### The three connection modes

| Mode | Enable TLS | CA Certificate | URI | Trust anchors | Use it when |
|---|---|---|---|---|---|
| 1 | off | *(must be blank)* | `tcp://host:port` | none | Plain MQTT on a trusted network. The default, and unchanged from `1.0.6` |
| 2 | on | blank | `ssl://host:port` | The Appian server's trusted CAs | The broker has a **publicly trusted** certificate — HiveMQ Cloud and similar. Do not paste a CA you do not need |
| 3 | on | PEM | `ssl://host:port` | **Only** the pasted certificate(s) | The broker is **self-signed or uses a private CA**. Paste the CA that signed the broker certificate; a self-signed broker certificate is its own CA and works |

Mode 2 reads the trust store the Appian server's JVM is configured with. A broker whose CA is
publicly trusted in general but absent from *that* store will fail in mode 2 and succeed in mode 3 —
which is one of the reasons mode 3 exists.

### The port is yours to set

**1883** is the convention for plain TCP and **8883** for TLS, and **the plug-in never changes the
port when you tick the checkbox.** A `tcp` port left behind after enabling TLS is the single
commonest cause of a failed TLS connection.

### Hostname verification

**It happens, and the broker's host name is genuinely checked.** This is a measured result, not a
reading of documentation.

Paho 1.2.5's `ssl://` transport (`SSLNetworkModuleFactory`, whose supported scheme set is exactly
`{"ssl"}`) copies `MqttConnectOptions.isHttpsHostnameVerificationEnabled()` onto its network module;
that field is seeded **`true`** in the `MqttConnectOptions` constructor; and `SSLNetworkModule.start()`
applies `SSLParameters.setEndpointIdentificationAlgorithm("HTTPS")` to the socket after `connect`
and before `startHandshake()`. The plug-in sets that flag **explicitly** rather than relying on a
library default it cannot see. Two offline tests confirm the behaviour end to end against a loopback
TLS server: a certificate whose subject alternative name matches the connected host completes the
handshake, and one for a different host is **rejected**.

So in modes 2 and 3 the guarantee is the full one: *the broker presents a certificate chain the
configured trust anchor signed, whose subject alternative name matches the host you configured.*
Not merely "encrypted", and not merely "signed by somebody".

**The residual risks, stated plainly:**

- **Verification matches the host string as typed.** If you configure the broker by **IP address**,
  the certificate must carry that IP in its subject alternative name. Prefer a host name.
- **In mode 2, any CA the Appian server trusts can vouch for the host.** That is the ordinary web
  PKI trust model, and it is the right trade-off for a publicly trusted broker. Mode 3 narrows trust
  to one CA when that matters.
- **There is deliberately no way to turn verification off.** No "trust all certificates" option, no
  permissive trust manager, not behind a flag. Mode 3 already covers self-signed brokers properly by
  trusting the specific certificate, so the escape hatch has no legitimate use and would be a
  security footgun.

### Mutual TLS is not supported

The plug-in presents no client certificate. A broker configured to **require** one will fail the
handshake. Client-certificate authentication is planned for a later release; username and password
over TLS works today.

### What a failure looks like

Configuration problems are caught before any socket is opened and come back as `Invalid
configuration`:

| What you did | Message |
|---|---|
| Pasted a CA but left TLS off | "A CA certificate is configured but TLS is disabled on the MQTT Broker connected system. Enable TLS, or clear the CA Certificate field." |
| Pasted something that is not PEM | "…is not a PEM certificate. It must start with `-----BEGIN CERTIFICATE-----`…" |
| Pasted a bare number | the same, plus "The configured value looks like an Appian document id." |
| Pasted a PEM block that will not parse | "…could not be parsed as an X.509 certificate. Check that the whole PEM block was pasted…" |

**No error message ever contains any part of the certificate field**, by policy and by test.

Handshake failures surface through the normal `MQTT publish failed` / `MQTT subscribe failed` error
carrying Paho's own wording. The two worth recognising:

- `unable to find valid certification path to requested target` — the CA is **wrong or missing**.
  In mode 2, the Appian server does not trust the broker's CA; switch to mode 3 and paste it.
- `No subject alternative DNS name matching <host> found` — the certificate is for a different host.
  That is hostname verification doing its job.

**The hard error on a stale CA is a deliberate trade-off.** If you untick **Enable TLS** to test a
plain connection while leaving a certificate pasted, both integrations fail until you clear the
field. Failing loudly beats the alternative: a field that is configured, visible, and silently
ignored — which is exactly the trap `1.0.3`'s unenforced `maxReconnectAttempts` set.

### What the plug-in does not do with TLS

- **No `System.setProperty`, ever.** The usual shortcut for MQTT-over-TLS is to set
  `javax.net.ssl.*` trust-store properties. That mutates JVM-global state, which Appian's plug-in
  policies forbid, and it would change TLS behaviour for every other plug-in on the server. The
  trust material is assembled in memory and handed to Paho as a socket factory instead.
- **No temporary keystore file.** Nothing is written to the filesystem — also forbidden by policy,
  and also something the `1.0.3` code did.
- **No pinned protocol version.** `SSLContext.getInstance("TLS")` lets the JDK negotiate its best
  available; on Java 17 that is TLS 1.3 and 1.2, with 1.0 and 1.1 already disabled by the platform.
  `1.0.3` pinned `TLSv1.2`, which would have *excluded* TLS 1.3.

### Design-time validation: the probe is finished

`1.0.5` validated the configuration on **every render, including the very first one**, attaching
messages through `PropertyState.setErrorAtPath`. That was deliberately ungated as a probe: Appian's
26.9 restrictions page says `setErrors` is ignored on a connected system template, but this plug-in
attached through `setErrorAtPath`, a different method, so the documentation could not settle whether
field errors display at all. A gate would have made a clean first page ambiguous between "the gate
worked" and "Appian discards our errors".

**Result, observed on a live Appian 26.9 instance: no error message is displayed.** The page renders
normally and the plug-in works, but Appian discards errors set this way, so the `setErrors`
restriction covers `setErrorAtPath` too.

**`1.0.6` acts on that.** `de.hock.mqtt.validation.ConfigValidator` is **deleted**, along with the
whole `clearErrorsAtPath` / `setErrorAtPath` machinery that existed only to serve a display that
never happened. A brand-new connected system now opens clean, which is pinned by
`brandNewConnectedSystemRendersWithNoAttachedErrors`.

The rules were correct, so they moved rather than being dropped. They now live in
`de.hock.mqtt.ConnectionConfigValidator` and run at **invocation time** in both integrations, before
any broker contact:

| Rule | Reported as |
|---|---|
| `brokerHost` present and non-blank | `Invalid configuration` — "Broker host is required on the MQTT Broker connected system." |
| `brokerPort` present and `1`–`65535` | `Invalid configuration`, naming the offending value |
| `connectionTimeoutSeconds`, when supplied, `1`–`300` | `Invalid configuration`, naming the offending value |
| `keepAliveSeconds`, when supplied, `0`–`65535` | `Invalid configuration`, naming the offending value |
| `caCertificate` must be blank when `tlsEnabled` is off | `Invalid configuration` — "A CA certificate is configured but TLS is disabled…" |
| `caCertificate`, when TLS is on, must contain the PEM header | `Invalid configuration`, naming `-----BEGIN CERTIFICATE-----`, plus an Appian-document-id hint for a bare number |
| `caCertificate`, when TLS is on, must parse as X.509 | `Invalid configuration` — "…could not be parsed as an X.509 certificate…" |

So a misconfigured connected system produces a readable integration error instead of an obscure Paho
exception. What this means for anyone working on the plug-in:

- `isRequired` is the **only** design-time guard available on a connected system. Appian honours it,
  and it stays on `brokerHost` and `brokerPort`.
- **Do not attempt design-time validation on the connected system again.** Validate at run time and
  report through the integration response.
- Whether **integration input** errors display at design time is a separate, untested question, and
  `1.0.6` deliberately does not probe it — mixing two experiments into one deploy test would muddy
  both. Integration inputs are validated at run time like everything else.

## Using the Publish integration

Create an Integration object against the MQTT Broker connected system and choose **Publish MQTT
Message**. It publishes a single message and is marked `WRITE`.

### Inputs

| Label | Key | Type | Required | Default | Expressionable |
|---|---|---|---|---|---|
| Topic | `topic` | Text | Yes | — | Yes |
| Payload | `payload` | Text | Yes | — | Yes |
| QoS | `qos` | Integer | No | `0` when blank | Yes |

`payload` is sent as UTF-8 bytes. `qos` shows `0` as a placeholder and falls back to `0` at run time
when left blank; a value outside 0–2 is rejected before any broker contact.

**All three inputs accept expressions.** Unlike the connected system fields, integration inputs may
be expressionable, so each of `topic`, `payload` and `qos` can be driven by a rule or process
variable. That asymmetry is deliberate and pinned by a test.

### Output

On success the response result is a flat map:

| Field | Meaning |
|---|---|
| `success` | `true` |
| `status` | `delivered` for QoS 1 or 2 (the broker acknowledged), `dispatched` for QoS 0 (handed off without acknowledgment) |
| `topic` | The topic the message was published to |
| `qos` | The QoS the message was published at |

On failure the response carries an error only, with no result map. `error.title` is one of:

| Title | Meaning |
|---|---|
| `Invalid input` | A blank topic or a QoS outside 0–2. No broker was contacted. |
| `Invalid configuration` | The connected system is unusable: blank host, port outside 1–65535, or a session value outside its range. No broker was contacted. |
| `MQTT publish failed` | Paho could not connect or publish. `error.message` names the topic, the broker host and port, the QoS, and the underlying Paho message. |

Error messages are truncated to 500 characters. No secret is placed in a response — the password is
never read into a message.

### Example

Because this integration is `WRITE`, invoke it from a context that permits side effects — a process
model's Call Integration node, or a save on an interface:

```
rule!MQTT_PublishMessage(
  topic: "appian/smoketest",
  payload: "hello from Appian",
  qos: 0
)
```

The response follows Appian's standard integration shape:

```
if(
  local!publishResponse.success,
  local!publishResponse.result.status,
  local!publishResponse.error.message
)
```

Substitute the name of your own integration object; `topic`, `payload` and `qos` are the real input
names.

## Using the Subscribe integration

Create an Integration object against the MQTT Broker connected system and choose **Subscribe to MQTT
Topics**. It subscribes to one topic filter, collects messages until either limit is reached, then
unsubscribes and disconnects. It is marked `READ`.

### Inputs

| Label | Key | Type | Required | Default | Valid range | Expressionable |
|---|---|---|---|---|---|---|
| Topic Filter | `topicFilter` | Text | Yes | — | A single MQTT topic filter | Yes |
| QoS | `qos` | Integer | No | `0` when blank | `0`–`2` | Yes |
| Max Wait (seconds) | `maxWaitSeconds` | Integer | No | `10` when blank | `1`–`30` | Yes |
| Max Message Count | `maxMessageCount` | Integer | No | `10` when blank | `1`–`100` | Yes |

**All four inputs accept expressions**, consistent with the publish inputs and the deliberate
opposite of the connected system fields. A test pins all four.

**Out-of-range values are rejected, not clamped.** A `maxWaitSeconds` of 300 returns
`Invalid input` — "Max wait must be between 1 and 30 seconds, but was 300." — rather than quietly
becoming 30, because a designer who asked for five minutes and silently got thirty seconds has no
way to notice. Blank is different from out of range: blank takes the default in the table.

**One filter, with wildcards.** `sensors/+/temp` matches one level (`sensors/kitchen/temp`,
`sensors/hall/temp`); `home/#` matches a whole subtree. A list of filters is a deliberate follow-up —
see [What is still deliberately removed](#what-is-still-deliberately-removed).

### Output

On success the response result is:

| Field | Meaning |
|---|---|
| `messages` | A list of maps, one per collected message, in arrival order |
| `messageCount` | How many messages are in `messages` |

Each entry in `messages` carries:

| Field | Meaning |
|---|---|
| `topic` | The topic the message actually arrived on — useful with a wildcard filter |
| `payload` | The payload as text when it is valid UTF-8; otherwise **Base64** |
| `qos` | The QoS the message was delivered at |
| `retained` | `true` when the broker delivered it as a retained message |
| `binary` | `true` when `payload` is Base64 rather than text |

**Payload encoding.** A payload that decodes strictly as UTF-8 comes back as that text with `binary`
`false`. Anything else is Base64 (RFC 4648 standard alphabet, with padding) with `binary` `true`, so
no byte is lost or silently replaced. An empty payload is an empty string with `binary` `false`.
Decode a binary payload in an expression where you need the bytes, keying off the flag:

```
if(
  local!message.binary,
  "binary payload, " & len(local!message.payload) & " Base64 characters",
  local!message.payload
)
```

**An empty result is a success, not an error.** A subscription that saw no traffic in its window
returns `messageCount` 0 and an empty `messages` list. A quiet topic is a normal MQTT outcome and
must not look like a failure.

On failure the response carries an error only. `error.title` is one of:

| Title | Meaning |
|---|---|
| `Invalid input` | A blank topic filter, or a QoS / max wait / max message count out of range. No broker was contacted. |
| `Invalid configuration` | The connected system is unusable: blank host, port outside 1–65535, or a session value outside its range. No broker was contacted. |
| `MQTT subscribe failed` | Paho could not connect or subscribe. `error.message` names the filter, the broker host and port, the QoS, and the underlying Paho message. |

Error messages are truncated to 500 characters, and no secret is placed in a response.

### The blocking-wait caveat

**A subscribe occupies an Appian request thread for the whole wait.** That is the nature of turning
event-driven delivery into a synchronous integration call, and it is why:

- the plug-in caps `maxWaitSeconds` at **30 seconds**, and the cap is not going up;
- the default is **10 seconds**, not the cap;
- `maxMessageCount` ends the wait early — the loop polls in 200 ms slices, so it returns as soon as
  the count is reached rather than sitting out the window.

**Appian applies its own integration request timeout, independently of this plug-in and unknown to
it.** If Appian's timeout fires before your `maxWaitSeconds` elapses, you see an *Appian* error
rather than an empty result. Keep waits short, and treat a long wait as a design smell: for
continuous consumption, an external bridge that writes into Appian is the right shape, not a
long-polling integration.

### Client identifier

A blank `clientIdentifier` on the connected system uses Paho's `generateClientId()`, which is unique
per call. A configured one gets **`-sub`** appended for subscribe sessions, so a subscribe can never
take over the publish session's identifier on the same broker — a broker that sees a second
connection with an existing client id disconnects the first. Two consequences worth knowing:

- Two **concurrent** subscribe calls against the same connected system still share one derived id,
  so the broker will drop the earlier one. Leave `clientIdentifier` blank to avoid it.
- A broker enforcing the MQTT 3.1 **23-character** client-id limit may reject the suffixed form if
  the configured id is already 20 or more characters.

### Example

```
rule!MQTT_Subscribe(
  topicFilter: "sensors/+/temp",
  qos: 0,
  maxWaitSeconds: 5,
  maxMessageCount: 20
)
```

```
if(
  local!subscribeResponse.success,
  local!subscribeResponse.result.messageCount,
  local!subscribeResponse.error.message
)
```

## Runtime behavior, stated honestly

One **connect → publish → disconnect** per publish request through `de.hock.mqtt.MqttPublisher`, and
one **connect → subscribe → collect → unsubscribe → disconnect** per subscribe request through
`de.hock.mqtt.MqttSubscriber`. Those two classes are the only Paho call sites in the plug-in.
Specifically:

- **Connect timeout from the connected system**, defaulting to **5 seconds** when the field is
  blank, so an unreachable broker fails fast rather than hanging an Appian request thread.
- **Keep-alive, clean session and automatic reconnect from the connected system**, each applied to
  `MqttConnectOptions` by the shared `MqttPublisher.buildConnectOptions`, with the defaults listed in
  the field table.
- **10-second cap** on any single blocking Paho call (`setTimeToWait`).
- **No connection reuse**, no session manager, no Last Will.
- **No background threads, executors or callbacks that outlive a request.** A subscribe registers no
  `MqttCallback` and starts no thread of its own: the listener passed to `subscribe` only `offer`s a
  finished map into a bounded queue sized to `maxMessageCount`, so Paho's receiver thread is never
  blocked and a flood of traffic cannot grow memory — the overflow is dropped. Unsubscribe,
  disconnect and close all run in a `finally` block, on every path including a timeout or an
  exception.
- **`tcp://` when TLS is off, `ssl://` when it is on.** The TLS trust material is built as an
  in-memory `SSLContext` and handed to Paho through `MqttConnectOptions.setSocketFactory`, from the
  shared `buildConnectOptions` so neither call site can forget it. There is still no
  `System.setProperty` call anywhere, still no temporary keystore, and still nothing written to the
  filesystem — Appian's plug-in policies forbid JVM-global mutation and direct filesystem access,
  and those two bans are exactly what rule out the usual MQTT-over-TLS shortcuts. See [TLS](#tls).
- `MemoryPersistence` rather than Paho's default file persistence, so nothing is written to the
  filesystem.
- The client is disconnected and closed in a `finally` block, so a failed publish or subscribe never
  leaks a connection or leaves a subscription behind.

The plug-in contains no logging at all — no Log4j dependency, and no `System.out`, `System.err`,
`printStackTrace()` or `java.util.logging` call. Failure detail surfaces in the integration
response, which is where a Designer sees it.

## Known limitations

- **A subscribe blocks an Appian request thread for its whole wait**, and Appian's own integration
  timeout may fire first. See [the blocking-wait caveat](#the-blocking-wait-caveat).
- **Automated test coverage is offline only — 35 tests in nine classes.** No test needs a broker, an
  Appian runtime, or a network route off the machine; two bind an ephemeral loopback port on
  `127.0.0.1`.
  - `src/test/java/de/hock/mqtt/ProjectStructureTest.java` (2) — `appian-plugin.xml` and
    `resources_en_US.properties` are on the classpath.
  - `src/test/java/de/hock/mqtt/template/MqttConnectedSystemTemplateDescriptorTest.java` (8) —
    drives all three templates' configuration descriptors: a non-null root state with a resolvable
    root type, exactly the eleven expected field keys in order, that no connected system descriptor
    reads back expressionable, that no connected system field uses a List/Document/AppianValue
    descriptor type, that the two TLS fields use Boolean and Paragraph descriptors and neither is
    expressionable, that the publish integration renders its three inputs all expressionable, and
    the same two checks for the subscribe integration's four inputs.
  - `src/test/java/de/hock/mqtt/template/MqttConnectedSystemTemplateNoDesignTimeErrorsTest.java` (1)
    — a brand-new connected system renders with no error on any of the eleven fields and none on the
    root, which is the intended behaviour since `1.0.6`.
  - `src/test/java/de/hock/mqtt/ConnectionConfigValidatorTest.java` (7) — one test per run-time
    rule: broker host, broker port, the two session ranges (with keep-alive `0` accepted as a legal
    disable), a valid configuration with blank optionals reporting nothing, and the three TLS rules
    — a CA with TLS off, an unparseable CA, and a bare number diagnosed as a probable Appian
    document id. Each TLS test also asserts the message echoes no part of the field.
  - `src/test/java/de/hock/mqtt/TlsSupportTest.java` (5) — the three connection modes, a
    multi-certificate PEM chain, and malformed input rejected without echoing its content. The
    mode-3 test is the one that matters: it reads the trust manager back and asserts a certificate
    the configured CA signed is **accepted** while an unrelated one is **rejected**, which is the
    only offline proof that trust is restricted rather than merely configured.
  - `src/test/java/de/hock/mqtt/TlsHostnameVerificationTest.java` (3) — Paho's hostname-verification
    flag defaults on and the plug-in sets it explicitly, plus two real handshakes against a loopback
    `SSLServerSocket` replicating Paho's exact socket sequence: a matching subject alternative name
    completes, a mismatched one fails.
  - `src/test/java/de/hock/mqtt/CertificateFixtures.java` — not a test class. Generates throwaway
    CAs, leaf certificates and PEM at run time by hand-writing X.509 DER and signing it with
    `java.security.Signature`, because no public JDK API creates a certificate. **No key material,
    keystore or passphrase is committed**, and nothing is written to disk.
  - `src/test/java/de/hock/mqtt/MqttPublisherConnectOptionsTest.java` (2) — each of the four session
    settings reaching `MqttConnectOptions`, and the documented defaults for absent and out-of-range
    values.
  - `src/test/java/de/hock/mqtt/MqttSubscriberTest.java` (3) — the collection loop terminating on
    the message count and on the deadline, driven directly against a pre-filled queue and an
    absolute `System.nanoTime` deadline, plus UTF-8 text, Base64-with-`binary` and empty payload
    handling.
  - `src/test/java/de/hock/mqtt/integration/MqttSubscribeIntegrationInputsTest.java` (4) — input
    defaults, out-of-range rejection with the offending value named, an empty collection reported as
    success with `messageCount` 0, and a blank topic filter failing before any broker contact.

  **Nothing exercises a real publish or a real subscribe** — that is what the deploy-time smoke test
  is for.
- **Appian does not display design-time errors on a connected system — confirmed, not assumed.**
  The `1.0.5` probe was run on a live 26.9 instance and no message appeared; see
  [Design-time validation](#design-time-validation-the-probe-is-finished). `ConfigValidator` was
  therefore deleted in `1.0.6`, `isRequired` is the only design-time guard that works, and
  configuration validation now runs at invocation time in both integrations.
- **Connected system fields cannot be driven by expressions.** An Appian platform restriction, not a
  design choice. Per-environment values therefore have to come from separate connected system
  objects or from deployment-time customization. Integration inputs are unaffected.
- **No connected system logo assets.** Appian looks for
  `src/main/resources/de/hock/mqtt/MqttConnectedSystemTemplate_{40,50,80}px.png`; without them the
  connected system renders without an icon.
- **No existing Connected System object can break on upgrade.** The configuration page never
  rendered successfully on `1.0.0`–`1.0.3`, so no MQTT Broker connected system was ever created
  against this plug-in.

## Restoring the full build

Git commit `d023a76` is the complete `1.0.3` build as diffable files:

```bash
git show d023a76:src/main/java/de/hock/mqtt/validation/ConfigValidator.java   # read one file
git checkout d023a76 -- src/main/java/de/hock/mqtt/tls                        # restore selectively
```

To read a single archived file without touching the working tree:

```bash
git show d023a76:src/main/java/de/hock/mqtt/validation/InputValidator.java
```

Restore selectively — the TLS layer, or the multi-topic subscribe inputs — rather than wholesale,
and re-read [the steering file](.kiro/steering/appian-appmarket-compliance.md) first: the three
platform contracts that broke `1.0.1`–`1.0.3` still apply to anything you put back, and the version
must be bumped past `1.0.7`. Note that `1.0.3`'s subscribe integration carried the sticky-error
defect and the removed adapter indirection; `1.0.6`'s is a fresh implementation that reuses only its
collection semantics.

## License and third-party software

This plug-in bundles one third-party library: the Eclipse Paho Java Client (MQTT v3)
`org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5`, redistributed unmodified as a nested jar.
Paho is dual-licensed under EPL-2.0 and EDL-1.0; redistribution here relies on the permissive
**EDL-1.0**. No copyleft-licensed library is bundled. Full detail is in
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md), which also ships at the root of the plug-in jar.

## Links

- [.agents/tasks/server-side-tls-1.0.7.md](.agents/tasks/server-side-tls-1.0.7.md) —
  **the `1.0.7` release record, and the place to start**: the two TLS fields and three modes, the
  hostname-verification finding with its bytecode evidence and the no-wrapper decision, the three
  `1.0.3` TLS defects, the validator rules, the full verification output, and the deploy-and-test
  checklist.
- [.agents/tasks/subscribe-integration-1.0.6.md](.agents/tasks/subscribe-integration-1.0.6.md) —
  the `1.0.6` release record: every decision behind the subscribe integration and the validator
  demotion, the full verification output, and the deploy-and-test checklist.
- [.agents/tasks/validation-and-config-fields-1.0.5.md](.agents/tasks/validation-and-config-fields-1.0.5.md)
  — the `1.0.5` release record: the ungated-probe decision, the error-clearing fix with its `javap`
  evidence, the full verification output, and the deploy-and-test checklist.
- [.agents/tasks/minimal-smoke-build.md](.agents/tasks/minimal-smoke-build.md) — the `1.0.4`
  reduction record: every deleted and kept file, the verification evidence, and its deploy checklist.
- [docs/mqtt-plugin-guide.md](docs/mqtt-plugin-guide.md) — **describes the pre-reduction full
  build**, not what ships here. Useful if you restore the archive.
- [.kiro/steering/appian-appmarket-compliance.md](.kiro/steering/appian-appmarket-compliance.md) —
  the AppMarket packaging and submission constraints for this plug-in (namespace, version formats,
  sources-in-jar, descriptor restrictions, submission process). Consult it before packaging a
  release or submitting a new version; it is not summarized here.

Appian documentation content referenced above was rephrased for compliance with licensing
restrictions.
