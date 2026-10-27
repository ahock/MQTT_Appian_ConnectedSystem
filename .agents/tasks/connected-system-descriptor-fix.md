# Connected System descriptor fix — consolidated hand-off report

Date: 2026-10-13. Workspace: `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component`.
Target platform: Appian 26.9, Java 17, package `de.hock.mqtt`, groupId `de.hock`.
This project is **not** a git repository; every edit was made in place.

This report stands on its own. A reviewer who opens only this file gets the whole story — the
reported failure, why it happened, what was changed, what was verified offline, and what the user
must do on a live instance. Nothing below requires reading a sibling document.

**Artifact to deploy: `target/mqtt-broker-connected-system-1.0.2-plugin.jar`.**

---

## 1. Confirmed root cause and evidence

The user reported that **New > Connected System > MQTT Broker** failed with an expression-evaluation
error:

> Cannot invoke `PropertyState.setType(...)` because the return value of
> `ConfigurationDescriptor.getRootState()` is null

That message is literally accurate, and the cause is a one-line dereference.

`ConfigurationDescriptor.getRootState()` is implemented as `getState().get("root")`. For a brand-new
connected system Appian supplies a descriptor whose state map has **no `"root"` entry**, so
`getRootState()` returns `null`. The pre-fix code called `getRootState().setType(rootType)` directly,
which is `null.setType(...)`. All three templates — `MqttConnectedSystemTemplate`,
`MqttPublishIntegration`, `MqttSubscribeIntegration` — carried the same line, so the same crash was
reachable from all three design-time forms.

Evidence: a scratch probe compiled against `connected-systems-core:1.9.1` alone ran the pre-fix line
against `ConfigurationDescriptor.builder().build()` — the exact input Appian supplies for a new
object — and reproduced the user's message character for character. The probe lived under `/tmp` and
has been deleted; its role is now played by the committed test
`rootStateIsPopulatedForABrandNewConnectedSystem`, which asserts the `null`-root-state premise and
then asserts the rendered root state is non-null.

**A second, independent defect in the same three methods.** Even with a non-null state, the code used
`PropertyState.setType(LocalTypeDescriptor)`, which stores a derived `TypeReference` but does **not**
register the type in the descriptor's type map. The resulting state would have carried an
unresolvable `#/types/...` reference. This is guarded now by
`rootTypeIsRegisteredAndResolvable`, which asserts the root state's type reference resolves inside
`getTypes()`.

---

## 2. The fix chosen, and why

All three templates were migrated from the low-level `ConnectedSystemTemplate` /
`IntegrationTemplate` interfaces to the **`Simple*` facade**: `SimpleConnectedSystemTemplate` and
`SimpleIntegrationTemplate`. The hand-written `getConfigurationDescriptor(...)` overrides were
replaced by `getConfiguration(SimpleConfiguration, ExecutionContext)`, and `buildRootType()` /
`ROOT_TYPE_NAME` were deleted from each.

Why this rather than patching the null check on the low-level path:

- It is Appian's documented contract for a connected system in 26.9, so the plug-in stops fighting
  the framework.
- It removes the whole failure class permanently. The plug-in never constructs root state again, so
  no future edit can reintroduce the dereference.
- `PropertyState`'s constructor is **package-private**, so the only public route to a populated state
  was `StateGenerator` — which is exactly what `SimpleConfiguration.setProperties` runs. Hand-rolling
  the fix would have meant reimplementing, by hand, what the facade already does correctly.
- It fixes both defects at once: the facade generates the state *and* registers the local type.

The low-level path is retained as a **documented fallback**, not as a dependency change: if a live
instance turns out not to supply the client artifact, keep the `Simple*` shape's logic but build the
descriptor by hand with `StateGenerator` + `withType` + `withState`. Do **not** fall back by bundling
the client jar.

---

## 3. SDK coordinates, and why this pairing

| Artifact | Version | Scope | Bundled? |
|---|---|---|---|
| `com.appian:connected-systems-core` | `1.9.1` | `provided` | no |
| `com.appian:connected-systems-client` | `1.3.0` | `provided` | no |
| `org.eclipse.paho:org.eclipse.paho.client.mqttv3` | `1.2.5` | compile | yes, `META-INF/lib/` |

Core stays at **1.9.1 — no downgrade.** Appian's compatibility table lists Core 1.2.0 for 26.9, but
on Maven Central 1.2.0 dates from 2019-07-02 while 1.9.1 is 2023-11-07. Moving to 1.2.0 would mean
adopting a seven-year-old artifact and would likely break compilation. That contradiction is
unresolved and deliberately untouched by this work.

