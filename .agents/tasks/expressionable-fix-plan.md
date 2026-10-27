# Implementation plan — remove expressionability from the Connected System descriptor (1.0.2 → 1.0.3)

Workspace: `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component`. **Not a git repository —
edit in place. Do not run any `git` command.** Target: Appian 26.9, Java 17, package `de.hock.mqtt`.

## The failure being fixed

Creating a new Connected System object on the live 26.9 instance fails with
`Property Descriptors in Connected System Template must not be Expressionable (APNX-1-4198-000)`,
thrown as an `IllegalStateException` from
`com.appiancorp.connectedsystems.templateframework.functions.validation.ConnectedSystemPropertiesValidator.lambda$expressionableGuard$3`
→ `checkPropertiesModifiers` → `ConfigurationDescriptorToValueConverter.apply` →
`GetConnectedSystemConfigurationDescriptorFunction.eval`. Appian rejects **any** expressionable
property descriptor on a connected system template, at descriptor-conversion time. No diagnosis is
needed; the fix is to stop declaring them.

Third in a sequence. The first two are fixed and **must not be revisited**: the `getRootState()` NPE
(fixed by the `Simple*` migration) and first-render validation errors (fixed by the `firstRender`
gate).

## Findings from exploration that shape this plan

Measured against the current 1.0.2 tree, baseline `mvn -o clean package` **BUILD SUCCESS**,
`Tests run: 12, Failures: 0, Errors: 0`.

**1. The scope figure is confirmed.** `grep -rc isExpressionable src/main/java/...` returns
`MqttConnectedSystemTemplate.java:14`, `MqttPublishIntegration.java:4`,
`MqttSubscribeIntegration.java:3` — 21 repo-wide today, 7 after the fix. The 14 template occurrences
sit at lines 247, 254, 262, 269, 280, 297, 335, 342, 349, 358, 365, 382, 393, 400 exactly as the
brief states. Line numbers shift as deletions are made, so item 1 below identifies them by **field
key**, which is stable.

**2. Appian documents the rule, and nine siblings alongside it.** The authority is
[Design Considerations / Restrictions and Limitations (26.9)](https://docs.appian.com/suite/help/26.9/restrictions-and-limitations.html),
whose connected-system restrictions list includes that fields must not be expressionable, must use a
`RefreshPolicy` of `NEVER`, must not be `LIST` type, and must not deeply nest local types — and,
separately, that `ListTypePropertyDescriptor`s **must** be expressionable. That last line
independently confirms the integrations' `topicFilters` must keep its `isExpressionable(true)`.
Content rephrased for licensing compliance.

**3. The validator cannot be decompiled.** The brief asks for it, so record the negative result
rather than retrying: `ConnectedSystemPropertiesValidator` lives in `com.appiancorp.*`, which is
**platform-side, not SDK-side**. `connected-systems-core-1.9.1.jar` contains **0** `com/appiancorp/`
entries and **0** entries matching `functions`; `connected-systems-client-1.3.0.jar` holds exactly
nine `com/appian/connectedsystems/simplified/sdk/**` classes. No Appian installation exists on this
machine (`/opt/appian*`, `~/appian*`, `~/Appian*`, `/Applications/Appian*` all absent). The
restrictions page above is therefore the real available authority, and item 10 works from it instead.

**4. Blast radius on `CLEAN_SESSION` is clear — the behavior does not change.** Decompiled
`StateGenerator` (`javap -p -c`, core 1.9.1): `isExpressionable()` is referenced in **exactly one
place**, inside `shouldKeepOldState(PropertyDescriptor, PropertyState)`, which decodes to

```java
if (oldState.isExpression() && descriptor.isExpressionable()) return true;
return oldState.getType().equals(descriptor.getTypeRef())
    && !DisplayHint.EXPRESSION.equals(descriptor.getDisplayHint());
```

`PropertyState.isExpression()` is `TypeReference.from(SystemType.EXPRESSION).equals(this.type)`.
`cleanSession` is a `BooleanPropertyDescriptor` whose state holds a plain `Boolean`, so
`isExpression()` is `false`, the first branch never applies, and the second branch — type equality
plus a `NORMAL` display hint — decides identically before and after the change. **Default-state
generation does not consult `isExpressionable` at all**: `getDefaultState` branches on
`shouldRenderAsExpression`, which checks only `DisplayHint.EXPRESSION` and local-type nesting depth.
The `firstRender` signal (`priorState.getValueAtPath(CLEAN_SESSION) == null`) and the Clean Session
`true` seed are therefore untouched. Item 7 pins this with tests rather than trusting the analysis.

**5. One real migration consequence, worth a sentence in the record.** A field whose *saved* value is
an `Expression` will now fail both `shouldKeepOldState` branches (branch 1 because the descriptor is
no longer expressionable, branch 2 because `EXPRESSION` ≠ the declared `STRING`/`INTEGER`/`BOOLEAN`
type ref) and be regenerated to its default — the stored expression is silently discarded. In
practice no such state can exist, because all three errors blocked **New > Connected System** from
ever completing on this instance. Record it in item 11; do not build migration handling.

**6. `isExpressionable()` is NPE-safe to read after the fix.** `PropertyDescriptor.isExpressionable()`
is `((Boolean) data.get("isExpressionable")).booleanValue()` — an unguarded unbox — but
`PropertyDescriptorBuilder`'s constructor seeds `isExpressionable → Boolean.FALSE` (alongside
`isRequired`, `isReadOnly`, `isImportCustomizable`, `isHidden` false, `refresh → NEVER`,
`displayHint → NORMAL`). Descriptors that never call the setter read back `false`, so the test in
item 7 is safe across all 20 fields.

