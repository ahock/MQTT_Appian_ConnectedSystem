# Connected System descriptor fix — verification note

Date: 2026-10-13. Workspace: `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component`.
No git in this project; all edits made in place.

This note is written for a reviewer who will **not** re-run the build. Every number below is the
real output of the command shown.

---

## 1. What changed

| File | Change |
|---|---|
| `pom.xml` | version `1.0.0` → `1.0.1`; new `appian.sdk.client.version` = `1.3.0`; new `com.appian:connected-systems-client` dependency, scope `provided`; corrected the stale comment claiming the `Simple*` hierarchy is undeclared |
| `src/main/resources/appian-plugin.xml` | `<version>1.0.0</version>` → `<version>1.0.1</version>`. Nothing else: `key`, all three `class=` attributes, `<vendor>` and `<application-version min="26.9.0.0"/>` untouched |
| `MqttConnectedSystemTemplate.java` | `implements ConnectedSystemTemplate` → `extends SimpleConnectedSystemTemplate`; `getConfigurationDescriptor(...)` override replaced by `getConfiguration(SimpleConfiguration, ExecutionContext)`; `buildRootType()` and `ROOT_TYPE_NAME` deleted; `allProperties()` now private; Clean Session seeded `true` on first render only; two false Javadoc paragraphs rewritten |
| `MqttPublishIntegration.java` | `implements IntegrationTemplate` → `extends SimpleIntegrationTemplate`; both overrides re-signatured onto `SimpleConfiguration`; `execute` reads state through `toConfiguration().getRootState()`; `buildRootType()` / `ROOT_TYPE_NAME` deleted; Javadoc corrected |
| `MqttSubscribeIntegration.java` | same migration, same deletions, Javadoc corrected |
| `MqttConnectedSystemTemplateDescriptorTest.java` | **new** test (7 tests) reproducing the reported failure and guarding the fix |
| `README.md` | version and artifact paths → `1.0.1`; client dependency added to Requirements; the "templates implement the low-level SDK interfaces" limitation replaced with the `connected-systems-client`-on-server-classpath caveat; test-coverage bullet brought in line with the new test |
| `.kiro/steering/appian-appmarket-compliance.md` | §1 version table → `1.0.1`; §1 import-count guard 47 → 45 with the reason; §2 artifact name → `1.0.1`, client coordinate added; §4 `J=` path → `1.0.1`, plus a new "no Appian artifact bundled" check; §5 item 2 rewritten from open divergence to resolved; §5 item 4 updated for the new test |

**Not touched**, deliberately: `validate(PropertyState)`, `toConnectionConfig(PropertyState)`, all
four validators, every value-reading helper, `readRequest`, `readStringList`, `readInt`,
`InputValidator`, `DiagnosticsBuilder`, `ResponseMapper`, `MqttConnectionManager`. The `Simple*`
facade hands back the same `PropertyState` type those methods already accept, so the bridge is
`toConfiguration().getRootState()` and nothing downstream changed. The five known pre-existing bugs
(inert `maxReconnectAttempts`, key passphrase, `MqttConnectionManager.classify` divergence, username
in the connection fingerprint, dropped subscribe `validationErrors`) are all untouched; none is on
this code path.

**No user-visible field, label, default, input, output or error category changed.** The one default
that *would* have changed silently was caught and preserved — see §4.

---

## 2. Build and test — real output

```
$ rm -rf target && mvn -o clean package
[INFO] Compiling 33 source files with javac [debug release 17] to target/classes
[INFO] Compiling 2 source files with javac [debug release 17] to target/test-classes
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- in de.hock.mqtt.template.MqttConnectedSystemTemplateDescriptorTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in de.hock.mqtt.ProjectStructureTest
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
[INFO] Building jar: .../target/mqtt-broker-connected-system-1.0.1-plugin.jar
[INFO] BUILD SUCCESS
```

`mvn -o test` separately: same 10 tests, 0 failures.

Only one command in this task needed the network: `mvn dependency:get
-Dartifact=com.appian:connected-systems-client:1.3.0`, which populated
`~/.m2/repository/com/appian/connected-systems-client/1.3.0/`. Every build since ran with `-o`.

---

## 3. Why the fixed descriptor's root state is non-null — traced through SDK bytecode

### 3.1 The old failure, reproduced verbatim

A throwaway probe compiled against `connected-systems-core-1.9.1.jar` only, running the exact
pre-fix line on the exact input Appian supplies for a new connected system:

```
empty.getRootState() = null
NPE: Cannot invoke "com.appian.connectedsystems.templateframework.sdk.configuration.PropertyState.setType(
     com.appian.connectedsystems.templateframework.sdk.configuration.LocalTypeDescriptor)"
     because the return value of
     "com.appian.connectedsystems.templateframework.sdk.configuration.ConfigurationDescriptor.getRootState()" is null
generated rootState = true
generated type      = #/types/MqttConnectedSystemConfiguration
```

That is the user's reported message, character for character. The last two lines are the same probe
calling `new StateGenerator(types).generateFromExistingState(rootType, null)` — the SDK's public
route — which returns a populated state from a `null` input. The probe was scratch work under
`/tmp`, not part of the project, and has been deleted.

Root cause in one line, from `javap -c` on core 1.9.1: `getRootState()` is
`getState().get("root")`, and a brand-new connected system's state map has no `"root"` entry.

### 3.2 Where the root state now comes from

`javap -c` on `connected-systems-client-1.3.0.jar`, following the framework's entry point:

1. `SimpleConnectedSystemTemplate.getConfigurationDescriptor(descriptor, path, ctx)` — now inherited,
   not ours. Its bytecode is three calls: `SimpleConfiguration.from(descriptor, typePropertyFactory,
   ctx)` → `getConfiguration(simpleConfig, ctx)` (our override) → `toConfiguration()`.
2. `SimpleConfiguration.from` → private constructor. Bytecode `14: aload_1; 15: ifnonnull 40`: a
   `null` descriptor is replaced with a fresh built one, a non-null-but-empty one is stored as-is.
   Either way no dereference of the root state.
3. Our `getConfiguration` calls `configuration.setProperties(allProperties())` →
   `setPropertiesGeneric(getRootTypeName(), props)`.
   - `getRootTypeName()` bytecode `5: ldc "RootType"; 8: aload_1; 9: ifnull 20` — null-safe: no prior
     state yields the literal `RootType`.
   - `setPropertiesGeneric` builds a `LocalTypeDescriptor` with that name and **puts it in
     `typePropertyFactory.getTypes()`** (bytecode `36: Map.put`), then calls the private
     `getConfigurationDescriptor(rootType, types.values())`.
4. That private method is the whole mechanism:
   `new StateGenerator(types)` → `generateFromExistingState(rootType, getRootState())` →
   `ConfigurationDescriptor.builder().withTypes(types).withState(generatedState).build()`.
   It passes the possibly-`null` existing state straight into `generateFromExistingState`, which
   tolerates it (demonstrated in §3.1) and returns a fully-populated `PropertyState`.
5. `toConfiguration()` returns that rebuilt descriptor. `getState().get("root")` is now populated and
   `getTypes()` contains the root type, so the framework's subsequent
   `getRootState().setType(...)` cannot NPE.

This also fixes a second latent defect the old code had: `PropertyState.setType(LocalTypeDescriptor)`
only stores a `TypeReference` (bytecode: `TypeReference.from(...)` then `putfield type`) — it does
**not** register the type. The old code never called `withType`/`withTypes`, so even a non-null root
state would have carried an unresolvable `#/types/...` reference. The facade's `withTypes(...)` call
closes that.

Both integrations get the same treatment: `SimpleIntegrationTemplate.getConfigurationDescriptor`
wraps both descriptors with `SimpleConfiguration.from` and calls our `getConfiguration`, and its
`deroot(propertyPath)` is null-safe (bytecode `0: aload_1; 1: ifnull 59`).

### 3.3 Execution path is preserved

`SimpleIntegrationTemplate.execute(ConfigurationDescriptor, ConfigurationDescriptor,
ExecutionContext)` bytecode is only two `SimpleConfiguration.from` calls and a dispatch to our
`execute(SimpleConfiguration, SimpleConfiguration, ExecutionContext)`. It never calls
`setProperties`, so `toConfiguration()` returns the *saved* descriptor unchanged and
`getRootState()` yields the real saved state. The integrations read exactly what they read before.

---

## 4. The one behavioral trap, and how it was closed

State generation pre-populates values, and core 1.9.1 generates `Boolean.FALSE` for a `BOOLEAN`
property. `toConnectionConfig` reads
`asBoolean(rootState, ConfigFields.CLEAN_SESSION, true)` — it relies on `null` meaning "unset, use
`true`". Left alone, the documented `cleanSession = true` default would have silently flipped to
`false`, which is protocol-visible.

