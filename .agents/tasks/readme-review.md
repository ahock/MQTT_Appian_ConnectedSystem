# README accuracy gate: MQTT Broker Connected System

Documentation-accuracy review of `README.md` against the plug-in source. Every config key, label,
integration input/output, enum constant, default, bound, error message, and packaging fact asserted
in the README was traced to a file and line. No build was run; the verification is reading, grepping,
and a listing of the already-present artifact in `target/`.

Result: every documented identifier exists and every documented behavior matches the implementation,
including the parts where the implementation is awkward (the `DISCONNECTION`-not-`TLS_VALIDATION`
connect path, the inert `maxReconnectAttempts`, the passphrase that cannot unlock a key, the
document-bytes resolution gap). The README does not claim a Smart Service and carries the
clarification that `Call Integration` is Appian's own node. `grep -n 'com\.appian\.mqtt' README.md`
returns nothing.

Watch for: one prose overstatement in the security section — "Secrets never enter the connection
cache key" (confirmed: `username` is appended verbatim into the fingerprint at
`MqttConnectionManager.java:252`, while `Redactor` treats username as a secret at `Redactor.java:51`).
The `conf.plugins.poll-interval` hot-deployment property is the only documented fact with no backing
in this repository (unverified: platform property, not a plug-in identifier). Neither is an invented
plug-in identifier.

**Verdict**: APPROVED

## High-level view

The README's configuration tables are a faithful projection of `ConfigFields` and the descriptor
built in `MqttConnectedSystemTemplate.allProperties()`. All 20 connected-system keys exist, all 20
quoted labels match `resources_en_US.properties` byte-for-byte under the
`[Template].[fieldKey].label` convention, and the documented render order matches the descriptor
array order. The expressionability carve-out (`password`, `caCertificate`, `clientCertificate`,
`clientPrivateKey`, `keyPassphrase`, `lwtQos`) matches exactly which descriptors omit
`isExpressionable(true)`.

Defaults and bounds line up with the validators rather than with MQTT folklore: timeout 30 (1–300),
keep-alive 60 (0–65535), max reconnect attempts 3 (0–10), reconnect interval 5 (1–120), clean session
`true` when unset, LWT QoS 0, host ≤253, port 1–65535, credentials ≤256, LWT payload 0–268435455.
The three error messages the README quotes verbatim are exact string matches, including the
parenthetical in the persistent-session message.

Every integration input and output named in the README resolves to a declared constant or map key,
including the per-message `topic`/`payload`/`qos`/`retained`/`binary` shape in the order the README
lists it, and the `delivered`-vs-`dispatched` rule keyed on QoS 0.

The Subscribe semantics section describes the actual implementation, not assumed MQTT behavior: a
queue capacity of exactly `maxMessageCount` with `offer`-based overflow drop, 200 ms poll slices
against a nanosecond deadline, termination on count-or-deadline, unsubscribe-and-release in
`finally`, empty collection as success.

All eight `MqttErrorCategory` constants and their glosses match the enum, the 500-character
truncation and category-name-as-title match `ResponseMapper`, the four diagnostics keys and their
insertion order match `DiagnosticsBuilder`, and the redaction claim matches `Redactor` (four secrets,
non-blank only, `***`). The README's statement that the two classification paths disagree is
correct and specific.

Packaging facts check out against the artifact already in `target/`: one jar named
`mqtt-broker-connected-system-1.0.0-plugin.jar`, 33 `.java` entries, bare root `appian-plugin.xml`,
Paho under `META-INF/lib/`, `<version>1.0.0</version>`, `application-version min="26.9.0.0"`,
`vendor name="Hock" url="https://itondemand.com"`.

<details>
<summary>Issues (3)</summary>

1. **Cache-key secret claim overstates redaction** — "Secrets never enter the connection cache key …
   reduces every secret to a presence plus hash token" is true for password, private key, passphrase,
   and certificate bytes, but `username` is embedded verbatim (`MqttConnectionManager.java:252`), and
   the README's own diagnostics section counts username among the redacted secrets. Narrow the
   sentence to password/key material, or say username appears in plaintext in the in-memory key.
2. **`conf.plugins.poll-interval` is unbacked by this repo** — the hot-deployment paragraph is the
   only factual claim with no source in the project. It is a platform property, not a plug-in
   identifier; confirm against the Appian 26.9 docs or drop the property name.