**7. The SDK does expose the modifier for reading, so the test can be real.**
`PropertyDescriptor.isExpressionable()` is `public`, and `LocalTypeDescriptor.getProperties()`
returns `List<PropertyDescriptor>`. Reaching them from a rendered descriptor —
`rendered.getTypes().get(rendered.getRootState().getType().getUnqualifiedTypeName()).getProperties()`
— is the pattern the existing test class already uses, and it inspects the very object graph
`ConnectedSystemPropertiesValidator` walks. No source grepping, no reflection, no faking.

## Design decisions

- **New tests go into the existing `MqttConnectedSystemTemplateDescriptorTest`**, not a new class.
  The class already renders all three templates from `ConfigurationDescriptor.builder().build()` and
  owns the 20-key `CONFIG_FIELD_KEYS` list; a second class would duplicate both. Suite stays at two
  classes, 12 → 16 tests.
- **Expressionability is asserted per key, not in aggregate**, because the two halves of the platform
  rule are genuinely asymmetric and so is the current code: all 4 publish inputs are expressionable,
  but only 3 of 4 subscribe inputs are (`qos` is not). An aggregate "all integration inputs are
  expressionable" assertion would be false today and would push someone to "fix" working code.
- **The fourth test pins the other documented connected-system rules** (`RefreshPolicy.NEVER`, not
  hidden, no `LIST`-typed field) even though nothing violates them now. It is a handful of lines over
  descriptors already in hand, and this is the third contract failure found only at deploy time —
  cheap insurance against the fourth.
- **Latent findings from item 10 are reported, not fixed.** Both candidates found (below) change what
  the designer sees or how deployment behaves, which puts them outside the "same mechanical class"
  licence in the brief.

---

# Implementation Plan

