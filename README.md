# MQTT Broker Connected System

An Appian Connected System plug-in that lets Appian applications publish messages to, and collect
messages from, an MQTT broker. A designer configures the broker connection once (host, credentials,
TLS, Last Will and Testament, reconnection, session) on a Connected System object, then creates
Integration objects from it to publish a message or to collect a bounded batch of messages from one
or more topic filters. Built on the Eclipse Paho MQTT v3 client.

- **Plug-in key:** `de.hock.mqtt`
- **Version:** `1.0.3` (manifest `<plugin-info><version>`)
- **Minimum Appian version:** `26.9.0.0`
- **Vendor:** Hock, <https://itondemand.com>

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
`src/main/java`. What you get is a connected system plus two integration templates.

The usage model follows from that:

1. Create one **Connected System** object of type **MQTT Broker** and fill in the broker settings.
2. Create **Integration** objects against that connected system, choosing **Publish MQTT Message**
   or **Subscribe to MQTT Topics**.
3. Call those integration objects from interfaces and expressions, or from a process model using
   Appian's **built-in "Call Integration" smart service node**.

That `Call Integration` node is part of the Appian platform, not something this plug-in installs.

## Requirements

- Appian **26.9** (manifest sets `<application-version min="26.9.0.0"/>`)
- **Java 17** to build (`maven.compiler.release` is `17`)
- A reachable **MQTT broker** speaking MQTT 3.1.1 over TCP or TLS
- `com.appian:connected-systems-core:1.9.1`, Maven scope `provided` — supplied by the Appian
  runtime, never bundled
- `com.appian:connected-systems-client:1.3.0`, Maven scope `provided` — the `Simple*` template
  facade the three templates subclass; also supplied by the Appian runtime, never bundled
- `org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5`, bundled inside the plug-in jar under
  `META-INF/lib/`

## Build

```bash
mvn clean package
```

The deployment and AppMarket submission artifact is:

```
target/mqtt-broker-connected-system-1.0.3-plugin.jar
```

The `-plugin` suffix comes from the assembly `<id>`; a jar without it is a stale build. Exactly one
jar is produced by design — the default `maven-jar-plugin` execution is bound to phase `none`, so
there is no second archive that also looks deployable. The jar carries the plug-in's own `.java`
sources alongside the compiled classes (an AppMarket requirement), `appian-plugin.xml` at the
archive root, and Paho as a nested jar under `META-INF/lib/`.

## Deploy

Copy the jar into the plug-ins directory of the Appian installation:

```bash
cp target/mqtt-broker-connected-system-1.0.3-plugin.jar APPIAN_INSTALL/_admin/plugins/
```

