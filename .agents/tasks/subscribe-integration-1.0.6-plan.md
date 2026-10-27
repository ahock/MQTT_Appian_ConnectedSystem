# Implementation Plan — Subscribe (READ) integration + ConfigValidator demotion, 1.0.5 → 1.0.6

Target: Appian 26.9, Java 17, `de.hock.mqtt`, plug-in key `de.hock.mqtt`, SDK core 1.9.1 / client
1.3.0 (both `provided`), Paho 1.2.5 (bundled under `META-INF/lib/`).

Baseline measured in the working tree before planning (HEAD `4bef5ec`, clean):

```
mvn -o test   →  Tests run: 13, Failures: 0, Errors: 0   (4 classes)
target/mqtt-broker-connected-system-1.0.5-plugin.jar
5 .java sources · 25 SDK import lines across 3 files · isExpressionable: template 0, publish 3
16 resource bundle keys (12 labels) · 9 connected system fields · 1 integration module
```

Do not commit, push or branch. The user owns git.

---

## Part 0 — Decisions, resolved up front

### (1) `MqttSubscriber` as a new class, no shared connect helper

**Decision:** add `de.hock.mqtt.MqttSubscriber` beside `MqttPublisher`. Introduce **no** third
"connection helper" class, and change **nothing** in `MqttPublisher`.

**Why:** `MqttSubscriber` lands in the same package as `MqttPublisher`, so it calls the existing
package-private `MqttPublisher.buildConnectOptions(...)` and `MqttPublisher.TIME_TO_WAIT_MILLIS`
directly — no visibility widening, no duplication of the default-from-`null` logic, and the proven
1.0.5 publish path is untouched. A shared helper would move that method into a third file for the
sake of ~15 lines of connect/teardown that genuinely differ anyway: subscribe must register the
message listener as part of `subscribe(filter, qos, listener)`, must `unsubscribe` before
disconnecting, and must not disconnect until the collection window closes. Steering §1's "exactly
one class references Paho" invariant becomes **two** classes (`MqttPublisher`, `MqttSubscriber`) —
that is an unavoidable consequence of adding subscribe and must be recorded, not worked around.

`MqttSubscriber` stays **SDK-free** (pure Paho + JDK), symmetric with `MqttPublisher`, so it takes
the connection values as explicit parameters. Accepted cost: the ~30-line "read nine values off the
connected system state" block is duplicated in `MqttSubscribeIntegration`. The alternative —
a `ConnectionSettings` record with a `from(PropertyState)` factory — is exactly 1.0.3's removed
`MqttConnectionConfig` indirection and would change the signature of the one method that is proven
to work on a live instance. Duplication of mechanical reader calls is the cheaper risk.

### (2) Out-of-range inputs are REJECTED, not clamped

**Decision:** `qos`, `maxWaitSeconds` and `maxMessageCount` are **rejected** with an
`Invalid input` integration error before any broker contact. Blank/absent falls back to the
documented default (`qos` 0, `maxWaitSeconds` 10, `maxMessageCount` 10).