- [ ] 1. Delete all 14 `.isExpressionable(true)` builder calls from the Connected System template,
      and nothing else.
      Edit only `src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java`, inside
      `allProperties()`. Remove the whole single-line `.isExpressionable(true)` call from each of
      these 14 descriptors, identified by field key (line numbers shift as you delete): `brokerHost`,
      `brokerPort`, `clientIdentifier`, `connectionTimeoutSeconds`, `username`, `tlsEnabled`,
      `autoReconnectEnabled`, `maxReconnectAttempts`, `reconnectIntervalSeconds`, `lwtTopic`,
      `lwtPayload`, `lwtRetained`, `cleanSession`, `keepAliveSeconds`. Keep `key`, `label`,
      `instructionText`, `isRequired`, `masked`, `displayMode`, `choices` and the type of every
      descriptor exactly as they are, and keep each fluent chain compiling (`.build()` must still
      terminate it). The six descriptors that already omit the call — `password`, `caCertificate`,
      `clientCertificate`, `clientPrivateKey`, `keyPassphrase`, `lwtQos` — are untouched; 14 + 6 = 20.
      **Do not** run a repo-wide `sed`, find-and-replace, or any edit against the integration files:
      `MqttPublishIntegration.java` keeps all 4 of its calls and `MqttSubscribeIntegration.java` keeps
      all 3, because integration input descriptors are permitted to be expressionable and removing
      that is a functional regression.
      Files: `src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java`
      Verify: `mvn -o clean package` → BUILD SUCCESS with `Tests run: 12, Failures: 0, Errors: 0`
      (the existing suite must stay green before any new test is added, which is the real proof that
      state generation and the Clean Session default did not shift). Then
      `grep -c isExpressionable src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java`
      → `0`, and `grep -rn isExpressionable src/main/java/ | wc -l` → `7`, with
      `grep -rc isExpressionable src/main/java/de/hock/mqtt/integration/` showing
      `MqttPublishIntegration.java:4` and `MqttSubscribeIntegration.java:3`.

- [ ] 2. Update the class Javadoc on `MqttConnectedSystemTemplate` to record the platform rule.
      Add a short paragraph stating that Appian forbids expressionable property descriptors on a
      connected system template — `ConnectedSystemPropertiesValidator.checkPropertiesModifiers`
      rejects the descriptor at conversion time with "Property Descriptors in Connected System
      Template must not be Expressionable" — so no field here may call `isExpressionable(true)`, and
      note that integration input descriptors are not subject to the rule. This is the signpost that
      stops the next editor reintroducing it; keep it to a few lines.
      Files: `src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java`
      Verify: `mvn -o clean package` → BUILD SUCCESS (Javadoc-only change must not break compilation).

- [ ] 3. Add the regression test that fails if any connected system descriptor becomes expressionable.
      Add `noConnectedSystemPropertyDescriptorIsExpressionable()` to
      `src/test/java/de/hock/mqtt/template/MqttConnectedSystemTemplateDescriptorTest.java`. Render the
      connected system through the existing `renderConnectedSystem(emptyDescriptor())` helper, resolve
      the root `LocalTypeDescriptor` via
      `rendered.getTypes().get(rendered.getRootState().getType().getUnqualifiedTypeName())` (the
      pattern `allTwentyConfigFieldsArePresentInTheGeneratedState` already uses), then iterate
      `rootType.getProperties()` and `assertFalse(descriptor.isExpressionable(), ...)` naming the key
      in the failure message. Assert the iteration actually covered 20 descriptors so an empty list
      can never pass vacuously. Read the flag only through the SDK's public
      `PropertyDescriptor.isExpressionable()` — no source grepping and no reflection; the builder
      seeds the flag `Boolean.FALSE`, so unset descriptors read back `false` without an NPE.
      Files: `src/test/java/de/hock/mqtt/template/MqttConnectedSystemTemplateDescriptorTest.java`
      Verify: `mvn -o test` → `Tests run: 13` in the suite, all passing. Then prove the test is
      load-bearing: temporarily re-add `.isExpressionable(true)` to the `brokerHost` descriptor, run
      `mvn -o test`, confirm **this test fails** naming `brokerHost`, then remove it again and
      re-run green. Record both outcomes for item 11.

