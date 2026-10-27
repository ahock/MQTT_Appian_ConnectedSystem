# Review — 1.0.5 (design-time validation probe + four wired session fields)

Reviewer pass over the uncommitted working tree. Date: 2026-10-27.
Verdict: **APPROVED** (see `validation-1.0.5-review.json`).

Scope read: `git status` / `git diff` for the eleven modified files, the four untracked additions
(`src/main/java/de/hock/mqtt/validation/ConfigValidator.java`,
`src/test/java/de/hock/mqtt/MqttPublisherConnectOptionsTest.java`,
`src/test/java/de/hock/mqtt/template/MqttConnectedSystemTemplateValidationTest.java`, plus the two
task records), the plan, the release record, and the steering file in its modified state. Build and
test suites were not re-run; the recorded evidence in
`.agents/tasks/validation-and-config-fields-1.0.5.md` §2 was read instead.

## Findings against the ten judging criteria

**1. Expressionability — clean.** `MqttConnectedSystemTemplate` never calls the expressionable
builder method (`grep -c` on the file: `0`; repo-wide `3`, all three on
`MqttPublishIntegration`, spot-checked). The nine descriptors are
`TextPropertyDescriptor` ×3, `IntegerPropertyDescriptor` ×3, `BooleanPropertyDescriptor` ×2,
`EncryptedTextPropertyDescriptor` ×1 — no Document, AppianValue, List, Folder or Timestamp type, so
no builder forces the flag on. `noConnectedSystemPropertyDescriptorIsExpressionable` still reads the
flag back off the rendered descriptor through `PropertyDescriptor.isExpressionable()` and now
asserts `inspected == 9`; `connectedSystemFieldsUseNoListDocumentOrAppianValueDescriptor` iterates
all nine and rejects the three forced types. The record (§2.3) shows the test failing when
`.isExpressionable(true)` was temporarily added to `cleanSession`, naming
`cleanSession (BooleanPropertyDescriptor)` — so the new Boolean type is covered by the real check,
not just by the grep. The three publish inputs keep `.isExpressionable(true)` and
`publishIntegrationRendersItsRootStateAndItsThreeExpressionableInputs` asserts each one is still
expressionable.

**2. SDK shape unchanged.** `MqttConnectedSystemTemplate extends SimpleConnectedSystemTemplate`,
`MqttPublishIntegration extends SimpleIntegrationTemplate`; validation was added inside the existing
`getConfiguration` override, not by dropping to `ConnectedSystemTemplate` /`IntegrationTemplate`.
`brandNewConnectedSystemRendersANonNullRootStateWithAResolvableRootType` is untouched. The pom diff
touches only `<version>`, so core 1.9.1 / client 1.3.0 at scope `provided` are unchanged.