**Why:** it matches what the publish integration already does for `qos` ("QoS must be 0, 1 or 2,
but was 3."), and silent clamping of `maxWaitSeconds` 300 → 30 would make a designer believe they
waited five minutes. Rejecting also guarantees `maxMessageCount >= 1` before it is used as a
`LinkedBlockingQueue` capacity (capacity 0 throws `IllegalArgumentException`).

**Deliberate asymmetry to document:** out-of-range *connected system* session values
(`connectionTimeoutSeconds`, `keepAliveSeconds`) are now also **rejected** at run time, by the
demoted validator (Part 2) — a behaviour change from 1.0.5, where they silently fell back to a
default inside `buildConnectOptions`. The fallbacks inside `buildConnectOptions` stay as a total
function / defence in depth (and keep their two tests), but the validator now gates them, so on the
integration path they are unreachable. Flag this change in the README and the release record.

### (3) Non-UTF-8 payloads: Base64, standard alphabet

**Decision:** `java.util.Base64.getEncoder().encodeToString(bytes)` (RFC 4648 standard alphabet,
with padding), `binary = true`. Text detection is a **strict** UTF-8 decode:
`StandardCharsets.UTF_8.newDecoder().onMalformedInput(REPORT).onUnmappableCharacter(REPORT)` — a
`CharacterCodingException` means binary. Valid UTF-8 → the decoded string, `binary = false`. An
empty payload is an empty string with `binary = false`.

**Why:** same scheme as the reviewed 1.0.3 logic, lossless, and Appian's response surface is
string-typed so raw bytes cannot be returned.

### (4) Subscribe client identifier: configured id + `-sub`

**Decision:** blank `clientIdentifier` → `MqttClient.generateClientId()` (already unique per call).
Non-blank → `clientIdentifier.trim() + "-sub"`, from a `static final String
SUBSCRIBE_CLIENT_ID_SUFFIX = "-sub"` on `MqttSubscriber`.

**Why:** satisfies the requirement that a subscribe cannot take over the publish session's id on the
same broker, with no randomness to explain. Two **documented caveats** for the README's limitations
section: (a) two *concurrent* subscribe calls on the same connected system share `base-sub`, so the
broker will disconnect the earlier one — leave `clientIdentifier` blank to avoid it; (b) a broker
enforcing the MQTT 3.1 23-character client-id limit may reject `base-sub` if `base` is already
20+ characters.

### (5) Demoted validator: `de.hock.mqtt.ConnectionConfigValidator`

**Decision:** delete `src/main/java/de/hock/mqtt/validation/ConfigValidator.java` (and the now-empty
`validation/` directory) and add `src/main/java/de/hock/mqtt/ConnectionConfigValidator.java` with
one public method:

```java
public static String firstError(PropertyState connectionState)   // null when the config is usable
```

**Why the move and the name:** it no longer does anything at design time, so the `validation`
package — which in 1.0.3 held design-time validators — is actively misleading. The root package puts
it beside the two Paho call sites that need its verdict, and the name says *what* it validates (the
connection configuration) instead of *when*. Returning `String`/`null` keeps it to one small class
with no new result type.

### (6) The minimal testable seam for the collection loop

No adapter interface, no injection, no new types. Two package-private statics on `MqttSubscriber`:

```java
static List<Map<String,Object>> drain(BlockingQueue<Map<String,Object>> queue,
                                      int maxMessageCount, long deadlineNanos)
static Map<String,Object> toMessageMap(String topic, MqttMessage message)
```

`drain` takes an **absolute** `System.nanoTime()` deadline, so a test passes a past deadline (must
return empty at once) or a far-future deadline with a pre-filled queue (must return exactly
`maxMessageCount` at once, not wait). `toMessageMap` takes a plain `new MqttMessage(bytes)` —
constructible offline, Paho is a compile-scope dependency and therefore on the test classpath.
`subscribe(...)` wires them: Paho's listener lambda does `queue.offer(toMessageMap(topic, message))`.

Three one-line package-private statics on `MqttSubscribeIntegration` make input defaults observable
without a broker — `qosOf`, `maxWaitSecondsOf`, `maxMessageCountOf`, each delegating to
`MqttConnectedSystemTemplate.intValue(state, KEY, DEFAULT)` — plus
`static String inputError(int qos, int maxWaitSeconds, int maxMessageCount)` (null when all in
range) and `static IntegrationResponse success(List<Map<String,Object>> messages)`.
`IntegrationResponse.isSuccess()`, `getResult()` and `getError().getTitle()/getMessage()` are all
public on core 1.9.1 (verified with `javap`), so the response shape is assertable offline.

### (7) Documents and figures to update in lockstep

| Figure | Current | New (1.0.6) |
|---|---|---|
| `<version>` in `pom.xml` and `appian-plugin.xml` | `1.0.5` | `1.0.6` |
| `<application-version min=...>` | `26.9.0.0` | `26.9.0.0` — **do not touch** |
| Submission artifact | `...-1.0.5-plugin.jar` | `mqtt-broker-connected-system-1.0.6-plugin.jar` |
| `.java` sources in the jar | 5 | **7** |
| Files importing `com.appian.connectedsystems` | 3 | **4** |
| Total SDK import lines | 25 | **36** (template 11, publish 12, subscribe 12, validator 1) |
| `isExpressionable` occurrences, repo-wide | 3 | **7** (template 0, publish 3, subscribe 4) |
| Resource bundle keys | 16 (12 labels) | **22** (16 labels) |
| Tests | 13 in 4 classes | **23 in 7 classes** |
| Connected system fields | 9 | 9 — unchanged |
| Integration modules | 1 | **2** |
| Classes referencing Paho | 1 | **2** |
| Nested jars in `META-INF/lib/` | 1 | 1 — unchanged |
| `info.md` version | `1.0.4` (stale) | **1.0.6** |

The 36-import figure is a prediction from the planned import lists. If the finished code differs by
an import or two, use the **measured** number consistently in all three places that assert it
(steering §1 census, steering §4 block, release record) — do not leave a stale figure.

Documents: `README.md`, `.kiro/steering/appian-appmarket-compliance.md`,
`docs/mqtt-plugin-guide.md`, `info.md`, new `.agents/tasks/subscribe-integration-1.0.6.md`.
`THIRD-PARTY-NOTICES.md` needs no change (no dependency change) but must still ship at the root.

### Offline evidence on `ListTypePropertyDescriptor` — reported, NOT acted on

Measured against `connected-systems-core-1.9.1.jar` with `javap -c`:

1. `ListTypePropertyDescriptor$Builder.<init>` calls
   `PropertyDescriptorBuilder.isExpressionable(true)` and
   `PropertyDescriptorBuilder.displayHint(DisplayHint.EXPRESSION)` — both forced in the constructor.
2. `ListTypePropertyDescriptor$Builder.build()` calls `Guard.isDisplayHintExpression(data)` and, when
   true, throws `IllegalStateException: List property descriptors cannot have display hint of
   expression and not be expressionable` if the flag was cleared. It also rejects a placeholder in
   that state.
3. `PropertyDescriptorBuilder.displayHint(DisplayHint)` **is public**, so the coupling in (2) is in
   principle escapable by setting `displayHint(NORMAL)` *and* `isExpressionable(false)`. Unlike
   `DocumentPropertyDescriptor`, `build()` does **not** call
   `Guard.checkPropertyIsExpressionable`, so no unconditional guard forces the flag. This is new
   detail relative to steering §1's table, which records the type as "effectively no" for a
   connected system — that conclusion still holds for a *default-built* list descriptor.
4. **No evidence either way about integration inputs.** `connected-systems-core-1.9.1.jar` contains
   0 `com/appiancorp/` entries and no validation classes; `connected-systems-client-1.3.0.jar`
   contains only its nine `com/appian/connectedsystems/simplified/sdk/**` classes. The validator
   that rejected the expressionable descriptors was `ConnectedSystemPropertiesValidator`, which is
   platform-side and not inspectable here. Legality of a list descriptor on an *integration input*
   therefore **cannot be proven offline**.

Conclusion: this is not hard evidence of safety. Ship the single `TextPropertyDescriptor`
`topicFilter` as specified; multi-topic stays a deliberate follow-up.

---

## Implementation plan

- [ ] 1. **Bump the version to 1.0.6 in both files.**
      `pom.xml` `<version>1.0.5</version>` → `1.0.6`. `src/main/resources/appian-plugin.xml`
      `<plugin-info><version>1.0.5</version>` → `1.0.6`; leave
      `<application-version min="26.9.0.0"/>` alone. Also refresh the `<description>` in both to
      mention publish **and** subscribe, and the manifest's leading comment block (it currently
      says "MINIMAL SMOKE-TEST BUILD … two runtime entry points").
      Files: `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/pom.xml`,
      `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/resources/appian-plugin.xml`
      Verify: `mvn -o clean package` → BUILD SUCCESS and
      `ls target/*.jar` shows exactly `mqtt-broker-connected-system-1.0.6-plugin.jar`;
      `unzip -p target/mqtt-broker-connected-system-1.0.6-plugin.jar appian-plugin.xml | grep -E '<version>|application-version'`
      shows `1.0.6` and `min="26.9.0.0"`.