- [ ] 4. Add the test that pins the other half of the rule — integration inputs stay expressionable.
      In the same test class, add `integrationInputExpressionabilityIsPinnedPerInput()`. Render each
      integration with
      `new MqttPublishIntegration().getConfigurationDescriptor(emptyDescriptor(), emptyDescriptor(), new PropertyPath(), null)`
      (and the subscribe equivalent), resolve each root type the same way, and assert **per key**
      against the current, deliberately asymmetric code: publish `topic`, `payload`, `qos`,
      `retained` are all expressionable; subscribe `topicFilters`, `maxWaitSeconds`,
      `maxMessageCount` are expressionable while `qos` is **not**. Use
      `rootType.getProperty(key).orElseThrow()` so a renamed key fails loudly. Add a comment that
      Appian's restrictions page requires `ListTypePropertyDescriptor`s to be expressionable, which
      is why `topicFilters` must keep the flag. This test is what stops a future cleanup from
      stripping expressionability repo-wide.
      Files: `src/test/java/de/hock/mqtt/template/MqttConnectedSystemTemplateDescriptorTest.java`
      Verify: `mvn -o test` → `Tests run: 14`, all passing.

- [ ] 5. Add the test pinning the remaining documented connected-system descriptor rules.
      Add `connectedSystemDescriptorsSatisfyThePlatformModifierRules()` to the same class, iterating
      the 20 root-type descriptors and asserting, per Appian's 26.9 restrictions page:
      `getRefresh() == RefreshPolicy.NEVER`, `!isHidden()`, and that no descriptor is a
      `ListTypePropertyDescriptor` (connected system fields must not be `LIST` type). Nothing
      violates these today — this is a guard against the next deploy-time contract surprise, written
      now because the descriptors are already in hand.
      Files: `src/test/java/de/hock/mqtt/template/MqttConnectedSystemTemplateDescriptorTest.java`
      Verify: `mvn -o test` → `Tests run: 15`, all passing.

- [ ] 6. Update the test class Javadoc to describe the expanded scope.
      The existing Javadoc describes only the root-state NPE and first-render behavior. Add the
      third concern: that the class now also pins the platform's descriptor-modifier rules, with the
      expressionability rule asymmetric between connected system (forbidden) and integration inputs
      (permitted, and required for list types).
      Files: `src/test/java/de/hock/mqtt/template/MqttConnectedSystemTemplateDescriptorTest.java`
      Verify: `mvn -o test` → suite still green.