3. **Integration-input expressionability is undocumented and asymmetric** — publish `qos` is
   `isExpressionable(true)` while subscribe `qos` is not, and subscribe `topicFilters`,
   `maxWaitSeconds`, `maxMessageCount` are. The README states expressionability only for the
   connected system, so nothing is wrong, but a designer wiring a dynamic QoS into the subscribe
   integration will be surprised.

</details>

<details>
<summary>Details</summary>

### Connected system config keys (gate item 1)

Every key named in the README exists in `ConfigFields.java`. The key is also the descriptor key in
`MqttConnectedSystemTemplate.allProperties()`.

| README key | `ConfigFields.java` | Descriptor type |
|---|---|---|
| `brokerHost` | 14 | `TextPropertyDescriptor`, required, expressionable |
| `brokerPort` | 17 | `IntegerPropertyDescriptor`, required, expressionable |
| `clientIdentifier` | 20 | `TextPropertyDescriptor`, optional, expressionable |
| `connectionTimeoutSeconds` | 23 | `IntegerPropertyDescriptor`, optional, expressionable |
| `username` | 26 | `TextPropertyDescriptor`, optional, expressionable |
| `password` | 29 | `EncryptedTextPropertyDescriptor`, `masked(true)` |
| `tlsEnabled` | 40 | `BooleanPropertyDescriptor`, `CHECKBOX` |
| `caCertificate` | 43 | `DocumentPropertyDescriptor` |
| `clientCertificate` | 46 | `DocumentPropertyDescriptor` |
| `clientPrivateKey` | 49 | `EncryptedTextPropertyDescriptor`, `masked(true)` |
| `keyPassphrase` | 52 | `EncryptedTextPropertyDescriptor`, `masked(true)` |
| `autoReconnectEnabled` | 57 | `BooleanPropertyDescriptor`, `CHECKBOX` |
| `maxReconnectAttempts` | 60 | `IntegerPropertyDescriptor`, optional, expressionable |
| `reconnectIntervalSeconds` | 63 | `IntegerPropertyDescriptor`, optional, expressionable |
| `lwtTopic` | 68 | `TextPropertyDescriptor`, optional, expressionable |
| `lwtPayload` | 71 | `TextPropertyDescriptor`, optional, expressionable |
| `lwtQos` | 74 | `TextPropertyDescriptor` with choices `0`/`1`/`2`, not expressionable |
| `lwtRetained` | 77 | `BooleanPropertyDescriptor`, `CHECKBOX` |
| `cleanSession` | 32 | `BooleanPropertyDescriptor`, `CHECKBOX` |
| `keepAliveSeconds` | 35 | `IntegerPropertyDescriptor`, optional, expressionable |

No key appears in the README that is absent from `ConfigFields`; no `ConfigFields` key is
misdescribed. The README's expressionability exception list is exactly the set of descriptors above
without `isExpressionable(true)`.

Group order claimed by the README (Connection → Authentication → TLS → Reconnection → LWT → Session)
is the order of the `PropertyDescriptor[]` literal in `allProperties()`.

### Integration inputs and outputs (gate item 2)

Publish, `MqttPublishIntegration.java`: `INPUT_TOPIC = "topic"` (90), `INPUT_PAYLOAD = "payload"`
(93), `INPUT_QOS = "qos"` (96), `INPUT_RETAINED = "retained"` (99). Outputs
`RESULT_STATUS = "status"` (102), `RESULT_TOPIC = "topic"` (105), `RESULT_QOS = "qos"` (108), with
`STATUS_DELIVERED = "delivered"` (111) and `STATUS_DISPATCHED = "dispatched"` (114). The README's
QoS-0-is-`dispatched` rule matches `request.qos() == 0 ? STATUS_DISPATCHED : STATUS_DELIVERED`.

The README's "non-integer `qos` becomes an out-of-range sentinel rather than silently defaulting" is
`parseIntOrDefault` returning `-1` on `NumberFormatException` in the same file, which
`InputValidator.validateQos` then rejects.