- [ ] 2. **Add `de.hock.mqtt.ConnectionConfigValidator` and delete the design-time validator.**
      New file with `private` constructor, package-private `int` constants `PORT_MIN = 1`,
      `PORT_MAX = 65535`, `TIMEOUT_MIN = 1`, `TIMEOUT_MAX = 300`, `KEEP_ALIVE_MIN = 0`,
      `KEEP_ALIVE_MAX = 65535`, and one public method
      `public static String firstError(PropertyState connectionState)` returning `null` when the
      configuration is usable. Rules, evaluated in this order, each reading through the existing
      `MqttConnectedSystemTemplate.textValue` / `.integerValue` helpers:
      blank/`null` `brokerHost` → `"Broker host is required on the MQTT Broker connected system."`;
      `null` `brokerPort` → `"Broker port is required on the MQTT Broker connected system."`;
      port outside 1–65535 → `"Broker port must be between 1 and 65535 on the MQTT Broker connected
      system, but was N."`; supplied `connectionTimeoutSeconds` outside 1–300 → `"Connection timeout
      must be between 1 and 300 seconds on the MQTT Broker connected system, but was N."`; supplied
      `keepAliveSeconds` outside 0–65535 → `"Keep alive interval must be between 0 and 65535 seconds
      on the MQTT Broker connected system, but was N."`. A `null` `connectionState` returns the
      broker-host message. It imports **only** `PropertyState` (no `PropertyPath`, no
      `clearErrorsAtPath`, no `setErrorAtPath`). Its Javadoc must **not** contain the literal token
      `isExpressionable`, or the steering §1 grep stops being per-file exact. Then delete
      `src/main/java/de/hock/mqtt/validation/ConfigValidator.java` and the empty `validation/`
      directory.
      Files: create
      `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/ConnectionConfigValidator.java`;
      delete `.../src/main/java/de/hock/mqtt/validation/ConfigValidator.java`
      Verify: `mvn -o clean package` fails to compile until step 3 removes the template's call — run
      steps 2 and 3 together and verify with `mvn -o test` at the end of step 4.

- [ ] 3. **Strip design-time validation out of the connected system template.**
      In `MqttConnectedSystemTemplate.getConfiguration`, delete the
      `ConfigValidator.validate(configuration.toConfiguration().getRootState());` line and the
      `import de.hock.mqtt.validation.ConfigValidator;` import. Keep every `isRequired(...)` call,
      all nine descriptors in their current order, and all four static readers (`textValue`,
      `integerValue`, `booleanValue`, `intValue`) — the integrations and the new validator all use
      them. Rewrite the class Javadoc's "Design-time validation runs on every render" paragraph to
      record the finished experiment: Appian discards errors attached through `setErrorAtPath`, so
      `isRequired` is the only design-time guard available and configuration validation now runs at
      invocation time in both integrations via `ConnectionConfigValidator`. Keep the paragraph that
      explains why no field here may be expressionable, and keep its deliberate avoidance of the
      literal token `isExpressionable`.
      Files: `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java`
      Verify: with step 4, `mvn -o test`.

- [ ] 4. **Route the publish integration's configuration guards through the new validator, and
      replace the four design-time validation tests.**
      In `MqttPublishIntegration.execute`, delete the inline `brokerHost`-blank and
      `brokerPort`-range guards and insert, after the `topic` guard:
      `String configError = ConnectionConfigValidator.firstError(connection); if (configError != null)
      return error("Invalid configuration", configError);`. Keep the `topic` guard (title
      `Invalid input`) and the `qos` guard (title `Invalid input`) exactly as they are. Guard order
      becomes: topic → configuration → qos. Update the class Javadoc: four guards become three plus
      the shared configuration check, and the error-title list gains `Invalid configuration`.
      Then delete
      `src/test/java/de/hock/mqtt/template/MqttConnectedSystemTemplateValidationTest.java` and
      replace its coverage with two new classes:
      `src/test/java/de/hock/mqtt/template/MqttConnectedSystemTemplateNoDesignTimeErrorsTest.java`
      — one test, `brandNewConnectedSystemRendersWithNoAttachedErrors`: render
      `ConfigurationDescriptor.builder().build()` through `getConfigurationDescriptor`, assert the
      root state is non-null, that `getErrorsAtPath` is null-or-empty for **all nine** field keys,
      and that `getRootState().getErrors()` is empty. Its Javadoc states that this is the intended
      1.0.6 behaviour, replacing 1.0.5's deliberate ungated probe.
      `src/test/java/de/hock/mqtt/ConnectionConfigValidatorTest.java` — four tests, one per demoted
      rule, each building a `PropertyState` by rendering the real template and calling
      `setValueAtPath` (the pattern the deleted test used): `missingOrBlankBrokerHostIsReported`,
      `missingOrOutOfRangeBrokerPortIsReported`,
      `outOfRangeConnectionTimeoutOrKeepAliveIsReported` (and keep-alive `0` is accepted),
      `aValidConfigurationWithBlankOptionalsReportsNothing` (returns `null`).
      Files: `.../src/main/java/de/hock/mqtt/integration/MqttPublishIntegration.java`; delete
      `.../src/test/java/de/hock/mqtt/template/MqttConnectedSystemTemplateValidationTest.java`;
      create `.../src/test/java/de/hock/mqtt/template/MqttConnectedSystemTemplateNoDesignTimeErrorsTest.java`
      and `.../src/test/java/de/hock/mqtt/ConnectionConfigValidatorTest.java`
      Verify: `mvn -o test` → `Tests run: 14, Failures: 0, Errors: 0` (13 − 4 + 1 + 4), with the five
      kept descriptor tests and both publisher tests still passing unchanged.

