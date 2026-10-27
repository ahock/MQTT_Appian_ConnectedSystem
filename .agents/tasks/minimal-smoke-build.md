# Minimal Smoke-Test Build — Reduction Record (1.0.3 → 1.0.4)

**Date:** 2026-10-27
**Artifact to deploy:** `target/mqtt-broker-connected-system-1.0.4-plugin.jar`
**Status:** implemented and verified offline. **Deploy-time confirmation is outstanding** — see §10.

This is a **deliberate, user-requested scope reduction, not a regression.** The full plug-in hit
three successive deploy-time platform-contract failures (`1.0.1` NPE on `getRootState()`, `1.0.2`
document descriptors, `1.0.3` expressionable descriptors), so the surface is stripped back to a
baseline that can actually be confirmed on a real Appian 26.9 instance before anything is restored.

No git was used: this directory is not a git repository and the brief forbade it. All edits were
made in place.

---

## 1. What the minimal build contains

Four things are being proven, and nothing else is in scope:

1. the plug-in **installs** on Appian 26.9,
2. the bundled **Eclipse Paho 1.2.5** library loads and is usable at run time,
3. a **Connected System configuration page renders** with a few basic parameters,
4. **one integration can actually publish** to a broker.

| | |
|---|---|
| Java sources in `src/main/java` | **4** (was 34) |
| `.java` entries in the shipped jar | **4**, all under `de/hock/mqtt/` |
| Resource-bundle keys | **12** (was 34); of those, **8** are field labels (was 28) |
| Tests | **7** in 2 classes (was 19) |
| `isExpressionable` call sites in `src/main/java` | **3**, all on the publish integration (was 7) |
| Connected system fields | **5** (was 20) |
| Publish integration inputs | **3** (was 4); subscribe integration removed |
| SDK imports (`com.appian.connectedsystems`) | **22** across **2** files (was ~45) |

The four surviving sources:

| File | Role |
|---|---|
| `de/hock/mqtt/package-info.java` | Base-package Javadoc, rewritten to describe the reduced build |
| `de/hock/mqtt/MqttPublisher.java` | **New.** The single Paho call site: connect → publish → disconnect |
| `de/hock/mqtt/template/MqttConnectedSystemTemplate.java` | The five-field connected system, rewritten |
| `de/hock/mqtt/integration/MqttPublishIntegration.java` | The one WRITE integration, rewritten |

---

## 2. Deleted files — 31 under `src/main/java`

Every one removed outright, together with its manifest declaration and its resource-bundle keys.

**Base package (6 of 7; `package-info.java` survives):**

| File | Reason |
|---|---|
| `MqttConnectionConfig.java` | A 20-component record for 5 surviving values; the integration now reads the 5 and passes them as parameters |
| `MqttErrorCategory.java` | An 8-value closed enum for a build with two failure modes; replaced by two string literals |
| `OperationResult.java` | The hand-off record between the operation layer and `ResponseMapper`; both are gone |
| `PublishRequest.java` | 3 inputs as plain parameters is shorter than constructing and unpacking a record |
| `ReceivedMessage.java` | Existed only to serve subscribe |
| `SubscribeRequest.java` | Existed only to serve subscribe |

**`diagnostics/` — whole directory (5 files):** `DiagnosticsBuilder.java`,
`ExceptionCategoryTranslator.java`, `Redactor.java`, `ResponseMapper.java`, `package-info.java`.
Reason: diagnostics and redaction are out of scope; the integration builds its `IntegrationResponse`
directly and never puts a secret in one.

**`protocol/` — whole directory (8 files):** `ConnectOptionsException.java`,
`ConnectOptionsFactory.java`, `MqttClientAdapter.java`, `MqttConnectionManager.java`,
`MqttOperationException.java`, `MqttSession.java`, `PahoMqttClientAdapter.java`,
`package-info.java`. Reason: connection caching, session management, the adapter indirection and
the TLS-capable options factory all collapse into `MqttPublisher`. `MqttOperationException` carried
an error category across the connection-manager boundary; there is no boundary left, so the
integration catches Paho's `MqttException` directly.

**`validation/` — whole directory (11 files):** `CertificateLoader.java`,
`ClientIdentifierGenerator.java`, `ConfigFields.java`, `ConfigValidator.java`,
`InputValidator.java`, `LwtValidator.java`, `PemValidator.java`, `ReconnectionValidator.java`,
`ValidationResult.java`, `ValidationResults.java`, `package-info.java`. Reasons: the design-time
validation machinery is dropped outright (§4); `CertificateLoader`/`PemValidator` served TLS;
`LwtValidator`/`ReconnectionValidator` served removed fields; `ClientIdentifierGenerator`'s
SHA-256 stable-id scheme only mattered for the connection cache, so a blank client identifier now
falls back to Paho's own `MqttClient.generateClientId()` — one line, supplied by the library being
smoke-tested; `ConfigFields` became five `public static final String` constants on the template.