Client `1.3.0` is the right partner for core 1.9.1 for three concrete reasons:

1. It is core 1.9.1's own generation — the two were published a day apart (client 1.3.0 on
   2023-11-08, core 1.9.1 on 2023-11-07).
2. It declares **zero dependencies**, so it cannot transitively drag in a conflicting core.
3. The `Simple*` public API is identical across client 1.1.0 / 1.3.0 / 1.4.0, so swapping
   `${appian.sdk.client.version}` is a one-line change if a live instance demands a different one.

Both Appian artifacts are `scope provided` and neither is bundled — verified against the built jar in
§6.

---

## 4. The code path that now populates root state

Traced through `javap -c` on client 1.3.0 and core 1.9.1, not inferred:

```
inherited getConfigurationDescriptor(incoming, path, ctx)
  → SimpleConfiguration.from(incoming)            // null-tolerant: accepts a null root state
  → our getConfiguration(SimpleConfiguration, ExecutionContext)
      → configuration.setProperties(allProperties())
          → setPropertiesGeneric(...)
              • names the type via the null-safe getRootTypeName(), defaulting to the literal
                "RootType"
              • Map.put()s the generated LocalTypeDescriptor into the descriptor's type map
                  → this is what closes the second defect in §1
  → private getConfigurationDescriptor()
      = new StateGenerator(types).generateFromExistingState(rootType, getRootState())
          .withTypes(...)
          .withState(...)
```

The load-bearing step is `generateFromExistingState(rootType, existingState)`: it **short-circuits a
`null` existing state into `generateDefaultState`**, so the first render produces a fully populated
root state from the declared properties rather than throwing. Every consumer downstream —
`validate(PropertyState)`, `toConnectionConfig(PropertyState)`, all four validators, every
value-reading helper — receives the same `PropertyState` type it already accepted, so the bridge is
just `toConfiguration().getRootState()` and nothing downstream changed.

---

## 5. First-render behavior (added 2026-10-13)

`getConfiguration` already computed a `firstRender` boolean **before** `setProperties`, to seed the
Clean Session default:

```java
PropertyState priorState = configuration.toConfiguration().getRootState();
boolean firstRender =
    priorState == null
        || priorState.getValueAtPath(new PropertyPath(ConfigFields.CLEAN_SESSION)) == null;
```

The final `validate(...)` call is now gated on that **same** signal:

```java
if (!firstRender) {
  validate(configuration.toConfiguration().getRootState());
}
```

There is exactly **one** definition of freshness in the class, shared by the Clean Session seed and
the validation gate. No second signal was introduced — a "are all the text fields empty" heuristic
would drift from the Clean Session guard and could be fooled by a designer who clears every field.

**Ordering matters and is preserved.** `priorState` / `firstRender` are captured *before*
`setProperties`, because state generation pre-populates booleans with `Boolean.FALSE`; afterwards a
generated `false` would be indistinguishable from a designer's deliberate `false`.

**What the designer sees.** A brand-new connected system opens clean — no "Broker host is required",
no broker-port bounds message — because the generated state hands `validate()` `""` for `brokerHost`
and `null` for `brokerPort`, which `ConfigValidator` correctly rejects; there is simply nothing to
report yet. From the second render onward (the moment the form is touched or saved) every error is
reported against its own field with the entered values retained, exactly as before.

**`ConfigValidator` and the three sibling validators were not touched.** This change is purely about
*when* `validate()` is invoked. `validate(PropertyState)` keeps its signature and body.

**Round-trip assumption, and what breaks if it is wrong.** The signal reads `cleanSession` out of the
incoming state, so it is only reliable if Appian hands the template back a descriptor derived from the
one the template last returned. If the platform instead supplies only persisted object state,
`cleanSession` reads `null` on every render, `firstRender` stays permanently `true`, and **two**
behaviors break together: Clean Session can never be turned off, *and* design-time validation never
runs at all. This cannot be settled offline — see the discriminating check in §8.

**Observed while testing (pre-existing, deliberately not fixed).** With the gate temporarily removed,
`enteringAValueClearsItsErrorWhileOtherFieldsStillReport` failed with *"a supplied broker host must
not be reported as an error"*: an error attached on one render survives into the next through
`StateGenerator.generateFromExistingState`, even after the field is given a value. That is a
pre-existing characteristic of the SDK's state cloning, not something this change introduced, and it
is **unreachable from a clean first render** — with the gate in place no errors exist on render 1 to
leak into render 2. Recorded, not fixed; out of scope.

