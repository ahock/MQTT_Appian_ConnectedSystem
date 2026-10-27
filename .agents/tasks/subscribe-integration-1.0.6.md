# Release record — Subscribe (READ) integration + `ConfigValidator` demotion, 1.0.6

Built 2026-10-27 on the deployed-and-working 1.0.5 baseline (commit `4bef5ec`). Target: Appian 26.9,
Java 17, `de.hock.mqtt`, plug-in key `de.hock.mqtt`, SDK core 1.9.1 / client 1.3.0 (both
`provided`), Paho 1.2.5 (bundled under `META-INF/lib/`).

**Artifact to deploy:** `target/mqtt-broker-connected-system-1.0.6-plugin.jar`

**Status: built, fully verified offline, NOT yet deployed.** Final confirmation requires the user
deploying that jar to a live Appian 26.9 instance and working through the
[deploy-and-test checklist](#deploy-and-test-checklist). Nothing below proves the subscribe path runs
on a real broker — no test opens a socket, by design.

Plan: `.agents/tasks/subscribe-integration-1.0.6-plan.md`.

---

## 1. What changed

### Added

| File | Purpose |
|---|---|
| `src/main/java/de/hock/mqtt/integration/MqttSubscribeIntegration.java` | The READ integration: four inputs, bounded collection, flat output |
| `src/main/java/de/hock/mqtt/MqttSubscriber.java` | The second Paho call site: connect → subscribe → collect → unsubscribe → disconnect → close |
| `src/main/java/de/hock/mqtt/ConnectionConfigValidator.java` | The demoted configuration rules, called at invocation time by both integrations |
| `src/test/java/de/hock/mqtt/MqttSubscriberTest.java` | 3 tests: both loop termination conditions, payload encoding |
| `src/test/java/de/hock/mqtt/integration/MqttSubscribeIntegrationInputsTest.java` | 4 tests: defaults, rejection, empty-result success, guard-before-broker |
| `src/test/java/de/hock/mqtt/ConnectionConfigValidatorTest.java` | 4 tests: one per demoted rule, on the run-time path |
| `src/test/java/de/hock/mqtt/template/MqttConnectedSystemTemplateNoDesignTimeErrorsTest.java` | 1 test: a brand-new connected system renders clean |

### Changed

| File | Change |
|---|---|
| `pom.xml` | `1.0.5` → `1.0.6`; description mentions both integrations; Surefire comment says seven test classes |
| `src/main/resources/appian-plugin.xml` | `<version>1.0.6</version>`; second `<integration-template>` nested in the one `<connected-system-template>`; comment block rewritten |
| `src/main/resources/resources_en_US.properties` | 16 → **22** keys (12 → **16** labels): the subscribe template's name, description and four input labels |
| `src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java` | Removed the `ConfigValidator.validate(...)` call and its import; Javadoc records the finished experiment. **Nine fields, their order, their types and every `isRequired` are untouched.** |
| `src/main/java/de/hock/mqtt/integration/MqttPublishIntegration.java` | The inline host/port guards replaced by `ConnectionConfigValidator.firstError`; guard order topic → configuration → QoS; new `Invalid configuration` error title |
| `src/main/java/de/hock/mqtt/MqttPublisher.java` | Javadoc only: two Paho call sites now, `buildConnectOptions` shared, out-of-range fallbacks are defence in depth |
| `src/main/java/de/hock/mqtt/package-info.java` | Javadoc only |
| `src/test/java/de/hock/mqtt/template/MqttConnectedSystemTemplateDescriptorTest.java` | +2 tests for the subscribe descriptor; the five existing tests are unchanged |
| `README.md`, `info.md`, `docs/mqtt-plugin-guide.md`, `.kiro/steering/appian-appmarket-compliance.md` | Documentation lockstep |

### Deleted

| File | Why |
|---|---|
| `src/main/java/de/hock/mqtt/validation/ConfigValidator.java` | Dead code: Appian discards errors attached via `setErrorAtPath` (steering §5 item 7). Rules demoted, not dropped. |
| `src/main/java/de/hock/mqtt/validation/` (the directory) | Empty afterwards. The `validation` package no longer exists. |
| `src/test/java/de/hock/mqtt/template/MqttConnectedSystemTemplateValidationTest.java` | Asserted design-time error attachment, which is what was removed. Its four rules are re-covered on the run-time path; its one behavioural assertion was replaced. |

---

## 2. Decisions, and why

### (1) A new `MqttSubscriber` class, not a shared connect helper

`MqttSubscriber` sits in the same package as `MqttPublisher`, so it calls the existing
package-private `MqttPublisher.buildConnectOptions(...)` and `MqttPublisher.TIME_TO_WAIT_MILLIS`
directly. No visibility was widened, the default-from-`null` logic is not duplicated, and **the
proven 1.0.5 publish path is untouched**.

A third "connection helper" class was rejected: it would move the one method that is proven to work
on a live instance for the sake of ~15 lines of connect/teardown that genuinely differ anyway.
Subscribe must register the message listener *as part of* `subscribe(filter, qos, listener)`, must
`unsubscribe` before disconnecting, and must not disconnect until the collection window closes.

Accepted cost: the ~30-line "read nine values off the connected system state" block appears in both
integrations. The alternative — a `ConnectionSettings` record with a `from(PropertyState)` factory —
is exactly 1.0.3's removed `MqttConnectionConfig` indirection. Duplicating mechanical reader calls
is the cheaper risk.

**Consequence recorded in steering §1:** the "exactly one class references Paho" invariant is now
**two** classes. A `NoClassDefFoundError` names whichever one ran.

### (2) Out-of-range inputs are REJECTED, not clamped

`qos`, `maxWaitSeconds` and `maxMessageCount` fail with `Invalid input` before any broker contact.
Blank is different: blank takes the documented default.

Reasons: it matches what the publish integration already does for `qos`; silently turning a
requested 300-second wait into 30 would leave a designer with no way to notice; and rejection
guarantees `maxMessageCount >= 1` before it is used as a `LinkedBlockingQueue` capacity, where 0
throws `IllegalArgumentException`.

**Deliberate behaviour change, documented in the README.** Out-of-range *connected system* session
values (`connectionTimeoutSeconds`, `keepAliveSeconds`) are now **also rejected** at run time, by
the demoted validator. In 1.0.5 they silently fell back to a default inside `buildConnectOptions`.
Those fallbacks remain — that method is total and must never throw — but the validator now gates
them, so on the integration path they are unreachable.

### (3) Non-UTF-8 payloads: Base64, standard alphabet

`java.util.Base64.getEncoder().encodeToString(bytes)` — RFC 4648 standard alphabet, with padding —
and `binary = true`. Text detection is a **strict** UTF-8 decode
(`newDecoder().onMalformedInput(REPORT).onUnmappableCharacter(REPORT)`); a `CharacterCodingException`
means binary. Valid UTF-8 gives the decoded string with `binary = false`. An empty payload is an
empty string with `binary = false`.

Lossless, and Appian's response surface is string-typed so raw bytes cannot be returned. Same scheme
as the reviewed 1.0.3 logic.

### (4) Subscribe client identifier: configured id + `-sub`

Blank `clientIdentifier` → `MqttClient.generateClientId()`, already unique per call. Non-blank →
`clientIdentifier.trim() + "-sub"`, from `MqttSubscriber.SUBSCRIBE_CLIENT_ID_SUFFIX`.

A subscribe therefore cannot take over the publish session's id on the same broker, with no
randomness to explain. Two caveats are documented in the README rather than coded around:

- Two **concurrent** subscribe calls on the same connected system share `base-sub`, so the broker
  disconnects the earlier one. Leave `clientIdentifier` blank to avoid it.
- A broker enforcing the MQTT 3.1 23-character client-id limit may reject `base-sub` when `base` is
  already 20+ characters.

### (5) The demoted validator: `de.hock.mqtt.ConnectionConfigValidator`

One public method, `static String firstError(PropertyState connectionState)` — the first problem as a
designer-ready sentence, or `null` when the configuration is usable.

The name and location both changed on purpose. It no longer does anything at design time, so a
`validation` package — which in 1.0.3 held design-time validators — is actively misleading. The root
package puts it beside the two Paho call sites that consume its verdict, and the name says *what* it
validates instead of *when*. Returning `String`/`null` keeps it to one small class with no new result
type.

What replaced each of `ConfigValidator`'s four rules:

| Rule | 1.0.5 (design time, discarded) | 1.0.6 (run time, surfaced) |
|---|---|---|
| `brokerHost` non-blank | `setErrorAtPath(brokerHost, "Broker host is required.")` | `Invalid configuration` — "Broker host is required on the MQTT Broker connected system." |
| `brokerPort` 1–65535 | `setErrorAtPath(brokerPort, …)` | `Invalid configuration`, naming the value |
| `connectionTimeoutSeconds` 1–300 when supplied | `setErrorAtPath(connectionTimeoutSeconds, …)` | `Invalid configuration`, naming the value — **now fails the call** |
| `keepAliveSeconds` 0–65535 when supplied | `setErrorAtPath(keepAliveSeconds, …)` | `Invalid configuration`, naming the value — **now fails the call** |

`isRequired` stays on `brokerHost` and `brokerPort`: it is the only design-time guard Appian honours.

### (6) The testable seam for the collection loop: two package-private statics

No adapter interface, no injected client, no new types — the 1.0.3 `MqttClientAdapter` /
`PahoMqttClientAdapter` / `MqttConnectionManager` / `MqttSession` indirection was **not**
reintroduced.

```java
static List<Map<String,Object>> drain(BlockingQueue<Map<String,Object>> queue,
                                      int maxMessageCount, long deadlineNanos)
static Map<String,Object> toMessageMap(String topic, MqttMessage message)
```

`drain` takes an **absolute** `System.nanoTime()` deadline, so a test passes a past deadline (must
return empty at once) or a far-future deadline with a pre-filled queue (must return exactly
`maxMessageCount` at once, not wait). `toMessageMap` takes a plain `new MqttMessage(bytes)`,
constructible offline because Paho is a compile-scope dependency. `subscribe` wires them: the Paho
listener does `queue.offer(toMessageMap(topic, message))` and nothing else.

Three one-line statics on the integration — `qosOf`, `maxWaitSecondsOf`, `maxMessageCountOf` — make
the defaults observable, and `inputError` / `success` make rejection and the response shape
assertable without a broker.

### (7) Single topic filter, not a list — and the offline evidence, reported not acted on

`topicFilter` is a plain `TextPropertyDescriptor`. Measured with `javap -c` against
`connected-systems-core-1.9.1.jar`:

1. `ListTypePropertyDescriptor$Builder.<init>` calls
   `PropertyDescriptorBuilder.isExpressionable(true)` **and**
   `displayHint(DisplayHint.EXPRESSION)` — both forced in the constructor.
2. Its `build()` calls `Guard.isDisplayHintExpression(data)` and, when true, throws
   `IllegalStateException: List property descriptors cannot have display hint of expression and not
   be expressionable` if the flag was cleared.
3. `PropertyDescriptorBuilder.displayHint(DisplayHint)` **is public**, so that coupling is in
   principle escapable with `displayHint(NORMAL)` *plus* `isExpressionable(false)`. Unlike
   `DocumentPropertyDescriptor`, `build()` does **not** call `Guard.checkPropertyIsExpressionable`,
   so no unconditional guard forces the flag. This is new detail relative to steering §1's table,
   which records the type as "effectively no" on a connected system — still true for a
   *default-built* list descriptor.
4. **No evidence either way about integration inputs.** The validator that rejected the forced flags
   was `ConnectedSystemPropertiesValidator`, platform-side and connected-system-specific. Neither
   SDK jar contains it or any integration-input validator (steering §5 item 9), so legality there
   **cannot be proven offline**.

That is not hard evidence of safety, so it was reported and not acted on, as instructed. A single
filter with wildcards (`sensors/+/temp`, `home/#`) covers most real subscriptions. Multi-topic is a
deliberate follow-up. The precaution is now recorded in steering §1, clearly labelled as a
precaution rather than a known rule, together with a test asserting no subscribe input uses a
`ListType` / `Document` / `AppianValue` descriptor.

---

## 3. The subscribe integration as it ships

`@TemplateId(name = "MqttSubscribeIntegration")`,
`@IntegrationTemplateType(IntegrationTemplateRequestPolicy.READ)`, extends
`SimpleIntegrationTemplate`.

### Inputs — four, all expressionable

| Key | Type | Required | Default when blank | Valid range |
|---|---|---|---|---|
| `topicFilter` | Text | **Yes** | — | one MQTT filter; `+` and `#` supported, stated in the instruction text |
| `qos` | Integer | No | `0` | `0`–`2` |
| `maxWaitSeconds` | Integer | No | `10` | `1`–`30` |
| `maxMessageCount` | Integer | No | `10` | `1`–`100` |

All four set the expressionable modifier, which is legal on an integration and **the deliberate
opposite of the connected system**, where Appian rejects any expressionable descriptor and all nine
fields therefore leave the modifier untouched. That asymmetry is asserted per key by two tests.

### Output

`messages` — a list of maps with keys in the order `topic`, `payload`, `qos`, `retained`, `binary` —
plus `messageCount`. **An empty collection is a success with `messageCount` 0**, never an error.

Error titles: `Invalid input`, `Invalid configuration`, `MQTT subscribe failed`. Messages truncated
to 500 characters. The password is never read into any message.

### Guard order in `execute`

1. blank `topicFilter` → `Invalid input`
2. `ConnectionConfigValidator.firstError(connection)` → `Invalid configuration`
3. `inputError(qos, maxWaitSeconds, maxMessageCount)` → `Invalid input`

All three return before any broker contact. The publish integration mirrors this: topic →
configuration → QoS.

---

## 4. The blocking-wait risk, and why the cap is 30

**A subscribe occupies an Appian request thread for up to `maxWaitSeconds`.** That is inherent to
turning event-driven delivery into a synchronous integration call, and it is why the cap is **30
seconds**, the default is **10**, and `maxMessageCount` ends the wait early — the loop polls in
200 ms slices, so it returns as soon as the count is reached instead of sitting out the window.

**Appian applies its own integration request timeout, independently of this plug-in and unknown to
it.** If Appian's timeout fires first, the designer sees an *Appian* error rather than an empty
result. The cap is not going up; the README says all of this plainly.

### Nothing outlives the request — the teardown path

From `MqttSubscriber.subscribe`:

```java
boolean subscribed = false;
try {
  client.connect(options);
  client.subscribe(topicFilter, qos, (topic, message) -> queue.offer(toMessageMap(topic, message)));
  subscribed = true;
  return drain(queue, maxMessageCount, System.nanoTime() + TimeUnit.SECONDS.toNanos(maxWaitSeconds));
} finally {
  if (subscribed) {
    try { client.unsubscribe(topicFilter); } catch (MqttException ignored) { }
  }
  try { if (client.isConnected()) { client.disconnect(); } } catch (MqttException ignored) { }
  try { client.close(); } catch (MqttException ignored) { }
}
```

Read it against each requirement:

- **No thread, executor or `MqttCallback` is created anywhere in the class.** Paho's own receiver
  thread calls the listener; the listener does one `offer` and returns.
- **The queue is bounded to `maxMessageCount`** (`new LinkedBlockingQueue<>(maxMessageCount)`), and
  `offer` **drops** when full rather than blocking, so Paho's receiver thread is never held up and
  memory cannot grow without bound.
- **The three-argument `subscribe` overload** registers the listener as part of the subscription, so
  nothing delivered between SUBACK and the first poll is lost. There is no window where a callback
  exists without a subscription or vice versa.
- **`unsubscribe` runs only when `subscribed` is true**, so a connect failure does not call it on a
  client that never subscribed.
- **All three teardown steps are in the `finally`**, each in its own `try`/`catch (MqttException
  ignored)`, so one failure cannot skip the next. A timeout, a thrown `MqttException` and a
  `RuntimeException` all take the same path.
- **`drain` cannot block forever**: it breaks when `deadlineNanos - System.nanoTime() <= 0`, and
  every `poll` is bounded by `min(200 ms, remaining + 1 ms)`. On `InterruptedException` it re-sets
  the interrupt flag and breaks.
- **`toMessageMap` is total** — a `null` message or payload becomes an empty payload — because it
  runs on Paho's thread, where a throw would be swallowed or would tear down delivery.
- `MemoryPersistence` is used, so nothing touches the filesystem. Transport is plain `tcp://`. No
  `System.setProperty`, no logging, no `printStackTrace`.

---

## 5. Figures

| Figure | 1.0.5 | 1.0.6 (measured) |
|---|---|---|
| `<version>` in `pom.xml` and `appian-plugin.xml` | `1.0.5` | **`1.0.6`** |
| `<application-version min=...>` | `26.9.0.0` | `26.9.0.0` — untouched |
| Submission artifact | `…-1.0.5-plugin.jar` | **`mqtt-broker-connected-system-1.0.6-plugin.jar`** |
| `.java` sources in the jar | 5 | **7** |
| Files importing `com.appian.connectedsystems` | 3 | **4** |
| Total SDK import lines | 25 | **36** (template 11, publish 12, subscribe 12, validator 1) |
| `isExpressionable` occurrences, repo-wide | 3 | **7** (template 0, publish 3, subscribe 4) |
| Resource bundle keys | 16 (12 labels) | **22 (16 labels)** |
| Tests | 13 in 4 classes | **23 in 7 classes** |
| Connected system fields | 9 | 9 — unchanged |
| Integration modules | 1 | **2** |
| Classes referencing Paho | 1 | **2** |
| Nested jars in `META-INF/lib/` | 1 | 1 — unchanged |

The 36-import figure was a prediction in the plan and is now a **measurement**; it matched. Test
count 23 is above the task's "roughly 16–20" guidance: the KEEP list is 9 tests plus 1 replacement
and the ADD list enumerates 13 distinct behaviours. Compressing further would merge unrelated rules
into single tests and make failures harder to read. The figure is reported, not padded or trimmed.

---

## 6. Verification — real pasted output

Every block below is actual terminal output from this working tree, not a claim.

### 6.1 `mvn -o clean package` and the test suite

```
### mvn -o clean package
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.080 s -- in de.hock.mqtt.MqttPublisherConnectOptionsTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.016 s -- in de.hock.mqtt.integration.MqttSubscribeIntegrationInputsTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.004 s -- in de.hock.mqtt.template.MqttConnectedSystemTemplateNoDesignTimeErrorsTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.008 s -- in de.hock.mqtt.template.MqttConnectedSystemTemplateDescriptorTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.002 s -- in de.hock.mqtt.ProjectStructureTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.011 s -- in de.hock.mqtt.ConnectionConfigValidatorTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.325 s -- in de.hock.mqtt.MqttSubscriberTest
[INFO] Tests run: 23, Failures: 0, Errors: 0, Skipped: 0
[INFO] Building jar: /Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/target/mqtt-broker-connected-system-1.0.6-plugin.jar
[INFO] BUILD SUCCESS
```

**23 tests, 0 failures, 0 errors, 7 classes.** The slowest test is 0.325 s (`MqttSubscriberTest`,
which includes a deliberate ~300 ms deadline wait). No test opens a socket.

### 6.2 Packaging invariants (a)–(l) against the built jar

```
### ls target/*.jar | wc -l
       1
### ls target/*.jar
target/mqtt-broker-connected-system-1.0.6-plugin.jar
### unzip -l $J | grep -c '\.java$'
7
### java sources outside de/hock/mqtt/
0
### grep -cE ' appian-plugin\.xml$'
1
### bare-root appian-plugin.xml
1
### META-INF/lib jars
1
### META-INF/lib paho
1
### connected-systems entries
0
### com/appian/mqtt
0
### test entries
0
### backup entries
0
### validation/ entries
0
### THIRD-PARTY-NOTICES.md at root
1
```

The seven source entries, listed:

```
de/hock/mqtt/ConnectionConfigValidator.java
de/hock/mqtt/MqttPublisher.java
de/hock/mqtt/MqttSubscriber.java
de/hock/mqtt/integration/MqttPublishIntegration.java
de/hock/mqtt/integration/MqttSubscribeIntegration.java
de/hock/mqtt/package-info.java
de/hock/mqtt/template/MqttConnectedSystemTemplate.java
```

Expected vs actual, every invariant: **(a)** package `de.hock.mqtt`, zero `com/appian/mqtt` entries —
0, as expected. **(b)** sources inside the jar at the root mirroring the package — 7 expected, 7
actual, 0 outside `de/hock/mqtt/`. **(c)** bare-root `appian-plugin.xml` — 1 expected, 1 actual.
**(d)** Paho the only nested jar, zero `connected-systems` entries — 1 / 1 / 0, as expected.
**(e)** `<version>1.0.6</version>` three-part, `min="26.9.0.0"` four-part — both as expected (6.3).
**(f)** vendor Hock — as expected (6.3). **(g)** exactly one jar in `target/` — 1, as expected.
**(h)** bytecode major version 61 on all six classes — as expected (6.3). **(i)** bundle clean —
6.5. **(j)** `THIRD-PARTY-NOTICES.md` at the root — 1, as expected. **(k)** nothing from `target/`
and no test entries — 0, as expected. **(l)** all keys stable — 6.3.

### 6.3 Manifest, module registration and bytecode

```
### manifest version/vendor
  key as well. <plugin-info><version> is three-part x.y.z (the AppMarket validator rejects
  four-part); <application-version min=...> is the four-part Appian platform version.
        <vendor name="Hock" url="https://itondemand.com"/>
        <version>1.0.6</version>
        <application-version min="26.9.0.0"/>
### plug-in key
1
### module keys
3
### module nesting
12:  <connected-system-template> module whose nested <integration-template> elements declare the
33:    <connected-system-template key="MqttConnectedSystemTemplate"
36:        <integration-template key="MqttPublishIntegration"
39:        <integration-template key="MqttSubscribeIntegration"
42:    </connected-system-template>
### bytecode major versions
de.hock.mqtt.template.MqttConnectedSystemTemplate               major version: 61
de.hock.mqtt.MqttPublisher                                      major version: 61
de.hock.mqtt.MqttSubscriber                                     major version: 61
de.hock.mqtt.ConnectionConfigValidator                          major version: 61
de.hock.mqtt.integration.MqttPublishIntegration                 major version: 61
de.hock.mqtt.integration.MqttSubscribeIntegration               major version: 61
### class files resolve for each manifest class
de/hock/mqtt/template/MqttConnectedSystemTemplate       1
de/hock/mqtt/integration/MqttPublishIntegration         1
de/hock/mqtt/integration/MqttSubscribeIntegration       1
```

The first two lines of the version grep are manifest *comment* prose about the two formats, not
fields — the three real values follow. The nesting listing (line 12 is also a comment) shows
**both** `<integration-template>` elements at lines 36 and 39, inside the single
`<connected-system-template>` that opens at 33 and closes at 42. All three manifest classes resolve
to a real `.class` entry in the jar.

### 6.4 Expressionability, namespace and code policies

```
### isExpressionable, template
0
### isExpressionable, repo-wide
       7
### isExpressionable, per file non-zero
src/main/java/de/hock/mqtt/integration/MqttPublishIntegration.java:3
src/main/java/de/hock/mqtt/integration/MqttSubscribeIntegration.java:4
### com.appian.mqtt hits (expect none)
(end)
### SDK import lines
      36
### files importing SDK
       4
### code policy grep
src/main/java/de/hock/mqtt/MqttPublisher.java:29: * {@code System.setProperty} call anywhere.
(end)
```

Expected 0 / 3 / 4 per file; actual 0 / 3 / 4. No `com.appian.mqtt` hit anywhere outside `target/`,
`.agents/`, `.kiro/` and `.git/`. The single code-policy hit is the `MqttPublisher` Javadoc sentence
asserting the *absence* of a `System.setProperty` call — prose, not a call. `MqttSubscriber`'s
Javadoc deliberately phrases the same assurance without the token, so it adds no new match.

**The grep is the weaker of the two expressionability checks.** The proof is
`MqttConnectedSystemTemplateDescriptorTest.noConnectedSystemPropertyDescriptorIsExpressionable`,
which renders the descriptor and reads every modifier back through
`PropertyDescriptor.isExpressionable()` — the same object graph Appian's validator walks — asserts
all nine fields were inspected, and names any offender.
`connectedSystemFieldsUseNoListDocumentOrAppianValueDescriptor` covers the forced-flag types. Both
passed (6.1), so requirement 7 of the task is satisfied by test, not by grep.

### 6.5 Resource bundle — orphans and missing keys

```
### key count: expected
      22
### key count: actual
      22
### label keys
16
### diff expected vs actual
(no output above = clean, exit 0)
```

Expected keys are derived programmatically from the source: the nine `public static final String`
constants on the template, the three `INPUT_*` constants on the publish integration, the four on the
subscribe integration, plus `.name` / `.description` for all three class names. The diff is empty, so
there is **no orphan key and no missing key**. Result-map keys (`success`, `status`, `topic`, `qos`,
`messages`, `messageCount`, `payload`, `retained`, `binary`) are inline literals, which is what keeps
the `sed` derivation exact.

### 6.6 Archive recovery still works

```
commit d023a76cd1fad3a485960cb1755d6608283c7908
Author: Andreas Hock <andreas.hock@appian.com>
Date:   Tue Oct 27 14:59:10 2026 +0100
36
```

The complete 1.0.3 build, 36 `.java` files, still recoverable with `git show d023a76:<path>`. No
`git checkout`, branch, commit or reset was performed; only read-only git commands were used, and
the working tree holds the 1.0.6 changes for the user to commit.

### 6.7 What is NOT verified

- **No test exercises a real publish or a real subscribe.** No broker, no socket, no Appian runtime.
- **Whether Appian's own integration timeout fires before a 30-second wait** is unknown to this
  plug-in and untestable here.
- **Whether integration-input design-time errors display** was deliberately not probed.
- **Final confirmation requires the user deploying**
  `target/mqtt-broker-connected-system-1.0.6-plugin.jar` to a live Appian 26.9 instance.

---

## 7. Deploy-and-test checklist

1. **Deploy and check the version.** Copy
   `target/mqtt-broker-connected-system-1.0.6-plugin.jar` into
   `APPIAN_INSTALL/_admin/plugins/` and confirm **Admin Console → Plug-ins** shows **1.0.6**. If it
   still shows 1.0.5, nothing below is meaningful — the new build did not take.
2. **Open the existing MQTT Broker connected system.** Expect **nine fields and no validation
   errors**. The clean page is Part 2's intended change, not a failure to validate.
3. **Run the existing publish integration.** Regression check: it must still deliver, unchanged.
4. **Create a new integration** against the connected system and confirm **Subscribe to MQTT
   Topics** is offered alongside **Publish MQTT Message**.
5. **Subscribe to a quiet filter.** After the wait, expect **success with `messageCount` 0** and an
   empty `messages` list — not an error.
6. **Publish while a subscribe is waiting**, from another client or from the publish integration.
   The message must come back with its `topic`, `payload`, `qos` and `retained` flag.
7. **Confirm a wildcard filter matches** — `sensors/+/temp` or `home/#`. Check that `topic` on each
   returned message is the concrete topic, not the filter.
8. **Point the connected system at a bad port** (for example 99999) and confirm the integration now
   fails with a clear `Invalid configuration` message naming the value, rather than an obscure Paho
   error.

Worth watching while testing 5–7: `tomcat-stdOut.log` for anything that looks like a leaked
connection or a lingering subscription. The teardown path in §4 says there cannot be one; a real
deploy is what confirms it.