**`integration/MqttSubscribeIntegration.java` (1 file):** out of scope — one integration only.

Directories `diagnostics/`, `protocol/` and `validation/` are gone, not left empty:

```
$ find src/main/java -type d | sort
src/main/java
src/main/java/de
src/main/java/de/hock
src/main/java/de/hock/mqtt
src/main/java/de/hock/mqtt/integration
src/main/java/de/hock/mqtt/template
```

Both test classes were **replaced in place** rather than deleted, because both referenced deleted
production classes. Tests for removed behavior were deleted, not weakened — see §6.

### Judgement calls, as decided

| Item | Decision |
|---|---|
| `ConfigFields` vs inline constants | **Deleted.** Five `public static final String` constants on `MqttConnectedSystemTemplate` — still one source of truth shared by template, integration and tests, one fewer file, and the whole `validation` package goes |
| `ClientIdentifierGenerator` | **Deleted.** `MqttClient.generateClientId()` is a genuine one-liner |
| `MqttOperationException` | **Deleted.** No layer boundary left to carry a category across |
| `MqttErrorCategory` | **Deleted.** Two titles do not need an eight-value enum |
| `OperationResult` | **Deleted.** Its only consumer was `ResponseMapper` |
| `MqttConnectionConfig` | **Deleted.** 20 components for 5 values |
| `PublishRequest` | **Deleted.** Plain parameters are shorter |
| `MqttPublisher` as a class vs inlined | **Separate class.** Isolating the one Paho call site means a classloading failure names exactly `de.hock.mqtt.MqttPublisher` |
| Logging framework | **None added.** The 1.0.3 tree logged nothing and the pom declared no logging dependency. Adding log4j even at `provided` scope would add another unverified server-classpath assumption to a build whose purpose is to remove them. The compliance rule ("Log4j only; no `System.out`/`System.err`/`printStackTrace`/`java.util.logging`") is satisfied because nothing prints; failure detail goes into the integration response. Recorded as a deliberate decision |

---

## 3. Kept and created files

| File | K/C | Reason |
|---|---|---|
| `src/main/java/de/hock/mqtt/package-info.java` | KEEP | Base-package documentation, no runtime weight; Javadoc rewritten for the reduced build |
| `src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java` | KEEP | Requirement (3); class name, `@TemplateId(name = "MqttConnectedSystemTemplate")` and manifest key unchanged so 1.0.4 replaces 1.0.3 |
| `src/main/java/de/hock/mqtt/integration/MqttPublishIntegration.java` | KEEP | Requirement (4); class name, `@TemplateId(name = "MqttPublishIntegration")` and `@IntegrationTemplateType(WRITE)` unchanged |
| `src/main/java/de/hock/mqtt/MqttPublisher.java` | **CREATE** | The single direct Paho call site; isolates a deploy-time `NoClassDefFoundError` to one named class |
| `src/main/resources/appian-plugin.xml` | KEEP | Manifest; rewritten to one nested integration, version 1.0.4, keys and vendor unchanged |
| `src/main/resources/resources_en_US.properties` | KEEP | i18n bundle; pruned to exactly 12 keys, every surviving label copied verbatim from the old bundle |
| `pom.xml` | KEEP | Version 1.0.4; jqwik, moquette and the Failsafe wiring removed |
| `src/assembly/plugin-assembly.xml` | KEEP | Implements all three packaging invariants (sources-in-jar, bare-root manifest, `META-INF/lib/`). **No structural change** — only the comment naming `PahoMqttClientAdapter`/`ConnectOptionsFactory` now names `MqttPublisher` |
| `THIRD-PARTY-NOTICES.md` | KEEP | Paho 1.2.5 is still bundled; unchanged, the dependency set did not change |

Left in place deliberately, not widened into this change: `.jqwik-database` (4 bytes) at the repo
root and its `.gitignore` entry are now dead. Neither ships in the jar.

---

## 4. The five config fields and three publish inputs, as implemented

### Connected System — `MqttConnectedSystemTemplate` ("MQTT Broker")

| Key | Label | Descriptor | Required | Notes |
|---|---|---|---|---|
| `brokerHost` | Broker Host | `TextPropertyDescriptor` | yes | — |
| `brokerPort` | Broker Port | `IntegerPropertyDescriptor` | yes | `.placeholder("1883")` |
| `clientIdentifier` | Client Identifier | `TextPropertyDescriptor` | no | Blank → `MqttClient.generateClientId()` |
| `username` | Username | `TextPropertyDescriptor` | no | — |
| `password` | Password | `EncryptedTextPropertyDescriptor` | no | `.masked(true)` |

All five keys and all five labels are the **existing** keys and label text from the 1.0.3 bundle —
nothing was re-authored. **No field is expressionable**, and the file contains zero
`isExpressionable` tokens (its Javadoc deliberately avoids the literal word so the grep gate stays
a clean zero). Only Text / Integer / EncryptedText descriptor types are used.