`getConfiguration` therefore captures the freshness signal **before** `setProperties` (afterwards a
generated `false` is indistinguishable from a chosen one) and seeds `Boolean.TRUE` only on the first
render. `tlsEnabled`, `autoReconnectEnabled` and `lwtRetained` all document `false`, so they are
unaffected. Blank strings and null integers are harmless: `isBlank`, `trimToNull` and
`parseIntOrDefault` already treat `null` and `""` the same way.

Guarded by two assertions in `cleanSessionDefaultsToTrueOnFirstRenderAndRespectsADeliberateFalse`:
first render reads `true`; a descriptor whose `cleanSession` was deliberately set `false` still reads
`false` after a second render.

---

## 5. The new test

`src/test/java/de/hock/mqtt/template/MqttConnectedSystemTemplateDescriptorTest.java`, 7 tests, runs
offline with no Appian runtime (the SDK configuration model is plain Java and `ExecutionContext` is
unused on this path, so it is passed as `null`). Every test starts from
`ConfigurationDescriptor.builder().build()` — the input whose `getRootState()` is `null`, asserted
explicitly in the first test so the premise is not taken on trust — and drives the real framework
entry point `getConfigurationDescriptor`.

| Test | Asserts |
|---|---|
| `rootStateIsPopulatedForABrandNewConnectedSystem` | incoming root state is `null`; rendered root state is not — the direct NPE regression guard |
| `rootTypeIsRegisteredAndResolvable` | `getTypes()` non-empty and the root state's type reference resolves in it |
| `allTwentyConfigFieldsArePresentInTheGeneratedState` | all 20 `ConfigFields` keys are declared on the root type and present in the generated state |
| `cleanSessionDefaultsToTrueOnFirstRenderAndRespectsADeliberateFalse` | §4 |
| `blankBrokerHostIsReportedAgainstItsField` | `getErrorsAtPath("brokerHost")` non-empty — design-time validation still attaches field-scoped errors |
| `publishIntegrationPopulatesItsRootStateAndFourInputs` | non-null root state, registered root type, input keys exactly `topic, payload, qos, retained` |
| `subscribeIntegrationPopulatesItsRootStateAndFourInputs` | same, keys exactly `topicFilters, qos, maxWaitSeconds, maxMessageCount` |

The last two pin the eight integration input keys the i18n bundle depends on; the third pins the 20
config keys. Together they are a label-key regression guard.

---

## 6. Invariant re-verification against the built jar

`J=target/mqtt-broker-connected-system-1.0.1-plugin.jar`

This table records the `1.0.1` build. Every invariant was re-verified against the current `1.0.2`
jar — see §8.

| Check | Command | Result |
|---|---|---|
| exactly one jar | `ls target/*.jar` | `mqtt-broker-connected-system-1.0.1-plugin.jar` — one line |
| sources shipped | `unzip -l $J \| grep -c '\.java$'` | **33** (unchanged — the only new source is a test, and the assembly `fileSet` covers `src/main/java` only) |
| sources root-mirrored | `unzip -l $J \| grep 'de/hock/mqtt/.*\.java'` | e.g. `de/hock/mqtt/template/MqttConnectedSystemTemplate.java`, `de/hock/mqtt/integration/Mqtt{Publish,Subscribe}Integration.java` — no prefix |
| manifest bare at root | `unzip -l $J \| grep -cE ' appian-plugin.xml$'` | **1**, listed as `appian-plugin.xml` with no directory |
| Paho under `META-INF/lib/` | `unzip -l $J \| grep 'META-INF/lib/'` | `META-INF/lib/org.eclipse.paho.client.mqttv3-1.2.5.jar`; no root `lib/` entry |
| no Appian artifact bundled | `unzip -l $J \| grep -c 'connected-systems'` | **0** — both core and client are `provided`, and the assembly `dependencySet` is `<scope>runtime</scope>` |
| namespace | `unzip -l $J \| grep -c 'com/appian/mqtt'` | **0** |
| no tests shipped | `unzip -l $J \| grep -ci test` | **0** |
| version / platform / vendor | `unzip -p $J appian-plugin.xml \| grep -E '<version>\|application-version\|<vendor'` | `<version>1.0.1</version>`, `min="26.9.0.0"`, `<vendor name="Hock" url="https://itondemand.com"/>` |
| bytecode | `javap -verbose -cp target/classes <all three templates> \| grep 'major version'` | **61, 61, 61** (Java 17) |
| label keys | `grep -c '\.label=' src/main/resources/resources_en_US.properties` | **28** (20 config + 8 integration inputs), keys unchanged |
| code policy | `grep -rn 'System\.out\|System\.err\|printStackTrace\|java\.util\.logging\|System\.setProperty' src/main/java/` | **0 matches** |
| SDK imports | `grep -rh 'import com.appian.connectedsystems' src/main/java/ \| wc -l` | **45** (was 47; see below) |

