# Review — Subscribe (READ) integration + `ConfigValidator` demotion, 1.0.6

Scope: the uncommitted working-tree changes against
`.agents/tasks/subscribe-integration-1.0.6-plan.md` and the ten numbered requirements.
Read-only git. Build and test evidence read from `.agents/tasks/subscribe-integration-1.0.6.md`
§6, not re-run.

**Verdict: APPROVED.** No blocking finding. Two non-blocking notes are recorded in §12 for the
deploy test; neither authorizes a change to approved behaviour.

---

## 1. Platform contracts (requirement 1)

- `MqttConnectedSystemTemplate.getConfiguration` (lines 112–190) declares the nine descriptors and
  nothing else — no `isExpressionable` call anywhere in the file, and the per-file grep recorded in
  §6.4 of the release record reads `0`. The nine keys, their order, their types
  (Text / Integer / Text / Text / EncryptedText / Integer / Integer / Boolean / Boolean) and every
  `isRequired` are byte-for-byte the 1.0.5 set; the only diff in the file is the removed
  `ConfigValidator.validate(...)` line plus its import and the Javadoc rewrite.
- `MqttSubscribeIntegration` (lines 69–71) carries `@TemplateId(name = "MqttSubscribeIntegration")`,
  `@IntegrationTemplateType(IntegrationTemplateRequestPolicy.READ)` and extends
  `SimpleIntegrationTemplate` — the `Simple*` facade, never a low-level interface, so the
  root-state NPE class of failure cannot recur.
- `topicFilter` is a plain `TextPropertyDescriptor` (lines 120–129). All four inputs end
  `.isExpressionable(true)`, measured per file as `MqttSubscribeIntegration:4` /
  `MqttPublishIntegration:3` / template `0` (§6.4).
- The sufficient check, not the grep, is present:
  `noConnectedSystemPropertyDescriptorIsExpressionable` still reads each modifier back through
  `PropertyDescriptor.isExpressionable()` and asserts nine fields inspected, and
  `connectedSystemFieldsUseNoListDocumentOrAppianValueDescriptor` still rejects the forced-flag
  types. Both unchanged.

## 2. Inputs (requirement 2)

Exactly four, in display order, pinned by
`subscribeIntegrationRendersItsRootStateAndItsFourExpressionableInputs`:
`topicFilter` (Text, `isRequired(true)`, instruction text names `+ (sensors/+/temp)` and
`# (home/#)`), `qos` (Integer, default `0`, 0–2), `maxWaitSeconds` (Integer, default `10`, 1–30),
`maxMessageCount` (Integer, default `10`, 1–100). `MAX_WAIT_SECONDS_MAX = 30` (line 98) is the cap
and is not raised anywhere; `inputError` rejects 31 and the test asserts the message names the
value. Defaults live in `DEFAULT_*` constants read by `qosOf` / `maxWaitSecondsOf` /
`maxMessageCountOf` and asserted off a freshly rendered state.

## 3. Output (requirement 3)

`success(...)` (line 315) returns `messages` then `messageCount` through
`IntegrationResponse.forSuccess`. `toMessageMap` (line 223) builds a `LinkedHashMap` with keys in
the order `topic`, `payload`, `qos`, `retained`, `binary`; `tryDecodeUtf8` uses a strict decoder
(`REPORT` on malformed and unmappable), so valid UTF-8 returns text with `binary` false and
anything else returns `Base64.getEncoder().encodeToString(payload)` with `binary` true — non-lossy
and documented in the README's output section. Empty payload → `""`, `binary` false.
`anEmptyCollectionIsReportedAsSuccessWithMessageCountZero` pins that an empty list is
`isSuccess() == true` with `messageCount` 0. Failure paths use the same truncating `error(...)`
helper and the same title style as publish, with `Invalid configuration` now shared by both.

## 4. Runtime safety (requirement 4) — traced in source, not taken from the record

`MqttSubscriber.subscribe`, lines 128–168:

- Queue: `new LinkedBlockingQueue<>(maxMessageCount)` (128) — bounded to the cap. The listener at
  line 140 does only `queue.offer(toMessageMap(topic, message))`, so overflow is dropped and Paho's
  receiver thread is never blocked. `toMessageMap` is total (null message and null payload both
  become an empty payload), so the listener cannot throw into Paho's thread.
- No thread, executor or `MqttCallback`: none is created anywhere in the class. The three-argument
  `subscribe(filter, qos, listener)` overload registers the listener as part of the subscription, so
  there is no window where a callback exists without a subscription.
- Teardown: `boolean subscribed` (134) is set only after `client.subscribe` returns, and the
  `finally` at 145 runs `unsubscribe` (148, only when `subscribed`), then `disconnect` when
  `isConnected()`, then `close()` (161) — three independent `try` / `catch (MqttException ignored)`
  steps, so one failure cannot skip the next. A timeout, an `MqttException` from connect or
  subscribe, and a `RuntimeException` (for example Paho's `IllegalArgumentException` on a bad
  filter) all take the same path, and the integration catches `MqttException | RuntimeException`.
