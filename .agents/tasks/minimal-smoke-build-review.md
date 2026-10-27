# Review — Minimal Smoke-Test Build (1.0.4)

**Reviewed:** current state of `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component`
(no diff available; not reviewed through git, and no git command was run).
**Inputs read:** `.agents/tasks/minimal-smoke-build-plan.md`, `.agents/tasks/minimal-smoke-build.md`,
`.kiro/steering/appian-appmarket-compliance.md`, all four `src/main/java` sources, both
`src/test/java` classes, `src/main/resources/appian-plugin.xml`,
`src/main/resources/resources_en_US.properties`, `pom.xml`, `src/assembly/plugin-assembly.xml`,
`README.md`, `info.md`, `docs/mqtt-plugin-guide.md`.
**Build/test evidence:** read from report §7.1–7.2 (`Tests run: 7, Failures: 0, Errors: 0,
Skipped: 0`, `BUILD SUCCESS`, one jar built). Not re-run.

**Verdict: APPROVED.** No blocking findings.

---

## 1. Requirement-by-requirement result

### KEEP — connected system, five fields
`/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java`
declares exactly `brokerHost` (Text, `isRequired(true)`), `brokerPort` (Integer,
`isRequired(true)`, `.placeholder("1883")` plus instruction text naming 1883), `clientIdentifier`
(Text, optional), `username` (Text, optional), `password` (EncryptedText, `masked(true)`,
optional). Keys are the five `public static final String` constants; no sixth descriptor.

"Seeded/suggested 1883" is satisfied by suggestion. The report (§4) and plan (§12 coupling 1)
record why seeding is unavailable without the first-render gate this build deletes, which is an
approved trade-off, not a shortfall.

Label text preservation verified against the archive, read-only:
`unzip -p .backup/mqtt-plugin-FULL-1.0.3-src.zip src/main/resources/resources_en_US.properties`
returns `Broker Host`, `Broker Port`, `Client Identifier`, `Username`, `Password`, `Topic`,
`Payload`, `QoS` — byte-identical to the eight surviving labels in the current bundle. Only the two
`.description` values were re-authored, which the report states.

### KEEP — one publish integration
`/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/integration/MqttPublishIntegration.java`
keeps the class name, `@TemplateId(name = "MqttPublishIntegration")` and
`@IntegrationTemplateType(IntegrationTemplateRequestPolicy.WRITE)`. Compared against the archived
1.0.3 source (lines 77–91 there), the annotations and the three surviving input keys are unchanged.
Inputs: `topic` (Text, required, expressionable), `payload` (Text, required, expressionable, UTF-8
bytes), `qos` (Integer, optional, `.placeholder("0")`, run-time default 0, expressionable). The
success value is a flat `LinkedHashMap` of `success` / `status` / `topic` / `qos` with no nested
member; failure returns `IntegrationError` titled `Invalid input` or `MQTT publish failed` with a
500-character-truncated message. Expressionable integration inputs are correct here and were not
treated as a violation.

Manifest key parity confirmed against the archived 1.0.3 manifest: `key="de.hock.mqtt"`,
`MqttConnectedSystemTemplate`, `MqttPublishIntegration` all unchanged; only
`MqttSubscribeIntegration` is gone and `<version>` moved 1.0.3 → 1.0.4.

### KEEP — runtime
`/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/MqttPublisher.java`
uses `MqttClient` + `MqttConnectOptions` directly, one connect → publish → disconnect per call,
`MemoryPersistence`, `setConnectionTimeout(5)`, `setTimeToWait(10_000)`,
`setAutomaticReconnect(false)`, disconnect and close in `finally`. No cache, session manager,
reconnection or thread anywhere in the tree. Failures propagate as `MqttException` and are caught in
`execute` (`catch (MqttException | RuntimeException)`) with the message placed in the response.

The state-access idiom in `execute`
(`integrationConfiguration.toConfiguration().getRootState()`) is identical to archived 1.0.3 lines
147–148, so the one path that cannot be exercised offline is unchanged from the previously built
code rather than newly invented. `textValue` additionally null-guards the state, so a null root
state yields the `Invalid input` guard rather than an NPE.

Logging: `grep -rn 'System\.out\|System\.err\|printStackTrace\|java\.util\.logging\|System\.setProperty' src/`
returns one hit, and it is the words `System.setProperty` inside `MqttPublisher` Javadoc at line 26.
No print, no logger, no JVM-global mutation.