Two notes on the namespace guard:

- `grep -rn 'com\.appian\.mqtt' . --exclude-dir=target --exclude-dir=.agents` returns **2 hits, both
  prose in `.kiro/steering/appian-appmarket-compliance.md`** describing the trap itself. No hit in
  `src/`, `pom.xml` or the jar. The steering file's own command has been updated to exclude
  `.kiro/` so it stops matching its own documentation.
- The SDK import count legitimately moved 47 → 45: each template dropped its low-level imports
  (`ConnectedSystemTemplate` / `IntegrationTemplate`, `ConfigurationDescriptor`,
  `LocalTypeDescriptor`) and gained two `com.appian.connectedsystems.simplified.sdk.*` imports. The
  steering file now says 45 and records why.

---

## 7. What is still unproven, and must be checked on deploy

1. **`connected-systems-client` on the Appian server classpath.** It is `provided`, so the server
   must supply it. This is Appian's documented primary development path, but it is unverified on the
   target instance. A `NoClassDefFoundError` naming `com/appian/connectedsystems/simplified/sdk/...`
   at install or first render is the symptom. Fallback: keep the low-level interfaces and build the
   descriptor by hand with `StateGenerator` + `withType` + `withState`. Do **not** bundle the client
   jar.
2. **The actual UI render.** Deploy `target/mqtt-broker-connected-system-1.0.2-plugin.jar` to
   `APPIAN_INSTALL/_admin/plugins/`, watch `tomcat-stdOut.log` for `Successfully installed Plug-in`
   and the `ConnectedSystemTemplateModuleParser` lines, then do the thing that originally failed:
   **New > Connected System > MQTT Broker**, and confirm all 20 fields render.
3. **Clean Session persistence — THE DISCRIMINATING CHECK.** Clear the **Clean Session** checkbox,
   save, close and reopen the connected system object, and confirm it is **still cleared**.

   This is the one check that distinguishes the two possible platform behaviors. The check previously
   recorded here — "save with Clean Session untouched and confirm it persists as enabled" — **passes
   under both behaviors and proves nothing**, which is why it has been replaced. If Clean Session comes
   back **checked**, Appian is supplying only persisted object state rather than round-tripping the
   descriptor the template returned; the `firstRender` signal is then permanently `true`, and both the
   Clean Session default (§4) and the first-render validation suppression have to be reworked.
4. **First-render validation.** Open a brand-new **New > Connected System > MQTT Broker** and confirm
   **no** validation errors appear before any input — no "Broker host is required", no broker-port
   bounds message. Then touch a required field and clear it (type into Broker Host, delete it) and
   confirm the error **does** appear. Errors never appearing at all is the same underlying failure as
   Clean Session coming back checked: the freshness signal is stuck `true`. Errors appearing
   immediately on an untouched new object means the guard is not taking effect.
5. **Core 1.9.1 vs Appian's table (Core 1.2.0).** Unresolved, unchanged by this fix.
6. **Corrected 2026-10-13 — the `topicFilters` note previously recorded here was wrong.** It claimed a
   `ListTypePropertyDescriptor`'s generated default is an `Expression` object, so an untouched
   `topicFilters` could read back as a one-element list holding an expression's `toString()`. That is
   not what the SDK does. Re-verified against core 1.9.1 bytecode (`javap -p -c` on `StateGenerator`):
   `getDefaultState(PropertyDescriptor, int)` only takes the `SystemType.EXPRESSION` branch when
   `shouldRenderAsExpression` is true, and that method returns true only when
   `DisplayHint.EXPRESSION.equals(descriptor.getDisplayHint())` or when
   `descriptor.getTypeRef().isLocalType()` and `level > 1`. Otherwise it calls
   `generateDefaultValue(descriptor.getTypeRef(), level)`, whose `isListType()` branch returns
   `new ArrayList()`. The `topicFilters` descriptor is a `ListTypePropertyDescriptor` with
   `itemType(SystemType.STRING)` and `isExpressionable(true)` and **no** `displayHint(EXPRESSION)`, at
   level 1 — neither condition holds. `isExpressionable(true)` only lets a Designer switch the field
   into expression mode; it is **not** `displayHint(EXPRESSION)` and has no effect on default-state
   generation. So an untouched `topicFilters` generates an **empty list**, `readStringList` returns an
   empty list, and `InputValidator` reports the "1–10 filters" error — the correct outcome, reached for
   a different reason than was recorded. Leaving `readStringList` unchanged was still correct; only the
   reason was wrong.