- Bounded wait: `drain` (183) breaks when `deadlineNanos - System.nanoTime() <= 0` and every
  `poll` is `min(200 ms, remainingMillis + 1)`, so it is a bounded wait rather than a busy-wait and
  terminates on count **or** deadline. `InterruptedException` re-sets the flag and breaks. The
  deadline is absolute, which is what makes both terminations testable without a broker
  (`drainStopsAsSoonAsTheMessageCountIsReached`, `drainStopsWhenTheDeadlineHasPassed`).
- Client id: blank → `MqttClient.generateClientId()`; configured → `trim() + "-sub"`, so a
  subscribe cannot take over a concurrent publish's session on the same broker. The two residual
  caveats (two concurrent subscribes sharing the derived id; the MQTT 3.1 23-character limit) are
  documented in the README rather than coded around, which matches the plan.
- Sharing: session settings go through `MqttPublisher.buildConnectOptions` (package-private,
  same-package call) and `MqttPublisher.TIME_TO_WAIT_MILLIS`. The default-from-`null` logic exists
  in exactly one place; `MqttPublisher`'s diff is Javadoc only, so the deployed publish path is
  behaviourally untouched apart from the guard reshuffle in §5. Only the mechanical "read nine
  values off the state" block is duplicated in the integration, which is the trade-off the plan
  approved.

## 5. Part 2 — the demotion (requirement 5)

`ConfigValidator.validate(...)` and its import are gone from the template; a repo-wide grep over
`src/main/java` for `setErrorAtPath`, `clearErrorsAtPath`, `setErrors(` and `ConfigValidator`
returns only prose in Javadoc and references to the new `ConnectionConfigValidator`. The
`validation` package is deleted and the built jar has zero `validation/` entries (§6.2).
`ConnectionConfigValidator.firstError` carries the four rules in the specified order with the
specified messages, imports only `PropertyState` from the SDK, tolerates a `null` state, and treats
keep-alive `0` as legal. Both integrations call it before any broker contact —
`MqttPublishIntegration:153` and `MqttSubscribeIntegration:176` — and wrap the result in
`Invalid configuration`. Publish's guard order is topic → configuration → QoS; subscribe mirrors it
with topic filter → configuration → inputs. `isRequired` remains on all nine descriptors. No new
design-time validation attempt, and no integration-input error-display probe is mixed in — the
release record and the README both state that question is deliberately left untested.

The behaviour change this creates (out-of-range `connectionTimeoutSeconds` / `keepAliveSeconds` now
fail the call instead of silently defaulting) is flagged in the README, `MqttPublisher`'s Javadoc,
`ConnectionConfigValidator`'s Javadoc and the release record, and the two
`MqttPublisherConnectOptionsTest` tests are kept as defence in depth.

## 6. Packaging (requirement 6)

Read from §6.2 / §6.3 of the release record, plus one `unzip -l` spot-check of the built jar which
matched the record exactly: 7 `.java` entries all under `de/hock/mqtt/**`, bare-root
`appian-plugin.xml`, `THIRD-PARTY-NOTICES.md` at the root, `META-INF/lib/` containing only
`org.eclipse.paho.client.mqttv3-1.2.5.jar`, one jar in `target/` named
`mqtt-broker-connected-system-1.0.6-plugin.jar`. The record shows zero `connected-systems`, zero
test, zero backup and zero `com/appian/mqtt` entries, `<version>1.0.6</version>` three-part,
`min="26.9.0.0"` untouched, `vendor name="Hock" url="https://itondemand.com"`, bytecode major
version 61 on all six classes, and the three module keys unchanged. The manifest diff confirms the
new `<integration-template key="MqttSubscribeIntegration">` is nested inside the single
`<connected-system-template>` (lines 36 and 39 inside 33–42), and that key matches 1.0.3's, which I
verified against `git show d023a76:src/main/resources/appian-plugin.xml`. `pom.xml` changes only the
version, the description and a Surefire comment — core 1.9.1 `provided`, client 1.3.0 `provided`,
Paho 1.2.5 bundled are all unchanged.

## 7. Code policy (requirement 7)