- [ ] 5. **Add `de.hock.mqtt.MqttSubscriber` — the bounded collection runtime.**
      `public final class MqttSubscriber` with a private constructor, `static final long
      POLL_SLICE_MILLIS = 200L` and `static final String SUBSCRIBE_CLIENT_ID_SUFFIX = "-sub"`, and
      three methods:
      `public static List<Map<String,Object>> subscribe(String brokerHost, int brokerPort, String
      clientIdentifier, String username, char[] password, String topicFilter, int qos, int
      maxWaitSeconds, int maxMessageCount, Integer connectionTimeoutSeconds, Integer
      keepAliveSeconds, Boolean cleanSession, Boolean autoReconnectEnabled) throws MqttException` —
      derives the client id per decision (4); builds options via
      `MqttPublisher.buildConnectOptions(username, password, connectionTimeoutSeconds,
      keepAliveSeconds, cleanSession, autoReconnectEnabled)` (same-package access, no visibility
      change); creates `new LinkedBlockingQueue<Map<String,Object>>(maxMessageCount)`; creates
      `new MqttClient("tcp://" + brokerHost + ":" + brokerPort, clientId, new MemoryPersistence())`
      and `client.setTimeToWait(MqttPublisher.TIME_TO_WAIT_MILLIS)`; then
      `client.connect(options)`, `client.subscribe(topicFilter, qos, (topic, message) ->
      queue.offer(toMessageMap(topic, message)))` — the three-argument Paho overload, which registers
      the listener as part of the subscription so nothing between SUBACK and the first poll is lost —
      sets a `boolean subscribed = true`, and returns `drain(queue, maxMessageCount, System.nanoTime()
      + TimeUnit.SECONDS.toNanos(maxWaitSeconds))`. A `finally` block does, each in its own
      `try`/`catch (MqttException ignored)`: `client.unsubscribe(topicFilter)` when `subscribed`,
      `client.disconnect()` when `client.isConnected()`, then `client.close()`. No executor, no
      thread, no `MqttCallback`, no `System.setProperty`, no logging, no `printStackTrace`.
      `static List<Map<String,Object>> drain(BlockingQueue<Map<String,Object>> queue, int
      maxMessageCount, long deadlineNanos)` — the reviewed 1.0.3 loop: while `collected.size() <
      maxMessageCount`, compute `remainingNanos = deadlineNanos - System.nanoTime()`, break when
      `<= 0`, `poll(min(POLL_SLICE_MILLIS, toMillis(remaining) + 1), MILLISECONDS)`, on
      `InterruptedException` re-set the interrupt flag and break, and on a non-null message add it
      then `queue.drainTo(collected, maxMessageCount - collected.size())`.
      `static Map<String,Object> toMessageMap(String topic, MqttMessage message)` — total, never
      throws (the Paho listener must not throw): a `LinkedHashMap` with keys in the order `topic`,
      `payload`, `qos`, `retained`, `binary`, using the strict-UTF-8-then-Base64 rule of decision (3)
      via a `private static String tryDecodeUtf8(byte[])`. A `null` payload is treated as `new
      byte[0]`.
      Class Javadoc: the blocking-wait warning (up to `maxWaitSeconds` on an Appian request thread,
      capped at 30, and Appian's own integration timeout may fire first), the teardown guarantee, the
      `-sub` suffix rationale, the `MemoryPersistence` reason, plain `tcp://` only, and that this is
      now the **second** Paho call site. The Javadoc must not contain the token `isExpressionable`.
      Files: create
      `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/MqttSubscriber.java`
      Verify: `mvn -o test` → `Tests run: 14` still green (no new tests yet, nothing regressed).

- [ ] 6. **Add `MqttSubscriberTest` — the no-broker runtime proof.**
      `src/test/java/de/hock/mqtt/MqttSubscriberTest.java`, three tests:
      `drainStopsAsSoonAsTheMessageCountIsReached` — pre-fill a `LinkedBlockingQueue` with 5 maps,
      `maxMessageCount` 3, deadline `System.nanoTime() + SECONDS.toNanos(30)`; assert exactly 3
      returned, in order, and that the call took well under the deadline (assert elapsed < 5 s, a
      loose bound that still fails a loop that waits out the window).
      `drainStopsWhenTheDeadlineHasPassed` — empty queue, deadline already in the past → empty list
      returned, elapsed < 1 s; then an empty queue with a deadline ~300 ms out → empty list, elapsed
      < 5 s.
      `aUtf8PayloadDecodesToTextAndANonUtf8PayloadIsBase64Flagged` — drive `toMessageMap` with
      `new MqttMessage("Grüße/日本".getBytes(UTF_8))` (set `qos` 1 and `retained` true) and assert
      `topic`/`payload`/`qos`/`retained` round-trip with `binary == false`; then
      `new MqttMessage(new byte[] {(byte)0xC3, (byte)0x28, (byte)0xFF})` and assert `binary == true`
      with `payload` equal to `Base64.getEncoder().encodeToString(...)` of the same bytes; also
      assert an empty payload yields `""` with `binary == false`.
      Files: create `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/test/java/de/hock/mqtt/MqttSubscriberTest.java`
      Verify: `mvn -o test` → `Tests run: 17`, all passing, no test taking more than a few seconds.

- [ ] 7. **Add `de.hock.mqtt.integration.MqttSubscribeIntegration`.**
      `@TemplateId(name = "MqttSubscribeIntegration")`,
      `@IntegrationTemplateType(IntegrationTemplateRequestPolicy.READ)`, extends
      `SimpleIntegrationTemplate`. Public input-key constants, which must be the **only**
      `public static final String` members on the class (the steering §4 bundle check greps
      `public static final String INPUT_[A-Z_]+ *= *"([A-Za-z]+)"`; result-map keys stay inline
      literals): `INPUT_TOPIC_FILTER = "topicFilter"`, `INPUT_QOS = "qos"`,
      `INPUT_MAX_WAIT_SECONDS = "maxWaitSeconds"`, `INPUT_MAX_MESSAGE_COUNT = "maxMessageCount"`.
      Package-private `int` constants: `DEFAULT_QOS = 0`, `DEFAULT_MAX_WAIT_SECONDS = 10`,
      `DEFAULT_MAX_MESSAGE_COUNT = 10`, `MAX_WAIT_SECONDS_MIN = 1`, `MAX_WAIT_SECONDS_MAX = 30`,
      `MAX_MESSAGE_COUNT_MIN = 1`, `MAX_MESSAGE_COUNT_MAX = 100`,
      `MAX_ERROR_MESSAGE_LENGTH = 500`.
      `getConfiguration(...)` calls `integrationConfiguration.setProperties(...)` with exactly four
      descriptors in this display order, each ending `.isExpressionable(true).build()` — **four**
      occurrences of that call in the file and the Javadoc must avoid the literal token so the
      per-file count stays exactly 4:
      1. `TextPropertyDescriptor.builder().key(INPUT_TOPIC_FILTER).label("Topic Filter")
         .instructionText("A single MQTT topic filter. Wildcards are supported: + matches one level
         (sensors/+/temp) and # matches the rest of the tree (home/#).").isRequired(true)
         .isExpressionable(true).build()`
      2. `IntegerPropertyDescriptor.builder().key(INPUT_QOS).label("QoS")
         .instructionText("Quality of Service 0, 1 or 2. Defaults to 0 when blank.")
         .placeholder("0").isRequired(false).isExpressionable(true).build()`
      3. `IntegerPropertyDescriptor.builder().key(INPUT_MAX_WAIT_SECONDS).label("Max Wait (seconds)")
         .instructionText("How long to collect messages, 1 to 30 seconds. Defaults to 10 when blank.
         This blocks an Appian request thread for the whole wait.").placeholder("10")
         .isRequired(false).isExpressionable(true).build()`
      4. `IntegerPropertyDescriptor.builder().key(INPUT_MAX_MESSAGE_COUNT).label("Max Message Count")
         .instructionText("Stop as soon as this many messages have been collected, 1 to 100.
         Defaults to 10 when blank.").placeholder("10").isRequired(false).isExpressionable(true)
         .build()`
      No `ListTypePropertyDescriptor`, no `Choice` list, no `DocumentPropertyDescriptor`, no
      `AppianValuePropertyDescriptor`.
      Package-private seams per decision (6): `static int qosOf(PropertyState)`,
      `static int maxWaitSecondsOf(PropertyState)`, `static int maxMessageCountOf(PropertyState)`
      (each one line over `MqttConnectedSystemTemplate.intValue`),
      `static String inputError(int qos, int maxWaitSeconds, int maxMessageCount)` returning `null`
      or `"QoS must be 0, 1 or 2, but was N."` / `"Max wait must be between 1 and 30 seconds, but was
      N."` / `"Max message count must be between 1 and 100, but was N."`, and
      `static IntegrationResponse success(List<Map<String,Object>> messages)` building a
      `LinkedHashMap` with `messages` then `messageCount` (`messages.size()`) and returning
      `IntegrationResponse.forSuccess(result).build()`.
      `execute(...)` order, mirroring publish: read the input state and the connection state; blank
      `topicFilter` → `error("Invalid input", "Topic filter is required.")`;
      `ConnectionConfigValidator.firstError(connection)` non-null →
      `error("Invalid configuration", …)`; `inputError(...)` non-null →
      `error("Invalid input", …)`; then read the nine connected system values with the same
      `MqttConnectedSystemTemplate` readers the publish integration uses, convert the password with
      `toCharArray()`, call `MqttSubscriber.subscribe(...)` inside
      `try { … } catch (MqttException | RuntimeException e) { return error("MQTT subscribe failed",
      "Failed to subscribe to filter '…' on MQTT broker host:port at QoS n: " + e.getMessage()); }`,
      and return `success(messages)` — **including when the list is empty**. Add the same private
      `error(String,String)` truncating helper the publish integration has (deliberately duplicated:
      no cross-integration coupling and no extra file for six lines). No secret may reach a message.
      Register the module in the manifest as a **second** `<integration-template>` nested inside the
      existing `<connected-system-template>`:
      `<integration-template key="MqttSubscribeIntegration" name="MqttSubscribeIntegration"
      class="de.hock.mqtt.integration.MqttSubscribeIntegration"/>`. Leave the connected system key
      and the publish key untouched.
      Add six keys to the bundle (16 → 22): `MqttSubscribeIntegration.name=Subscribe to MQTT Topics`,
      `MqttSubscribeIntegration.description=…`, and `.topicFilter.label=Topic Filter`,
      `.qos.label=QoS`, `.maxWaitSeconds.label=Max Wait (seconds)`,
      `.maxMessageCount.label=Max Message Count`. Update the bundle's header comment (22 keys, 16 of
      them labels, three template classes).
      Files: create
      `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/integration/MqttSubscribeIntegration.java`;
      edit `.../src/main/resources/appian-plugin.xml` and
      `.../src/main/resources/resources_en_US.properties`
      Verify: `mvn -o clean package` → BUILD SUCCESS; `mvn -o test` → `Tests run: 17` still green;
      `unzip -p target/mqtt-broker-connected-system-1.0.6-plugin.jar appian-plugin.xml | grep -c integration-template`
      → 2 opening elements inside the one `<connected-system-template>`.