---

## 8. Re-verification for 1.0.2 — first-render polish, 2026-10-13

`1.0.1` → `1.0.2` in `pom.xml` and `src/main/resources/appian-plugin.xml` (manifest `<version>` stays
three-part `x.y.z`; `<application-version min="26.9.0.0"/>` untouched). Design-time validation is now
gated on the same first-render signal as the Clean Session seed. Deploy
`target/mqtt-broker-connected-system-1.0.2-plugin.jar`.

`rm -rf target && mvn -o clean package`, then `mvn -o test` — both **BUILD SUCCESS**:

```
[INFO] Compiling 33 source files with javac [debug release 17] to target/classes
[INFO] Compiling 2 source files with javac [debug release 17] to target/test-classes
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0 -- in de.hock.mqtt.template.MqttConnectedSystemTemplateDescriptorTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in de.hock.mqtt.ProjectStructureTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
[INFO] Building jar: .../target/mqtt-broker-connected-system-1.0.2-plugin.jar
[INFO] BUILD SUCCESS
```

Test total moved **10 → 12**. Every §6 invariant re-checked against
`J=target/mqtt-broker-connected-system-1.0.2-plugin.jar`, all unchanged except the version string:
one jar; `.java` count **33**; every `.java` entry under `de/hock/mqtt/**` (`grep -vc 'de/hock/mqtt/'`
→ **0**); bare root `appian-plugin.xml` (**1**); `META-INF/lib/org.eclipse.paho.client.mqttv3-1.2.5.jar`
with no root `lib/`; **0** `connected-systems` entries; **0** `com/appian/mqtt` in the jar and no
source/`pom.xml` hit; **45** SDK imports; **0** test entries; `<version>1.0.2</version>`,
`min="26.9.0.0"`, `<vendor name="Hock" url="https://itondemand.com"/>`; bytecode **61, 61, 61**;
**28** label keys; all three templates still `extends Simple...`; **0** code-policy matches.

**Behavioral proof.** All 9 descriptor tests green, including
`firstRenderOfANewConnectedSystemHasNoValidationErrors` (no errors on any of the 20 fields on a
brand-new object), `blankBrokerHostIsReportedOnEverySubsequentRender` (the renamed — not deleted —
former `blankBrokerHostIsReportedAgainstItsField`, now asserting a clean first render *and* the error
on the second), and `enteringAValueClearsItsErrorWhileOtherFieldsStillReport` (a supplied value clears
only its own error, so `ConfigValidator` was not weakened). Temporarily replacing the
`if (!firstRender)` gate with `if (true)` fails exactly those three — the gate is load-bearing; it was
restored and the jar rebuilt from the restored source.
`cleanSessionDefaultsToTrueOnFirstRenderAndRespectsADeliberateFalse` passes unchanged (`TRUE` on the
first render, a deliberate `FALSE` preserved), and the NPE guards
`rootStateIsPopulatedForABrandNewConnectedSystem` and `rootTypeIsRegisteredAndResolvable` are both
still green — the descriptor fix is not regressed.

Observed while testing and deliberately **not** fixed: with the gate removed, an error attached on one
render survives into the next through `StateGenerator.generateFromExistingState` even after the field
is given a value. Pre-existing SDK state-cloning behavior, unreachable from a clean first render, out
of scope.

The consolidated hand-off report for this work is
[`connected-system-descriptor-fix.md`](connected-system-descriptor-fix.md).

**Final confirmation still requires the user redeploying the `1.0.2` jar to their own Appian 26.9
instance** — in particular §7 items 3 and 4, the two discriminating checks.