Hot deployment is governed by the `conf.plugins.poll-interval` custom property (seconds). When it is
set to `0`, hot deployment is off and loading the plug-in requires an application server restart.
See [Appian Suite Plug-ins](https://docs.appian.com/suite/help/26.9/Appian_Plug-ins.html).

Confirm the deployment three ways:

- The **Admin Console → Plug-ins** page lists the plug-in.
- **New > Connected System** in Appian Designer offers the **MQTT Broker** type.
- `tomcat-stdOut.log` shows a `Successfully installed Plug-in` line plus
  `ConnectedSystemTemplateModuleParser` registration lines.

## Configuring the Connected System

Create a Connected System object and pick the **MQTT Broker** template. Fields below are listed in
the order the template renders them. Keys are the internal field identifiers from
`de.hock.mqtt.validation.ConfigFields`; labels are from `resources_en_US.properties`.

**No connected system configuration field is expressionable.** Every field below takes a literal
value only. This is an Appian platform restriction rather than a design choice: a connected system
template that declares an expressionable property descriptor is rejected outright at
descriptor-conversion time, and the configuration form then fails to render at all. Expressions are
supported on the **integration inputs** instead — see
[Using the Publish integration](#using-the-publish-integration) and
[Using the Subscribe integration](#using-the-subscribe-integration).

Design-time validation attaches each error to its own field and keeps the entered values, so a
rejected save re-displays what you typed.

A brand-new Connected System **opens clean**: no validation messages appear before you have entered
anything, so you are not greeted by "Broker host is required" on an empty form. Validation runs from
the next render onward — the moment you interact with the form or save it — and from then on every
required-field and bounds error is reported against its own field.

### Connection

| Label | Key | Type | Required | Default | Validation |
|---|---|---|---|---|---|
| Broker Host | `brokerHost` | Text | Yes | — | Non-blank, at most 253 characters |
| Broker Port | `brokerPort` | Integer | Yes | — | Whole number 1–65535 |
| Client Identifier | `clientIdentifier` | Text | No | Auto-generated | When supplied, 1–65535 characters |
| Connection Timeout (seconds) | `connectionTimeoutSeconds` | Integer | No | `30` | When supplied, whole number 1–300 |

Leaving **Client Identifier** blank auto-generates one via
`de.hock.mqtt.validation.ClientIdentifierGenerator`: always 23 characters, made up of the fixed
prefix `appian-` plus 16 characters from `[0-9a-zA-Z]` derived from a SHA-256 digest of `host:port`.
The same host and port always yield the same identifier.

### Authentication

| Label | Key | Type | Required | Default | Validation |
|---|---|---|---|---|---|
| Username | `username` | Text | No | — | At most 256 characters |
| Password | `password` | Encrypted text (masked) | No | — | At most 256 characters |

Username and password are **both-or-neither**: supplying one without the other is rejected with
"Username and password must both be provided or both be omitted." Leave both blank for an anonymous
connection. At connect time, credentials are applied to Paho only when both a non-blank username and
a non-empty password are present.

### TLS

| Label | Key | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| Enable TLS | `tlsEnabled` | Boolean (checkbox) | No | `false` | Selects the `ssl://` scheme instead of `tcp://` |
| CA Certificate (PEM) | `caCertificate` | Text, multi-line (pasted PEM) | No | — | Validates the broker; falls back to the platform trust store when absent |
| Client Certificate (PEM) | `clientCertificate` | Text, multi-line (pasted PEM) | No | — | X.509 certificate for mutual TLS |
| Client Private Key | `clientPrivateKey` | Encrypted text (masked) | No | — | Unencrypted PKCS#8, PEM or DER |
| Key Passphrase | `keyPassphrase` | Encrypted text (masked) | No | — | Stored and redacted; see the caveat below |

When TLS is enabled, `ConnectOptionsFactory` creates an `SSLContext` for `TLSv1.2` and wraps the
resulting socket factory so each socket offers only `TLSv1.2` and `TLSv1.3`. Mutual TLS requires
**both** a client certificate and a private key; one without the other fails with category
`CONFIGURATION` and the message "Mutual TLS requires both a client certificate and its private key."
**Both certificate fields take pasted PEM text**, including the `-----BEGIN CERTIFICATE-----` and
`-----END CERTIFICATE-----` lines. Paste the certificate exactly as the file contains it; the text is
sent to the X.509 parser as UTF-8 bytes. This makes the three TLS material fields consistent, since
**Client Private Key was already a text field**. Certificates are public key material, so holding
them as text is not a secret-handling downgrade — the private key and its passphrase remain
encrypted and masked.

The **client certificate and private key** are parsed by `CertificateLoader` at design time whenever
TLS is enabled and either value is present, so malformed client material is rejected before a
handshake would fail. The **CA certificate is shape-checked** at design time — a value with no
`-----BEGIN` line is reported on its field — but it is first fully parsed at connect time by
`ConnectOptionsFactory`, so a well-formed but invalid CA certificate surfaces on the first
connection attempt as category `CONFIGURATION`.

Three things worth knowing before you plan a TLS rollout:

- **Key Passphrase is not used to decrypt the key.** `CertificateLoader` parses *unencrypted*
  PKCS#8 only (`PKCS8EncodedKeySpec`, trying RSA, EC, DSA, Ed25519), and the passphrase is never
  passed to it. The value is stored encrypted and is redacted from output, but a passphrase-protected
  key will not load.
- **Certificate uploads are gone, and TLS with a certificate should now work for the first time.**
  Up to `1.0.2` both certificate fields were `DocumentPropertyDescriptor` uploads whose saved value
  was an opaque document id that this SDK version offers no way to resolve into bytes — so a custom
  CA or a client certificate was never actually presented to the broker. They had to change
  regardless: the SDK forces a document descriptor to be expressionable, which Appian rejects on a
  connected system template outright.
- **Upgrading from `1.0.2` or earlier is a breaking change for these two fields.** An existing
  connected system holds a document reference where PEM text is now expected, and that value will
  not parse. Reopen each affected object with TLS enabled and you will see a field error naming the
  problem; clear the field and paste the certificate's PEM text. Nothing that previously worked is
  lost, for the reason in the preceding bullet.

### Reconnection

| Label | Key | Type | Required | Default | Validation |
|---|---|---|---|---|---|
| Enable Automatic Reconnect | `autoReconnectEnabled` | Boolean (checkbox) | No | `false` | — |
| Max Reconnect Attempts | `maxReconnectAttempts` | Integer | No | `3` | When supplied, integer 0–10 |
| Reconnect Interval (seconds) | `reconnectIntervalSeconds` | Integer | No | `5` | When supplied, integer 1–120 |

With automatic reconnect on, Paho's `setAutomaticReconnect(true)` is enabled and
`setMaxReconnectDelay` is set to the reconnect interval in milliseconds, bounding Paho's exponential
backoff. **`maxReconnectAttempts` is validated and carried in the configuration but is not enforced
anywhere at runtime** — no code caps the attempt count. Treat the field as inert for now.

### Last Will and Testament

| Label | Key | Type | Required | Default | Validation |
|---|---|---|---|---|---|
| LWT Topic | `lwtTopic` | Text | No | — | When supplied, 1–65535 characters; required when a payload is set |
| LWT Payload | `lwtPayload` | Text | No | — | 0–268435455 bytes (sent as UTF-8) |
| LWT QoS | `lwtQos` | Text with choices `0` / `1` / `2` | No | `0` | Must be 0, 1, or 2 when LWT is configured |
| LWT Retained | `lwtRetained` | Boolean (checkbox) | No | `false` | — |

A payload without a topic is rejected: "An LWT topic is required when an LWT payload is configured."
The will is registered with the broker only when both a topic and a payload are present.

### Session

| Label | Key | Type | Required | Default | Validation |
|---|---|---|---|---|---|
| Clean Session | `cleanSession` | Boolean (checkbox) | No | `true` when unset | — |
| Keep Alive (seconds) | `keepAliveSeconds` | Integer | No | `60` | When supplied, whole number 0–65535 |

Disabling Clean Session makes the client identifier mandatory: "A client identifier is required when
the clean session is disabled (persistent session)."

## Using the Publish integration

Create an Integration object against the MQTT Broker connected system and choose **Publish MQTT
Message**. It publishes a single message and is marked `WRITE`.

### Inputs

| Label | Key | Type | Required | Default | Validation |
|---|---|---|---|---|---|
| Topic | `topic` | Text | Yes | — | 1–65535 UTF-8 bytes; must not contain `+` or `#` |
| Payload | `payload` | Text | No | empty | Sent as UTF-8 bytes, 0–268435455 bytes |
| QoS | `qos` | Text with choices `0` / `1` / `2` | Yes | `0` when blank | Must be 0, 1, or 2 |
| Retained | `retained` | Boolean (checkbox) | No | `false` | — |

**All four inputs accept expressions.** Unlike the connected system fields, integration inputs may
be expressionable, so each of `topic`, `payload`, `qos`, and `retained` can be driven by a rule or
process variable.

A non-integer `qos` becomes an out-of-range sentinel rather than silently defaulting, so it is
reported as a validation error. Input validation runs before any broker contact: a rejected input
returns a `VALIDATION` failure without opening a connection.

### Output

On success the response data map carries:

| Field | Meaning |
|---|---|
| `status` | `delivered` for QoS 1 or 2 (the broker acknowledged), `dispatched` for QoS 0 (handed off without acknowledgment) |
| `topic` | The topic the message was published to |
| `qos` | The QoS the message was published at |

### Example

Because this integration is `WRITE`, invoke it from a context that permits side effects — a process
model's Call Integration node, or a save on an interface. The call shape:

```
rule!MQTT_PublishMessage(
  topic: "appian/orders/created",
  payload: a!toJson(ri!order),
  qos: "1",
  retained: false
)
```

The response follows Appian's standard integration shape, so `success` selects between the data map
above and the error:

```
if(
  local!publishResponse.success,
  local!publishResponse.result.status,
  local!publishResponse.error.title
)
```

Substitute the name of your own integration object; `topic`, `payload`, `qos`, and `retained` are
the real input names. On failure, `error.title` is the `MqttErrorCategory` name.

## Using the Subscribe integration

Create an Integration object against the same connected system and choose **Subscribe to MQTT
Topics**. It is marked `READ` — it does not mutate broker state.

### Inputs

| Label | Key | Type | Required | Validation |
|---|---|---|---|---|
| Topic Filters | `topicFilters` | List of Text | Yes | 1–10 filters, each non-blank (the offending index is named in the error) |
| Quality of Service | `qos` | Text with choices `0` / `1` / `2` | Yes | Must be 0, 1, or 2 |
| Max Wait (seconds) | `maxWaitSeconds` | Integer | Yes | 1–300 |
| Max Message Count | `maxMessageCount` | Integer | Yes | 1–1000 |

**Expressions are accepted on `topicFilters`, `maxWaitSeconds`, and `maxMessageCount`, but not on
`qos`**, which is a fixed choice list. The asymmetry is deliberate: `topicFilters` is a list input,
and Appian requires a list-typed integration input to be expressionable.

### Semantics: how the collection terminates

Appian integrations are request/response while MQTT delivery is event-driven, so this integration
collects a **bounded batch per invocation**. It is not a long-lived listener. Per invocation it:

1. Acquires a session and registers a message sink feeding a `LinkedBlockingQueue` whose capacity is
   exactly `maxMessageCount`. Overflow beyond that capacity is dropped (the sink uses `offer`, never
   blocking the Paho callback).
2. Subscribes to every filter at the requested QoS.
3. Drains the queue in 200 ms poll slices, appending each message and then draining anything already
   buffered, until **either** `maxMessageCount` messages have been collected **or** the
   `maxWaitSeconds` budget elapses — whichever comes first.
4. Unsubscribes and releases the session in a `finally` block, so a failed or timed-out collection
   never leaks the connection.

Collecting nothing before the wait elapses is a **success with an empty collection**, not an error.
Messages are returned in arrival order. For continuous ingestion, invoke the integration repeatedly
on a schedule and tune the two limits.

### Output

| Field | Meaning |
|---|---|
| `messages` | List of collected messages, in arrival order |
| `messageCount` | Number of messages in `messages` |

Each entry in `messages` has:

| Field | Meaning |
|---|---|
| `topic` | The originating topic the message was published on |
| `payload` | The payload as text, or a Base64 string when the payload is binary |
| `qos` | The QoS the message was delivered at |
| `retained` | Whether the broker flagged the message retained |
| `binary` | `true` when `payload` is Base64-encoded binary, `false` when it is text |

Payload classification is strict: bytes that decode as valid UTF-8 are passed through as text with
`binary: false`; anything else is Base64-encoded with `binary: true`.

### Example

```
a!localVariables(
  local!result: rule!MQTT_SubscribeTopics(
    topicFilters: {"sensors/+/temperature"},
    qos: "1",
    maxWaitSeconds: 10,
    maxMessageCount: 50
  ),
  local!result.result.messageCount
)
```

Unlike a publish topic, subscribe filters may contain the MQTT wildcards `+` and `#`.

## Calling the integrations

**From a process model.** Add Appian's built-in **Call Integration** smart service node, point it at
your integration object, and map the inputs above to process variables. Map the response to a
process variable to read `status`, or `messages` and `messageCount`. Again: this node ships with
Appian; the plug-in contributes no smart service of its own.

**From an interface or expression.** Call the integration object like any other rule, as in the two
examples above. The Publish integration is `WRITE`, so Appian will only let you invoke it from a
save or a context that permits side effects — not during interface evaluation. The Subscribe
integration is `READ` and can be evaluated from an interface, though its wait budget blocks for up to
`maxWaitSeconds`, which makes a process or a saved interaction the better host for anything beyond a
very short wait.

## Error handling and diagnostics

Every failure maps to exactly one value of `de.hock.mqtt.MqttErrorCategory`, so expressions can
branch on a bounded set instead of parsing message text. The category name becomes the
`IntegrationError` **title**, and the message is truncated to at most 500 characters.

| Category | Meaning |
|---|---|
| `VALIDATION` | Design-time or runtime input failed validation |
| `AUTHENTICATION` | The broker rejected the supplied credentials |
| `CONNECTION_TIMEOUT` | The connection attempt did not complete within the timeout |
| `TLS_VALIDATION` | The TLS handshake or certificate validation failed |
| `DELIVERY_TIMEOUT` | A QoS 1/2 publish was not acknowledged within the timeout |
| `DISCONNECTION` | An established connection to the broker was lost |
| `CONFIGURATION` | The connection configuration is invalid or unusable |
| `UNKNOWN` | An unrecognized failure that could not be classified |

There are **two** classification paths, and they do not agree in every case.

Publish and subscribe failures on an established connection go through
`ExceptionCategoryTranslator`, which inspects both the throwable and its cause chain:
`MqttSecurityException` and Paho's authentication reason codes map to `AUTHENTICATION`; an
`SSLException`/`SSLHandshakeException` anywhere in the chain, or Paho's SSL config and
socket-factory-mismatch codes, map to `TLS_VALIDATION`; client-timeout and server-connect-error
codes or a `SocketTimeoutException` map to `CONNECTION_TIMEOUT`; connection-lost and
client-not-connected map to `DISCONNECTION`; write-timeout and max-inflight map to
`DELIVERY_TIMEOUT`; everything else maps to `UNKNOWN`.

Failures raised while *establishing* the connection are classified separately by
`MqttConnectionManager`, on the Paho reason code alone with no cause-chain inspection: the
authentication codes map to `AUTHENTICATION`, client-timeout and connect-in-progress map to
`CONNECTION_TIMEOUT`, the SSL config code maps to `TLS_VALIDATION`, and **every other reason code
falls through to `DISCONNECTION`** rather than `UNKNOWN`. A TLS handshake that fails during connect
with a wrapped `SSLHandshakeException` is therefore reported as `DISCONNECTION`, not
`TLS_VALIDATION`. Keep that in mind when debugging a connect-time category.

Both connect-time paths name the broker host and port in the message: a failed connect reads
"Failed to establish a connection to MQTT broker host:port: …", and a lost cached session reads
"Connection to MQTT broker host:port was lost."

### Diagnostics pane

Each invocation attaches an `IntegrationDesignerDiagnostic` with a response diagnostic map
containing, in order:

| Key | Value |
|---|---|
| `brokerHost` | The configured broker host |
| `operation` | `Publish` or `Subscribe` |
| `outcome` | `SUCCESS` or `FAILURE` |
| `errorCategory` | The `MqttErrorCategory` name, present only on failure |

**Nothing secret appears there.** `Redactor` is built from the configuration's username, password,
client private key, and key passphrase, and replaces every literal occurrence of each non-blank
secret with `***` in every diagnostic value and in every error message before it is returned. Blank
secrets are ignored so an empty password cannot cause unrelated text to be rewritten. Do not expect
to recover a credential from the diagnostics pane — that is deliberate.

On a failure the response carries **only** the error title and message — `ResponseMapper` returns the
result data map on the success path only, so there is no structured error payload to read. A
subscribe validation failure still reports every rejected input, enumerated in the message itself as
`"Invalid subscribe inputs: <field>: <message>; <field>: <message>"`, subject to the same
500-character truncation.

## Security notes

- **Never hardcode credentials.** Put the broker username and password in the connected system
  configuration. `password`, `clientPrivateKey`, and `keyPassphrase` are declared as
  `EncryptedTextPropertyDescriptor` with `masked(true)`, so Appian stores them encrypted, masks them
  in the UI, and does not render them back after save. Those three encrypted fields are the
  mechanism for secrets on the connected system. A Secure Credentials Store reference **cannot** be
  used here: connected system fields take literal values only, so there is no expression in which to
  resolve one. Expressions, including SCS references, are available on the **integration inputs**.
- **No JVM global mutation.** The plug-in deliberately does **not** set `javax.net.ssl.*` system
  properties, which is the usual shortcut for MQTT-over-TLS and which Appian's plug-in policies
  forbid. Instead `ConnectOptionsFactory` builds an in-memory `SSLContext` with trust managers from
  the configured CA certificate and key managers from the client certificate/key, then hands the
  resulting socket factory to Paho via `MqttConnectOptions.setSocketFactory`. Nothing is written to
  the filesystem and no server-wide setting is touched.
- **TLS material stays in memory.** Certificates and keys are parsed from bytes into an in-memory
  `KeyStore`; no temporary keystore file is created. Private-key bytes converted for parsing are
  zero-filled immediately after use, and secrets are held as `char[]` rather than `String` where the
  configuration carries them.
- **Secrets never enter the connection cache key.** The session fingerprint used for connection
  reuse reduces every secret to a presence plus hash token rather than embedding plaintext.
- **Logging.** The plug-in contains no `System.out`, `System.err`, or `printStackTrace()` calls;
  failures surface through the integration response rather than ad-hoc output.

## Known limitations

Stated plainly so nobody is surprised in production:

- **Automated test coverage is design-time only.** `src/test/java/de/hock/mqtt/ProjectStructureTest.java`
  checks that the base package resolves and that `appian-plugin.xml` and
  `resources_en_US.properties` are on the classpath;
  `src/test/java/de/hock/mqtt/template/MqttConnectedSystemTemplateDescriptorTest.java` drives all
  three templates' configuration descriptors and asserts the generated root state, the registered
  root types, the declared field keys, the Clean Session default, and field-scoped validation
  errors. It also asserts both halves of the first-render behavior: a brand-new Connected System
  renders with no validation errors on any of its 20 fields, and a blank required field **is**
  reported on every subsequent render — including the case where filling one field clears only that
  field's error. It additionally pins the platform's descriptor-modifier rules — that no connected
  system field is expressionable, that the integration inputs still are, per input, and that no field
  uses a descriptor type the SDK forces to be expressionable — and proves the certificate handoff by
  parsing a pasted PEM certificate through to an SSL socket factory. **19 tests across the two
  classes.** Beyond that one TLS path, no automated test exercises publish, subscribe, LWT, or
  reconnection at runtime, or diagnostics. `net.jqwik` and `io.moquette` are declared and
  Surefire/Failsafe are wired for `**/*Properties.java` and `**/*IT.java`, but no such files exist.
  Smoke-test against a real broker before relying on this.
- **Two behaviors depend on an unverified platform assumption.** The clean first render and the
  Clean Session `true` default both read the incoming configuration state to decide whether this is
  a brand-new object, so both assume Appian hands the template back a descriptor derived from the one
  it last returned. If the platform instead supplies only persisted object state, that signal stays
  permanently "new": Clean Session could not be turned off, and validation would never report at all.
  The check that distinguishes the two is clearing **Clean Session**, saving, and reopening the
  object — it must still be cleared. Unverified on a live instance.
- **The `connected-systems-client` requirement on the server classpath is unverified.**
  `MqttConnectedSystemTemplate` subclasses `SimpleConnectedSystemTemplate` and both integrations
  subclass `SimpleIntegrationTemplate` — the documented shape, and the shape that makes the
  configuration form render, because the facade generates the descriptor's root state. Both Appian
  artifacts are `provided`, so the Appian server must supply `com.appian:connected-systems-client`
  at runtime. That is the documented development path, but like the `META-INF/lib/` layout below it
  has not been confirmed on a live instance. A `NoClassDefFoundError` naming
  `com/appian/connectedsystems/simplified/sdk/...` at install or first render would mean the server
  does not provide it.
- **The Paho-under-`META-INF/lib/` packaging has not been validated on a live Appian instance.** The
  layout matches where Appian's plug-in classloader resolves nested jars, but it is unverified in
  practice. Confirm on first deploy.
- **`maxReconnectAttempts` is inert.** Validated and stored, never enforced (see above).
- **Key Passphrase cannot unlock an encrypted private key** (see the TLS section).
- **Upgrading from `1.0.2` breaks the two certificate fields**, which now take pasted PEM instead of
  a document upload (see the TLS section).
- **Connected system fields cannot be driven by expressions.** An Appian platform restriction, not a
  design choice. Per-environment values therefore have to come from separate connected system
  objects or from deployment-time customization, not from an expression. Integration inputs are
  unaffected.
- **A field error, once reported, is not cleared by fixing the field within the same render cycle.**
  Design-time validation only attaches errors; it never calls the SDK's `clearErrorsAtPath`. Whether
  a stale message is actually visible depends on whether Appian returns the previously rendered
  state, which is the same unverified round-trip assumption noted above.
- **No connected system logo assets.** Appian looks for
  `src/main/resources/de/hock/mqtt/MqttConnectedSystemTemplate_{40,50,80}px.png`; without them the
  connected system renders without an icon.

## License and third-party software

This plug-in bundles one third-party library: the Eclipse Paho Java Client (MQTT v3)
`org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5`, redistributed unmodified as a nested jar.
Paho is dual-licensed under EPL-2.0 and EDL-1.0; redistribution here relies on the permissive
**EDL-1.0**. No copyleft-licensed library is bundled. Full detail is in
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md), which also ships at the root of the plug-in jar.

## Links

- [docs/mqtt-plugin-guide.md](docs/mqtt-plugin-guide.md) — the developer guide, with use cases,
  behavior notes, and binary payload handling in more depth.
- [.kiro/steering/appian-appmarket-compliance.md](.kiro/steering/appian-appmarket-compliance.md) —
  the AppMarket packaging and submission constraints for this plug-in (namespace, version formats,
  sources-in-jar, submission process). Consult it before packaging a release or submitting a new
  version; it is not summarized here.

Appian documentation content referenced above was rephrased for compliance with licensing
restrictions.