---

## 6. Invariant verification evidence — real output

`rm -rf target && mvn -o clean package`, then `mvn -o test`. Both **BUILD SUCCESS**.

```
[INFO] Compiling 33 source files with javac [debug release 17] to target/classes
[INFO] Compiling 2 source files with javac [debug release 17] to target/test-classes
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0 -- in de.hock.mqtt.template.MqttConnectedSystemTemplateDescriptorTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in de.hock.mqtt.ProjectStructureTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
[INFO] Building jar: .../target/mqtt-broker-connected-system-1.0.2-plugin.jar
[INFO] BUILD SUCCESS
```

Test total moved **10 → 12** (two new tests; one existing test renamed and rewritten, not deleted).

With `J=target/mqtt-broker-connected-system-1.0.2-plugin.jar`:

| Invariant | Command | Real result |
|---|---|---|
| exactly ONE jar | `ls target/*.jar` | `target/mqtt-broker-connected-system-1.0.2-plugin.jar` — one line |
| 33 `.java` sources | `unzip -l $J \| grep -c '\.java$'` | `33` |
| sources root-mirrored | `unzip -l $J \| grep '\.java$' \| grep -vc 'de/hock/mqtt/'` | `0` — every source sits under `de/hock/mqtt/**` |
| bare root manifest | `unzip -l $J \| grep -cE ' appian-plugin.xml$'` | `1`, listed as bare `appian-plugin.xml` with no directory prefix |
| Paho only under `META-INF/lib/` | `unzip -l $J \| grep 'lib/'` | `META-INF/lib/` and `META-INF/lib/org.eclipse.paho.client.mqttv3-1.2.5.jar` — no root `lib/` |
| no Appian artifact bundled | `unzip -l $J \| grep -c 'connected-systems'` | `0` — confirms both `provided` scopes |
| namespace, in jar | `unzip -l $J \| grep -c 'com/appian/mqtt'` | `0` |
| namespace, in sources | `grep -rn 'com\.appian\.mqtt' .` (excluding `target/`, `.agents/`, `.kiro/`) | no hits |
| SDK imports intact | `grep -rh 'import com.appian.connectedsystems' src/main/java/ \| wc -l` | `45` — the `com.appian.connectedsystems.*` imports a blind find-and-replace would corrupt |
| no test classes shipped | `unzip -l $J \| grep -ci test` | `0` |
| version / platform / vendor | `unzip -p $J appian-plugin.xml \| grep -E '<version>\|application-version\|<vendor'` | `<version>1.0.2</version>`, `min="26.9.0.0"`, `<vendor name="Hock" url="https://itondemand.com"/>` |
| Java 17 bytecode | `javap -verbose -cp target/classes <each template> \| grep 'major version'` | `major version: 61` on all three templates |
| label keys resolve | `grep -c '\.label=' src/main/resources/resources_en_US.properties` | `28` |
| all three on `Simple*` | `grep -rn 'extends Simple' src/main/java/` | `MqttConnectedSystemTemplate extends SimpleConnectedSystemTemplate`, both integrations `extends SimpleIntegrationTemplate` |
| code-policy sweep | `grep -rn 'System\.out\|System\.err\|printStackTrace\|java\.util\.logging\|System\.setProperty' src/main/java/` | no hits |

Note the two version fields are **different formats on purpose**: `<plugin-info><version>` is
three-part `x.y.z` (`1.0.2`) because the AppMarket validator rejects four-part there, while
`<application-version min="26.9.0.0"/>` is the four-part platform version and was not touched.

### Behavioral proof, by test

All 9 tests in `MqttConnectedSystemTemplateDescriptorTest` pass:

```
cleanSessionDefaultsToTrueOnFirstRenderAndRespectsADeliberateFalse
enteringAValueClearsItsErrorWhileOtherFieldsStillReport
firstRenderOfANewConnectedSystemHasNoValidationErrors
publishIntegrationPopulatesItsRootStateAndFourInputs
rootTypeIsRegisteredAndResolvable
allTwentyConfigFieldsArePresentInTheGeneratedState
rootStateIsPopulatedForABrandNewConnectedSystem
subscribeIntegrationPopulatesItsRootStateAndFourInputs
blankBrokerHostIsReportedOnEverySubsequentRender
```

- **First render is clean.** `firstRenderOfANewConnectedSystemHasNoValidationErrors` renders the
  empty descriptor and asserts no errors on **any** of the 20 config fields.
