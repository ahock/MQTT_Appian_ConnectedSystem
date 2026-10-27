# Implementation Plan — Minimal Smoke-Test Build (1.0.4)

Scope reduction of the MQTT Broker Connected System plug-in at
`/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component` from the full 1.0.3 surface to a
minimal smoke-test build that proves four things only: the plug-in installs on Appian 26.9, the
bundled Eclipse Paho library loads and runs, a Connected System form renders five basic fields, and
one integration publishes to a real broker.

**This is a deliberate, user-requested reduction, not a regression.**

**No git.** This directory is not a git repository (`git rev-parse` fails). Never run
`git init`/`worktree`/`checkout`/`commit`/anything. All edits happen in place.

**Do not touch** `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/.backup/` — the full
1.0.3 source archive lives there (`mqtt-plugin-FULL-1.0.3-src.zip`, 136397 bytes,
sha256 `386f3cab7ab660e19325f2825071152338eb48855e6f45b8d9665afcc22f89b4`, 74 entries). Read-only.

---

## 0. Grounding facts measured in the current tree (not assumed)

| Fact | Measured value |
|---|---|
| `.java` files under `src/main/java` | 34 |
| `.java` files under `src/test/java` | 2 (`ProjectStructureTest`, `MqttConnectedSystemTemplateDescriptorTest`) |
| `.java` entries in the built 1.0.3 jar | 34, all under `de/hock/mqtt/` |
| Keys in `resources_en_US.properties` | **34 total** (6 name/description + 28 field labels). The brief's "28" is the field-label subset; both numbers are reported. |
| `isExpressionable` call sites in `src/main/java` | 7 — Publish 4, Subscribe 3, template 0 |
| Jar layout today | bare-root `appian-plugin.xml`, `resources_en_US.properties`, `THIRD-PARTY-NOTICES.md`; one nested jar `META-INF/lib/org.eclipse.paho.client.mqttv3-1.2.5.jar`; zero `connected-systems` entries |
| `target/*.jar` | exactly one (`default-jar` execution is bound to phase `none`) |
| Logging in `src/main/java` | **none at all** — zero `Logger`, zero log4j import, zero `System.out`/`printStackTrace`. The pom declares no logging dependency. |
| `*Properties.java` / `*IT.java` test files | **none exist** — the jqwik/moquette deps and the Surefire/Failsafe wiring for them are dead config |
| Paho API confirmed by `javap` on `org.eclipse.paho.client.mqttv3-1.2.5.jar` | `MqttClient(String,String)`, `connect(MqttConnectOptions)`, `publish(String,byte[],int,boolean)`, `disconnect()`, `close()`, `setTimeToWait(long)`, `public static String generateClientId()`; `IMqttClient extends AutoCloseable` |
| SDK builder guards confirmed by `javap` on `connected-systems-core-1.9.1.jar` | `PropertyDescriptorBuilder` exposes `placeholder(String)`; only `LocalTypePropertyDescriptorBuilder` calls `Guard.checkPropertyHasNoPlaceholder`, so `placeholder` is safe on Text/Integer/EncryptedText |

## 0.1 Why this is NOT decomposed into FEAT artifacts

Two reasons, both decisive:

1. The per-FEAT coder contract requires each feature to **commit locally on the branch**. This
   directory is not a git repository and the brief forbids git entirely, so that contract cannot be
   honored.
2. The work does not separate. Deleting 31 sources, rewriting the two templates, the manifest and
   the resource bundle is one atomic change — nothing compiles between the delete and the rewrite,
   so there is no intermediate feature boundary that leaves a working tree.

The workflow's existing implement-and-review loop runs this plan. The stop contract is unchanged:
the loop ends when
`/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/.agents/tasks/minimal-smoke-build-review.json`
has top-level `"verdict": "APPROVED"`, written by the reviewer as the last step.

---

## 1. DELETE list — 31 files under `src/main/java`, absolute paths

Every one of these is removed outright, together with its manifest declaration and its
resource-bundle keys. Nothing from this list may be kept "just in case".

Base package (6 of 7 files; `package-info.java` survives):

```
/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/MqttConnectionConfig.java
/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/MqttErrorCategory.java
/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/OperationResult.java
/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/PublishRequest.java
/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/ReceivedMessage.java
/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/SubscribeRequest.java
```

`diagnostics` package — delete the whole directory (5 files):

```
/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/diagnostics/DiagnosticsBuilder.java
/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/diagnostics/ExceptionCategoryTranslator.java
/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/diagnostics/Redactor.java
/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/diagnostics/ResponseMapper.java
/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/diagnostics/package-info.java
```

`protocol` package — delete the whole directory (8 files):

```
/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/protocol/ConnectOptionsException.java
/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/protocol/ConnectOptionsFactory.java
/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/protocol/MqttClientAdapter.java
/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/protocol/MqttConnectionManager.java
/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/protocol/MqttOperationException.java
/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/protocol/MqttSession.java
/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/protocol/PahoMqttClientAdapter.java
/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/protocol/package-info.java
```

`validation` package — delete the whole directory (11 files):

```
/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/validation/CertificateLoader.java
/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/validation/ClientIdentifierGenerator.java
/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/validation/ConfigFields.java
/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/validation/ConfigValidator.java
/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/validation/InputValidator.java
/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/validation/LwtValidator.java
/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/validation/PemValidator.java
/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/validation/ReconnectionValidator.java
/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/validation/ValidationResult.java
/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/validation/ValidationResults.java
/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/validation/package-info.java
```

Subscribe integration (1 file):

```
/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/integration/MqttSubscribeIntegration.java
```

After the deletions the directories `src/main/java/de/hock/mqtt/diagnostics`,
`.../protocol` and `.../validation` must be gone, not left empty.

Under `src/test/java`, no file is deleted outright — both existing test classes are **replaced in
place** (see item 4). Their current contents reference deleted production classes
(`MqttSubscribeIntegration`, `ConnectOptionsFactory`, `ConfigFields`, `MqttConnectionConfig`), so
replacing them is mandatory, not optional.

## 2. KEEP / CREATE list — 4 files under `src/main/java`

| Absolute path | K/C | One-line reason | What changes inside |
|---|---|---|---|
| `.../src/main/java/de/hock/mqtt/package-info.java` | KEEP | Base-package documentation; one line of Javadoc, no runtime weight. | Rewrite the Javadoc to describe the minimal smoke-test build (connected system + one publish integration) instead of the six-group/two-integration/diagnostics surface. |
| `.../src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java` | KEEP | The connected system template is requirement (3); its class name, `@TemplateId(name = "MqttConnectedSystemTemplate")` and manifest key must stay so 1.0.4 replaces 1.0.3 rather than installing beside it. | Full rewrite — see §3.1. |
| `.../src/main/java/de/hock/mqtt/integration/MqttPublishIntegration.java` | KEEP | The one surviving integration, requirement (4); class name, `@TemplateId(name = "MqttPublishIntegration")` and `@IntegrationTemplateType(WRITE)` unchanged. | Full rewrite — see §3.2. |
| `.../src/main/java/de/hock/mqtt/MqttPublisher.java` | **CREATE** | The single, direct Paho call site: connect → publish → disconnect per request. Isolating it means a deploy-time `NoClassDefFoundError` for Paho names exactly one class. | New file — see §3.3. |