- [ ] 8. **Add `MqttSubscribeIntegrationInputsTest` — defaults, rejection, and the empty-result
      contract, all without a broker.**
      `src/test/java/de/hock/mqtt/integration/MqttSubscribeIntegrationInputsTest.java`, four tests.
      Render the integration descriptor the way the existing tests do
      (`new MqttSubscribeIntegration().getConfigurationDescriptor(empty, empty, new PropertyPath(),
      null)`) and mutate it with `setValueAtPath` where a value is needed.
      `absentInputsFallBackToTheDocumentedDefaults` — on a freshly rendered root state,
      `qosOf` → 0, `maxWaitSecondsOf` → 10, `maxMessageCountOf` → 10; then set 2 / 25 / 50 and assert
      they read back.
      `outOfRangeQosMaxWaitAndMaxMessageCountAreRejected` — `inputError` is non-null for qos −1 and
      3, for wait 0 and 31, and for count 0 and 101, each message naming the offending value; and
      `null` for (0, 10, 10), (2, 30, 100) and (1, 1, 1).
      `anEmptyCollectionIsReportedAsSuccessWithMessageCountZero` — `success(List.of())` →
      `isSuccess()` true, `getResult().get("messageCount")` equals 0, `getResult().get("messages")`
      an empty list; and with two maps → `messageCount` 2.
      `executeWithoutATopicFilterReportsTheMissingInputAndContactsNoBroker` — drive the **public**
      `execute(ConfigurationDescriptor, ConfigurationDescriptor, ExecutionContext)` overload
      inherited from `SimpleIntegrationTemplate` (verified present on client 1.3.0 with `javap`;
      `SimpleConfiguration`'s own constructor is private and its `from(...)` factory needs a
      `TypePropertyFactory`, so this overload is the correct seam) with
      `ConfigurationDescriptor.builder().build()` twice and a `null` context. Assert `isSuccess()`
      false, `getError().getTitle()` equals `"Invalid input"`, and that the message mentions the
      topic filter. This also proves the first guard runs before any broker contact.
      Files: create `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/test/java/de/hock/mqtt/integration/MqttSubscribeIntegrationInputsTest.java`
      Verify: `mvn -o test` → `Tests run: 21`, all passing; no test opens a socket.