**3. The probe is ungated, as approved.** `ConfigValidator.validate(...)` is called unconditionally
after `setProperties(...)` in `getConfiguration` — no first-render gate, no freshness signal, no
"untouched" check, no `setValue` seeding anywhere in the template or the validator. Rationale is
recorded in three places as required: `ConfigValidator`'s class Javadoc (first paragraph, including
why a gate would make a clean page ambiguous), the template Javadoc ("Design-time validation runs on
every render, including the first — deliberately"), `README.md`
("Design-time validation: a deliberate probe"), and steering §5 item 7, which is rewritten as the
probe with the verbatim settling observation. `package-info.java` was corrected too — it had claimed
validation was removed. `brandNewConnectedSystemReportsRequiredErrorsOnBrokerHostAndBrokerPortOnly`
pins the behaviour and its Javadoc says plainly that it exists to stop someone "fixing" it. No
finding here; nothing asks for suppression.

**4. Error clearing — correct API, correct order, tested.** `PropertyState.clearErrorsAtPath(
PropertyPath)` is confirmed present on core 1.9.1 by the `javap` output quoted in the record (§1.2)
and the plan (A1), bottoming out in `setErrorsAtPath(path, Collections.emptyList())`.
`ConfigValidator.validate` clears all four validated paths (`brokerHost`, `brokerPort`,
`connectionTimeoutSeconds`, `keepAliveSeconds`) before evaluating anything and re-attaches only
through `setErrorAtPath` — `addErrors` appears nowhere, which matters because a cleared path holds
the immutable empty list. The clear pass runs after `setProperties`, so the nested paths resolve.
`aCorrectedValueClearsThePreviouslyAttachedError` asserts both messages are gone on the re-render
after correction, and the record shows 3 of 4 validation tests failing when the clear calls were
commented out — the test genuinely bites.

**5. No inert fields.** All four values flow
`MqttPublishIntegration.execute` → `MqttPublisher.publish` → `buildConnectOptions` →
`setConnectionTimeout` / `setKeepAliveInterval` / `setCleanSession` / `setAutomaticReconnect`.
Parameter order at the call site matches the twelve-parameter `publish` signature. Defaults are
interpreted at run time only: `null`/out-of-range timeout → `CONNECT_TIMEOUT_SECONDS` (5, the
previous hardcoded value); `null`/out-of-range keep-alive → the setter is not called at all, leaving
Paho's `KEEP_ALIVE_INTERVAL_DEFAULT` (60); `cleanSession == null` → `true`;
`autoReconnectEnabled == null` → `false`. `keepAliveSeconds = 0` passes through as a legal disable.
`MqttPublisherConnectOptionsTest` reads all four back through Paho's own getters with no broker, and
covers absent, out-of-range and the `0` case. `maxReconnectAttempts` and `reconnectIntervalSeconds`
are absent from the template, the bundle and the manifest, and README keeps them in "still
deliberately removed" with the Paho-setter evidence. The `publish` `finally` block is intact
byte-for-byte in behaviour: disconnect when connected, then `close()`, each swallowing its own
`MqttException`; `MemoryPersistence` and `setTimeToWait` unchanged.

**6. Packaging invariants.** Recorded in the record §2.5 as an (a)–(l) table with expected vs actual,
all matching: one jar, 5 `.java` entries all under `de/hock/mqtt/` (the five are listed), bare-root
`appian-plugin.xml`, one nested jar and it is Paho under `META-INF/lib/`, zero `connected-systems`
and zero `com/appian/mqtt` entries, zero test and zero `.backup` entries, `THIRD-PARTY-NOTICES.md`
at the root, `<version>1.0.5</version>` three-part, `min="26.9.0.0"` four-part untouched, vendor
`Hock`, major version 61 on all four classes, plug-in key `de.hock.mqtt` and both module keys
unchanged. The version bump is present in **both** places — `pom.xml` and
`src/main/resources/appian-plugin.xml` (confirmed in the diff). `src/assembly/plugin-assembly.xml`
is untouched, which is right: its `fileSet` over `src/main/java` picks up the new source and the
`dependencySet` already targets `META-INF/lib`. `src/main/java/de/hock/mqtt/validation/` contains
exactly `ConfigValidator.java` — no `package-info.java`, so the in-jar count is 5, not 6.

**7. Resource bundle.** Four new label keys added with labels matching the `label(...)` strings in
the template exactly (`Connection Timeout`, `Keep Alive Interval`, `Clean Session`,
`Automatic Reconnect`); 16 keys, 12 of them labels (nine config + three inputs) plus two name and
two description keys. Record §2.6 shows the orphan/missing diff empty. The derivation precondition
still holds: the nine field keys are the only `public static final String` constants on the template,
the three `INPUT_*` the only such constants on the integration (`MAX_ERROR_MESSAGE_LENGTH` is
package-visible `int`), and the result-map keys stay inline literals.

**8. Code policy.** Diff adds no `System.out`/`System.err`/`printStackTrace()`/`java.util.logging`
and no `System.setProperty`; the only grep hit is the pre-existing Javadoc sentence in
`MqttPublisher` asserting the absence of such a call, and the record states this openly rather than
claiming "no hits" as steering §4 previously did — steering now matches reality. No logging
dependency added; the pom diff is the version line alone, so the SDK versions and `provided` scopes
are unchanged and neither Appian artifact is bundled. No credentials in the new code or tests (the
publisher tests pass `null` for username and password); `ConfigValidator`'s Javadoc avoids the
literal `isExpressionable` token, keeping the repo-wide count at 3.

**9. Documentation lockstep.** README carries the nine-field table with key, label, type,
required/optional, effective default and validation rule per field, the "all four are genuinely
wired" statement, the `autoReconnectEnabled` caveat, the `cleanSession` honesty note (Boolean seeds
`false`, so a new object renders unchecked), the probe section with the "do not add a gate before
1.0.6" instruction, version `1.0.5` throughout, the artifact name, "five `.java` sources", "renders
nine fields" in the deploy checks and the 13-tests-in-four-classes breakdown with the new class
names. No TLS, subscribe, LWT or diagnostics claim is reinstated anywhere. Steering figures are all
updated and independently confirmed where cheap: 25 SDK imports across 3 files (measured), 5
sources, 13 tests, 16 bundle keys, `BooleanPropertyDescriptor` marked measured-safe in the
forced-flag table, §4 block rewritten for 1.0.5, §5 item 7 rewritten as the probe with the settling
observation and the one-line fallback, item 8 marked resolved by implementation.
`docs/mqtt-plugin-guide.md` is not left silently wrong: its banner now names 1.0.5, the nine fields,
and says explicitly that validation is present but ungated and that this contradicts the gated
behaviour the body still describes near line 116. The record itself is present and complete,
including a "what could NOT be verified offline" section and the deploy-and-test checklist whose
step 3 is the observation.

**10. Git hygiene.** `HEAD` is still `7c9e12b` on `main`, nothing staged (`git diff --cached` empty),
no new branch, no push. All 1.0.5 work is in the working tree as eleven modifications plus four
untracked additions.

## Non-blocking observation

The claim "it starts no thread that outlives the request" about `autoReconnectEnabled`
(`MqttPublisher.buildConnectOptions` Javadoc, README, record §1.3) is slightly stronger than Paho
1.2.5 guarantees. Bytecode check: `MqttAsyncClient.close(boolean)` delegates straight to
`ClientComms.close` and does **not** call `stopReconnectCycle`, which is reached only from
`reconnect()` and the internal reconnect listener. So if a connection is lost *after* a successful
connect with automatic reconnect on, `connectionLost` schedules a reconnect `Timer` that is not
cancelled by the `finally` block. It is bounded, which is why this is not a blocker:
`attemptReconnect` catches `MqttException`/`MqttSecurityException` and only logs — it does not
reschedule — and a reconnect against a closed `ClientComms` fails immediately, so the one scheduled
task dies and the timer thread is reaped once the client is unreachable. No persistent connection
and no unbounded retry loop. Worth a one-word softening ("starts no thread that keeps working after
the request") if the wording is ever revisited; nothing to change in code.