Subscribe, `MqttSubscribeIntegration.java`: input keys come from `InputValidator` —
`FIELD_TOPIC_FILTERS = "topicFilters"` (37), `FIELD_QOS = "qos"` (34),
`FIELD_MAX_WAIT_SECONDS = "maxWaitSeconds"` (40), `FIELD_MAX_MESSAGE_COUNT = "maxMessageCount"` (43).
Outputs `data.put("messages", …)` (203) and `data.put("messageCount", …)` (204); per-message map keys
`topic`, `payload`, `qos`, `retained`, `binary` at 341–345, the order the README's table uses.
`ReceivedMessage` is a record with exactly those five components.

The README's payload classification rule matches `MqttSession.toReceivedMessage` plus
`tryDecodeUtf8`: strict UTF-8 decode with `CodingErrorAction.REPORT`, text passes through with
`binary=false`, otherwise `Base64.getEncoder()` with `binary=true`.

### Designer-facing labels (gate item 3)

Every label the README quotes matches `resources_en_US.properties` exactly, under
`[Template].[fieldKey].label`: Broker Host (17), Broker Port (18), Client Identifier (19), Connection
Timeout (seconds) (20), Username (23), Password (24), Enable TLS (27), CA Certificate (28), Client
Certificate (29), Client Private Key (30), Key Passphrase (31), Enable Automatic Reconnect (34), Max
Reconnect Attempts (35), Reconnect Interval (seconds) (36), LWT Topic (39), LWT Payload (40), LWT QoS
(41), LWT Retained (42), Clean Session (45), Keep Alive (seconds) (46), Topic (52), Payload (53), QoS
(54), Retained (55), Topic Filters (61), Quality of Service (62), Max Wait (seconds) (63), Max
Message Count (64).

The three template display names in the README's module table also match the bundle: `MQTT Broker`
(`MqttConnectedSystemTemplate.name`), `Publish MQTT Message` (`MqttPublishIntegration.name`),
`Subscribe to MQTT Topics` (`MqttSubscribeIntegration.name`). Manifest keys and classes match
`appian-plugin.xml`, and the `WRITE`/`READ` annotations match
`@IntegrationTemplateType(IntegrationTemplateRequestPolicy.WRITE|READ)` on the two integration
classes.

### Error categories, diagnostics, redaction (gate item 4)

`MqttErrorCategory.java` declares exactly `VALIDATION, AUTHENTICATION, CONNECTION_TIMEOUT,
TLS_VALIDATION, DELIVERY_TIMEOUT, DISCONNECTION, CONFIGURATION, UNKNOWN` — the README's eight rows,
in that order, with glosses that match the enum's own javadoc.

`ResponseMapper`: `MAX_ERROR_MESSAGE_LENGTH = 500`, `.title(category.name())`, and
`IntegrationResponse.forSuccess(data)` only on the success branch. The README's claim that a failure
carries no structured payload is right in effect, and worth keeping: `MqttSubscribeIntegration`
builds a `validationErrors` list into the failure `data` map, and `ResponseMapper` discards it.

`DiagnosticsBuilder`: `KEY_BROKER_HOST = "brokerHost"`, `KEY_OPERATION = "operation"`,
`KEY_OUTCOME = "outcome"`, `KEY_ERROR_CATEGORY = "errorCategory"`, `OUTCOME_SUCCESS = "SUCCESS"`,
`OUTCOME_FAILURE = "FAILURE"`, assembled into a `LinkedHashMap` in the README's stated order with
`errorCategory` present only when a failure was recorded. Operation names are
`MqttPublishIntegration.OPERATION_NAME = "Publish"` and
`MqttSubscribeIntegration.OPERATION = "Subscribe"`.

`Redactor`: `REDACTION_MARKER = "***"`; `forConfig` passes `username`, `password`,
`clientPrivateKey`, `keyPassphrase` (49–55); `addSecret` skips null/blank; `redact` replaces every
literal occurrence; `redactValue` scrubs string diagnostic values. The README's redaction paragraph
is accurate, including the reason blank secrets are ignored.