- [ ] 9. **Extend `MqttConnectedSystemTemplateDescriptorTest` with the subscribe descriptor
      contracts.**
      Add a `renderSubscribe()` helper beside `renderPublish()` and two tests, bringing the class to
      7: `subscribeIntegrationRendersItsRootStateAndItsFourExpressionableInputs` — non-null root
      state with a resolvable root type, `getPropertyKeys()` equal to the ordered
      `List.of(INPUT_TOPIC_FILTER, INPUT_QOS, INPUT_MAX_WAIT_SECONDS, INPUT_MAX_MESSAGE_COUNT)`, and
      every one of the four reading back `true` from `PropertyDescriptor.isExpressionable()` (the
      deliberate asymmetry with the connected system, asserted per key);
      `subscribeInputsUseNoListDocumentOrAppianValueDescriptor` — no input is a
      `ListTypePropertyDescriptor`, `DocumentPropertyDescriptor` or
      `AppianValuePropertyDescriptor`, with a comment recording that this is a **precaution** on
      integration inputs, not a known platform rule. Leave the five existing tests byte-identical —
      in particular `noConnectedSystemPropertyDescriptorIsExpressionable` must keep reading modifiers
      back through `PropertyDescriptor.isExpressionable()` and keep asserting 9 inspected fields.
      Files: `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/test/java/de/hock/mqtt/template/MqttConnectedSystemTemplateDescriptorTest.java`
      Verify: `mvn -o test` → `Tests run: 23, Failures: 0, Errors: 0` across seven classes:
      `ProjectStructureTest` 2, `MqttConnectedSystemTemplateDescriptorTest` 7,
      `MqttConnectedSystemTemplateNoDesignTimeErrorsTest` 1, `ConnectionConfigValidatorTest` 4,
      `MqttPublisherConnectOptionsTest` 2, `MqttSubscriberTest` 3,
      `MqttSubscribeIntegrationInputsTest` 4. (23 is above the "roughly 16–20" guidance because the
      KEEP list is 9 tests plus 1 replacement and the ADD list enumerates 13 distinct behaviours;
      compressing further would merge unrelated rules into single tests and make failures harder to
      read. Report the figure, do not pad or trim it.)