- **Validation still fires afterwards.** `blankBrokerHostIsReportedOnEverySubsequentRender` — the
  renamed and rewritten former `blankBrokerHostIsReportedAgainstItsField`, kept rather than deleted —
  asserts a clean first render *and* a non-empty `brokerHost` error list on the second.
- **A supplied value clears only its own error.**
  `enteringAValueClearsItsErrorWhileOtherFieldsStillReport` sets `brokerHost`, re-renders, and asserts
  `brokerHost` is clean while the still-blank `brokerPort` reports — proving `ConfigValidator` was not
  weakened or bypassed.
- **The gate is load-bearing, proved by negative check.** Temporarily replacing `if (!firstRender)`
  with `if (true)` makes exactly those three tests fail, with
  *"the first render of a new connected system must open clean, but field brokerHost reported [Broker
  host is required and must not be empty.]"*. The gate was restored and the suite re-run green, and
  the jar was rebuilt from the restored source.
- **Clean Session default, both directions.**
  `cleanSessionDefaultsToTrueOnFirstRenderAndRespectsADeliberateFalse` passes unchanged: `Boolean.TRUE`
  on the first render, and a deliberate `Boolean.FALSE` still `FALSE` on the second.
- **The descriptor NPE fix is not regressed.** `rootStateIsPopulatedForABrandNewConnectedSystem` is
  green with its `incoming.getRootState() == null` premise assertion intact, and
  `rootTypeIsRegisteredAndResolvable` still confirms the type map resolves.

---

## 7. The exact artifact to deploy

```
target/mqtt-broker-connected-system-1.0.2-plugin.jar
```

The `-plugin` suffix comes from the assembly `<id>` — **a jar without it is a stale build.** Exactly
one jar is produced by design (the default `maven-jar-plugin` execution is bound to phase `none`), so
there is no second archive that also carries `appian-plugin.xml` at its root and looks deployable.
Run `rm -rf target` before building so no earlier `1.0.1` jar lingers.

The version was bumped `1.0.1` → `1.0.2` in `pom.xml` and `src/main/resources/appian-plugin.xml`
precisely because Appian will not deploy a new build over an existing one at the same version.

---

## 8. What the user must do to confirm

Copy the jar to `APPIAN_INSTALL/_admin/plugins/` and watch `tomcat-stdOut.log` for
`Successfully installed Plug-in` plus the `ConnectedSystemTemplateModuleParser` registration lines.
Hot deployment is governed by the `conf.plugins.poll-interval` custom property; when it is `0`, hot
deployment is off and loading the plug-in needs an application server restart.

Then, in order:

1. **The render that originally failed.** Open **New > Connected System > MQTT Broker** and confirm
   the form renders all 20 fields with their labels resolved, instead of the expression-evaluation
   error.

2. **`NoClassDefFoundError` watch.** A `NoClassDefFoundError` naming
   `com/appian/connectedsystems/simplified/sdk/...` at install or first render means the server does
   **not** supply `connected-systems-client`. Fall back to the low-level option in §2 — do **not**
   bundle the client jar.

3. **Clean Session — THE DISCRIMINATING CHECK.** Clear the **Clean Session** checkbox, save, close
   and reopen the connected system object, and confirm it is **still cleared**.

   This is the one check that distinguishes the two possible platform behaviors. The older check —
   "save with Clean Session left untouched and confirm it persists as enabled" — **passes under both
   behaviors and therefore proves nothing**, which is exactly why it was replaced. If Clean Session
   comes back **checked**, Appian is supplying only persisted object state rather than round-tripping
   the descriptor the template returned; the `firstRender` signal is then permanently `true`, and both
   the Clean Session default and the first-render validation suppression must be reworked.

4. **First-render validation.** On a brand-new connected system, confirm **no** validation errors
   appear before any input — no "Broker host is required", no broker-port bounds message. Then touch a
   required field and clear it (type into Broker Host, then delete what you typed) and confirm the
   error **does** appear.

   Errors never appearing at all is the same underlying failure as Clean Session coming back checked:
   the freshness signal is stuck `true`. Errors appearing immediately on an untouched new object means
   the guard is not taking effect.

5. **Smoke-test the runtime.** No automated test exercises publish, subscribe, TLS, LWT, reconnection
   or diagnostics, so exercise them against a real broker before relying on the plug-in.

---

## 9. What remains unverifiable offline

