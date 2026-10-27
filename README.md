# MQTT Broker Connected System — Minimal Build

**This is a minimal build.** Version `1.0.4` validated four things on a live Appian 26.9 instance:

1. the plug-in **installs** on Appian 26.9,
2. the bundled **Eclipse Paho** MQTT library loads and is usable at run time,
3. a **Connected System configuration page renders** with basic parameters,
4. **one integration can actually publish** to a broker.

That baseline is confirmed. It came out of a deliberate, requested scope reduction from the full
`1.0.3` plug-in — not a regression: the full build hit three successive deploy-time
platform-contract failures, so the surface was stripped back to something that could be proven on a
real instance before anything was added back.

**Version `1.0.5` adds exactly two things to that baseline:**

- **Design-time validation — present but inert.** It runs ungated on every render, as a probe for
  whether Appian displays connected system field errors at all. It does not: the messages are
  discarded. See [Design-time validation](#design-time-validation-probe-run-and-the-answer-is-no).
- **Four session configuration fields** — `connectionTimeoutSeconds`, `keepAliveSeconds`,
  `cleanSession`, `autoReconnectEnabled`, bringing the connected system to **nine** fields. Every
  one of them reaches `MqttConnectOptions` at run time; none is decorative.

## What is still deliberately removed

- **TLS and certificates** — `tlsEnabled`, `caCertificate`, `clientCertificate`,
  `clientPrivateKey`, `keyPassphrase`, the trust/key-manager construction and the `SSLContext`
  wiring. This build speaks plain `tcp://` only.
- **The subscribe integration** — `MqttSubscribeIntegration` and its bounded polling/queue/deadline
  machinery.
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
- **Version:** `1.0.5` (manifest `<plugin-info><version>`, three-part `x.y.z`)
- **Minimum Appian version:** `26.9.0.0`
- **Vendor:** Hock, <https://itondemand.com>

The plug-in key and both module keys are unchanged from `1.0.3`, and `1.0.5 > 1.0.4`, so this build
**replaces** the installed plug-in rather than installing alongside it.

## What this plug-in provides

`src/main/resources/appian-plugin.xml` registers one connected system template with one nested
integration template:

| Module | Manifest key | Designer-facing name | Class |
|---|---|---|---|
| Connected system template | `MqttConnectedSystemTemplate` | MQTT Broker | `de.hock.mqtt.template.MqttConnectedSystemTemplate` |
| Integration template (`WRITE`) | `MqttPublishIntegration` | Publish MQTT Message | `de.hock.mqtt.integration.MqttPublishIntegration` |

### This plug-in does not contribute a smart service

Worth stating plainly, because it is a common expectation: there is no custom smart service here.
The manifest declares no `<smart-service>` module and there is no smart service class under
`src/main/java`. What you get is a connected system plus one integration template.

The usage model follows from that:

1. Create one **Connected System** object of type **MQTT Broker** and fill in the broker settings.
2. Create an **Integration** object against that connected system, choosing **Publish MQTT
   Message**.
3. Call that integration object from an expression, or from a process model using Appian's
   **built-in "Call Integration" smart service node**.

That `Call Integration` node is part of the Appian platform, not something this plug-in installs.

## Requirements

- Appian **26.9** (manifest sets `<application-version min="26.9.0.0"/>`)
- **Java 17** to build (`maven.compiler.release` is `17`)
- A reachable **MQTT broker** speaking MQTT 3.1.1 over **plain TCP** — there is no TLS in this build
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
target/mqtt-broker-connected-system-1.0.5-plugin.jar
```

The `-plugin` suffix comes from the assembly `<id>`; a jar without it is a stale build. Exactly one
jar is produced by design — the default `maven-jar-plugin` execution is bound to phase `none`, so
there is no second archive that also looks deployable. The jar carries the plug-in's **five `.java`
sources** alongside the compiled classes (an AppMarket requirement), `appian-plugin.xml` at the
archive root, and Paho as a nested jar under `META-INF/lib/`.

## Deploy

Copy the jar into the plug-ins directory of the Appian installation:

```bash
cp target/mqtt-broker-connected-system-1.0.5-plugin.jar APPIAN_INSTALL/_admin/plugins/
```

Hot deployment is governed by the `conf.plugins.poll-interval` custom property (seconds). When it is
set to `0`, hot deployment is off and loading the plug-in requires an application server restart.
See [Appian Suite Plug-ins](https://docs.appian.com/suite/help/26.9/Appian_Plug-ins.html).

Confirm the deployment three ways:

- The **Admin Console → Plug-ins** page lists the plug-in at version `1.0.5`.
- **New > Connected System** in Appian Designer offers the **MQTT Broker** type **and the form
  renders nine fields**. This is the check the three releases before `1.0.4` failed.
- `tomcat-stdOut.log` shows a `Successfully installed Plug-in` line plus
  `ConnectedSystemTemplateModuleParser` registration lines.

## Configuring the Connected System

Create a Connected System object and pick the **MQTT Broker** template. The nine fields below are
the whole configuration surface, listed in the order the template renders them. Keys are the
constants on `de.hock.mqtt.template.MqttConnectedSystemTemplate`; labels are from
`resources_en_US.properties`.

| Label | Key | Type | Required | Effective default | Validation |
|---|---|---|---|---|---|
| Broker Host | `brokerHost` | Text | Yes | — | Required; non-blank after trimming |
| Broker Port | `brokerPort` | Integer | Yes | — | Required; `1`–`65535`. Placeholder suggests `1883` |
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
`de.hock.mqtt.MqttPublisher.buildConnectOptions`, and a test reads every one of them back through
Paho's own getters, so a setting that stops being honoured fails the build. A `null` value is
interpreted at run time as the default in the table — no descriptor state is ever pre-populated.
An out-of-range value also falls back to its default rather than refusing the message.

**`autoReconnectEnabled` has very little room to act — say so out loud.** One publish connects,
sends and disconnects inside a single request, so there is almost nothing for automatic reconnect
to do. It is exposed because it is a legitimate `MqttConnectOptions` setting and more connection
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

### Design-time validation: probe run, and the answer is no

`1.0.5` validates the configuration in `de.hock.mqtt.validation.ConfigValidator` on **every render,
including the very first one**, attaching messages through `PropertyState.setErrorAtPath`.

That was deliberately ungated as a probe. Appian's 26.9 restrictions page says `setErrors` is
ignored on a connected system template, but this plug-in attaches through `setErrorAtPath`, a
different method, so the documentation could not settle whether field errors display at all. Running
validation ungated turned the question into one observation: open **New > Connected System > MQTT
Broker** and see whether the messages appear.

**Result, observed on a live Appian 26.9 instance: they do not.** No error message is displayed. The
page renders normally and the plug-in works, but Appian discards errors set this way, so the
`setErrors` restriction covers `setErrorAtPath` too.

What that means for anyone working on this plug-in:

- `isRequired` is the **only** design-time guard available on a connected system. Appian honours it.
- Validate configuration at **run time**, inside the integration, and report through the integration
  response. Do not attempt design-time validation on the connected system.
- `ConfigValidator` is consequently **dead code** at `1.0.5` — it executes and its results are
  thrown away. It should be removed or demoted to a run-time check. It is left in place only so this
  release documents the experiment that produced the finding.
- Whether **integration input** errors display is a separate, untested question.

Attaching errors does not break rendering, so this is a cosmetic dead end rather than a hazard.

Validation rules are the four in the field table. Every message is attached to its own field path,
never to the top of the form. Optional fields left blank report nothing.

**Errors are cleared before they are re-evaluated.** Each pass calls
`PropertyState.clearErrorsAtPath` for every validated field and only then re-attaches what still
fails. This matters because the SDK copies existing messages into the regenerated state, so the
`1.0.3` validator — which only ever attached — left stale complaints on fields the designer had
already corrected. `aCorrectedValueClearsThePreviouslyAttachedError` is the regression test.

`isRequired` remains in place as well, and the publish integration re-checks host, port, topic and
QoS at invocation time before it contacts a broker.

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
| `Invalid input` | A blank topic, a blank broker host, a port outside 1–65535, or a QoS outside 0–2. No broker was contacted. |
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

## Runtime behavior, stated honestly

One **connect → publish → disconnect** per request, through `de.hock.mqtt.MqttPublisher` — the
single Paho call site in the plug-in. Specifically:

- **Connect timeout from the connected system**, defaulting to **5 seconds** when the field is
  blank, so an unreachable broker fails fast rather than hanging an Appian request thread.
- **Keep-alive, clean session and automatic reconnect from the connected system**, each applied to
  `MqttConnectOptions` with the defaults listed in the field table.
- **10-second cap** on any single blocking Paho call (`setTimeToWait`).
- **No connection reuse**, no session manager, no background threads, no Last Will.
- **Plain `tcp://` only.** No `ssl://`, no `SSLContext`, no socket factory, and no
  `System.setProperty` call anywhere — Appian's plug-in policies forbid JVM-global mutation, which
  is the usual shortcut for MQTT-over-TLS.
- `MemoryPersistence` rather than Paho's default file persistence, so nothing is written to the
  filesystem.
- The client is disconnected and closed in a `finally` block, so a failed publish never leaks a
  connection.

The plug-in contains no logging at all — no Log4j dependency, and no `System.out`, `System.err`,
`printStackTrace()` or `java.util.logging` call. Failure detail surfaces in the integration
response, which is where a Designer sees it.

## Known limitations

- **Automated test coverage is offline only — 13 tests in four classes.** No test needs a broker or
  an Appian runtime.
  - `src/test/java/de/hock/mqtt/ProjectStructureTest.java` (2) — `appian-plugin.xml` and
    `resources_en_US.properties` are on the classpath.
  - `src/test/java/de/hock/mqtt/template/MqttConnectedSystemTemplateDescriptorTest.java` (5) —
    drives both templates' configuration descriptors and asserts a non-null root state with a
    resolvable root type, exactly the nine expected field keys in order, that no connected system
    descriptor reads back expressionable, that no field uses a List/Document/AppianValue descriptor
    type, and that the publish integration renders its three inputs and all three stay
    expressionable.
  - `src/test/java/de/hock/mqtt/template/MqttConnectedSystemTemplateValidationTest.java` (4) — the
    ungated first-render errors, a valid host and port leaving both fields clean, an out-of-range
    port landing on the port path and not the root, and the corrected-value-clears-the-error
    regression.
  - `src/test/java/de/hock/mqtt/MqttPublisherConnectOptionsTest.java` (2) — each of the four session
    settings reaching `MqttConnectOptions`, and the documented defaults for absent and out-of-range
    values.

  **Nothing exercises a real publish** — that is what the deploy-time smoke test is for.
- **Appian does not display design-time errors on a connected system — confirmed, not assumed.**
  The `1.0.5` probe was run on a live 26.9 instance and no message appeared; see
  [Design-time validation](#design-time-validation-probe-run-and-the-answer-is-no). `ConfigValidator`
  is therefore inert, `isRequired` is the only design-time guard that works, and configuration
  validation belongs at run time in the integration.
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

Restore selectively — the TLS layer, or the subscribe integration — rather than wholesale, and
re-read [the steering file](.kiro/steering/appian-appmarket-compliance.md) first: the three platform
contracts that broke `1.0.1`–`1.0.3` still apply to anything you put back, and the version must be
bumped past `1.0.5`.

## License and third-party software

This plug-in bundles one third-party library: the Eclipse Paho Java Client (MQTT v3)
`org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5`, redistributed unmodified as a nested jar.
Paho is dual-licensed under EPL-2.0 and EDL-1.0; redistribution here relies on the permissive
**EDL-1.0**. No copyleft-licensed library is bundled. Full detail is in
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md), which also ships at the root of the plug-in jar.

## Links

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