- [ ] 10. **Rewrite the README for 1.0.6.**
      Files: `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/README.md`
      - Title/intro: 1.0.6 adds the Subscribe (READ) integration and demotes `ConfigValidator` to a
        run-time check. Keep the 1.0.4/1.0.5 history short.
      - Version, artifact name (`...-1.0.6-plugin.jar`), the **seven** `.java` sources, and the
        module table (add the `READ` row: `MqttSubscribeIntegration` / **Subscribe to MQTT Topics** /
        `de.hock.mqtt.integration.MqttSubscribeIntegration`).
      - "What is still deliberately removed": drop the subscribe bullet; keep TLS/certificates, LWT,
        reconnect attempt tuning, diagnostics, the `retained` publish input, and the
        `git show d023a76:…` recovery route. Add a bullet that **multi-topic subscribe** is a
        deliberate follow-up, with the forced-flag-builder reasoning from decision (0) and the
        wildcard workaround.
      - New "Using the Subscribe integration" section: the four inputs with type, required/optional,
        default and range; all four expressionable; the output shape (`messages` list of maps with
        `topic`, `payload`, `qos`, `retained`, `binary`, plus `messageCount`); Base64 for
        non-UTF-8 payloads with a note on decoding it in an expression; **empty result is success
        with `messageCount` 0, not an error**; the error titles `Invalid input`,
        `Invalid configuration`, `MQTT subscribe failed`; wildcard examples `sensors/+/temp` and
        `home/#`; and an example call.
      - A clearly flagged **blocking-wait caveat**: a subscribe occupies an Appian request thread for
        up to `maxWaitSeconds`, the plug-in cap is 30 seconds and will not be raised, Appian applies
        its own integration timeout independently, and if Appian times out first the designer sees an
        Appian error rather than an empty result.
      - The client-id caveats from decision (4).
      - Rewrite "Design-time validation: probe run, and the answer is no": the probe is finished,
        the answer was no, `ConfigValidator` is **gone** and its rules now live in
        `de.hock.mqtt.ConnectionConfigValidator`, called at invocation time by both integrations;
        `isRequired` is the only design-time guard; a brand-new connected system now opens clean.
        Delete the `clearErrorsAtPath` paragraph and the
        `aCorrectedValueClearsThePreviouslyAttachedError` reference (that test is gone).
      - State the **behaviour change** from decision (2): an out-of-range `connectionTimeoutSeconds`
        or `keepAliveSeconds` on the connected system now fails the integration with
        `Invalid configuration` instead of silently falling back.
      - Known limitations: replace the 13-tests-in-four-classes block with 23 in seven classes and
        the one-line purpose of each class; keep "nothing exercises a real publish or subscribe".
      Verify: `grep -c '1\.0\.5' README.md` → 0 except where 1.0.5 is named as history;
      `grep -n 'ConfigValidator' README.md` → only in the demotion narrative.

- [ ] 11. **Update the steering file in lockstep.** Every figure in the table of decision (7), plus:
      Files: `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/.kiro/steering/appian-appmarket-compliance.md`
      - Scope note: a 1.0.6 paragraph — the subscribe integration arrives, `ConfigValidator` is
        demoted and renamed, the connected system is untouched at nine fields, design-time validation
        is **gone**.
      - §1 namespace: import census `25, across 3 files` → `36 across 4 files`; the `grep -rl` figure
        `3` → `4`.
      - §1 sources-in-jar: `5 sources` → **7**, with the new list
        (`package-info`, `MqttPublisher`, `MqttSubscriber`, `ConnectionConfigValidator`,
        `template/MqttConnectedSystemTemplate`, `integration/MqttPublishIntegration`,
        `integration/MqttSubscribeIntegration`). Replace the "no `validation/package-info.java`" note
        with the fact that the `validation` package no longer exists.
      - §1 `META-INF/lib`: "exactly one class still references Paho" → **two**, `MqttPublisher` and
        `MqttSubscriber`, and a `NoClassDefFoundError` now names whichever one ran.
      - §1 version table: `1.0.5` → `1.0.6`.
      - §1 expressionability: repo-wide total `3` → **7**; the per-file block gains
        `integration/MqttSubscribeIntegration.java:4`; extend the "Javadoc avoids the literal token"
        rule to `MqttSubscriber`, `ConnectionConfigValidator` and `MqttSubscribeIntegration`.
      - §1 or §2, clearly labelled **precaution, not a known rule**: forced-flag descriptor builders
        (`ListType`, `Document`, `AppianValue`, `Folder`, `Timestamp`) are avoided on *integration*
        inputs too. Record the `javap` findings of decision (0) — the forced constructor flags, the
        coupled `build()` guard, the public `displayHint` escape hatch, and that no platform-side
        integration-input validator exists in either jar, so legality there is unproven offline.
      - §2: artifact name → 1.0.6; `Tests run: 13` → **23**; "all four test classes" → **seven**.
      - §3: the guaranteed-teardown and no-background-thread bullets now cover `MqttSubscriber` as
        well; note the bounded queue and `offer`-based drop so Paho's callback thread never blocks.
      - §4: rewrite the whole verification block to the new expected values — jar name, 7 sources,
        `javap` lines for `de.hock.mqtt.MqttSubscriber`, `de.hock.mqtt.ConnectionConfigValidator` and
        `de.hock.mqtt.integration.MqttSubscribeIntegration` (drop
        `de.hock.mqtt.validation.ConfigValidator`), the two-line
        `grep -rc isExpressionable … | grep -v ':0$'` expectation, the module-key grep widened to
        `Mqtt(ConnectedSystemTemplate|PublishIntegration|SubscribeIntegration)` expecting **3**, and
        the bundle check with a third `sed` block for `MqttSubscribeIntegration` (22 keys, 16 labels).
      - §5: baseline paragraph gains "1.0.6 built on the working 1.0.5 baseline, not yet deployed";
        item 4's inventory → the 23 tests in seven classes; item 7 gains the consequence — the dead
        code was removed at 1.0.6 and the rules now run at invocation time in
        `de.hock.mqtt.ConnectionConfigValidator`; item 8 keeps its three measured facts as history
        for the archived build and records that the clear/attach machinery was deleted.
      - §8: add `subscribe-integration-1.0.6-plan.md` and `subscribe-integration-1.0.6.md` as the new
        "start here" entries.
      Verify: run the rewritten §4 block end to end and confirm every stated expectation matches the
      real output, pasting both.

