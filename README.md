# MQTT Broker Connected System — Minimal Smoke-Test Build

**This is a minimal smoke-test build.** Version `1.0.4` exists to validate exactly four things and
nothing else:

1. the plug-in **installs** on Appian 26.9,
2. the bundled **Eclipse Paho** MQTT library loads and is usable at run time,
3. a **Connected System configuration page renders** with a few basic parameters,
4. **one integration can actually publish** to a broker.

It is a deliberate, requested scope reduction from the full `1.0.3` plug-in — not a regression. The
full build hit three successive deploy-time platform-contract failures, so this build strips the
surface back to a known-good baseline that can be confirmed on a real instance before anything is
added back.

## What was deliberately removed

- **TLS and certificates** — `tlsEnabled`, `caCertificate`, `clientCertificate`,
  `clientPrivateKey`, `keyPassphrase`, the trust/key-manager construction and the `SSLContext`
  wiring. This build speaks plain `tcp://` only.
- **The subscribe integration** — `MqttSubscribeIntegration` and its bounded polling/queue/deadline
  machinery.
- **Last Will and Testament** — `lwtTopic`, `lwtPayload`, `lwtQos`, `lwtRetained`.
- **Reconnection and session tuning** — `autoReconnectEnabled`, `maxReconnectAttempts`,
  `reconnectIntervalSeconds`, `keepAliveSeconds`, `cleanSession`, and `connectionTimeoutSeconds` as
  a configurable field (a 5-second connect timeout is hardcoded instead).
- **Diagnostics and redaction** — the whole `diagnostics` package and the `MqttErrorCategory`
  taxonomy.