§6.4's policy grep has one hit, the `MqttPublisher` Javadoc sentence asserting the absence of a
`System.setProperty` call. `MqttSubscriber` phrases the same assurance without the token ("no
JVM-global property is ever set"), so the per-file grep contract in steering §1 still holds, as do
the per-file `isExpressionable` counts (the new subscribe Javadoc discusses expressionability in
prose but never uses the literal token — its count is exactly its four builder calls). No logging
dependency was added, no credential is hardcoded in source or tests (`broker.example.test` /
`appian-client` only), and both Paho call sites close in a `finally`.

## 8. Tests (requirement 8)

23 in 7 classes, 0 failures, per §6.1, slowest 0.325 s, none opening a socket. Every kept test is
present and unchanged: the root-state NPE guard, the ordered nine-key test, the modifier-readback
expressionability test, the no-List/Document/AppianValue test, the publish-inputs test, both
`ProjectStructureTest` tests and both `MqttPublisherConnectOptionsTest` tests. Added:
two subscribe descriptor tests (four keys in display order, all four expressionable; no forced-flag
input type, with the precaution labelled as such in its Javadoc), four input-contract tests
(defaults, rejection naming the value, empty-as-success, blank filter failing before any broker
contact through the public `execute` overload), three `MqttSubscriberTest` tests (both loop
terminations driven off a pre-filled queue and an absolute deadline — no adapter interface, no
injected client — plus UTF-8 / Base64 / empty payload), four `ConnectionConfigValidatorTest` tests
building real state by rendering the template and calling `setValueAtPath`, and
`brandNewConnectedSystemRendersWithNoAttachedErrors`. The deleted
`MqttConnectedSystemTemplateValidationTest` asserted the machinery that was removed; its four rules
are re-covered on the run-time path. 23 exceeds the "roughly 16–20" guidance; the record reports the
figure and its reasoning rather than trimming, which is acceptable.

## 9. Documentation lockstep (requirement 9)

README has the subscribe section with the four inputs, the full output shape including `binary` and
the RFC 4648 encoding note, the blocking-wait caveat naming the 30-second cap and Appian's
independent timeout, the single-filter decision with wildcard examples and the multi-topic
follow-up, a rewritten design-time validation section, and updated version, artifact name, source
count (7), test inventory (23 in seven classes) and module table. `info.md` moves from its stale
`1.0.4` to `1.0.6`. `docs/mqtt-plugin-guide.md` keeps its history banner and adds an explicit
1.0.6-differences note under *Subscribe to MQTT Topics* (one Text filter not a list, 1–30 not 1–300,
1–100 not 1–1000, rejection not clamping). The steering file updates every figure I checked —
version, artifact, 7 sources, 36 SDK imports across 4 files, 22 bundle keys (16 labels), 23 tests in
7 classes, the per-file expressionability grep now listing both integration files, two Paho call
sites, two integration modules — records the 1.0.6 demotion in §5 item 7 and the moot-ing of item 8,
rewrites item 4's inventory, and adds the forced-flag-builder note explicitly headed "Precaution,
NOT a known rule". Every §1 hard constraint survives: the diff's deletions are all stale figures and
superseded 1.0.5 prose. The release record exists and is detailed.

## 10. Deploy checklist (requirement 10)

Present as §7 of the release record: eight ordered steps covering the version check, the clean
nine-field page, the publish regression, both integrations being offered, a quiet filter returning
`messageCount` 0, a live publish round-trip, a wildcard filter's concrete `topic`, and the bad-port
`Invalid configuration` message — plus a note to watch `tomcat-stdOut.log` for leaked connections.

## 11. Verification evidence

§6 of the release record pastes real output for the build and suite (23/0/0, seven classes), the
twelve packaging invariants, the manifest and bytecode checks, the expressionability and namespace
greps, the policy grep and the bundle diff. Nothing required was missing, so I did not re-run the
suite. Spot-checks used: one `unzip -l` on the built jar (matched), one `grep` for residual
design-time error machinery (clean), and `javap` against Paho (see §12).

## 12. Non-blocking notes for the deploy test

Neither is a blocker; both are recorded so they are not lost.

1. **Paho 1.2.5 never cancels a started reconnect cycle from `disconnect()` or `close()`.** I
   disassembled `org.eclipse.paho.client.mqttv3.MqttAsyncClient`: the only call sites of
   `stopReconnectCycle()` are `reconnect()` and the synthetic accessor used by the internal
   reconnect callback on a successful reconnect. So if the connection drops *during* a collection
   window while `autoReconnectEnabled` is ticked, the non-daemon `MQTT Reconnect: <clientId>` timer
   Paho starts can outlive the request even though `MqttSubscriber`'s `finally` disconnects and
   closes correctly. This is **not** a finding against this diff: the mechanism lives in
   `MqttPublisher.buildConnectOptions`, which is unchanged, approved at 1.0.5 and deployed, and
   requirement 4 of this task explicitly mandates sharing it rather than diverging on the subscribe
   path. I did not establish the start-of-cycle path by bytecode, the field defaults to false, and
   the README already discourages it. Worth watching for a lingering reconnect thread in
   `tomcat-stdOut.log` during checklist steps 5–7; if it shows up, the fix is a deliberate decision
   about `autoReconnectEnabled` on short-lived requests, not a local patch.
2. **`MqttSubscriber.subscribe` is public and would throw `IllegalArgumentException` on
   `maxMessageCount < 1`** (it is the queue capacity). Unreachable from the plug-in — `inputError`
   gates it and the integration would catch it anyway — and the Javadoc states the caller's
   obligation, so this is only a note for any future caller.