Two-path classification. `ExceptionCategoryTranslator.categorize` checks `MqttSecurityException`,
then `SSLException`/`SSLHandshakeException` anywhere in the cause chain, then reason codes
(`FAILED_AUTHENTICATION`/`NOT_AUTHORIZED` → `AUTHENTICATION`;
`SSL_CONFIG_ERROR`/`SOCKET_FACTORY_MISMATCH` → `TLS_VALIDATION`;
`CLIENT_TIMEOUT`/`SERVER_CONNECT_ERROR` → `CONNECTION_TIMEOUT`;
`CONNECTION_LOST`/`CLIENT_NOT_CONNECTED` → `DISCONNECTION`; `WRITE_TIMEOUT`/`MAX_INFLIGHT` →
`DELIVERY_TIMEOUT`), then `SocketTimeoutException`, else `UNKNOWN`.
`MqttConnectionManager.classify` switches on the reason code alone with `default:
DISCONNECTION` — so the README's warning that a connect-time wrapped `SSLHandshakeException` is
reported as `DISCONNECTION` rather than `TLS_VALIDATION` is confirmed, not speculation.

Both quoted connect-time messages are exact:
`"Failed to establish a connection to MQTT broker " + host + ":" + port + ": " + e.getMessage()`
(`connectionMessage`) and
`"Connection to MQTT broker " + host + ":" + port + " was lost."` (`disconnection`).
The subscribe validation prefix `"Invalid subscribe inputs: "` with `field: message` joined by `"; "`
matches `validationFailure`.

### Defaults, bounds, and exact messages (gate item 5)

| README claim | Source |
|---|---|
| timeout default 30 | `MqttConnectedSystemTemplate.DEFAULT_CONNECTION_TIMEOUT_SECONDS = 30` (86); `ConfigValidator.DEFAULT_CONNECTION_TIMEOUT_SECONDS = 30` |
| timeout 1–300 when supplied | `ConfigValidator.TIMEOUT_MIN/MAX = 1/300` |
| host ≤253, non-blank | `ConfigValidator.HOST_MAX = 253`, blank check |
| port 1–65535, required | `PORT_MIN/MAX = 1/65535`; blank/non-integer rejected |
| client id 1–65535 when supplied | `CLIENT_ID_MIN/MAX = 1/65535` |
| username/password ≤256 | `CREDENTIAL_MAX = 256` |
| both-or-neither message | `"Username and password must both be provided or both be omitted."` |
| keep-alive default 60, 0–65535 | `DEFAULT_KEEP_ALIVE_SECONDS = 60`, `KEEP_ALIVE_MIN/MAX = 0/65535` |
| persistent-session message | `"A client identifier is required when the clean session is disabled (persistent session)."` |
| clean session `true` when unset | `asBoolean(rootState, CLEAN_SESSION, true)` (template 141, 437) |
| max reconnect default 3, 0–10 | `ReconnectionValidator.MAX_ATTEMPTS_DEFAULT/MIN/MAX = 3/0/10` |
| reconnect interval default 5, 1–120 | `INTERVAL_DEFAULT_SECONDS/MIN/MAX = 5/1/120` |
| LWT topic 1–65535, required with payload | `LwtValidator.TOPIC_MIN/MAX_CHARS`, message `"An LWT topic is required when an LWT payload is configured."` |
| LWT payload 0–268435455 | `LwtValidator.PAYLOAD_MIN/MAX_BYTES` |
| LWT QoS default 0, checked when LWT configured | `parseIntOrDefault(…, 0)` (template 163); `(topicPresent \|\| payloadPresent) && (lwtQos < 0 \|\| lwtQos > 2)` |
| publish topic 1–65535 UTF-8 bytes, no `+`/`#` | `InputValidator` 46–49, `WILDCARD_PLUS`/`WILDCARD_HASH` |
| publish payload 0–268435455, empty default | `PAYLOAD_MIN/MAX_BYTES` 52–55; `payloadText == null → new byte[0]` |
| filters 1–10, index named | `FILTERS_MIN/MAX = 1/10`; `FIELD_TOPIC_FILTERS + "[" + i + "]"`, `"Topic filter at index i must not be empty."` |
| max wait 1–300 | `MAX_WAIT_SECONDS_MIN/MAX` 64–67 |
| max message count 1–1000 | `MAX_MESSAGE_COUNT_MIN/MAX` 70–73 |
| auto-generated client id: 23 chars, `appian-` + 16 from `[0-9a-zA-Z]`, SHA-256 of `host:port`, stable | `ClientIdentifierGenerator`: `PREFIX = "appian-"`, `MAX_LENGTH = 23`, `suffixLength = 23 - 7`, `SAFE_ALPHABET`, `sha256(host + ":" + port)` |