**`brokerPort` is suggested, not seeded.** Actually seeding `1883` needs
`configuration.setValue(...)`, which is only safe behind a first-render signal — otherwise every
re-render overwrites what the Designer typed — and that signal is exactly what this build removes.
`.placeholder("1883")` plus instruction text naming 1883 is the behavior-preserving alternative.
`PropertyDescriptorBuilder.placeholder(String)` was confirmed present in core 1.9.1 by `javap`, and
only `LocalTypePropertyDescriptorBuilder` calls `Guard.checkPropertyHasNoPlaceholder`, so
Text/Integer/EncryptedText accept it. If a live instance ever rejects it, dropping to instruction
text only is a one-line change.

### Integration — `MqttPublishIntegration` ("Publish MQTT Message", `WRITE`)

| Key | Label | Descriptor | Required | Expressionable | Notes |
|---|---|---|---|---|---|
| `topic` | Topic | `TextPropertyDescriptor` | yes | **yes** | — |
| `payload` | Payload | `TextPropertyDescriptor` | yes | **yes** | Sent as UTF-8 bytes |
| `qos` | QoS | `IntegerPropertyDescriptor` | no | **yes** | `.placeholder("0")`, run-time default 0 |

Output on success — a flat map, no nesting:

| Field | Value |
|---|---|
| `success` | `true` |
| `status` | `dispatched` for QoS 0, `delivered` for QoS 1 or 2 |
| `topic` | the topic published to |
| `qos` | the QoS published at |