### DELETED items
`find src -name '*.java'` returns exactly the four main sources and the two test classes.
`find src/main/java -type d` shows only `de`, `de/hock`, `de/hock/mqtt`, `.../integration`,
`.../template` — `diagnostics/`, `protocol/` and `validation/` are gone, not emptied. A grep for
`tlsEnabled|caCertificate|clientPrivateKey|keyPassphrase|lwt|autoReconnect|cleanSession|keepAlive|setErrorAtPath|firstRender|Subscribe|ssl`
over `src/main/java` and `src/test/java` returns only Javadoc prose, the `getResourceAsStream`
hits in `ProjectStructureTest`, and `options.setCleanSession(true)` in `MqttPublisher` — a
hardcoded connect option, not the deleted `cleanSession` config field. No surviving
`MqttErrorCategory`, `OperationResult`, `MqttConnectionConfig`, `PublishRequest`,
`ClientIdentifierGenerator`, `ConfigFields` or `MqttOperationException`. `MqttPublisher` is the one
retained extraction and the report (§2 judgement calls) earns it: it is the sole Paho call site, so
a classloading failure names exactly one class.

Manifest and bundle are clean of the deleted surface: no `MqttSubscribeIntegration` element, and
the bundle holds no key for a removed field.

### NO DESIGN-TIME VALIDATION
`getConfiguration` on the template is `setProperties(...)` followed by `return configuration;`.
No `validate` override, no `setErrorAtPath`, no `firstRender`, no `setValue` seeding anywhere in
`src/main/java`. `isRequired` is the only design-time guard. The four checks in `execute` are
invocation-time guards returning an error response, which the brief explicitly permits.

### PLATFORM RULES
1. `grep -c isExpressionable .../template/MqttConnectedSystemTemplate.java` → `0`;
   `grep -rn isExpressionable src/main/java/ | wc -l` → `3`;
   `grep -rc isExpressionable src/main/java/ | grep -v ':0$'` → the single line
   `src/main/java/de/hock/mqtt/integration/MqttPublishIntegration.java:3`. The sufficient check is
   present too: `noConnectedSystemPropertyDescriptorIsExpressionable` iterates
   `rootType.getProperties()` and reads `descriptor.isExpressionable()` through the public SDK
   accessor, collects every offender with its descriptor type, and asserts `inspected == 5` so an
   empty loop cannot pass.
2. Only Text / Integer / EncryptedText descriptors are used, and
   `connectedSystemFieldsUseNoListDocumentOrAppianValueDescriptor` asserts no
   `ListTypePropertyDescriptor` / `DocumentPropertyDescriptor` / `AppianValuePropertyDescriptor`,
   plus `RefreshPolicy.NEVER` and not hidden.
3. Both templates still extend `SimpleConnectedSystemTemplate` / `SimpleIntegrationTemplate`, and
   `brandNewConnectedSystemRendersANonNullRootStateWithAResolvableRootType` asserts the premise
   (`ConfigurationDescriptor.builder().build().getRootState() == null`) before the rendered
   non-null root state and its resolvable type — the NPE guard is intact.
4. `pom.xml` keeps `connected-systems-core:1.9.1` `provided`, `connected-systems-client:1.3.0`
   `provided`, `paho:1.2.5` bundled, `junit-jupiter:5.10.2` `test` and nothing else. jqwik,
   moquette, the Failsafe block and the Surefire `<configuration>` are gone; Java 17 release and the
   `default-jar` → phase `none` execution are untouched.

### PACKAGING INVARIANTS (a)–(k)
Each is covered in report §7.4 with expected-vs-actual output. I spot-checked the jar listing
independently and every figure matched:

| | Check | Reported | Observed |
|---|---|---|---|
| a | `com/appian/mqtt` in jar / `com\.appian\.mqtt` in `src`+`pom.xml` | 0 / 0 | 0 / 0 |
| a | SDK imports `com.appian.connectedsystems` | 22 across 2 files | 22 across 2 files |
| b | `.java` entries in jar / outside `de/hock/mqtt/` | 4 / 0 | 4 / 0 |
| b | `test` entries in jar | 0 | 0 |
| c | bare-root `appian-plugin.xml` | 1 | 1 |
| d | jars under `META-INF/lib/` / `connected-systems` entries | 1 (Paho) / 0 | 1 (`org.eclipse.paho.client.mqttv3-1.2.5.jar`) / 0 |
| e | manifest `<version>` / `application-version` / pom version | `1.0.4` / `min="26.9.0.0"` / `1.0.4` | same |
| f | vendor | `name="Hock" url="https://itondemand.com"` | same |
| g | jars in `target/` | 1 | 1 (`mqtt-broker-connected-system-1.0.4-plugin.jar`) |
| h | bytecode major version | 61 | 61 (`MqttPublisher`) |
| i | bundle keys / field labels / orphan-missing diff | 12 / 8 / empty | 12 / 8 / empty (re-ran the §4 sed+diff block) |
| j | bare-root `THIRD-PARTY-NOTICES.md` | 1 | 1 |
| k | `backup` entries in jar / archive sha256, size, entries | 0 / `386f3cab…f89b4`, 136397, 74 | 0 / identical |

The full 20-entry listing in the report matches the artifact on disk entry for entry.