- [ ] 7. Confirm the blast radius explicitly, before touching any documentation.
      No code change — this is a gate. Run `mvn -o test` and confirm all four pre-existing
      behavioral tests still pass unmodified:
      `cleanSessionDefaultsToTrueOnFirstRenderAndRespectsADeliberateFalse` (Clean Session is `TRUE`
      on first render and a deliberate `FALSE` survives the next render),
      `firstRenderOfANewConnectedSystemHasNoValidationErrors`,
      `blankBrokerHostIsReportedOnEverySubsequentRender`,
      `enteringAValueClearsItsErrorWhileOtherFieldsStillReport` — together these prove the
      `firstRender` signal, the Clean Session default, and field-scoped validation all survive.
      Also confirm `rootStateIsPopulatedForABrandNewConnectedSystem` and
      `rootTypeIsRegisteredAndResolvable` still pass (fix #1 not regressed) and that
      `allTwentyConfigFieldsArePresentInTheGeneratedState` still passes (all 20 fields present). If
      any of these fails, **stop** and report before editing docs — the `StateGenerator` analysis in
      finding 4 would be wrong and the plan needs rework.
      Files: none
      Verify: `mvn -o test` → `Tests run: 15, Failures: 0, Errors: 0, Skipped: 0`; no test modified
      to make it pass.

- [ ] 8. Bump the version 1.0.2 → 1.0.3 in both manifests.
      `pom.xml` line 9 `<version>1.0.2</version>` → `1.0.3`. `src/main/resources/appian-plugin.xml`
      line 29 `<version>1.0.2</version>` → `1.0.3` — three-part `x.y.z`, because the AppMarket
      validator rejects a four-part value in this field. **Do not touch** line 30
      `<application-version min="26.9.0.0"/>`; that field is the four-part platform version and is
      correct. Appian will not deploy a new build over an existing one at the same version, so this
      bump is what makes the fix installable.
      Files: `pom.xml`, `src/main/resources/appian-plugin.xml`
      Verify: `rm -rf target && mvn -o clean package` → builds
      `target/mqtt-broker-connected-system-1.0.3-plugin.jar`, and
      `ls target/*.jar` lists exactly **one** jar. Then
      `unzip -p target/mqtt-broker-connected-system-1.0.3-plugin.jar appian-plugin.xml | grep -E '<version>|application-version|<vendor'`
      → `<version>1.0.3</version>`, `min="26.9.0.0"`,
      `<vendor name="Hock" url="https://itondemand.com"/>`.

- [ ] 9. Correct the README's expressionability claims and bump its version references.
      Six edits in `README.md`:
      (a) line 10 `**Version:** \`1.0.2\`` → `1.0.3`; lines 62 and 76 artifact path
      `mqtt-broker-connected-system-1.0.2-plugin.jar` → `-1.0.3-plugin.jar`.
      (b) **Line 96, the backwards sentence.** "All fields are expressionable except `password`, …"
      is now the opposite of the truth. Replace it with a statement that **no** connected system
      configuration field is expressionable, because Appian forbids expressionable property
      descriptors on a connected system template, and point at the **integration inputs** as where
      expressions are supported. Keep the two sentences that follow it (field-scoped validation
      retaining entered values, and the clean first render) — they are still accurate.
      (c) The field-reference tables under "Configuring the Connected System" have columns
      Label/Key/Type/Required/Default/Validation and **no** expressionability column or per-field
      note, so no table edit is required. Confirm this by reading them rather than assuming, and
      leave them alone if it holds.
      (d) **Line ~409, Security notes.** "use Appian's Secure Credentials Store and reference it from
      the expressionable fields" is no longer possible on the connected system. Rewrite it honestly:
      connected system fields take literal values only, so an expression referencing a Secure
      Credentials Store field cannot be used there; the encrypted + masked `password`,
      `clientPrivateKey` and `keyPassphrase` fields are the mechanism for secrets on the connected
      system, and expressions (including SCS references) are available on the **integration** inputs.
      (e) **Integration input docs, per input, from the actual code.** Under "Using the Publish
      integration → Inputs", state that all four inputs (`topic`, `payload`, `qos`, `retained`)
      accept expressions. Under "Using the Subscribe integration → Inputs", state that
      `topicFilters`, `maxWaitSeconds` and `maxMessageCount` accept expressions and that `qos` does
      **not** — that asymmetry is real in the code and must be documented as such, not smoothed over.
      (f) **Known limitations.** Update the test-coverage bullet from the current description to 16
      tests in two classes, naming the new descriptor-modifier coverage, and add a short bullet that
      connected system configuration fields cannot be driven by expressions (an Appian platform
      restriction, not a design choice), so per-environment values must come from separate connected
      system objects or deployment-time customization rather than an expression.
      Files: `README.md`
      Verify: `grep -rn -i 'expressionab' README.md` — every remaining hit reads correctly in context
      (no surviving claim that connected system fields are expressionable); `grep -n '1\.0\.2' README.md`
      → no hits. `grep -rn -i 'expressionab' docs/mqtt-plugin-guide.md` → no hits and no `1.0.2`
      references there either, confirmed during exploration, so the guide needs no edit; re-check and
      leave it untouched if that holds.

- [ ] 10. Investigate the remaining platform modifier rules and record the result.
      No Appian platform jar is available to decompile: `ConnectedSystemPropertiesValidator` is
      `com.appiancorp.*`, and `connected-systems-core-1.9.1.jar` contains 0 `com/appiancorp/` entries
      while `connected-systems-client-1.3.0.jar` contains only the nine
      `com/appian/connectedsystems/simplified/sdk/**` classes; no Appian installation exists on this
      machine. **State that negative result plainly in the report instead of implying the bytecode was
      read.** Work instead from Appian's
      [26.9 restrictions and limitations page](https://docs.appian.com/suite/help/26.9/restrictions-and-limitations.html),
      checking every listed rule against this plug-in's 20 config descriptors and 7 integration
      inputs. Exploration already completed this sweep; reconfirm it against the post-fix tree and
      record it:
      **Clean** — no `TestableConnectedSystemTemplate`/`OAuthConnectedSystemTemplate` pairing (neither
      is implemented); all fields `RefreshPolicy.NEVER` (0 `.refresh(` calls, builder default
      `NEVER`); no `LIST`-typed connected system field (the only `ListTypePropertyDescriptor` is
      subscribe's `topicFilters`); no `LocalTypePropertyDescriptor` anywhere, so no nesting-depth
      issue; `isImportCustomizable` never set (0 calls) so the "integrations / documents must not set
      it true" rules hold; no `EncryptedTextPropertyDescriptor` on either integration; no
      `displayHint(EXPRESSION)` field (0 calls) so the "EXPRESSION fields must be expressionable"
      rule is unreachable; no `isHidden` field, so "required fields must not be hidden" holds; every
      `Choice` in all three `.choices(...)` blocks carries both a name and a value; no placeholder on
      the list descriptor (0 `.placeholder(` calls).
      **Two latent violations to report with evidence and leave unfixed:**
      (i) *`ListTypePropertyDescriptor` display hint.* The page states a list descriptor's
      `displayHint()` must be `DisplayHint.EXPRESSION`; subscribe's `topicFilters` sets none and
      defaults to `NORMAL`. Setting it is **not** mechanical: decompiled `StateGenerator`
      `shouldRenderAsExpression` returns true for a `DisplayHint.EXPRESSION` descriptor, which makes
      the generated default an `EXPRESSION`-typed state instead of the empty `ArrayList` that
      `readStringList` and `InputValidator`'s "1–10 filters" rule depend on, and it changes the field
      from a list editor to an expression editor in the designer. User-visible and
      behavior-changing → report, do not fix.
      (ii) *Encrypted fields and import customization.* The page states
      `EncryptedTextPropertyDescriptor` `isImportCustomizable` should be set to `true`; `password`,
      `clientPrivateKey` and `keyPassphrase` leave it at the builder default `false`. It is a
      "should", it appears nowhere in the reported stack trace, and turning it on changes deployment
      behavior across environments → report, do not fix in this pass.
      **One ambiguity to flag, not act on:** the same page says not to call `setErrors()` on a
      connected system because errors are ignored. `MqttConnectedSystemTemplate.validate` calls
      `PropertyState.setErrorAtPath`, which decompiles to a delegation to `setErrorsAtPath` — a
      distinct method from `setErrors()`, so whether the restriction covers it is genuinely unclear
      from the documentation. If it does, the entire design-time validation feature and the
      first-render gate built on it are decorative on a connected system. That is far larger than a
      descriptor-modifier edit, so **report it as the top deploy-time question** and resolve it by
      observation on the live instance (do validation messages appear at all once a required field is
      touched and cleared?). Change no validator code.
      Files: findings recorded in item 11's report section; no source changes
      Verify: the commands backing each claim re-run clean on the post-fix tree —
      `grep -rn '\.refresh(\|\.displayHint(\|\.isHidden(\|\.isReadOnly(\|\.isImportCustomizable(\|\.placeholder(' src/main/java/`
      → no hits; `grep -rn 'ListTypePropertyDescriptor\|LocalTypePropertyDescriptor' src/main/java/`
      → the single `topicFilters` hit in `MqttSubscribeIntegration.java` only;
      `grep -rn 'Testable\|OAuth' src/main/java/` → no hits;
      `unzip -l ~/.m2/repository/com/appian/connected-systems-core/1.9.1/connected-systems-core-1.9.1.jar | grep -c 'com/appiancorp'`
      → `0`. The two reported violations and the one ambiguity must each appear in the report with
      the command or decompiled evidence that supports them.

- [ ] 11. Append the third-fix record to the consolidated hand-off report.
      Add a new section (§11, after the existing §10 "Follow-up — first-render polish") to
      `.agents/tasks/connected-system-descriptor-fix.md` covering: the platform rule and its
      authority; the stack-trace evidence quoted from the user's instance; that 14
      `.isExpressionable(true)` calls were deleted from the connected system template and the
      integrations' 4 + 3 were deliberately preserved, with the repo-wide total moving 21 → 7; the
      `StateGenerator`/`shouldKeepOldState` evidence showing Clean Session and the `firstRender` gate
      are unaffected (finding 4 above), plus the saved-expression discard note (finding 5); that the
      SDK validator classes are **not** decompilable offline and why (finding 3); the four tests
      added and the suite moving 12 → 16, including the negative check from item 3; the two latent
      violations and the `setErrors` ambiguity from item 10; and the new artifact filename
      `target/mqtt-broker-connected-system-1.0.3-plugin.jar`. Keep the existing §1–§10 text intact —
      it is the record of the first two fixes. Add a deploy check to the §8 list: open **New >
      Connected System > MQTT Broker** and confirm the form renders instead of failing with
      APNX-1-4198-000.
      Files: `.agents/tasks/connected-system-descriptor-fix.md`
      Verify: the file still opens with its original §1 heading and the new section is appended last;
      `grep -c '^## ' .agents/tasks/connected-system-descriptor-fix.md` increased by exactly 1.

- [ ] 12. Update the steering file: new hard constraint, version bump, test count, audit trail.
      Four edits to `.kiro/steering/appian-appmarket-compliance.md`:
      (a) **New `###` subsection at the end of §1** (after "Vendor must be the real author", before
      the `---` at line 90): connected system property descriptors must never be expressionable;
      Appian's `ConnectedSystemPropertiesValidator.checkPropertiesModifiers` hard-rejects the
      descriptor at conversion time with "Property Descriptors in Connected System Template must not
      be Expressionable (APNX-1-4198-000)"; integration input descriptors **may** be expressionable
      and list-typed integration inputs **must** be; cite the 26.9 restrictions page; and give the
      verification greps — repo-wide total 7, all in the integration package, 0 in the template.
      (b) **Version references 1.0.2 → 1.0.3** at line 72 (the version-format table), line 96
      (submission artifact) and line 148 (`J=target/...`). **Leave line 278 alone** — it records the
      historical `1.0.1 → 1.0.2` bump — and add a matching history line for `1.0.2 → 1.0.3`.
      (c) **§4 pre-submission verification:** add the two expressionability checks to the command
      block, next to the existing `grep -c 'com/appian/mqtt'` line.
      (d) **§5 item 4:** test count `12` → `16`, `9 in MqttConnectedSystemTemplateDescriptorTest` →
      `13`, and a sentence naming the new descriptor-modifier coverage. If the two latent violations
      from item 10 are not fixed, add them to §5 as new numbered open questions so they are carried
      forward rather than lost.
      Files: `.kiro/steering/appian-appmarket-compliance.md`
      Verify: `grep -n '1\.0\.2' .kiro/steering/appian-appmarket-compliance.md` → only the historical
      line 278 hit remains; `grep -n -i 'expressionab' .kiro/steering/appian-appmarket-compliance.md`
      → hits in the new §1 subsection and the §4 command block.

- [ ] 13. Run the full pre-submission verification sweep against the built jar.
      Rebuild clean so no 1.0.2 jar lingers, then run every invariant check. Read the output; a green
      build proves nothing on its own.
      ```bash
      cd /Users/andreas.hock/Documents/Appian-Projects/MQTT_Component
      rm -rf target && mvn -o clean package
      J=target/mqtt-broker-connected-system-1.0.3-plugin.jar

      ls target/*.jar                                           # exactly ONE jar, -1.0.3-plugin
      unzip -l $J | grep -c '\.java$'                           # 33 (a test file does not ship)
      unzip -l $J | grep '\.java$' | grep -vc 'de/hock/mqtt/'   # 0
      unzip -l $J | grep -cE ' appian-plugin.xml$'              # 1, bare root entry, no prefix
      unzip -l $J | grep 'lib/'                                 # only META-INF/lib/ + Paho jar
      unzip -l $J | grep -c 'connected-systems'                 # 0, both Appian artifacts provided
      unzip -l $J | grep -c 'com/appian/mqtt'                   # 0
      unzip -l $J | grep -ci test                               # 0
      unzip -p $J appian-plugin.xml | grep -E '<version>|application-version|<vendor'
      javap -verbose -cp target/classes de.hock.mqtt.template.MqttConnectedSystemTemplate \
        | grep 'major version'                                  # 61
      grep -rn 'com\.appian\.mqtt' . | grep -v '/target/' | grep -v '/.agents/' | grep -v '/.kiro/'
      grep -rh 'import com.appian.connectedsystems' src/main/java/ | wc -l   # 45
      grep -c '\.label=' src/main/resources/resources_en_US.properties       # 28
      grep -rn 'extends Simple' src/main/java/                  # all three templates
      grep -rn 'System\.out\|System\.err\|printStackTrace\|java\.util\.logging\|System\.setProperty' src/main/java/
      grep -c isExpressionable src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java  # 0
      grep -rn isExpressionable src/main/java/ | wc -l          # 7
      ```
      Expect `Tests run: 16, Failures: 0, Errors: 0, Skipped: 0` across the two test classes, bytecode
      `major version: 61`, `<version>1.0.3</version>` with `min="26.9.0.0"` and the Hock vendor, 45
      SDK imports intact (the `com.appian.connectedsystems.*` imports a blind `com.appian` replace
      would corrupt), 28 label keys, and no hits from either policy sweep.
      Files: none
      Verify: every line above matches its stated expectation. Any mismatch blocks the handoff.

- [ ] 14. Write the handoff summary naming the artifact and the deploy checks.
      Summarize: what changed, the 21 → 7 expressionability count with the template at 0, the four
      new tests and 12 → 16 suite total, the version bump, the two reported-not-fixed latent
      violations plus the `setErrors` ambiguity, and
      `target/mqtt-broker-connected-system-1.0.3-plugin.jar` as the artifact to deploy. Deploy checks,
      in order: copy to `APPIAN_INSTALL/_admin/plugins/` and watch `tomcat-stdOut.log` for
      `Successfully installed Plug-in` plus the `ConnectedSystemTemplateModuleParser` registration
      lines; open **New > Connected System > MQTT Broker** and confirm it renders all 20 labelled
      fields instead of APNX-1-4198-000; confirm the fields now accept literal values only (no
      expression toggle) — that is expected, not a regression; clear **Clean Session**, save, reopen,
      confirm it is **still cleared** (the discriminating round-trip check carried over from the
      previous fix); confirm a brand-new object shows no validation errors before any input, then
      type into Broker Host and clear it and confirm the error **does** appear — if errors never
      appear at all, that is the `setErrors`-ignored ambiguity from item 10 surfacing, not a
      regression of the first-render gate. Note that `conf.plugins.poll-interval = 0` disables hot
      deployment and requires an application server restart.
      Files: none (step output)
      Verify: the summary names the exact artifact filename and every check above.

## Out of scope — do not touch

Known pre-existing bugs, all deliberately left alone: inert `maxReconnectAttempts`; key passphrase
cannot unlock a private key; `MqttConnectionManager.classify`'s divergent error path with no
SSL cause-chain check; username in the connection-cache fingerprint while `Redactor` treats it as a
secret; the subscribe failure path dropping its `validationErrors` map; the `SimpleConfiguration`
`HashMap` concurrency note; missing connected system logo assets; the dead jqwik/moquette
Surefire/Failsafe wiring; the immediate-save validation gap. Also not revisited: the root-state NPE
fix (`Simple*` migration) and the first-render validation gate — both are fixed and load-bearing.
Core stays at **1.9.1**; do not "fix" it to 1.2.0.

Appian documentation content referenced above was rephrased for compliance with licensing
restrictions.