TLS specifics. `ConnectOptionsFactory`: `TLS_PROTOCOL = "TLSv1.2"` for `SSLContext.getInstance`,
`ENABLED_TLS_PROTOCOLS = {"TLSv1.2","TLSv1.3"}` forced onto every socket by
`TlsVersionConstrainingSocketFactory`; `"Mutual TLS requires both a client certificate and its
private key."` thrown with `MqttErrorCategory.CONFIGURATION` when exactly one of cert/key is present;
CA parse failures throw `CONFIGURATION` from `buildTrustManagers` at connect time, and nothing parses
the CA at design time — the template's design-time `CertificateLoader.load` call covers only
`clientCertificate` + `clientPrivateKey`, gated on `tlsEnabled && (clientCertificate != null ||
privateKeyBytes != null)`, exactly as the README says.

Both TLS caveats hold. `CertificateLoader.parsePrivateKey` uses `PKCS8EncodedKeySpec` over
`KEY_ALGORITHMS = {"RSA","EC","DSA","Ed25519"}` with no passphrase parameter, and a repo-wide grep
for `keyPassphrase` finds it only in the config record, the descriptor, the fingerprint, and the
`Redactor` — never in a key-decryption path. `resolveDocumentBytes` returns bytes only for `byte[]`,
a `Document` with a readable stream, or non-blank text, and `null` otherwise.

`maxReconnectAttempts` inertness is confirmed by grep: the value is declared, validated, defaulted,
copied into the config, and hashed into the fingerprint (`MqttConnectionManager.java:261`) — no code
caps an attempt count. Note the stale javadoc at `ConnectOptionsFactory.java:44` claiming the manager
enforces it; the README's "inert" wording is the accurate one.

Credentials at connect time: `applyCredentials` sets username/password only when the username is
non-blank *and* the password array is non-empty. Reconnect: `setAutomaticReconnect(…)` plus
`setMaxReconnectDelay(intervalSeconds * 1000)`.

Security-note claims: no `System.out`, `System.err`, `printStackTrace`, or `System.setProperty`
anywhere under `src/main/java`; in-memory `KeyStore.load(null, null)` for both trust and key stores,
so no temp keystore file; `java.util.Arrays.fill(privateKeyBytes, (byte) 0)` after
`CertificateLoader.load` in `buildKeyManagers`; secrets held as `char[]` on `MqttConnectionConfig`.

### Subscribe termination semantics (gate item 6)

`MqttSubscribeIntegration.collect` matches the README step for step:
`new LinkedBlockingQueue<>(maxMessageCount)` with `Consumer<ReceivedMessage> sink = queue::offer`
(drop-on-full, never blocking the Paho callback); `session.subscribe(filters, qos, sink)`;
`POLL_SLICE_MILLIS = 200L` poll slices against `deadlineNanos = System.nanoTime() +
TimeUnit.SECONDS.toNanos(maxWaitSeconds)`, with `queue.drainTo(collected, maxMessageCount -
collected.size())` after each received message; loop exit on `collected.size() >= maxMessageCount` or
an expired deadline. The `finally` block calls `session.unsubscribe(...)` inside a swallowing
`try/catch` and then `connectionManager.release(session)`. An empty list is returned as
`new OperationResult(true, null, null, data)` — success, not an error. Arrival order follows from the
FIFO queue.

One sequencing nuance, not a documentation error: validation runs before `acquire`, so the README's
numbered list starts after the validation gate it already described in the inputs section.

### Namespace and Smart Service wording (gate items 7, 8)

`grep -n 'com\.appian\.mqtt' README.md` → no output (exit 1). The documented package, plug-in key,
and manifest key are all `de.hock.mqtt`, matching `appian-plugin.xml` and the source tree.

The README states plainly that no smart service is contributed, that the manifest declares no
`<smart-service>` module (confirmed: `appian-plugin.xml` contains only one
`<connected-system-template>` with two nested `<integration-template>` elements), and that
`Call Integration` is Appian's built-in node, repeated in the "Calling the integrations" section. No
smart service class exists under `src/main/java`.

### Snippets and paths (gate item 9)