- **Design-time validation** — no `validate()`, no `setErrorAtPath`, no first-render gate. See
  [Configuring the Connected System](#configuring-the-connected-system).
- The `retained` publish input.

**All of it is recoverable.** The complete `1.0.3` source — 36 Java files plus the pom, README,
`info.md`, `THIRD-PARTY-NOTICES.md`, `docs/` and `.kiro/` — is archived at
`.backup/mqtt-plugin-FULL-1.0.3-src.zip`. See
[Restoring the full build](#restoring-the-full-build).

## Facts

- **Plug-in key:** `de.hock.mqtt`
- **Version:** `1.0.4` (manifest `<plugin-info><version>`, three-part `x.y.z`)
- **Minimum Appian version:** `26.9.0.0`
- **Vendor:** Hock, <https://itondemand.com>

The plug-in key and both module keys are unchanged from `1.0.3`, and `1.0.4 > 1.0.3`, so this build
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
target/mqtt-broker-connected-system-1.0.4-plugin.jar
```

The `-plugin` suffix comes from the assembly `<id>`; a jar without it is a stale build. Exactly one
jar is produced by design — the default `maven-jar-plugin` execution is bound to phase `none`, so
there is no second archive that also looks deployable. The jar carries the plug-in's **four `.java`
sources** alongside the compiled classes (an AppMarket requirement), `appian-plugin.xml` at the
archive root, and Paho as a nested jar under `META-INF/lib/`.

## Deploy

Copy the jar into the plug-ins directory of the Appian installation:

```bash
cp target/mqtt-broker-connected-system-1.0.4-plugin.jar APPIAN_INSTALL/_admin/plugins/
```

Hot deployment is governed by the `conf.plugins.poll-interval` custom property (seconds). When it is
set to `0`, hot deployment is off and loading the plug-in requires an application server restart.
See [Appian Suite Plug-ins](https://docs.appian.com/suite/help/26.9/Appian_Plug-ins.html).

Confirm the deployment three ways:

- The **Admin Console → Plug-ins** page lists the plug-in at version `1.0.4`.
- **New > Connected System** in Appian Designer offers the **MQTT Broker** type **and the form
  renders five fields**. This is the check the three previous releases failed.
- `tomcat-stdOut.log` shows a `Successfully installed Plug-in` line plus
  `ConnectedSystemTemplateModuleParser` registration lines.

## Configuring the Connected System

Create a Connected System object and pick the **MQTT Broker** template. The five fields below are
the whole configuration surface, listed in the order the template renders them. Keys are the
constants on `de.hock.mqtt.template.MqttConnectedSystemTemplate`; labels are from
`resources_en_US.properties`.

| Label | Key | Type | Required | Notes |
|---|---|---|---|---|
| Broker Host | `brokerHost` | Text | Yes | Host name or address |
| Broker Port | `brokerPort` | Integer | Yes | Placeholder suggests `1883` |
| Client Identifier | `clientIdentifier` | Text | No | Auto-generated by Paho when left blank |
| Username | `username` | Text | No | Leave both credential fields blank for an anonymous connection |
| Password | `password` | Encrypted text, masked | No | Stored encrypted; not rendered back after save |

**No connected system field is expressionable.** Every field takes a literal value only. This is an
Appian platform restriction rather than a design choice: a connected system template that declares
an expressionable property descriptor is rejected outright at descriptor-conversion time, and the
configuration form then fails to render at all. Expressions are supported on the **integration
inputs** instead.

**There is no design-time validation in this build.** `isRequired` is the only guard: Appian itself
enforces that Broker Host and Broker Port are filled in. The template declares its five properties
and returns — it attaches no field errors. That is deliberate, for three reasons: whether Appian
honours field errors on a connected system at all is unresolved (the 26.9 restrictions page says
`setErrors` is ignored there), the full build never cleared an error once attached, and the
`cleanSession` field the first-render gate was built on no longer exists. The publish integration
re-checks host, port, topic and QoS at invocation time instead, before it contacts a broker.

**Broker Port is suggested, not seeded.** The field shows `1883` as a placeholder; it is not
pre-filled. Actually seeding a value would need a first-render signal to avoid overwriting what you
type on every re-render, and that machinery is exactly what this build removes.

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

- **5-second connect timeout**, hardcoded, so an unreachable broker fails fast rather than hanging
  an Appian request thread.
- **10-second cap** on any single blocking Paho call (`setTimeToWait`).
- **No connection reuse**, no session manager, no reconnection, no background threads, no Last Will.
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

- **Automated test coverage is design-time only — 7 tests in two classes.**
  `src/test/java/de/hock/mqtt/ProjectStructureTest.java` checks that `appian-plugin.xml` and
  `resources_en_US.properties` are on the classpath;
  `src/test/java/de/hock/mqtt/template/MqttConnectedSystemTemplateDescriptorTest.java` drives both
  templates' configuration descriptors and asserts a non-null root state with a resolvable root
  type, exactly the five expected field keys in order, that no connected system descriptor reads
  back expressionable, that no field uses a List/Document/AppianValue descriptor type, and that the
  publish integration renders its three inputs and all three stay expressionable. **Nothing
  exercises a real publish** — that is what the deploy-time smoke test is for.
- **The `META-INF/lib/` layout is still unverified on a live instance.** It matches where Appian's
  plug-in classloader resolves nested jars, but it has never been confirmed in practice. A
  `NoClassDefFoundError` naming `org/eclipse/paho/...` at first publish would mean it is wrong — and
  it would name `de.hock.mqtt.MqttPublisher` as the caller, which is the whole reason the Paho call
  site is isolated in one class.
- **The `connected-systems-client` requirement on the server classpath is unverified.** Both
  templates subclass the `Simple*` facade and both Appian artifacts are `provided`, so the Appian
  server must supply `com.appian:connected-systems-client` at run time. A `NoClassDefFoundError`
  naming `com/appian/connectedsystems/simplified/sdk/...` at install or first render would mean it
  does not.
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

```bash
mkdir -p /tmp/mqtt-full && unzip .backup/mqtt-plugin-FULL-1.0.3-src.zip -d /tmp/mqtt-full
```

The archive holds the complete `1.0.3` tree (74 entries). Restore selectively — a single validator,
the TLS layer, or the subscribe integration — rather than wholesale, and re-read
[the steering file](.kiro/steering/appian-appmarket-compliance.md) first: the three platform
contracts that broke `1.0.1`–`1.0.3` still apply to anything you put back, and the version must be
bumped past `1.0.4`.

## License and third-party software

This plug-in bundles one third-party library: the Eclipse Paho Java Client (MQTT v3)
`org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5`, redistributed unmodified as a nested jar.
Paho is dual-licensed under EPL-2.0 and EDL-1.0; redistribution here relies on the permissive
**EDL-1.0**. No copyleft-licensed library is bundled. Full detail is in
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md), which also ships at the root of the plug-in jar.

## Links

- [.agents/tasks/minimal-smoke-build.md](.agents/tasks/minimal-smoke-build.md) — the reduction
  record: every deleted and kept file, the verification evidence, and the deploy-and-test checklist.
- [docs/mqtt-plugin-guide.md](docs/mqtt-plugin-guide.md) — **describes the pre-reduction full
  build**, not what ships here. Useful if you restore the archive.
- [.kiro/steering/appian-appmarket-compliance.md](.kiro/steering/appian-appmarket-compliance.md) —
  the AppMarket packaging and submission constraints for this plug-in (namespace, version formats,
  sources-in-jar, descriptor restrictions, submission process). Consult it before packaging a
  release or submitting a new version; it is not summarized here.

Appian documentation content referenced above was rephrased for compliance with licensing
restrictions.