Non-Java files kept and edited:

| Absolute path | K/C | Reason | What changes |
|---|---|---|---|
| `.../src/main/resources/appian-plugin.xml` | KEEP | Plug-in manifest; must stay a bare root jar entry. | One connected-system-template with one nested integration-template, `<version>1.0.4</version>`, comment rewritten. Exact content in §4. |
| `.../src/main/resources/resources_en_US.properties` | KEEP | i18n bundle supplying template names and field labels. | Pruned to exactly 12 keys, labels copied verbatim from the current bundle. Exact content in §5. |
| `.../pom.xml` | KEEP | Build definition. | Version 1.0.4; jqwik + moquette + Failsafe removed. §6. |
| `.../src/assembly/plugin-assembly.xml` | KEEP | Implements sources-in-jar, bare-root manifest, `META-INF/lib/` nesting — all three packaging invariants. | **No structural change.** Only the comment naming `PahoMqttClientAdapter`/`ConnectOptionsFactory` is updated to name `MqttPublisher`. Do not touch the fileSets or the dependencySet. |
| `.../THIRD-PARTY-NOTICES.md` | KEEP | Paho 1.2.5 is still bundled; the notice still ships at the archive root. | Unchanged — the dependency set did not change. |

## 3. Resolved judgement calls and the shape of the four source files

### 3.0 The seven open calls from the brief, decided

| Item | Decision | Reason |
|---|---|---|
| `ConfigFields` vs inline constants | **Delete `ConfigFields`.** The five keys become `public static final String` constants on `MqttConnectedSystemTemplate` (`BROKER_HOST`, `BROKER_PORT`, `CLIENT_IDENTIFIER`, `USERNAME`, `PASSWORD`). | Still one source of truth shared by the template, the integration and the tests, but one fewer file and the whole `validation` package goes away. The integration already imports the template class to read config values, so no new coupling. |
| `ClientIdentifierGenerator` | **Delete.** A blank Client Identifier falls back to Paho's own `MqttClient.generateClientId()` (confirmed `public static` in 1.2.5). | Genuinely a one-liner, supplied by the library. The SHA-256 stable-id scheme only mattered for the connection cache, which is gone. |
| `MqttOperationException` | **Delete.** | It existed to carry an `MqttErrorCategory` across the connection-manager boundary. There is no boundary left: `MqttPublisher` throws Paho's `MqttException` and the integration catches it directly. |
| `MqttErrorCategory` | **Delete.** Error titles become two literals: `"Invalid input"` and `"MQTT publish failed"`. | An 8-value closed enum for a build with two failure modes is an abstraction wrapping nothing. |
| `OperationResult` | **Delete.** | It was the hand-off record between the operation layer and `ResponseMapper`; both are gone. The integration builds `IntegrationResponse` directly. |
| `MqttConnectionConfig` | **Delete.** | A 20-component record for 5 surviving values. The integration reads the 5 values and passes them as parameters. |
| `PublishRequest` | **Delete.** | 3 inputs passed as plain parameters is shorter than constructing and unpacking a record. |

Two further calls the brief did not enumerate, decided here:

- **No logging framework.** The 1.0.3 tree logs nothing at all and the pom declares no logging
  dependency. Adding `log4j` — even `provided` — introduces exactly the class of unverified
  server-classpath assumption this build exists to eliminate. Failure detail goes into the
  integration response, which is where a Designer sees it. The compliance rule ("Log4j only, no
  `System.out`/`System.err`/`printStackTrace`") is satisfied trivially: nothing prints. If logging
  is wanted later, `org.apache.log4j.Logger` with `provided` scope is the only sanctioned route.
- **`MqttPublisher` as a separate class rather than inlined.** Keeps the one Paho call site
  isolated and keeps the integration class readable at ~150 lines. The cost is one extra file in
  the jar; the benefit is that a Paho classloading failure names `de.hock.mqtt.MqttPublisher`.

### 3.1 `MqttConnectedSystemTemplate` — intended shape

```java
@TemplateId(name = "MqttConnectedSystemTemplate")
public class MqttConnectedSystemTemplate extends SimpleConnectedSystemTemplate {

  public static final String BROKER_HOST = "brokerHost";
  public static final String BROKER_PORT = "brokerPort";
  public static final String CLIENT_IDENTIFIER = "clientIdentifier";
  public static final String USERNAME = "username";
  public static final String PASSWORD = "password";

  @Override
  protected SimpleConfiguration getConfiguration(
      SimpleConfiguration configuration, ExecutionContext executionContext) {
    configuration.setProperties(
        TextPropertyDescriptor.builder().key(BROKER_HOST).label("Broker Host")
            .instructionText("MQTT broker host name or address.").isRequired(true).build(),
        IntegerPropertyDescriptor.builder().key(BROKER_PORT).label("Broker Port")
            .instructionText("Broker TCP port. Typically 1883.")
            .placeholder("1883").isRequired(true).build(),
        TextPropertyDescriptor.builder().key(CLIENT_IDENTIFIER).label("Client Identifier")
            .instructionText("MQTT client identifier. Auto-generated when left blank.")
            .isRequired(false).build(),
        TextPropertyDescriptor.builder().key(USERNAME).label("Username")
            .instructionText("Broker user name. Leave username and password blank for an "
                + "anonymous connection.").isRequired(false).build(),
        EncryptedTextPropertyDescriptor.builder().key(PASSWORD).label("Password")
            .instructionText("Broker password. Stored encrypted and masked.")
            .masked(true).isRequired(false).build());
    return configuration;
  }

  // Package-visible-or-public static readers used by the integration:
  //   static String  textValue(PropertyState state, String key)   // String | char[] | null
  //   static int     intValue(PropertyState state, String key, int fallback)
  // Both tolerate String, char[] and boxed Number, as the 1.0.3 helpers did (an
  // EncryptedTextPropertyDescriptor value may read back as either String or char[]).
}
```

Hard rules this file must obey:

- **Zero `.isExpressionable(` calls.** Not `false`, not at all — the base builder already seeds
  `false`, and a literal `isExpressionable` token in this file breaks the grep gate in §8.
- Only `TextPropertyDescriptor`, `IntegerPropertyDescriptor`, `EncryptedTextPropertyDescriptor`.
  Never `Document`, `AppianValue`, `ListType`, `Folder`, `Timestamp`.
- `getConfiguration` declares the five properties and returns. **No** `validate(...)`, **no**
  `setErrorAtPath`, **no** `firstRender` gate, **no** `setValue(...)` seeding, **no**
  `toConnectionConfig`.
- Must keep extending `SimpleConnectedSystemTemplate`. Reverting to the low-level
  `ConnectedSystemTemplate` interface reproduces the `getRootState() == null` NPE.

### 3.2 `MqttPublishIntegration` — intended shape

```java
@TemplateId(name = "MqttPublishIntegration")
@IntegrationTemplateType(IntegrationTemplateRequestPolicy.WRITE)
public class MqttPublishIntegration extends SimpleIntegrationTemplate {

  public static final String INPUT_TOPIC   = "topic";
  public static final String INPUT_PAYLOAD = "payload";
  public static final String INPUT_QOS     = "qos";

  @Override
  protected SimpleConfiguration getConfiguration(
      SimpleConfiguration integrationConfiguration,
      SimpleConfiguration connectedSystemConfiguration,
      PropertyPath propertyPath, ExecutionContext executionContext) {
    integrationConfiguration.setProperties(
        TextPropertyDescriptor.builder().key(INPUT_TOPIC).label("Topic")
            .instructionText("Topic to publish to.")
            .isRequired(true).isExpressionable(true).build(),
        TextPropertyDescriptor.builder().key(INPUT_PAYLOAD).label("Payload")
            .instructionText("Message payload. Sent as UTF-8 bytes.")
            .isRequired(true).isExpressionable(true).build(),
        IntegerPropertyDescriptor.builder().key(INPUT_QOS).label("QoS")
            .instructionText("Quality of Service 0, 1 or 2. Defaults to 0 when blank.")
            .placeholder("0").isRequired(false).isExpressionable(true).build());
    return integrationConfiguration;
  }

  @Override
  protected IntegrationResponse execute(
      SimpleConfiguration integrationConfiguration,
      SimpleConfiguration connectedSystemConfiguration,
      ExecutionContext executionContext) {
    // 1. read inputs + the 5 connected system values
    // 2. cheap runtime guards -> error response, no broker contact:
    //      blank topic            -> "Topic is required."
    //      blank broker host      -> "Broker host is required ..."
    //      port outside 1..65535  -> "Broker port must be between 1 and 65535 ..."
    //      qos outside 0..2       -> "QoS must be 0, 1 or 2."
    // 3. MqttPublisher.publish(...) inside try/catch (MqttException | RuntimeException)
    // 4. success -> IntegrationResponse.forSuccess(Map.of(
    //      "success", Boolean.TRUE, "status", qos == 0 ? "dispatched" : "delivered",
    //      "topic", topic, "qos", qos))
    //    failure -> IntegrationResponse.forError(IntegrationError.builder()
    //      .title("MQTT publish failed").message(<Paho message, truncated to 500>).build())
  }
}
```

Notes:

- Exactly **3** `.isExpressionable(true)` calls in this file — one per input. That is legal and
  wanted on an integration; §8 pins the count.
- The success map is flat, with `success`, `status`, `topic`, `qos`. Flat by design: no nested
  structures.
- The runtime guards above are **not** design-time validation. They run in `execute`, return an
  error `IntegrationResponse`, and never call `setErrorAtPath`. They exist because Paho throws
  `IllegalArgumentException` for a QoS outside 0–2 and because a blank host produces an opaque URI
  failure.
- Truncate the error message to 500 characters (the 1.0.3 `ResponseMapper` bound, kept as a
  two-line helper rather than a class).
- **No diagnostics, no redaction.** Nothing secret is placed in the response: the password is never
  read into a message.

### 3.3 `MqttPublisher` — intended shape

```java
package de.hock.mqtt;

public final class MqttPublisher {

  /** Connect timeout, seconds: a bad host must fail fast, not hang an Appian request. */
  static final int CONNECT_TIMEOUT_SECONDS = 5;
  /** Upper bound on any single blocking Paho call, milliseconds. */
  static final long TIME_TO_WAIT_MILLIS = 10_000L;

  private MqttPublisher() { }

  public static void publish(
      String brokerHost, int brokerPort, String clientIdentifier,
      String username, char[] password,
      String topic, byte[] payload, int qos) throws MqttException {

    String clientId = (clientIdentifier == null || clientIdentifier.isBlank())
        ? MqttClient.generateClientId() : clientIdentifier.trim();

    MqttConnectOptions options = new MqttConnectOptions();
    options.setConnectionTimeout(CONNECT_TIMEOUT_SECONDS);
    options.setCleanSession(true);
    options.setAutomaticReconnect(false);
    if (username != null && !username.isBlank() && password != null && password.length > 0) {
      options.setUserName(username);
      options.setPassword(password);
    }

    MqttClient client = new MqttClient("tcp://" + brokerHost + ":" + brokerPort,
        clientId, new MemoryPersistence());
    client.setTimeToWait(TIME_TO_WAIT_MILLIS);
    try {
      client.connect(options);
      client.publish(topic, payload == null ? new byte[0] : payload, qos, false);
    } finally {
      try { if (client.isConnected()) { client.disconnect(); } } catch (MqttException ignored) { }
      try { client.close(); } catch (MqttException ignored) { }
    }
  }
}
```

- Synchronous `MqttClient` (not `MqttAsyncClient`): the blocking API matches Appian's
  request/response model with no token plumbing.
- `MemoryPersistence` keeps the compliance rule "no direct filesystem manipulation" true — the
  default file persistence would write to disk.
- `tcp://` only. **No TLS, no `ssl://`, no `SSLContext`, no socket factory, no
  `System.setProperty`.**
- Close in `finally`. `IMqttClient` does extend `AutoCloseable`, but try-with-resources would close
  before the disconnect completes, so explicit `finally` is correct here.
- No connection caching, no session manager, no reconnect, no background threads, no LWT.

## 4. Exact intended content of `src/main/resources/appian-plugin.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!--
  Appian plug-in manifest for the MQTT Broker Connected System.

  MINIMAL SMOKE-TEST BUILD. Registers the two runtime entry points of the reduced plug-in:
    * de.hock.mqtt.template.MqttConnectedSystemTemplate - five-field broker configuration
    * de.hock.mqtt.integration.MqttPublishIntegration   - WRITE integration that publishes one message

  The keys are unchanged from 1.0.3 so this build replaces the installed plug-in rather than
  installing alongside it. <plugin-info><version> is three-part x.y.z (the AppMarket validator
  rejects four-part); <application-version min=...> is the four-part Appian platform version.

  The plug-in key must not live under com.appian / com.appiancorp (reserved for Appian's own
  plug-ins), hence key="de.hock.mqtt".
-->
<appian-plugin name="MQTT Broker Connected System" key="de.hock.mqtt">
    <plugin-info>
        <description>Minimal smoke-test build of the MQTT connected system: broker configuration plus one integration that publishes a message to a topic.</description>
        <vendor name="Hock" url="https://itondemand.com"/>
        <version>1.0.4</version>
        <application-version min="26.9.0.0"/>
    </plugin-info>

    <connected-system-template key="MqttConnectedSystemTemplate"
            name="MqttConnectedSystemTemplate"
            class="de.hock.mqtt.template.MqttConnectedSystemTemplate">
        <integration-template key="MqttPublishIntegration"
                name="MqttPublishIntegration"
                class="de.hock.mqtt.integration.MqttPublishIntegration"/>
    </connected-system-template>
</appian-plugin>
```

Unchanged on purpose: `key="de.hock.mqtt"`, both module keys, the vendor element, and
`min="26.9.0.0"`. The `MqttSubscribeIntegration` element is gone.

## 5. Exact intended content of `src/main/resources/resources_en_US.properties`

**12 keys** (down from 34): 2 template name/description pairs = 4, plus **8 field-label keys**
(down from 28). Every label value below is copied verbatim from the current bundle; only the two
`description` values are re-authored, because they enumerate removed features.

```properties
# Default internationalization bundle for the MQTT Broker Connected System plug-in.
#
# MINIMAL SMOKE-TEST BUILD (1.0.4). Appian derives each template's displayed name and description
# from [TemplateClass].name / [TemplateClass].description, where TemplateClass is the simple
# (case-sensitive) class name of the registered template. Field labels follow
# [TemplateClass].[fieldKey].label. The connected system field keys are the five constants on
# de.hock.mqtt.template.MqttConnectedSystemTemplate; the input keys are the three constants on
# de.hock.mqtt.integration.MqttPublishIntegration. There are no orphan keys and no missing keys.

# --- Connected System template ---------------------------------------------------------------
MqttConnectedSystemTemplate.name=MQTT Broker
MqttConnectedSystemTemplate.description=Reusable MQTT broker connection configuration (host, port, client identifier, and optional credentials) shared by the MQTT publish integration.

MqttConnectedSystemTemplate.brokerHost.label=Broker Host
MqttConnectedSystemTemplate.brokerPort.label=Broker Port
MqttConnectedSystemTemplate.clientIdentifier.label=Client Identifier
MqttConnectedSystemTemplate.username.label=Username
MqttConnectedSystemTemplate.password.label=Password

# --- Publish integration ----------------------------------------------------------------------
MqttPublishIntegration.name=Publish MQTT Message
MqttPublishIntegration.description=Publishes a single message to an MQTT topic at the requested quality of service.

MqttPublishIntegration.topic.label=Topic
MqttPublishIntegration.payload.label=Payload
MqttPublishIntegration.qos.label=QoS
```

Keys removed (22): `connectionTimeoutSeconds`, `tlsEnabled`, `caCertificate`,
`clientCertificate`, `clientPrivateKey`, `keyPassphrase`, `autoReconnectEnabled`,
`maxReconnectAttempts`, `reconnectIntervalSeconds`, `lwtTopic`, `lwtPayload`, `lwtQos`,
`lwtRetained`, `cleanSession`, `keepAliveSeconds`, `MqttPublishIntegration.retained`, and all six
`MqttSubscribeIntegration.*`.

The `.label(...)` strings in the two Java files must match these values exactly.

## 6. `pom.xml` changes

1. `<version>1.0.3</version>` → `<version>1.0.4</version>` (the project version, not
   `modelVersion`). This drives `finalName`, so the artifact becomes
   `mqtt-broker-connected-system-1.0.4-plugin.jar`.
2. Remove the `net.jqwik:jqwik` dependency and the `<jqwik.version>` property.
3. Remove the `io.moquette:moquette-broker` dependency and the `<moquette.version>` property.
4. Remove the whole `maven-failsafe-plugin` `<plugin>` block and the
   `<maven-failsafe-plugin.version>` property. **Yes, the dead `*Properties` / `*IT` wiring comes
   out with the dependencies** — the steering file calls it "dead config advertising coverage that
   isn't there", no such files exist, and leaving it would be a false coverage claim in sources that
   AppMarket reviewers read.
5. On `maven-surefire-plugin`, drop the `<configuration>` block (the `**/*Properties.java` include
   and the `**/*IT.java` exclude). Keep the plugin and its pinned version; Surefire's default
   include pattern picks up `**/*Test.java`, which is both remaining test classes.
6. Update the `<description>` to the minimal-build wording.
7. **Do not touch:** `groupId de.hock`, `artifactId`, `maven.compiler.release 17`,
   `connected-systems-core 1.9.1` `provided`, `connected-systems-client 1.3.0` `provided`,
   `paho 1.2.5` (compile/bundled), `junit-jupiter 5.10.2` `test`, the `maven-jar-plugin`
   `default-jar` → phase `none` execution, or the assembly plugin configuration.

Removing dependencies never needs a download, so `mvn -o` stays valid. If any Maven invocation
nonetheless reports a missing artifact, drop `-o`, say so in the report, and paste the output.

Also note in the report (no edit required): `.jqwik-database` at the repo root and its `.gitignore`
entry are now dead. Neither ships in the jar; leave them in place rather than widening the change.

## 7. Final test suite — 7 tests in 2 classes

### `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/test/java/de/hock/mqtt/ProjectStructureTest.java` — 2 tests

| Method | Asserts |
|---|---|
| `pluginDescriptorIsOnClasspath` | `appian-plugin.xml` resolves via the classloader. Kept verbatim. |
| `defaultResourceBundleIsOnClasspath` | `resources_en_US.properties` resolves via the classloader. Kept verbatim. |

`basePackageIsPresent` is dropped — it only asserted the test's own package name.

### `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/test/java/de/hock/mqtt/template/MqttConnectedSystemTemplateDescriptorTest.java` — 5 tests

Shared helpers, carried over from the current class:

```java
private static ConfigurationDescriptor emptyDescriptor() {
  return ConfigurationDescriptor.builder().build();            // getRootState() == null
}
private static ConfigurationDescriptor renderConnectedSystem() {
  return new MqttConnectedSystemTemplate()
      .getConfigurationDescriptor(emptyDescriptor(), new PropertyPath(), null);
}
private static ConfigurationDescriptor renderPublish() {
  return new MqttPublishIntegration()
      .getConfigurationDescriptor(emptyDescriptor(), emptyDescriptor(), new PropertyPath(), null);
}
private static LocalTypeDescriptor rootTypeOf(ConfigurationDescriptor d) {
  LocalTypeDescriptor t = d.getTypes().get(d.getRootState().getType().getUnqualifiedTypeName());
  assertNotNull(t, "the root state's type must resolve in the descriptor's type map");
  return t;
}
private static final List<String> CONFIG_FIELD_KEYS = List.of(
    MqttConnectedSystemTemplate.BROKER_HOST, MqttConnectedSystemTemplate.BROKER_PORT,
    MqttConnectedSystemTemplate.CLIENT_IDENTIFIER, MqttConnectedSystemTemplate.USERNAME,
    MqttConnectedSystemTemplate.PASSWORD);
```

| Method | Asserts |
|---|---|
| `brandNewConnectedSystemRendersANonNullRootStateWithAResolvableRootType` | **The NPE regression guard — the single most valuable test here.** Premise first: `emptyDescriptor().getRootState() == null`, which is what Appian hands in on `New > Connected System`. Then the rendered descriptor has a non-null `getRootState()`, its type name is non-null, `getTypes()` is non-empty and contains that name. |
| `exactlyTheFiveExpectedConfigFieldKeysArePresent` | `rootTypeOf(renderConnectedSystem()).getPropertyKeys()` equals `CONFIG_FIELD_KEYS` **as an ordered list** (`assertEquals(List.of(...), keys)`), which pins both "all five present" and "no sixth field". Plus `getPropertyStateAtPath(new PropertyPath(key)) != null` for each key. |
| `noConnectedSystemPropertyDescriptorIsExpressionable` | Iterates `rootType.getProperties()` and reads `descriptor.isExpressionable()` — the SDK's public accessor on the same object graph Appian's `ConnectedSystemPropertiesValidator` walks, **not** a source grep. Collects every offender as `key (SimpleClassName)` and asserts the list is empty; also asserts 5 descriptors were inspected, so a silently-empty loop cannot pass. This is the test that would have caught the two forced-expressionable document fields in 1.0.3. |
| `connectedSystemFieldsUseNoListDocumentOrAppianValueDescriptor` | For each of the 5 descriptors: not `instanceof ListTypePropertyDescriptor`, not `DocumentPropertyDescriptor`, not `AppianValuePropertyDescriptor` (the three types whose builders force the expressionable flag on), plus `getRefresh() == RefreshPolicy.NEVER` and `!isHidden()`. |
| `publishIntegrationRendersItsRootStateAndItsThreeExpressionableInputs` | `renderPublish().getRootState() != null`; `rootTypeOf(...).getPropertyKeys()` equals `List.of("topic", "payload", "qos")` in display order; and for each of those three keys `rootType.getProperty(key).orElseThrow(...).isExpressionable()` is **true** — again read back through the public `PropertyDescriptor` API, asserting the deliberate asymmetry with the connected system. |

Removed tests and why: everything covering `cleanSession` seeding, the first-render gate, blank-host
error reporting, per-field error clearing, pasted-PEM → `SSLSocketFactory`, legacy document
references, the subscribe descriptor, and the publish `retained` input — all cover behavior that no
longer exists. They are deleted, not weakened.

No test needs a live broker, and none is written for one. `ExecutionContext` is passed as `null`, as
today: the SDK's configuration model is plain Java and that parameter is unused on this path.

---

# Implementation Plan

- [ ] 1. Bump the project version and strip the unused test dependencies and dead Surefire/Failsafe wiring from the pom.
      Apply §6 items 1–6 exactly: version `1.0.4`, remove jqwik, moquette, the Failsafe plugin block and the three now-unused `<properties>` entries, drop the Surefire `<configuration>` block, update `<description>`. Change nothing else.
      Files: `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/pom.xml`
      Verify: `cd /Users/andreas.hock/Documents/Appian-Projects/MQTT_Component && mvn -o clean package` still succeeds on the *unchanged* 1.0.3 sources — expect `Tests run: 19, Failures: 0, Errors: 0` and exactly one jar, now named `target/mqtt-broker-connected-system-1.0.4-plugin.jar`. This isolates the pom edit from the source surgery: if it goes red, the cause is the pom.

- [ ] 2. Perform the source surgery: rewrite the three surviving Java files, add `MqttPublisher`, and delete the other 31.
      Rewrite `MqttConnectedSystemTemplate` per §3.1 (five descriptors, five key constants, `getConfiguration` = `setProperties` + `return`, no `validate`/`setErrorAtPath`/`firstRender`/`setValue`/`toConnectionConfig`, zero `isExpressionable` tokens), rewrite `MqttPublishIntegration` per §3.2 (three inputs, each `isExpressionable(true)`, flat success map, direct `IntegrationResponse`), create `MqttPublisher` per §3.3, rewrite the base `package-info.java` Javadoc, then delete every path in §1 and remove the emptied `diagnostics/`, `protocol/` and `validation/` directories.
      Files: created `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/MqttPublisher.java`; modified `.../de/hock/mqtt/package-info.java`, `.../de/hock/mqtt/template/MqttConnectedSystemTemplate.java`, `.../de/hock/mqtt/integration/MqttPublishIntegration.java`; deleted the 31 paths listed in §1.
      Verify: `mvn -o clean compile` succeeds and `find src/main/java -name '*.java' | wc -l` prints `4`. **Expected and temporary:** `mvn -o clean package` is red until item 4, because the two existing test classes still import deleted production classes. Do not "fix" that by restoring a deleted file.

- [ ] 3. Rewrite the manifest and prune the resource bundle to the reduced surface.
      Replace `appian-plugin.xml` with the exact content in §4 (one nested integration-template, `<version>1.0.4</version>`, vendor and all keys unchanged) and `resources_en_US.properties` with the exact 12 keys in §5. Update only the stale class names in the `plugin-assembly.xml` comment — leave its fileSets and dependencySet untouched.
      Files: `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/resources/appian-plugin.xml`, `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/resources/resources_en_US.properties`, `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/assembly/plugin-assembly.xml`
      Verify: `mvn -o clean compile` still succeeds, and the §8 bundle cross-check block (invariant **i**) reports no orphan and no missing key — both `diff`s empty.

- [ ] 4. Replace both test classes with the 7-test suite.
      Trim `ProjectStructureTest` to its two classpath tests and rewrite `MqttConnectedSystemTemplateDescriptorTest` to the five tests in §7, reading every expressionability and descriptor-type assertion back through the SDK's public `PropertyDescriptor` accessors. Delete the tests for removed behavior outright.
      Files: `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/test/java/de/hock/mqtt/ProjectStructureTest.java`, `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/test/java/de/hock/mqtt/template/MqttConnectedSystemTemplateDescriptorTest.java`
      Verify: `mvn -o test` prints `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`. Then prove the two guard tests bite: temporarily add `.isExpressionable(true)` to the `brokerHost` descriptor and confirm `noConnectedSystemPropertyDescriptorIsExpressionable` fails naming `brokerHost`; temporarily add a sixth descriptor and confirm `exactlyTheFiveExpectedConfigFieldKeysArePresent` fails. Revert both and re-run to green. Paste all three outputs.

- [ ] 5. Run the full packaging-invariant verification block and capture the real output.
      Execute the whole block in §8 and record expected vs actual for invariants (a) through (k), the test count, the per-file `isExpressionable` counts, and the backup-archive checksum. Fix any mismatch before moving on.
      Files: none (read-only verification)
      Verify: every line in §8 matches its stated expected value — notably exactly one jar, `4` `.java` entries all under `de/hock/mqtt/`, bare-root `appian-plugin.xml`, one nested jar under `META-INF/lib/` and it is Paho, zero `connected-systems` entries, zero `com/appian/mqtt`, three-part `1.0.4`, four-part `min="26.9.0.0"`, vendor `Hock`, bytecode major version `61`, and the archive sha256 still `386f3cab7ab660e19325f2825071152338eb48855e6f45b8d9665afcc22f89b4`.

- [ ] 6. Rewrite `README.md` for the reduced surface.
      Follow the outline in §9. Lead with the MINIMAL SMOKE-TEST BUILD statement and the four things it proves, name the deliberately removed features and the archive they are recoverable from, document the five config fields and three publish inputs with their real keys and labels, keep build/deploy correct for 1.0.4, and link to the steering file instead of restating compliance rules.
      Files: `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/README.md`
      Verify: `grep -c '1\.0\.3' README.md` → `0`; `grep -ci 'subscribe\|tlsEnabled\|lwt\|reconnect' README.md` returns hits only inside the "deliberately removed" section (inspect them); every field key named in the README is present in `resources_en_US.properties`.

- [ ] 7. Update every stale project-specific figure in the steering file and add the reduction note.
      Apply the itemized edits in §10. The hard constraints in sections 1 and 2 stay — only the project-specific figures change — plus a short note in section 8 recording the deliberate reduction at 1.0.4 and the archive path.
      Files: `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/.kiro/steering/appian-appmarket-compliance.md`
      Verify: `grep -n '1\.0\.3\|34 sources\|19 tests\|20 field\|jqwik\|moquette' .kiro/steering/appian-appmarket-compliance.md` returns only intentional historical references (inspect each); the §4 command block in the steering file, run verbatim, now passes against the built 1.0.4 jar.

- [ ] 8. Mark `docs/mqtt-plugin-guide.md` as pre-reduction and refresh `info.md`.
      Add a prominent banner at the top of the guide (decision and wording in §11) rather than trimming it; leave the body intact. Update `info.md` to version 1.0.4, the two-module contents, and a one-line minimal-build note.
      Files: `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/docs/mqtt-plugin-guide.md`, `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/info.md`
      Verify: `head -12 docs/mqtt-plugin-guide.md` shows the banner naming 1.0.4, the README and the archive path; `grep -n '1\.0\.4' info.md` matches; `grep -ci subscribe info.md` → `0`.

- [ ] 9. Write the deliverable report.
      Cover every section the brief requires: what the minimal build contains; the complete deleted-file list and the kept/created list with one-line reasons; the five config fields and three publish inputs as actually implemented; why design-time validation was dropped; all invariant evidence with the new counts (4 `.java`, 12 bundle keys / 8 field labels, 7 tests, 3 `isExpressionable` call sites); the exact artifact filename; the ordered deploy-and-test checklist; how to restore the full build from the archive; and what remains unverifiable offline. This file doubles as the new `.agents/tasks/` record for the reduction — do not add a second overlapping record, and leave every existing file in `.agents/tasks/` untouched.
      Files: `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/.agents/tasks/minimal-smoke-build.md`
      Verify: `mvn -o clean package && mvn -o test` green one final time, and every command output pasted in the report is reproducible by re-running the §8 block.

---

## 8. Verification command block, with expected values

Run from the project root; paste real output. `J` is the only artifact that may exist in `target/`.

```bash
cd /Users/andreas.hock/Documents/Appian-Projects/MQTT_Component
mvn -o clean package
J=target/mqtt-broker-connected-system-1.0.4-plugin.jar

# --- 1/2. build + tests ----------------------------------------------------------------
mvn -o test                                              # Tests run: 7, Failures: 0, Errors: 0

# --- 3. expressionability, per file ----------------------------------------------------
grep -c isExpressionable src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java   # 0
grep -rc isExpressionable src/main/java/ | grep -v ':0$'
#   expect exactly one line:
#   src/main/java/de/hock/mqtt/integration/MqttPublishIntegration.java:3
grep -rn isExpressionable src/main/java/ | wc -l                                                # 3

# --- (a) namespace ---------------------------------------------------------------------
grep -rn 'com\.appian\.mqtt' src pom.xml | wc -l                      # 0
unzip -l $J | grep -c 'com/appian/mqtt'                               # 0
grep -rl 'com\.appian\.connectedsystems' src/main/java | wc -l        # 2  (both templates)
grep -c '<groupId>de.hock</groupId>' pom.xml                          # 1
unzip -p $J appian-plugin.xml | grep -c 'key="de.hock.mqtt"'          # 1

# --- (b) sources inside the jar --------------------------------------------------------
unzip -l $J | grep -c '\.java$'                                       # 4
unzip -l $J | grep '\.java$' | grep -vc 'de/hock/mqtt/'               # 0
unzip -l $J | grep '\.java$'
#   expect: de/hock/mqtt/package-info.java, de/hock/mqtt/MqttPublisher.java,
#           de/hock/mqtt/template/MqttConnectedSystemTemplate.java,
#           de/hock/mqtt/integration/MqttPublishIntegration.java
unzip -l $J | grep -ci test                                           # 0

# --- (c) bare-root manifest ------------------------------------------------------------
unzip -l $J | grep -cE ' appian-plugin\.xml$'                         # 1
unzip -l $J | awk '{print $NF}' | grep -c '^appian-plugin.xml$'       # 1  (no directory prefix)

# --- (d) META-INF/lib holds Paho and nothing else --------------------------------------
unzip -l $J | grep -c 'META-INF/lib/.*\.jar$'                         # 1
unzip -l $J | grep -c 'META-INF/lib/org.eclipse.paho'                 # 1
unzip -l $J | grep -ci 'connected-systems'                            # 0

# --- (e) version formats ---------------------------------------------------------------
unzip -p $J appian-plugin.xml | grep -E '<version>|application-version'
#   expect: <version>1.0.4</version>   and   <application-version min="26.9.0.0"/>
grep -A1 '<artifactId>mqtt-broker-connected-system</artifactId>' pom.xml | grep '<version>'
#   expect: <version>1.0.4</version>

# --- (f) vendor ------------------------------------------------------------------------
unzip -p $J appian-plugin.xml | grep -c '<vendor name="Hock" url="https://itondemand.com"/>'  # 1

# --- (g) exactly one jar ---------------------------------------------------------------
ls target/*.jar | wc -l                                               # 1
ls target/*.jar                                                       # the 1.0.4 -plugin jar

# --- (h) Java 17 bytecode --------------------------------------------------------------
javap -verbose -cp target/classes de.hock.mqtt.template.MqttConnectedSystemTemplate \
  | grep 'major version'                                              # major version: 61
javap -verbose -cp target/classes de.hock.mqtt.MqttPublisher | grep 'major version'   # 61

# --- (i) resource bundle: no orphan keys, no missing keys ------------------------------
B=src/main/resources/resources_en_US.properties
# every key actually in the bundle
sed -nE 's/^([A-Za-z][A-Za-z.]*)=.*/\1/p' $B | sort > /tmp/bundle-actual.txt
wc -l < /tmp/bundle-actual.txt                                        # 12
# the key set derived from the surviving field keys and class names
{ echo MqttConnectedSystemTemplate.name; echo MqttConnectedSystemTemplate.description
  sed -nE 's/.*public static final String [A-Z_]+ *= *"([A-Za-z]+)".*/MqttConnectedSystemTemplate.\1.label/p' \
    src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java
  echo MqttPublishIntegration.name; echo MqttPublishIntegration.description
  sed -nE 's/.*public static final String INPUT_[A-Z_]+ *= *"([A-Za-z]+)".*/MqttPublishIntegration.\1.label/p' \
    src/main/java/de/hock/mqtt/integration/MqttPublishIntegration.java
} | sort > /tmp/bundle-expected.txt
wc -l < /tmp/bundle-expected.txt                                      # 12
diff /tmp/bundle-expected.txt /tmp/bundle-actual.txt                  # no output (exit 0)
#   a '>' line is an ORPHAN key; a '<' line is a MISSING key
#   The two sed patterns above only hold if the five config keys are the ONLY
#   `public static final String` constants on the template and the three input keys are the only
#   `public static final String INPUT_*` constants on the integration. Keep it that way: result-map
#   keys ("success", "status", "topic", "qos") stay inline literals in the success map.
unzip -l $J | grep -c ' resources_en_US.properties$'                  # 1, bare root

# --- (j) third-party notice ------------------------------------------------------------
unzip -l $J | awk '{print $NF}' | grep -c '^THIRD-PARTY-NOTICES.md$'  # 1
unzip -p $J THIRD-PARTY-NOTICES.md | grep -c 'EDL'                    # >= 1

# --- (k) nothing from .backup/ ---------------------------------------------------------
unzip -l $J | grep -ci 'backup'                                       # 0
unzip -l $J | grep -c '\.zip$'                                        # 0

# --- 6. the archive is untouched -------------------------------------------------------
shasum -a 256 .backup/mqtt-plugin-FULL-1.0.3-src.zip
#   expect 386f3cab7ab660e19325f2825071152338eb48855e6f45b8d9665afcc22f89b4
stat -f '%z bytes' .backup/mqtt-plugin-FULL-1.0.3-src.zip             # 136397 bytes
unzip -l .backup/mqtt-plugin-FULL-1.0.3-src.zip | tail -2             # 74 files
```

Step 7 of the brief is a statement, not a command, and belongs in the report verbatim in substance:
**final confirmation requires the user deploying
`target/mqtt-broker-connected-system-1.0.4-plugin.jar` to their real Appian 26.9 instance.**
Nothing offline can prove installation, the `META-INF/lib/` classloader layout, that the Appian
server supplies `connected-systems-client`, that the configuration form renders, or that a publish
reaches a broker.

If a non-offline resolve is forced at any point, drop `-o`, say so explicitly in the report, and
paste the output.

## 9. `README.md` rewrite outline

1. **Title + banner.** `# MQTT Broker Connected System — Minimal Smoke-Test Build`. First
   paragraph, unmissable: this 1.0.4 build exists to validate exactly four things — (1) the plug-in
   installs on Appian 26.9, (2) the bundled Eclipse Paho library loads and is usable at runtime,
   (3) a Connected System configuration page renders with a few basic parameters, (4) one
   integration can actually publish to a broker.
2. **What was deliberately removed**, named plainly: TLS and certificates, the subscribe
   integration, Last Will and Testament, reconnection/session tuning, diagnostics and redaction,
   and design-time validation. State that all of it is recoverable from
   `.backup/mqtt-plugin-FULL-1.0.3-src.zip`, and that this is a scope reduction, not a regression.
3. **Facts block.** Plug-in key `de.hock.mqtt`; version `1.0.4`; minimum Appian `26.9.0.0`; vendor
   Hock, <https://itondemand.com>.
4. **What the plug-in provides.** Table: connected system template `MqttConnectedSystemTemplate`
   ("MQTT Broker") and integration template `MqttPublishIntegration` ("Publish MQTT Message",
   `WRITE`). Keep the existing "contributes no smart service" note — still true and still a common
   expectation — and the three-step usage model.
5. **Requirements.** Appian 26.9, Java 17, a reachable MQTT broker over plain TCP (**no TLS in this
   build**), core 1.9.1 `provided`, client 1.3.0 `provided`, Paho 1.2.5 bundled under
   `META-INF/lib/`.
6. **Build.** `mvn clean package` → `target/mqtt-broker-connected-system-1.0.4-plugin.jar`; one jar
   by design; sources ship inside the jar.
7. **Deploy.** Copy to `APPIAN_INSTALL/_admin/plugins/`; the `conf.plugins.poll-interval` note;
   the three confirmation checks (Admin Console plug-ins page, `New > Connected System` offers MQTT
   Broker, `tomcat-stdOut.log` shows `Successfully installed Plug-in`).
8. **Configuring the Connected System.** Table of the five fields with real keys and labels:
   `brokerHost` / Broker Host / Text / required; `brokerPort` / Broker Port / Integer / required /
   placeholder 1883; `clientIdentifier` / Client Identifier / Text / optional / auto-generated by
   Paho when blank; `username` / Username / Text / optional; `password` / Password / Encrypted text,
   masked / optional. State that no connected system field is expressionable (platform restriction)
   and that **there is no design-time validation in this build** — `isRequired` alone is the guard.
9. **Using the Publish integration.** Table of `topic` (Text, required), `payload` (Text, required,
   UTF-8), `qos` (Integer, optional, default 0); all three accept expressions. The flat success map
   `success` / `status` (`dispatched` for QoS 0, `delivered` for QoS 1–2) / `topic` / `qos`; on
   failure, `error.title` is `MQTT publish failed` or `Invalid input` and `error.message` carries
   the detail. One short expression example using the real input names.
10. **Runtime behavior, stated honestly.** One connect-publish-disconnect per request; hardcoded
    5-second connect timeout and 10-second cap on any blocking call; no connection reuse, no
    reconnection, no background threads; plain `tcp://` only.
11. **Known limitations.** Test coverage is design-time only (7 tests, nothing exercises a real
    publish — smoke-test against a real broker); the `META-INF/lib/` layout and the server-side
    `connected-systems-client` requirement are still unverified on a live instance; no connected
    system logo assets.
12. **License / third-party.** Paho 1.2.5, dual EPL-2.0 / EDL-1.0, redistribution relies on
    EDL-1.0; link `THIRD-PARTY-NOTICES.md`.
13. **Links.** `docs/mqtt-plugin-guide.md` flagged as describing the pre-reduction full build, and
    `.kiro/steering/appian-appmarket-compliance.md` for the packaging and submission constraints —
    **link, do not duplicate**. Keep the closing Appian-documentation attribution line.

## 10. `.kiro/steering/appian-appmarket-compliance.md` — exact stale figures to update

The hard constraints in sections 1 and 2 remain true and valuable. Change only the project-specific
figures:

| Location | Current (stale) | Update to |
|---|---|---|
| §1, namespace trap | `grep -rc 'import com.appian.connectedsystems' src/main/java/` "must stay ~45"; "import count dropped from 47 to 45" | ~23 across **2** files; replace the historical sentence with: 45 → ~23 when the plug-in was reduced to the minimal smoke-test build at 1.0.4 |
| §1, sources-in-jar | implied 34 sources | 4 sources (`package-info`, `MqttPublisher`, the two templates) |
| §1, version table | `1.0.3` | `1.0.4` (three-part); `26.9.0.0` unchanged |
| §1, expressionability narrative | the `caCertificate` / `clientCertificate` → `ParagraphPropertyDescriptor` story | Keep the **table and the lesson** (they are the reusable finding), but mark that both certificate fields no longer exist at 1.0.4; `Paragraph` is no longer used by this plug-in |
| §1, verification snippet | `grep -rn isExpressionable src/main/java/ \| wc -l # 7`; `Publish:4 Subscribe:3` | `# 3`; `MqttPublishIntegration:3` only — subscribe is gone |
| §2, submission artifact | `target/mqtt-broker-connected-system-1.0.3-plugin.jar` | `...-1.0.4-plugin.jar` |
| §2, dependency list | `net.jqwik:jqwik:1.8.4` and `io.moquette:moquette-broker:0.17` declared with surefire/failsafe wiring | Both removed at 1.0.4 along with the Failsafe plugin and the `*Properties`/`*IT` wiring; JUnit 5 only |
| §4, verification block | `J=...1.0.3-plugin.jar`; `grep -c '\.java$'` → 34; `isExpressionable` 7 | `1.0.4`; `4`; `3`. Replace the whole block with §8 of this plan |
| §5 item 2 | the Clean Session / first-render round-trip assumption and its deploy check | The `Simple*` resolution stays (still load-bearing). Mark the first-render gate and the Clean Session seed **removed at 1.0.4**, so the round-trip assumption no longer governs any behavior and the "clear Clean Session, save, reopen" check no longer applies |
| §5 item 3 | logos expected at `.../MqttConnectedSystemTemplate_{40,50,80}px.png` | Unchanged — still true, still open |
| §5 item 4 | "19 tests in two classes", the 20-field / 4-input key counts, the first-render and certificate test names, the dead jqwik/moquette config | **7 tests in two classes** (2 + 5); 5 config keys + 3 publish input keys; list the five descriptor test names from §7; delete the dead-config sentence — the config is actually gone now |
| §5 item 6 | `isImportCustomizable` on `password`, `clientPrivateKey`, `keyPassphrase` | `password` only; the other two fields no longer exist. Still carried forward, not fixed |
| §5 item 7 | design-time validation on a connected system unresolved — "the top deploy-time question" | Mark **moot at 1.0.4**: the template no longer calls `setErrorAtPath`, so the question no longer blocks anything. Keep the finding for whoever restores the full build |
| §5 item 8 | "a reported field error is never cleared" | Mark **resolved by removal** at 1.0.4 (no validation, no errors to clear). Keep the note for the restored build |
| §5 item 9 | platform validators not inspectable offline | Unchanged — still true |
| §8, audit trail | existing list | Add `minimal-smoke-build-plan.md` (this plan), `minimal-smoke-build.md` (the reduction record and report) and `minimal-smoke-build-review.json`. Add a short note: **the project was deliberately reduced to a minimal smoke-test build at 1.0.4; the complete 1.0.3 source is archived at `.backup/mqtt-plugin-FULL-1.0.3-src.zip`, so the richer history in `.agents/tasks/` describes a superset of what now ships.** |

Leave §3 (code policies), §6 (submission process) and §7 (reference pages) as they are.

## 11. `docs/mqtt-plugin-guide.md` — decision: MARK as pre-reduction, do not trim

Reason: the guide is a 224-line narrative of the full feature set — bounded-subscribe semantics,
TLS, LWT, error-category taxonomy, binary payload handling. Trimming it to the minimal surface would
produce a second copy of the new README, while the full narrative stays genuinely useful to whoever
restores the archive. Marking it costs eight lines and leaves nothing silently wrong. Insert
immediately under the `# ...Plug-in Guide` heading:

> **⚠️ This guide describes the pre-reduction FULL build (1.0.x).** The plug-in that currently ships
> is **1.0.4, a minimal smoke-test build**: one connected system with five fields and one publish
> integration. TLS, the subscribe integration, Last Will and Testament, reconnection tuning,
> diagnostics and design-time validation are **not present** in 1.0.4. For what actually ships, see
> [README.md](../README.md). The full source this guide documents is archived at
> `.backup/mqtt-plugin-FULL-1.0.3-src.zip`.

Leave the body untouched, including its already-stale `1.0.0` artifact line — the banner now scopes
the whole document. `info.md` gets the small refresh described in plan item 8 (version 1.0.4,
two modules, one minimal-build line); it is a root-level description file that would otherwise
advertise a subscribe integration that no longer exists.

## 12. Couplings found between the KEEP list and the DELETE list

Five, each with the smallest alternative. None requires keeping anything from the DELETE list.

1. **`brokerPort` "seed/suggest 1883" vs. "delete the first-render gate".** Seeding an actual value
   needs `configuration.setValue(BROKER_PORT, 1883)`, which is only safe behind a first-render
   signal — otherwise every re-render overwrites what the Designer typed. The brief deletes that
   gate. *Alternative:* suggest rather than seed — `.placeholder("1883")` plus instruction text
   naming 1883. Verified safe: `PropertyDescriptorBuilder.placeholder(String)` exists in core
   1.9.1 and only `LocalTypePropertyDescriptorBuilder` calls
   `Guard.checkPropertyHasNoPlaceholder`, so Text/Integer/EncryptedText accept it. If a live
   instance ever rejects a placeholder on an Integer field, drop to instruction text only — a
   one-line change.
2. **`qos` "default 0" on an integration input.** Same hazard, same answer: `.placeholder("0")`
   plus a runtime default (null or blank → 0) in `execute`. No descriptor-state seeding.
3. **A blank `clientIdentifier` needs a client id, and `ClientIdentifierGenerator` is deleted.**
   *Alternative:* `MqttClient.generateClientId()`, confirmed `public static` in Paho 1.2.5. One
   line, supplied by the library already being smoke-tested.
4. **`IntegrationError.title` needs a value, and `MqttErrorCategory` is deleted.** *Alternative:*
   two string literals — `"Invalid input"` for the pre-flight guards, `"MQTT publish failed"` for a
   Paho failure. Two titles do not need an eight-value enum.
5. **"Logging through Log4j only" vs. no logging dependency in the pom.** The 1.0.3 tree logs
   nothing, and adding `log4j` — even `provided` — adds an unverified server-classpath assumption to
   a build whose purpose is to remove them. *Alternative:* log nothing; surface failure detail in
   the integration response. The compliance rule is satisfied because no `System.out`,
   `System.err`, `printStackTrace` or `java.util.logging` call exists. Recorded as a deliberate
   decision, not an oversight.

Also worth recording in the report, though not a coupling: **no existing Connected System object can
break.** The configuration page never rendered successfully on 1.0.0–1.0.3, so no MQTT Broker
connected system was ever created against this plug-in. The plug-in key and both module keys stay
unchanged, and 1.0.4 > 1.0.3, so Appian replaces the installed plug-in in place. Restarting the
version scheme at something like `0.1.0` would be **rejected** — Appian refuses to deploy over an
installed plug-in unless the version increases.