1. **Whether the Appian server supplies `connected-systems-client`.** It is `provided` and therefore
   must come from the server. This is Appian's documented development path, but unverified on the
   target instance. Symptom and fallback in §8 item 2.
2. **The real UI render.** That all 20 fields appear, with labels resolved from the 28
   `resources_en_US.properties` entries, is a deploy-time observation.
3. **Whether Appian round-trips the template-returned descriptor.** This is the highest-value open
   question, because **two** behaviors ride on it — the Clean Session default *and* the first-render
   validation suppression. Settled only by §8 item 3.
4. **Core 1.9.1 vs Appian's compatibility table (Core 1.2.0).** Unresolved and deliberately untouched;
   see §3.
5. **The `META-INF/lib/` nested-dependency layout.** Verified present in the archive, never validated
   by an Appian classloader. A `NoClassDefFoundError` on a Paho class would be the symptom.
6. **The `SimpleConfiguration` `HashMap` concurrency note.** Inherent to Appian's facade; there is
   nothing in plug-in code to change.

Also still open and **out of scope here**, tracked in `.kiro/steering/appian-appmarket-compliance.md`:
the missing connected-system logo assets, the dead jqwik/moquette Surefire/Failsafe wiring, and the
five known pre-existing runtime bugs (inert `maxReconnectAttempts`; a key passphrase that cannot
unlock a private key; `MqttConnectionManager.classify` as a second divergent error-classification
path; the username embedded verbatim in the connection cache fingerprint while `Redactor` treats
username as a secret; the subscribe failure path dropping its `validationErrors` map). None is on this
code path.

**Final confirmation requires the user redeploying
`target/mqtt-broker-connected-system-1.0.2-plugin.jar` to their own Appian 26.9 instance.** Everything
in §6 is offline evidence about the artifact; nothing in it can prove how the platform behaves.

Appian documentation content referenced above was rephrased for compliance with licensing
restrictions.

---

## 10. Follow-up — first-render polish, 2026-10-13

Appended after the sections above were written, covering the changes this task made on top of the
descriptor fix.

**First-render validation.** `getConfiguration` now wraps its final `validate(...)` call in
`if (!firstRender) { ... }`, reusing the identical `firstRender` boolean that already seeded the Clean
Session default — one definition of freshness in the class. A brand-new connected system opens clean;
validation runs on every subsequent render and as soon as the designer enters anything. Neither
`ConfigValidator` nor any other validator class was changed, and `validate(PropertyState)` keeps its
signature and body: this is purely about *when* validation is invoked. The `priorState` capture stays
ahead of `setProperties` so a generated `Boolean.FALSE` is never mistaken for a deliberate one. Class
Javadoc and the `@return` line were updated to match. Detail in §5.

**Test evidence.** Suite total **10 → 12**, all green
(`Tests run: 12, Failures: 0, Errors: 0, Skipped: 0`). The former
`blankBrokerHostIsReportedAgainstItsField` was **not deleted** — it was renamed to
`blankBrokerHostIsReportedOnEverySubsequentRender` and now proves both halves: a clean first render
and the error reported on the second. Two tests were added:
`firstRenderOfANewConnectedSystemHasNoValidationErrors` (no errors on any of the 20 fields) and
`enteringAValueClearsItsErrorWhileOtherFieldsStillReport` (a supplied value clears only its own
error). A negative check — temporarily swapping the gate for `if (true)` — failed exactly those three
tests, confirming they are load-bearing; the gate was restored and the jar rebuilt. Full output and
test names in §6.

**Three record corrections.**
1. The consolidated hand-off report named by the previous brief but never produced is this file
   (§1–§9), written to stand alone rather than link out. The steering file's §8 audit trail now names
   the descriptor-fix records that actually exist, instead of only the `appmarket-*` ones.
2. The claim that a `ListTypePropertyDescriptor`'s generated default is an `Expression` object was
   **false** and is corrected in both `connected-system-descriptor-fix-plan.md` §3 step 5 and
   `connected-system-descriptor-fix-verification.md` §7. Re-verified against core 1.9.1 bytecode
   (`javap -p -c` on `StateGenerator`): `getDefaultState` takes the `SystemType.EXPRESSION` branch
   only when `shouldRenderAsExpression` is true, which requires either
   `DisplayHint.EXPRESSION.equals(getDisplayHint())` or a local type below level 1; otherwise it calls
   `generateDefaultValue(descriptor.getTypeRef(), level)`, whose `isListType()` branch returns
   `new ArrayList()`. `topicFilters` is a list of `SystemType.STRING` with `isExpressionable(true)` and
   no `displayHint(EXPRESSION)` at level 1, so it is neither case and generates an **empty list**.
   `readStringList` returns an empty list and `InputValidator` reports the "1–10 filters" error — the
   correct outcome. Leaving `readStringList` unchanged was still right; only the recorded reason was
   wrong. `isExpressionable(true)` is not `displayHint(EXPRESSION)` and does not affect default-state
   generation.