The two SAIL snippets use only real input names (`topic`, `payload`, `qos`, `retained`;
`topicFilters`, `qos`, `maxWaitSeconds`, `maxMessageCount`) and real result keys (`status`,
`messageCount`), with `rule!MQTT_PublishMessage` / `rule!MQTT_SubscribeTopics` explicitly flagged as
placeholders for the designer's own integration object. `qos` is passed as the string `"1"`, which
matches the `Choice` values `"0"/"1"/"2"` on a `TextPropertyDescriptor`. `a!localVariables`,
`a!toJson`, and the `success`/`result`/`error.title` response shape are standard Appian.

Linked paths all resolve: `THIRD-PARTY-NOTICES.md`, `docs/mqtt-plugin-guide.md`,
`.kiro/steering/appian-appmarket-compliance.md`, `src/test/java/de/hock/mqtt/ProjectStructureTest.java`.
The shell snippets (`mvn clean package`, `cp target/… APPIAN_INSTALL/_admin/plugins/`) reference the
real artifact path.

### Version, vendor, artifact facts (gate item 10)

`target/` holds exactly one jar, `mqtt-broker-connected-system-1.0.0-plugin.jar`, matching
`finalName` + assembly `<id>plugin</id>`. Listing it: 33 `.java` entries, one bare root
`appian-plugin.xml`, one `META-INF/lib/org.eclipse.paho…` entry, one `THIRD-PARTY-NOTICES.md`, zero
`com/appian/mqtt` paths. `unzip -p` on the manifest shows `<version>1.0.0</version>`,
`<application-version min="26.9.0.0"/>`, `<vendor name="Hock" url="https://itondemand.com"/>` — the
three-part plug-in version and four-part platform minimum the README distinguishes.

`pom.xml` backs the rest: `groupId de.hock`, `maven.compiler.release 17`,
`com.appian:connected-systems-core:1.9.1` scope `provided`,
`org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5` bundled, `maven-assembly-plugin:3.7.1`
execution `build-appian-plugin` at `package`, and `maven-jar-plugin` `default-jar` bound to phase
`none` — which is what the README's "exactly one jar by design" sentence rests on.

Known-limitations section verified: `ProjectStructureTest` holds three tests asserting the base
package resolves and that `appian-plugin.xml` and `resources_en_US.properties` are on the classpath;
`net.jqwik:jqwik:1.8.4` and `io.moquette:moquette-broker:0.17` are declared with Surefire including
`**/*Properties.java` and Failsafe including `**/*IT.java`, and `find src/test` shows no file matching
either pattern; the templates do implement the low-level `ConnectedSystemTemplate` /
`IntegrationTemplate` interfaces and the POM declares no `connected-systems-client`; no `.png` exists
anywhere under `src`, and `src/main/resources/de` does not exist, so the iconless-rendering
limitation is real.

</details>

<details>
<summary>Files examined</summary>

- `README.md` — subject of review
- `src/main/resources/appian-plugin.xml`, `resources_en_US.properties` — manifest keys, version,
  vendor, labels
- `src/main/java/de/hock/mqtt/validation/ConfigFields.java`, `ConfigValidator.java`,
  `LwtValidator.java`, `ReconnectionValidator.java`, `InputValidator.java`,
  `ClientIdentifierGenerator.java`, `CertificateLoader.java` — keys, bounds, defaults, messages
- `src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java` — descriptor, defaults,
  document resolution
- `src/main/java/de/hock/mqtt/integration/MqttPublishIntegration.java`,
  `MqttSubscribeIntegration.java` — inputs, outputs, collection semantics
- `src/main/java/de/hock/mqtt/MqttErrorCategory.java`, `MqttConnectionConfig.java`,
  `ReceivedMessage.java` — enum constants, data shapes
- `src/main/java/de/hock/mqtt/diagnostics/ResponseMapper.java`, `DiagnosticsBuilder.java`,
  `Redactor.java`, `ExceptionCategoryTranslator.java` — response shape, diagnostics keys, redaction
- `src/main/java/de/hock/mqtt/protocol/ConnectOptionsFactory.java`, `MqttConnectionManager.java`,
  `MqttSession.java` — TLS, connect-time classification, fingerprint, payload classification
- `pom.xml`, `src/assembly/plugin-assembly.xml`, `target/mqtt-broker-connected-system-1.0.0-plugin.jar`
  — build and packaging facts
- `src/test/java/de/hock/mqtt/ProjectStructureTest.java` — test-coverage claim

</details>