### VERSION
`pom.xml` `<version>1.0.4</version>` and manifest `<version>1.0.4</version>`, three-part, above
1.0.3; `<application-version min="26.9.0.0"/>` left four-part. Plug-in key and both module keys
unchanged, so this replaces rather than installs alongside.

### TESTS
7 tests in 2 classes, within the 5–8 band, each one mapped to a required area, none needing a
broker. Every assertion covers surviving behavior; nothing from the removed surface was retained in
weakened form. `ProjectStructureTest` is trimmed to the two classpath checks (`basePackageIsPresent`
dropped, which only asserted its own package name). The report's §6 "proved to bite" block shows
both guard tests failing on an injected violation and then reverted to green.

### DOCUMENTATION LOCKSTEP
`README.md` leads with the minimal-smoke-test statement and the four things it validates, names
every removed feature with its real field keys, points at `.backup/mqtt-plugin-FULL-1.0.3-src.zip`,
documents the five config fields and three inputs with real keys and labels, and keeps build/deploy
on `target/mqtt-broker-connected-system-1.0.4-plugin.jar`. Compliance is linked, not duplicated.
Its eight `1.0.3` mentions are all historical/archive references. `docs/mqtt-plugin-guide.md` carries
a nine-line pre-reduction banner naming 1.0.4, the five fields, the three inputs, the README and the
archive path; the report §12 states the decision was "marked, not trimmed". `info.md` is at 1.0.4
with a minimal-build note. The steering file's project-specific figures match the tree (4 sources,
22 imports across 2 files, `isExpressionable` 3 / template 0, 12 keys / 8 labels, 7 tests, 1.0.4
artifact name, jqwik/moquette/Failsafe removal), its §1/§2 hard constraints are intact, and the
reduction scope note plus archive path appear at the top and in §8. Every pre-existing file in
`.agents/tasks/` retains its original mtime; `minimal-smoke-build.md` is the one new record.

### REPORT COMPLETENESS
All required sections are present and substantiated: contents (§1), the 31-file delete list with
reasons (§2), kept/created with one-line reasons (§3), the fields and inputs as implemented (§4),
why validation was dropped (§5), the test suite (§6), invariant evidence with new counts (§7), the
artifact filename (header and §9.1), the ordered deploy-and-test checklist (§9), what is
unverifiable offline including the explicit statement that final confirmation needs a real Appian
26.9 deploy (§10), restoring the full build (§11), and the documentation lockstep (§12). The report
also records its own two mid-flight deviations (a Javadoc token and a manifest comment that
inflated two grep counts) rather than quietly reporting the clean numbers.

---

## 2. Findings

No blocking findings.

### Non-blocking

**NB-1. "Not a git repository" is now a stale claim.**
`/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/.agents/tasks/minimal-smoke-build.md`
(§ preamble, "No git was used: this directory is not a git repository") and
`/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/.kiro/steering/appian-appmarket-compliance.md`
§5 item 5 ("Not a git repository. No history for a submission Appian source-reviews"). A `.git`
directory now exists at the project root with `COMMIT_EDITMSG`, an index, `refs/heads/main` and
`refs/remotes/origin/main`. I did not run any git command, per the review brief, so I cannot
attribute it; the timestamps (`.git` 14:49–15:02, `.gitignore` edited 15:04 and open in the editor)
point at the user initializing the repository alongside the work rather than at the implementer,
whose report pastes no git output. Nothing in the build, the jar or the deploy path is affected.
Worth a one-line correction in the steering file the next time it is touched, since the item claims
an absence that no longer holds.

**NB-2. Username without a password is silently dropped.**
`/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/main/java/de/hock/mqtt/MqttPublisher.java`
lines 83–86 apply credentials only when username *and* password are both non-blank, so a broker
configured for a username-only login would connect anonymously and fail authentication. This is the
code the plan prescribed verbatim (§3.3), both fields are documented optional, and it is a
smoke-test build against a broker the user controls — so it is approved behavior, recorded here only
so the trade-off is visible if a username-only broker is used for the deploy test.

**NB-3. `ProjectStructureTest` keeps 4-space indentation** while the other five files use 2-space.
`/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/test/java/de/hock/mqtt/ProjectStructureTest.java`.
It was deliberately kept verbatim; cosmetic only, and test sources do not ship in the jar.

---

## 3. What this review could not establish

Unchanged from the report §10 and not held against it: installation on a real instance, that
Appian's plug-in classloader resolves `META-INF/lib/`, that the server supplies
`connected-systems-client`, that the configuration form renders (the platform validators are
`com.appiancorp.*` and absent from both SDK jars), that `placeholder` renders on an Integer field,
and that a publish reaches a broker. The expressionability test reproduces the object graph the
validator walks, not the validator. Final confirmation requires deploying
`target/mqtt-broker-connected-system-1.0.4-plugin.jar` to Appian 26.9 and working through the §9
checklist.