- [ ] 12. **Write the release record and fix the two remaining documents.**
      Files: create
      `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/.agents/tasks/subscribe-integration-1.0.6.md`;
      edit `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/info.md` and
      `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/docs/mqtt-plugin-guide.md`
      - Release record: the seven decisions of Part 0 with their reasons; every file added, changed
        and deleted; the four subscribe inputs and the output shape; the blocking-wait risk and why
        the cap is 30; the teardown path proving nothing outlives the request; the `ConfigValidator`
        demotion and what replaced each of its four rules; the behaviour change on out-of-range
        session values; the figure table; the pasted verification output; and the deploy-and-test
        checklist below.
      - `info.md`: version `1.0.4` → `1.0.6`, and the Contents list gains the subscribe integration;
        drop "design-time validation … removed" in favour of "configuration validated at run time".
      - `docs/mqtt-plugin-guide.md`: update the top banner to 1.0.6 — subscribe now **does** ship,
        but as a single `topicFilter` with `maxWaitSeconds` 1–30 and `maxMessageCount` 1–100, not the
        `topicFilters` list with 1–300 / 1–1000 described below — and insert a short "1.0.6
        differences" note directly under the `## Subscribe to MQTT Topics` heading listing those
        three differences and pointing at the README. Leave the rest of the historical content as it
        is; it stays accurate about the archived build.
      Verify: `grep -rn '1\.0\.4\|1\.0\.5' info.md docs/mqtt-plugin-guide.md` returns only
      intentional historical references.

- [ ] 13. **Run the full pre-submission sweep and paste real output into the release record.**
      Commands (run from the repo root, `J=target/mqtt-broker-connected-system-1.0.6-plugin.jar`):
      `mvn -o clean package`; `mvn -o test`; `ls target/*.jar | wc -l` → 1;
      `unzip -l $J | grep -c '\.java$'` → 7;
      `unzip -l $J | grep '\.java$' | grep -vc 'de/hock/mqtt/'` → 0;
      `unzip -l $J | awk '{print $NF}' | grep -c '^appian-plugin.xml$'` → 1;
      `unzip -l $J | grep -c 'META-INF/lib/.*\.jar$'` → 1 and
      `… | grep -c 'META-INF/lib/org.eclipse.paho'` → 1;
      `unzip -l $J | grep -ci 'connected-systems'` → 0;
      `unzip -l $J | grep -c 'com/appian/mqtt'` → 0; `unzip -l $J | grep -ci test` → 0;
      `unzip -l $J | grep -ci backup` → 0;
      `unzip -l $J | awk '{print $NF}' | grep -c '^THIRD-PARTY-NOTICES.md$'` → 1;
      `unzip -l $J | grep -c 'validation/'` → 0;
      `unzip -p $J appian-plugin.xml | grep -E '<version>|application-version|<vendor'` → `1.0.6`,
      `min="26.9.0.0"`, `name="Hock"`;
      `unzip -p $J appian-plugin.xml | grep -cE 'key="Mqtt(ConnectedSystemTemplate|PublishIntegration|SubscribeIntegration)"'`
      → 3, and the two `<integration-template>` elements are nested inside the single
      `<connected-system-template>`;
      `javap -verbose -cp target/classes <each of the five classes> | grep 'major version'` → 61 for
      all;
      `grep -rc isExpressionable src/main/java/ | grep -v ':0$'` → exactly two lines
      (`MqttPublishIntegration.java:3`, `MqttSubscribeIntegration.java:4`) and
      `grep -c isExpressionable src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java`
      → 0;
      `grep -rn 'com\.appian\.mqtt' . | grep -v '/target/' | grep -v '/.agents/' | grep -v '/.kiro/' | grep -v '/.git/'`
      → no hits; `grep -rh 'import com.appian.connectedsystems' src/main/java/ | wc -l` → 36 and
      `grep -rl 'com\.appian\.connectedsystems' src/main/java | wc -l` → 4;
      `grep -rn 'System\.out\|System\.err\|printStackTrace\|System\.setProperty\|java\.util\.logging' src/main/java/`
      → only the `MqttPublisher` Javadoc sentence asserting the absence of a `System.setProperty`
      call (add no new prose match in `MqttSubscriber`);
      and the §4 resource-bundle diff extended with the third `sed` block → no output.
      Also confirm by reading the code that the teardown path (unsubscribe → disconnect → close in
      `finally`, `offer` into a bounded queue, no executor, no `MqttCallback`, no thread creation)
      leaves nothing alive after `subscribe` returns, and state plainly in the record that final
      confirmation requires the user deploying
      `target/mqtt-broker-connected-system-1.0.6-plugin.jar` to a live Appian 26.9 instance.
      Verify: every expected value above matches the actual output; any mismatch is fixed, not
      documented around.

---

## Deploy-and-test checklist (for the release record and the final report)

1. Deploy `target/mqtt-broker-connected-system-1.0.6-plugin.jar` and confirm **Admin Console →
   Plug-ins** shows **1.0.6**. If it still shows 1.0.5, nothing below is meaningful.
2. Open the existing MQTT Broker connected system: **nine fields, and no validation errors** — the
   intended Part 2 change.
3. Run the existing publish integration — regression check, it must still deliver.
4. Create a new integration against the connected system and confirm **Subscribe to MQTT Topics**
   is offered alongside **Publish MQTT Message**.
5. Subscribe to a quiet filter: after the wait, expect **success with `messageCount` 0**, not an
   error.
6. Publish from another client (or the publish integration) while a subscribe is waiting: the
   message comes back with its `topic`, `payload`, `qos` and `retained` flag.
7. Confirm a wildcard filter matches — `sensors/+/temp` or `home/#`.
8. Point the connected system at a bad port and confirm the integration now fails with a clear
   `Invalid configuration` message instead of an obscure Paho error.

## Assumptions and open risks

- **Appian's own integration timeout is unknown to this plug-in.** With `maxWaitSeconds` 30 the
  platform may time out first; the README says so and the cap stays at 30.
- **Whether integration-input errors display at design time is still untested** and deliberately not
  probed in this release.
- **The 36-import figure is a prediction**, not a measurement; step 13 measures it and all three
  places that assert it must carry the measured number.
- **Paho client-id length** on strict brokers, and concurrent-subscribe id collision, are documented
  caveats rather than code changes.