On failure, an `IntegrationError` with title `Invalid input` (blank topic, blank broker host, port
outside 1–65535, QoS outside 0–2 — none of which contacts a broker) or `MQTT publish failed`
(Paho's message, prefixed with topic, host, port and QoS). Messages truncate at 500 characters, the
same bound the deleted `ResponseMapper` used, now a four-line private helper. The password is never
read into a message.

### Runtime

`MqttPublisher.publish(...)` — one connect → publish → disconnect per request. Synchronous
`MqttClient` (its blocking API matches Appian's request/response model with no token plumbing),
`MemoryPersistence` (Paho's default persistence writes to disk, which the policies forbid), a
hardcoded **5-second connect timeout** so a bad host fails fast rather than hanging an Appian
request thread, and a **10-second** `setTimeToWait` cap on any blocking call. Plain `tcp://` only:
no TLS, no `SSLContext`, no socket factory, no `System.setProperty`. Disconnect and close happen in
a `finally` block — explicit rather than try-with-resources, because closing would otherwise race
the disconnect. No connection caching, no session manager, no reconnection, no background threads,
no LWT.

---

## 5. Why design-time validation was dropped

`getConfiguration` now declares the five properties and returns. There is no `validate()`, no
`PropertyState.setErrorAtPath`, no `firstRender` gate and no Clean Session seeding. Three reasons,
all recorded in the steering file:

1. **It may never have worked.** The Appian 26.9 restrictions page states `setErrors()` is ignored
   on a connected system template. The old code used `setErrorAtPath`, which decompiles to a
   delegation to `setErrorsAtPath` — a *different* method — so whether the restriction covers it
   cannot be settled from the documentation, and could not be settled offline. If it does, the
   entire feature was decorative. Carrying an unresolvable question into a build whose purpose is
   to eliminate unresolved questions makes no sense.
2. **It had a real defect.** `validate` only ever attached errors and never called the SDK's
   `clearErrorsAtPath`, so an error set on one render survived the next even after the Designer
   fixed the value.
3. **Its freshness signal is gone.** The `firstRender` gate read `cleanSession` from the incoming
   state, and `cleanSession` is removed as a field. The gate had nothing left to read.

`isRequired(true)` on `brokerHost` and `brokerPort` is the only design-time guard; Appian enforces
it natively. The publish integration re-checks host, port, topic and QoS at **invocation** time,
returning an error response before any broker contact — those are run-time guards in `execute`,
not design-time validation, and they never touch `setErrorAtPath`.

---

## 6. Test suite — 7 tests in 2 classes

`src/test/java/de/hock/mqtt/ProjectStructureTest.java` (2):

| Test | Asserts |
|---|---|
| `pluginDescriptorIsOnClasspath` | `appian-plugin.xml` resolves via the classloader |
| `defaultResourceBundleIsOnClasspath` | `resources_en_US.properties` resolves via the classloader |

`basePackageIsPresent` was dropped — it only asserted the test's own package name.

`src/test/java/de/hock/mqtt/template/MqttConnectedSystemTemplateDescriptorTest.java` (5):

| Test | Asserts |
|---|---|
| `brandNewConnectedSystemRendersANonNullRootStateWithAResolvableRootType` | **The NPE regression guard.** Premise first (`ConfigurationDescriptor.builder().build().getRootState() == null`, which is what Appian hands in on `New > Connected System`), then a non-null rendered root state, a non-null type name, a non-empty type map containing it |
| `exactlyTheFiveExpectedConfigFieldKeysArePresent` | `getPropertyKeys()` equals the five keys as an **ordered list**, pinning both "all five present" and "no sixth field"; plus `getPropertyStateAtPath` non-null for each |
| `noConnectedSystemPropertyDescriptorIsExpressionable` | Reads `descriptor.isExpressionable()` back through the SDK's **public accessor** on the same object graph Appian's validator walks — not a source grep. Collects every offender as `key (SimpleClassName)`; also asserts 5 were inspected, so a silently-empty loop cannot pass |
| `connectedSystemFieldsUseNoListDocumentOrAppianValueDescriptor` | No `ListTypePropertyDescriptor` / `DocumentPropertyDescriptor` / `AppianValuePropertyDescriptor`, plus `RefreshPolicy.NEVER` and not hidden, for all five |
| `publishIntegrationRendersItsRootStateAndItsThreeExpressionableInputs` | Non-null root state; the three input keys in display order; and all three read back **expressionable** — the deliberate asymmetry with the connected system |

Removed outright (not weakened), because they covered behavior that no longer exists: the
`cleanSession` default, the first-render gate, blank-host error reporting, per-field error clearing,
pasted-PEM → `SSLSocketFactory`, legacy document references, the subscribe descriptor, and the
publish `retained` input.

No test needs a live broker and none was written for one. `ExecutionContext` is passed as `null`,
as before: the SDK's configuration model is plain Java and that parameter is unused on this path.

**`net.jqwik` and `io.moquette` were removed from the pom**, together with the whole
`maven-failsafe-plugin` block and the Surefire `**/*Properties.java` include / `**/*IT.java`
exclude. No file ever matched those patterns — the steering file called it "dead config advertising
coverage that isn't there", which matters more now that AppMarket reviewers read the shipped
sources. Surefire's default `**/*Test.java` include covers both remaining classes.

### Both guard tests were proved to bite

Temporarily adding `.isExpressionable(true)` to `brokerHost`:

```
[ERROR] MqttConnectedSystemTemplateDescriptorTest.noConnectedSystemPropertyDescriptorIsExpressionable:165
  Appian rejects a connected system template declaring any expressionable property descriptor
  (APNX-1-4198-000), but these fields read back expressionable: [brokerHost (TextPropertyDescriptor)]
  ==> expected: <true> but was: <false>
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0
```

Temporarily adding a sixth descriptor:

```
[ERROR] MqttConnectedSystemTemplateDescriptorTest.exactlyTheFiveExpectedConfigFieldKeysArePresent:128
  the minimal build declares exactly these five connected system fields, in this order
  ==> expected: <[brokerHost, brokerPort, clientIdentifier, username, password]>
   but was: <[brokerHost, brokerPort, clientIdentifier, username, password, temporarySixthField]>
[ERROR] MqttConnectedSystemTemplateDescriptorTest.noConnectedSystemPropertyDescriptorIsExpressionable:164
  all five connected system fields must have been inspected ==> expected: <5> but was: <6>
[ERROR] Tests run: 7, Failures: 2, Errors: 0, Skipped: 0
```

Both changes were reverted and the suite re-run to green (below).

---

## 7. Verification — real command output

Every command below was run from `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component`.
`-o` (offline) held throughout; removing dependencies never needs a download, so no non-offline
resolve was forced.

### 7.1 `mvn -o clean package`

```
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.090 s -- in de.hock.mqtt.template.MqttConnectedSystemTemplateDescriptorTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.003 s -- in de.hock.mqtt.ProjectStructureTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
[INFO] Building jar: /Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/target/mqtt-broker-connected-system-1.0.4-plugin.jar
[INFO] BUILD SUCCESS
```

### 7.2 `mvn -o test`

```
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.093 s -- in de.hock.mqtt.template.MqttConnectedSystemTemplateDescriptorTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.002 s -- in de.hock.mqtt.ProjectStructureTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**7 tests, 0 failures, 0 errors, 0 skipped.**

### 7.3 Expressionability, per file

```
$ grep -c isExpressionable src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java
0

$ grep -rc isExpressionable src/main/java/ | grep -v ':0$'
src/main/java/de/hock/mqtt/integration/MqttPublishIntegration.java:3

$ grep -rn isExpressionable src/main/java/ | wc -l
       3
```

Expected 0 in the template and non-zero only in `MqttPublishIntegration`. Actual: exactly that —
template 0, publish integration 3 (one per input), repo-wide total 3.

> **One deviation worth recording.** The first run of this check returned `1` for the template,
> because its Javadoc contained the literal word `isExpressionable` while explaining the rule. The
> prose was reworded ("never touches the expressionable modifier") so the grep gate reads a true
> zero. No descriptor behavior changed. The grep is the weaker of the two checks anyway — the
> `noConnectedSystemPropertyDescriptorIsExpressionable` test is what actually proves compliance,
> because it reads the modifier back through the SDK API that the platform validator walks.

### 7.4 Packaging invariants (a)–(k), against the built jar

`J=target/mqtt-broker-connected-system-1.0.4-plugin.jar`

**(a) Namespace `de.hock.mqtt`, never `com.appian.*`**

```
$ grep -rn 'com\.appian\.mqtt' src pom.xml | wc -l
       0
$ unzip -l $J | grep -c 'com/appian/mqtt'
0
$ grep -rl 'com\.appian\.connectedsystems' src/main/java | wc -l
       2
$ grep -rh 'import com.appian.connectedsystems' src/main/java/ | wc -l
      22
$ grep -c '<groupId>de.hock</groupId>' pom.xml
1
$ unzip -p $J appian-plugin.xml | grep -c 'key="de.hock.mqtt"'
1
```

Expected / actual: `com.appian.mqtt` 0/0 in sources, 0/0 in the jar; SDK imports intact at 22
across 2 files (the templates), so no blind replace occurred; groupId `de.hock` 1/1; plug-in key
1/1. ✅

> The manifest comment originally also contained the literal `key="de.hock.mqtt"`, making that last
> count read `2`. The comment was reworded to refer to the de.hock namespace in prose, so the count
> now reflects the single real declaration. Cosmetic; no manifest value changed.

**(b) Sources ship inside the jar, none outside `de/hock/mqtt/`, no test entries**

```
$ unzip -l $J | grep -c '\.java$'
4
$ unzip -l $J | grep '\.java$' | grep -vc 'de/hock/mqtt/'
0
$ unzip -l $J | grep '\.java$'
     4506  02-01-1980 00:00   de/hock/mqtt/MqttPublisher.java
     8878  02-01-1980 00:00   de/hock/mqtt/integration/MqttPublishIntegration.java
      979  02-01-1980 00:00   de/hock/mqtt/package-info.java
     8935  02-01-1980 00:00   de/hock/mqtt/template/MqttConnectedSystemTemplate.java
$ unzip -l $J | grep -ci test
0
```

Expected 4 / actual **4** (was 34). None outside the package: 0/0. Test entries 0/0. ✅

**(c) `appian-plugin.xml` a bare root entry**

```
$ unzip -l $J | grep -cE ' appian-plugin\.xml$'
1
$ unzip -l $J | awk '{print $NF}' | grep -c '^appian-plugin.xml$'
1
```

Expected 1/1 both ways — present, and with no directory prefix. ✅

**(d) Paho is the only thing under `META-INF/lib/`; no Appian artifact anywhere**

```
$ unzip -l $J | grep -c 'META-INF/lib/.*\.jar$'
1
$ unzip -l $J | grep -c 'META-INF/lib/org.eclipse.paho'
1
$ unzip -l $J | grep -ci 'connected-systems'
0
$ unzip -l $J | grep 'META-INF/lib'
        0  02-01-1980 00:00   META-INF/lib/
   241861  02-01-1980 00:00   META-INF/lib/org.eclipse.paho.client.mqttv3-1.2.5.jar
```

Expected 1 nested jar / actual 1, and it is Paho. `connected-systems` entries expected 0 / actual
0 — both Appian artifacts remain `provided`. ✅

**(e) Version formats — three-part manifest version, four-part platform minimum**

```
$ unzip -p $J appian-plugin.xml | grep -E '<version>|application-version'
  installing alongside it. <plugin-info><version> is three-part x.y.z (the AppMarket validator
  rejects four-part); <application-version min=...> is the four-part Appian platform version.
        <version>1.0.4</version>
        <application-version min="26.9.0.0"/>
$ grep -A1 '<artifactId>mqtt-broker-connected-system</artifactId>' pom.xml | grep '<version>'
    <version>1.0.4</version>
```

Expected `<version>1.0.4</version>` three-part / actual that; `min="26.9.0.0"` four-part /
unchanged; pom version 1.0.4 / actual that. (The first two output lines are the manifest's own
explanatory comment, which the grep pattern also matches.) ✅

**(f) Vendor is the real author**

```
$ unzip -p $J appian-plugin.xml | grep -c '<vendor name="Hock" url="https://itondemand.com"/>'
1
```

Expected 1 / actual 1. ✅

**(g) Exactly one jar in `target/`**

```
$ ls target/*.jar | wc -l
       1
$ ls target/*.jar
target/mqtt-broker-connected-system-1.0.4-plugin.jar
```

Expected 1 / actual 1, and it carries the `-plugin` suffix. ✅

**(h) Java 17, bytecode major version 61**

```
$ javap -verbose -cp target/classes de.hock.mqtt.template.MqttConnectedSystemTemplate | grep 'major version'
  major version: 61
$ javap -verbose -cp target/classes de.hock.mqtt.MqttPublisher | grep 'major version'
  major version: 61
$ javap -verbose -cp target/classes de.hock.mqtt.integration.MqttPublishIntegration | grep 'major version'
  major version: 61
```

Expected 61 / actual 61 on all three. ✅

**(i) Resource bundle — no orphan keys, no missing keys**

Checked programmatically: the bundle's actual key set versus the set derived from the surviving
field-key constants and class names.

```
$ wc -l < /tmp/bundle-actual.txt      # keys present in the bundle
      12
$ wc -l < /tmp/bundle-expected.txt    # keys derived from the source constants
      12
$ diff /tmp/bundle-expected.txt /tmp/bundle-actual.txt
  (no output: exit 0, no orphans, no missing)

$ cat /tmp/bundle-actual.txt
MqttConnectedSystemTemplate.brokerHost.label
MqttConnectedSystemTemplate.brokerPort.label
MqttConnectedSystemTemplate.clientIdentifier.label
MqttConnectedSystemTemplate.description
MqttConnectedSystemTemplate.name
MqttConnectedSystemTemplate.password.label
MqttConnectedSystemTemplate.username.label
MqttPublishIntegration.description
MqttPublishIntegration.name
MqttPublishIntegration.payload.label
MqttPublishIntegration.qos.label
MqttPublishIntegration.topic.label

$ grep -c '\.label=' src/main/resources/resources_en_US.properties
8
$ unzip -l $J | grep -c ' resources_en_US.properties$'
1
```

**12 keys** total (was 34): 2 name/description pairs = 4, plus **8 field labels** (was 28). The
`diff` is empty in both directions, so there is no orphan key and no missing key. Both templates
have their `.name` and `.description`; all five config field keys and all three input keys have a
`.label`. Bundle at the jar root: 1/1. 22 keys were removed: `connectionTimeoutSeconds`,
`tlsEnabled`, `caCertificate`, `clientCertificate`, `clientPrivateKey`, `keyPassphrase`,
`autoReconnectEnabled`, `maxReconnectAttempts`, `reconnectIntervalSeconds`, `lwtTopic`,
`lwtPayload`, `lwtQos`, `lwtRetained`, `cleanSession`, `keepAliveSeconds`,
`MqttPublishIntegration.retained`, and all six `MqttSubscribeIntegration.*`. ✅

**(j) `THIRD-PARTY-NOTICES.md` at the archive root**

```
$ unzip -l $J | awk '{print $NF}' | grep -c '^THIRD-PARTY-NOTICES.md$'
1
$ unzip -p $J THIRD-PARTY-NOTICES.md | grep -c 'EDL'
2
```

Expected 1 bare-root entry / actual 1; EDL named (Paho 1.2.5, dual EPL-2.0 / EDL-1.0, relying on
EDL-1.0). ✅

**(k) Nothing from `.backup/` in the jar**

```
$ unzip -l $J | grep -ci 'backup'
0
$ unzip -l $J | grep -c '\.zip$'
0
```

Expected 0/0 both. ✅

**Full jar listing — 20 entries, nothing unexpected**

```
Archive:  target/mqtt-broker-connected-system-1.0.4-plugin.jar
  Length      Date    Time    Name
---------  ---------- -----   ----
        0  02-01-1980 00:00   META-INF/
       79  02-01-1980 00:00   META-INF/MANIFEST.MF
        0  02-01-1980 00:00   de/
        0  02-01-1980 00:00   de/hock/
        0  02-01-1980 00:00   de/hock/mqtt/
        0  02-01-1980 00:00   de/hock/mqtt/integration/
        0  02-01-1980 00:00   de/hock/mqtt/template/
        0  02-01-1980 00:00   META-INF/lib/
     2082  02-01-1980 00:00   appian-plugin.xml
     2818  02-01-1980 00:00   de/hock/mqtt/MqttPublisher.class
     9663  02-01-1980 00:00   de/hock/mqtt/integration/MqttPublishIntegration.class
      118  02-01-1980 00:00   de/hock/mqtt/package-info.class
     6058  02-01-1980 00:00   de/hock/mqtt/template/MqttConnectedSystemTemplate.class
     1642  02-01-1980 00:00   resources_en_US.properties
     4506  02-01-1980 00:00   de/hock/mqtt/MqttPublisher.java
     8878  02-01-1980 00:00   de/hock/mqtt/integration/MqttPublishIntegration.java
      979  02-01-1980 00:00   de/hock/mqtt/package-info.java
     8935  02-01-1980 00:00   de/hock/mqtt/template/MqttConnectedSystemTemplate.java
     1008  02-01-1980 00:00   THIRD-PARTY-NOTICES.md
   241861  02-01-1980 00:00   META-INF/lib/org.eclipse.paho.client.mqttv3-1.2.5.jar
---------                     -------
   288627                     20 files
```

### 7.5 The `.backup/` archive is untouched and out of the jar

```
$ shasum -a 256 .backup/mqtt-plugin-FULL-1.0.3-src.zip
386f3cab7ab660e19325f2825071152338eb48855e6f45b8d9665afcc22f89b4  .backup/mqtt-plugin-FULL-1.0.3-src.zip
$ stat -f '%z bytes' .backup/mqtt-plugin-FULL-1.0.3-src.zip
136397 bytes
$ unzip -l .backup/mqtt-plugin-FULL-1.0.3-src.zip | tail -2
---------                     -------
   392114                     74 files
```

Expected sha256 `386f3cab7ab660e19325f2825071152338eb48855e6f45b8d9665afcc22f89b4`, 136397 bytes,
74 entries — all three match the pre-change measurement exactly. The archive was read-only
throughout and nothing from it entered the jar (invariant **k** above). ✅

---

## 8. Version and upgrade safety

- `pom.xml` project version and manifest `<plugin-info><version>` are both **`1.0.4`** — three-part
  `x.y.z`, because the AppMarket validator rejects four-part with "The version number format is
  invalid."
- `<application-version min="26.9.0.0"/>` is unchanged and stays **four-part**; different field.
- The plug-in key `de.hock.mqtt` and both module keys (`MqttConnectedSystemTemplate`,
  `MqttPublishIntegration`) are **unchanged**, so 1.0.4 **replaces** the installed plug-in rather
  than installing alongside it.
- `1.0.4 > 1.0.3`, which is required: Appian refuses to deploy a plug-in over an installed one
  unless the version increases. Restarting the scheme at something like `0.1.0` would be rejected.
- **No existing Connected System object can break.** The configuration page never rendered
  successfully on 1.0.0–1.0.3 — that is the bug this reduction exists to isolate — so no MQTT Broker
  connected system was ever created against this plug-in. There is no saved state to migrate.

---

## 9. Deploy-and-test checklist

Work through these in order. Step 3 is the one that failed three times.

1. **Build.** `mvn clean package` → confirm exactly one jar,
   `target/mqtt-broker-connected-system-1.0.4-plugin.jar`.
2. **Install.** `cp target/mqtt-broker-connected-system-1.0.4-plugin.jar APPIAN_INSTALL/_admin/plugins/`
   Watch `tomcat-stdOut.log` for `Successfully installed Plug-in` plus the
   `ConnectedSystemTemplateModuleParser` registration lines. If `conf.plugins.poll-interval` is `0`,
   hot deployment is off and the application server must be restarted.
   *Proves requirement (1).* A `NoClassDefFoundError` naming
   `com/appian/connectedsystems/simplified/sdk/...` here means the server does not supply
   `connected-systems-client` — see §10.
3. **Confirm the configuration page renders.** Admin Console → Plug-ins lists the plug-in at
   `1.0.4`. Then **New > Connected System** → pick **MQTT Broker** → **the form must render five
   fields**: Broker Host, Broker Port, Client Identifier, Username, Password. No
   "Property Descriptors in Connected System Template must not be Expressionable", no
   expression-evaluation error.
   *Proves requirement (3) — the check 1.0.1, 1.0.2 and 1.0.3 all failed.*
4. **Fill in a broker.** Enter a reachable host and `1883` (the port shows 1883 as a placeholder; it
   is not pre-filled). Leave Client Identifier blank to exercise Paho's own id generation. Leave
   Username and Password blank for an anonymous broker, or fill both. Save.
5. **Create the publish integration.** New Integration against that connected system → **Publish
   MQTT Message**. Confirm three inputs render — Topic, Payload, QoS — and that each shows the
   expression toggle.
6. **Publish to a real topic.** Subscribe a separate client to the topic first, e.g.
   `mosquitto_sub -h <host> -t appian/smoketest -v`. Then invoke the integration with
   `topic: "appian/smoketest"`, `payload: "hello from Appian"`, `qos: 0` and **observe the message
   arrive** at the subscriber.
   *Proves requirements (2) and (4).* Confirm the response result map reads
   `success: true`, `status: "dispatched"`, `topic: "appian/smoketest"`, `qos: 0`. A
   `NoClassDefFoundError` naming `org/eclipse/paho/...` here means the `META-INF/lib/` layout is
   wrong, and it will name `de.hock.mqtt.MqttPublisher` as the caller.
7. **Try QoS 1** with the same topic and confirm `status: "delivered"` — that path returns only
   after the broker acknowledges, so it proves the ack round trip rather than just the handoff.
8. **Try a failure.** Point the connected system at an unreachable host and publish again. Expect an
   error titled `MQTT publish failed` within roughly 5 seconds (the hardcoded connect timeout), not
   a hung request.

Only after step 6 passes may 26.9 be claimed as a tested compatible version on an AppMarket
submission.

---

## 10. What remains unverifiable offline

Nothing in §7 can establish any of the following. Each needs step 2, 3 or 6 of §9.

- **Installation.** That Appian accepts the manifest and registers both modules.
- **The `META-INF/lib/` classloader layout.** It matches where Appian's plug-in classloader resolves
  nested jars, but it has never been confirmed on a live instance. The isolation of all Paho use in
  `MqttPublisher` exists precisely so this failure, if it happens, points at one class.
- **That the Appian server supplies `connected-systems-client`.** Both Appian artifacts are
  `provided`, which is the documented path, but unconfirmed. If it is absent, the fallback is to
  keep the `Simple*` logic and construct the descriptor by hand with `StateGenerator` + `withType`
  + `withState` on the low-level interfaces — **not** to bundle the client jar.
- **That the configuration form actually renders.** The descriptor-modifier rules are enforced by
  `com.appiancorp.*` platform classes which are not in either SDK jar (core 1.9.1 contains zero
  `com/appiancorp/` entries), so they cannot be inspected or executed offline. The
  expressionability test reproduces what the validator *walks*, not the validator itself.
- **That a publish reaches a broker.** No test contacts one, by design.
- **Whether `placeholder` renders as expected on an Integer field** in a real connected system form.
  The builder method exists and no SDK guard rejects it; the visual result is unconfirmed.

**Final confirmation requires the user deploying
`target/mqtt-broker-connected-system-1.0.4-plugin.jar` to their real Appian 26.9 instance.**

---

## 11. Restoring the full build

```bash
mkdir -p /tmp/mqtt-full && unzip .backup/mqtt-plugin-FULL-1.0.3-src.zip -d /tmp/mqtt-full
```

The archive holds the complete 1.0.3 tree — 36 Java files (34 main, 2 test) plus `pom.xml`,
`README.md`, `info.md`, `THIRD-PARTY-NOTICES.md`, `docs/` and `.kiro/`; 74 entries, 136397 bytes,
sha256 `386f3cab7ab660e19325f2825071152338eb48855e6f45b8d9665afcc22f89b4`.

Restore **selectively** — one validator, the TLS layer, or the subscribe integration — rather than
wholesale, and re-read `.kiro/steering/appian-appmarket-compliance.md` first. Specifically:

- The three platform contracts that broke 1.0.1–1.0.3 still bind anything you put back: no
  expressionable connected system descriptor, no `Document`/`AppianValue`/`ListType` descriptor on a
  connected system, and keep extending the `Simple*` base classes.
- The two open defects recorded against the archived code are still in it: design-time validation
  may be ignored outright on a connected system (steering §5 item 7), and an attached field error is
  never cleared (item 8). Fix the second before restoring validation.
- Restoring TLS also restores the "no `System.setProperty`" hazard — build an in-memory
  `SSLContext` and pass the socket factory via `MqttConnectOptions.setSocketFactory()`.
- Bump the version past `1.0.4`, or Appian will not deploy over the installed plug-in.
- If you restore a test that needs a broker or property-based testing, re-add `net.jqwik` and/or
  `io.moquette` with the Surefire/Failsafe wiring — do not re-add the wiring without the files.

---

## 12. Documentation updated in lockstep

- **`README.md`** — rewritten for the reduced surface. Leads with the minimal-smoke-test-build
  statement and the four things it proves, names every deliberately removed feature and the archive
  they are recoverable from, documents the five config fields and three publish inputs with their
  real keys and labels, keeps build and deploy correct for 1.0.4, and links to the steering file
  rather than duplicating the compliance rules. Every `1.0.3` mention in it is a historical or
  archive reference; none claims 1.0.3 as the current version.
- **`.kiro/steering/appian-appmarket-compliance.md`** — the hard constraints in §1 and §2 were kept
  intact; every project-specific figure was updated: version `1.0.4`, 4 `.java` sources, 22 SDK
  imports across 2 files, `isExpressionable` 3 / template 0, 12 bundle keys / 8 field labels, 7
  tests with the five descriptor test names listed, the submission artifact filename, the removal
  of jqwik/moquette/Failsafe, and the whole §4 verification block replaced with the one in §7 here.
  Items 2, 4, 6, 7 and 8 of §5 were annotated with what the reduction superseded; items 1, 3, 5 and
  9 stand unchanged. A scope note at the top and in §8 records the reduction and the archive path,
  so a future reader is not misled by the richer pre-reduction history in `.agents/tasks/`.
- **`docs/mqtt-plugin-guide.md`** — **marked, not trimmed.** A banner under the heading states it
  describes the pre-reduction full build, names what is absent from 1.0.4, points at `README.md` for
  what ships, and gives the archive path. The body is left intact because the full narrative stays
  genuinely useful to whoever restores the archive, and trimming it would just produce a second copy
  of the new README.
- **`info.md`** — version 1.0.4, the two modules, and a minimal-build note. It names the subscribe
  integration once, in the list of what was removed.
- **`.agents/tasks/`** — every existing history file was left untouched. This document is the single
  new record for the reduction; no second overlapping record was added.