3. The plan's §4 gate block claimed `~47` SDK imports. The real figure, measured, is **45**; the block
   now says 45 and also carries the aggregate form that actually yields one number
   (`grep -rh ... | wc -l`), since `grep -rc` prints a per-file count.

**Two new deploy-checklist items**, added to both `connected-system-descriptor-fix-plan.md` §5 and
`connected-system-descriptor-fix-verification.md` §7, and reproduced in §8 above:
- The **discriminating** Clean Session check — clear the checkbox, save, reopen, confirm it is still
  cleared. The old "untouched Clean Session persists as enabled" check passed under both platform
  behaviors and proved nothing, so it was replaced rather than supplemented.
- A first-render validation check — no errors on a brand-new object before any input, errors present
  once a required field is touched and cleared.

**New artifact filename.** `1.0.1` → `1.0.2` in `pom.xml` and `src/main/resources/appian-plugin.xml`
(manifest `<version>` stays three-part `x.y.z`; `<application-version min="26.9.0.0"/>` untouched).
The submission and deployment artifact is now
**`target/mqtt-broker-connected-system-1.0.2-plugin.jar`**. `README.md`, `docs/mqtt-plugin-guide.md`
and `.kiro/steering/appian-appmarket-compliance.md` were updated in lockstep, including the README's
new round-trip-assumption limitation. The `.java` count is **unchanged at 33** — only a test file
changed, and the assembly `fileSet` covers `src/main/java` only.

---

## 11. Third fix — expressionability, and the document fields it forced out, 2026-10-14 (1.0.2 → 1.0.3)

The third successive platform-contract failure found only at deploy time. Sections 1–10 above record
the first two and are unchanged.

### The platform rule and the evidence

Creating a Connected System object on the live 26.9 instance failed with:

```
Fehler beim Auswerten des UI-Ausdrucks
Fehler bei der Ausdrucksauswertung [evaluation ID = 3a66a:d6bd1] :
Property Descriptors in Connected System Template must not be Expressionable
```

Appian reports this as `APNX-1-4198-000`, raised from
`ConnectedSystemPropertiesValidator.checkPropertiesModifiers` (via its `expressionableGuard`
lambda) during `ConfigurationDescriptorToValueConverter.apply`, called from
`GetConnectedSystemConfigurationDescriptorFunction.eval`. The rule is absolute: a connected system
template may declare **no** expressionable property descriptor, and the check runs at
descriptor-conversion time, so the configuration form never renders. Documented on the 26.9
[restrictions and limitations page](https://docs.appian.com/suite/help/26.9/restrictions-and-limitations.html).
The same page states that integration input descriptors may be expressionable and that a
`ListTypePropertyDescriptor` **must** be — the two halves are genuinely asymmetric.

### What was removed, and what was deliberately preserved

Removed: all **14** `.isExpressionable(true)` calls from
`src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java` — `brokerHost`, `brokerPort`,
`clientIdentifier`, `connectionTimeoutSeconds`, `username`, `tlsEnabled`, `autoReconnectEnabled`,
`maxReconnectAttempts`, `reconnectIntervalSeconds`, `lwtTopic`, `lwtPayload`, `lwtRetained`,
`cleanSession`, `keepAliveSeconds`. Only the single-line builder call was deleted from each chain;
every key, label, instruction text, type, `isRequired`, `masked`, `displayMode` and `choices` is
untouched. The six descriptors that never declared it — `password`, `caCertificate`,
`clientCertificate`, `clientPrivateKey`, `keyPassphrase`, `lwtQos` — were left alone. 14 + 6 = 20.

Preserved: the integrations' **4 + 3** calls. `MqttPublishIntegration` keeps all four
(`topic`, `payload`, `qos`, `retained`); `MqttSubscribeIntegration` keeps three (`topicFilters`,
`maxWaitSeconds`, `maxMessageCount`) and `qos` remains non-expressionable, which is the pre-existing
asymmetry. Removing these would have been a functional regression, and `topicFilters` is a list input
that Appian requires to be expressionable. Repo-wide the count moved **21 → 7**, all seven in the
integration package, **0** in the template. No `sed` or repo-wide replace was used.

### The removal was necessary but not sufficient — the forced-flag trap

Deleting the 14 calls left **2 of the 20** descriptors still reading back expressionable:
`caCertificate` and `clientCertificate`, both `DocumentPropertyDescriptor`. The SDK sets the flag
itself and will not give it up. From `connected-systems-core:1.9.1` bytecode:

- `DocumentPropertyDescriptor$DocumentPropertyDescriptorBuilder.<init>` calls
  `isExpressionable(true)` (`iconst_1`).
- Its `checkValidState()` calls `Guard.checkPropertyIsExpressionable(data)`, which throws
  `IllegalStateException("<TYPE> property descriptors must be expressionable")` when the flag is
  false.

Confirmed by execution, not inference:
`DocumentPropertyDescriptor.builder().key("probe").label("Probe").isExpressionable(false).build()`
throws `java.lang.IllegalStateException: DOCUMENT property descriptors must be expressionable`.

The SDK therefore requires a document field to be expressionable while Appian forbids any
expressionable field on a connected system template. **A document-upload field cannot exist on a
connected system template with this SDK version.** A sweep of every builder in the jar found the full
set of affected types: `Document` and `AppianValue` force the flag and reject clearing it;
`Folder`, `Timestamp` and `ListType` set it in their constructors (ListType also sets
`displayHint(EXPRESSION)` and couples the two in its own guard); all other types inherit the base
builder's `false`. The table now lives in §1 of the steering file.

**A source grep cannot prove compliance.** `grep -c isExpressionable` on the template reads `0` while
the built descriptor still carried two expressionable fields. Only reading the modifier back off the
rendered descriptor — which is what the platform validator walks — catches this.

### Consequence: both certificate fields became pasted PEM

Decided by the user after the finding was escalated; the alternatives (dropping the fields, or
deploying to re-confirm the error) were rejected. Both fields are now
`ParagraphPropertyDescriptor` — the SDK's only multi-line text input, since `DisplayHint` offers
only `NORMAL` and `EXPRESSION` and `TextPropertyDescriptor` has no textarea option. **The field keys
`caCertificate` and `clientCertificate` are unchanged**, so `ConfigFields`, the 28 label keys, and
persisted value addressing all still resolve. Labels became "CA Certificate (PEM)" and
"Client Certificate (PEM)"; the instruction text tells the Designer to paste the PEM including its
`-----BEGIN/END CERTIFICATE-----` lines.

**The old document path was dead code.** `toConnectionConfig` could only ever produce certificate
bytes when the saved value already carried them; a saved document reference is an opaque id this SDK
version offers no accessor to resolve, as the method's own Javadoc conceded before this change. The
whole runtime chain (`MqttConnectionConfig` → `ConnectOptionsFactory.buildTrustManagers` /
`buildKeyManagers` → `CertificateLoader`) consumes `byte[]` and never resolves a document. So **TLS
with a custom CA or a client certificate was non-functional before 1.0.3 and is expected to work for
the first time now** — this change is a fix, not a regression. `clientPrivateKey` was already a text
field, so the three TLS material fields are now consistent. Certificates are public key material, so
holding them as text is not a secret-handling downgrade; the private key and passphrase remain
`EncryptedTextPropertyDescriptor` with `masked(true)`.

`resolveDocumentBytes` was renamed `resolveCertificateBytes` with **no behavior change** — the
`byte[]` and `Document` branches are retained so an upgraded object cannot throw.

### Migration consequence — breaking for existing objects

A connected system saved by 1.0.2 or earlier holds a document reference where PEM text is now
expected; read as text it is typically a bare numeric id, and it will not parse. Nothing that
previously worked is lost (see the dead-code finding above), but the field must be re-entered.

Mitigated by a new `de.hock.mqtt.validation.PemValidator`, which follows the existing validator
pattern (field-scoped `ValidationResult`s composed into `ValidationResults`) and is wired into
`validate` behind the existing TLS gate. Any non-blank certificate value with no `-----BEGIN` line is
reported on its own field with a message naming the expected header and the upgrade case, instead of
failing opaquely at handshake time. Blank stays valid — both certificates are optional.

### Blast radius on the first two fixes: none

`StateGenerator` reads `isExpressionable()` in exactly one place, `shouldKeepOldState`, whose first
branch requires `oldState.isExpression()`. `cleanSession` holds a plain `Boolean`, so that branch was
never taken; the second branch (type equality plus a non-`EXPRESSION` display hint) decides
identically before and after. Default-state generation never consults the flag. Predicted no change
to the Clean Session seed or the `firstRender` gate — and all 12 pre-existing tests passed unmodified
at every step, which is the actual evidence.

One migration note for completeness: a field whose *saved* value was an `Expression` would now fail
both `shouldKeepOldState` branches and be regenerated to its default, discarding the expression. No
such state can exist here, because all three errors blocked **New > Connected System** from ever
completing on this instance.

### Tests — 12 → 19

Seven added to `MqttConnectedSystemTemplateDescriptorTest`, all reading modifiers through the SDK's
public `PropertyDescriptor` accessors (`isExpressionable()`, `getRefresh()`, `isHidden()` are all
public), never by grepping source and never by reflection:

- `noConnectedSystemPropertyDescriptorIsExpressionable` — the acceptance criterion. Reports **every**
  offender rather than the first, which is what surfaced both document fields at once. Asserts 20
  descriptors were inspected so it cannot pass vacuously. **Verified load-bearing:** re-adding
  `.isExpressionable(true)` to `brokerHost` fails it with
  `[brokerHost (TextPropertyDescriptor)]`; removing it again returns the suite to green.
- `integrationInputExpressionabilityIsPinnedPerInput` — pins the other half per key, asymmetry
  included, so a future repo-wide cleanup cannot strip working integration behavior.
- `connectedSystemDescriptorsSatisfyThePlatformModifierRules` — `RefreshPolicy.NEVER`, not hidden,
  and not a `List`/`Document`/`AppianValue` descriptor type.
- `certificateFieldsAreMultiLinePastedTextNotDocuments`.
- `pastedPemCertificateReachesTheTlsLayerAsBytes` — the end-to-end handoff: a pasted PEM string →
  `resolveCertificateBytes` UTF-8 bytes → `MqttConnectionConfig` → `ConnectOptionsFactory.build`,
  asserting a non-null socket factory, which `buildTrustManagers` only produces if
  `CertificateFactory` really parsed a certificate out of the pasted text. The fixture is a throwaway
  self-signed **certificate only** — public key material, no private key, keystore or passphrase
  anywhere in the repository.
- `aLegacyDocumentReferenceInACertificateFieldIsReportedAsNonPem` and
  `validPastedPemIsNotReportedAsAnError`.

### Carried forward, not fixed

- **`isImportCustomizable` on the three encrypted fields.** A documented "should", absent from the
  stack trace, and enabling it changes cross-environment deployment behavior.
- **Whether `setErrors()`-is-ignored covers `setErrorAtPath`.** Unresolved; if it does, design-time
  validation and the first-render gate are decorative. Top deploy-time question.
- **A reported field error is never cleared.** `validate` only attaches errors and never calls
  `clearErrorsAtPath`, so an error set on one render survives the next even after the value is
  corrected. Pre-existing, independent of all three fixes, and visible only under the same
  round-trip assumption as §5.
- **The platform validators are not inspectable offline** — `com.appiancorp.*`, absent from both SDK
  jars, and no Appian install on this machine. SDK-side guards were the limit, which is exactly how
  the forced-flag trap was found.
- One plan assumption corrected: the `ListTypePropertyDescriptor` display-hint "latent violation" does
  not exist. Its builder constructor already sets `displayHint(EXPRESSION)`, and the rendered
  `topicFilters` descriptor reports `hint=EXPRESSION`, confirmed by execution.

### New artifact filename

`1.0.2` → `1.0.3` in `pom.xml` and `src/main/resources/appian-plugin.xml` (manifest `<version>` stays
three-part `x.y.z`; `<application-version min="26.9.0.0"/>` untouched). The submission and deployment
artifact is **`target/mqtt-broker-connected-system-1.0.3-plugin.jar`**. The shipped `.java` count
moves **33 → 34** with the addition of `PemValidator.java`. `README.md` and
`.kiro/steering/appian-appmarket-compliance.md` were updated in lockstep.

### Added to the §8 deploy checklist

- Open **New > Connected System > MQTT Broker** and confirm the form renders all 20 labelled fields
  instead of failing with `APNX-1-4198-000`.
- Confirm the fields accept literal values only, with no expression toggle. That is expected, not a
  regression.
- Confirm **CA Certificate (PEM)** and **Client Certificate (PEM)** render as multi-line text boxes,
  and that pasting a PEM certificate and saving works.
- On any connected system carried over from 1.0.2 with a certificate document selected, confirm the
  field reports the non-PEM error and that pasting the PEM clears it on save.
