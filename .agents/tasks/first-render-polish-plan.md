# First-render polish — implementation plan

Target: Appian 26.9, Java 17, package `de.hock.mqtt`, groupId `de.hock`.
**No git in this project** (`git rev-parse` fails) — every edit is made in place. No worktree, no
branch, no commit, no `git` command anywhere in this plan.

Scope: suppress design-time validation on the very first render of a brand-new connected system,
correct three confirmed factual errors in the descriptor-fix records, add two discriminating
deploy-checklist checks, create the consolidated hand-off report the previous brief named but never
produced, and bump `1.0.1` → `1.0.2`.

Everything below was grounded in the actual files. Baseline confirmed before planning:
`mvn -o clean package` is green, one jar (`mqtt-broker-connected-system-1.0.1-plugin.jar`), 33
`.java` entries, 1 bare `appian-plugin.xml`, 1 Paho under `META-INF/lib/`, 0 `connected-systems`
entries, 28 label keys, 45 SDK imports in `src/main/java`.

---

## Design decisions made here (not re-litigated downstream)

1. **Gate the existing `validate(...)` call on `!firstRender`.** The class already computes
   `firstRender` before `setProperties` for the Clean Session seed. Reusing it keeps exactly one
   definition of freshness, which is what the brief requires, and is a two-line change. The
   alternative — a second signal derived from "are all text fields empty" — would drift from the
   Clean Session guard and could be fooled by a designer who clears every field. Rejected.
2. **`ConfigValidator` and the three sibling validators are not touched.** This is purely about
   *when* `validate()` is invoked. `validate(PropertyState)` keeps its current signature and body,
   so nothing that depends on it (`toConnectionConfig`, the record evidence, the review trace)
   moves.
3. **Keep the existing test method, rename it, and add two.** The brief forbids deleting
   `blankBrokerHostIsReportedAgainstItsField`. Its *assertion* is now wrong for a first render, so
   it becomes a subsequent-render test under a name that says so, and a dedicated first-render-clean
   test is added beside it. Renaming is not deleting; the assertion it carries survives.
4. **The steering file gets the version/artifact/test-count updates.** It is the binding invariants
   doc and it currently hard-codes `1.0.1` in three places and "10 assertions". Leaving it stale
   would make its own §4 gate block fail when copy-pasted — exactly the class of defect item 2c is
   correcting. Not optional.
5. **The consolidated report is written fresh, standing alone.** Not a link stub. Its content is
   drawn from the plan and verification note but restated so a reviewer who opens only that file has
   the whole story.

---

## Known residual risk this change amplifies (carry it forward, do not try to fix it offline)

The `firstRender` signal is `priorState == null || getValueAtPath(CLEAN_SESSION) == null`. It is
only reliable if Appian hands the template back a descriptor derived from the one the template last
returned. If the platform instead supplies only persisted object state, `cleanSession` reads `null`
on every render, `firstRender` stays permanently `true`, and **two** things break rather than one:
Clean Session can never be turned off, *and* design-time validation never runs at all. That is why
item 6 adds the discriminating deploy check, and why item 8's report and item 7's README bullet must
state it plainly. It cannot be settled offline.

---

# Implementation Plan

- [ ] 1. Bump the plug-in version `1.0.1` → `1.0.2` in both files, so every artifact path written by
      later items is already correct. `pom.xml` line 10: `<version>1.0.1</version>` →
      `<version>1.0.2</version>`. `src/main/resources/appian-plugin.xml` line 31:
      `<version>1.0.1</version>` → `<version>1.0.2</version>` — **three-part `x.y.z` only**, the
      AppMarket validator rejects four-part. Do **not** touch
      `<application-version min="26.9.0.0"/>` (different field, four-part by design),
      `<vendor name="Hock" url="https://itondemand.com"/>`, `key="de.hock.mqtt"`, or any of the
      three `class=` attributes.
      Files: `pom.xml`, `src/main/resources/appian-plugin.xml`
      Verify: `rm -rf target && mvn -o clean package` succeeds and
      `ls target/*.jar` prints exactly one line,
      `target/mqtt-broker-connected-system-1.0.2-plugin.jar`. The `rm -rf target` matters — a
      lingering `1.0.1` jar also carries `appian-plugin.xml` at its root and would look deployable.
      Then `unzip -p target/mqtt-broker-connected-system-1.0.2-plugin.jar appian-plugin.xml | grep -E '<version>|application-version|<vendor'`
      shows `<version>1.0.2</version>`, `min="26.9.0.0"`, `name="Hock"`.

- [ ] 2. Suppress validation on the first render in
      `MqttConnectedSystemTemplate.getConfiguration`. The method currently ends with an
      unconditional `validate(configuration.toConfiguration().getRootState());`. Wrap it:

      ```java
      // Validation is skipped on the very first render so a brand-new connected system opens
      // clean instead of showing "Broker host is required" and the broker-port bounds error
      // before the designer has typed anything. The generated state hands validate() "" for
      // brokerHost and null for brokerPort, which ConfigValidator correctly rejects — there is
      // just nothing to report yet. Every subsequent render validates, so the moment the
      // designer touches the form (or saves) the errors appear and the entered values are
      // retained. Same `firstRender` signal as the Clean Session seed above: one definition of
      // freshness in this class, deliberately.
      if (!firstRender) {
        validate(configuration.toConfiguration().getRootState());
      }
      ```

      Do **not** add a second freshness variable, do not move the `priorState` / `firstRender`
      computation (it must stay *before* `setProperties`, or a generated `Boolean.FALSE` becomes
      indistinguishable from a designer's deliberate `false`), do not reorder the Clean Session seed
      relative to `setProperties`, and do not change `validate(PropertyState)` or any validator
      class. Also update the class-level Javadoc: the "Design-time validation (task 9.2)" paragraph
      says validation "runs inside `getConfiguration`" — extend it to say it runs on every render
      *except* the first render of a new connected system, and note the round-trip assumption from
      the residual-risk section above. Update the `@return` line of `getConfiguration`, which
      currently reads "with properties declared, defaults seeded, and errors attached", to say
      errors are attached from the second render onward.
      Files: `src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java`
      Verify: `mvn -o clean package` compiles. Item 3's tests are the behavioral proof — expect
      `blankBrokerHostIsReportedAgainstItsField` to **fail** at this point, which confirms the gate
      took effect; item 3 corrects it.

- [ ] 3. Update and extend `MqttConnectedSystemTemplateDescriptorTest` so the suite proves both
      halves: the form opens clean **and** validation fires afterwards. Three edits to the one file:

      a. Add a small private helper, because `getErrorsAtPath` may return `null` or an empty list
         for a field with no errors — tolerate both:
         ```java
         private static boolean hasErrors(PropertyState state, String fieldKey) {
           List<String> errors = state.getErrorsAtPath(new PropertyPath(fieldKey));
           return errors != null && !errors.isEmpty();
         }
         ```

      b. **New test** `firstRenderOfANewConnectedSystemHasNoValidationErrors`: render
         `emptyDescriptor()` through `renderConnectedSystem(...)` and assert `hasErrors(...)` is
         `false` for **every** key in the existing `CONFIG_FIELD_KEYS` list, with a message naming
         the offending field. This is the "form opens clean" half.

      c. **Rename and rewrite** the existing `blankBrokerHostIsReportedAgainstItsField` (do not
         delete it) to `blankBrokerHostIsReportedOnEverySubsequentRender`, keeping its
         non-empty-errors assertion and putting it on the second render:
         ```java
         ConfigurationDescriptor firstRender = renderConnectedSystem(emptyDescriptor());
         assertFalse(hasErrors(firstRender.getRootState(), ConfigFields.BROKER_HOST),
             "the first render of a new connected system must open clean");

         ConfigurationDescriptor secondRender = renderConnectedSystem(firstRender);
         assertTrue(hasErrors(secondRender.getRootState(), ConfigFields.BROKER_HOST),
             "a blank required broker host must be reported on its field once the form re-renders");
         ```
         Feeding `firstRender` straight back works because `setProperties` seeded `cleanSession`
         to `Boolean.TRUE`, so on the second pass `firstRender` evaluates `false` and `validate`
         runs — the same mechanism
         `cleanSessionDefaultsToTrueOnFirstRenderAndRespectsADeliberateFalse` already relies on.

      d. **New test** `enteringAValueClearsItsErrorWhileOtherFieldsStillReport`: take the
         first-render descriptor, `setValueAtPath(new PropertyPath(ConfigFields.BROKER_HOST), "broker.example.com")`,
         re-render, then assert `brokerHost` has no error while `brokerPort` (still blank) does.
         This is the "validation fires once a value is set" half, and it also proves the gate did
         not weaken `ConfigValidator`.

      Leave `rootStateIsPopulatedForABrandNewConnectedSystem`,
      `rootTypeIsRegisteredAndResolvable`, `allTwentyConfigFieldsArePresentInTheGeneratedState`,
      `cleanSessionDefaultsToTrueOnFirstRenderAndRespectsADeliberateFalse` and both integration
      tests untouched — the NPE regression guard in particular must keep passing unchanged.
      Note while here: if `enteringAValueClearsItsErrorWhileOtherFieldsStillReport` reveals that
      errors attached on one render survive into the next (state cloning in
      `StateGenerator.generateFromExistingState`), record it in the task notes as a pre-existing
      characteristic and **do not fix it** — it is out of scope and unreachable from a clean first
      render anyway.
      Files: `src/test/java/de/hock/mqtt/template/MqttConnectedSystemTemplateDescriptorTest.java`
      Verify: `mvn -o test` reports `Tests run: 12, Failures: 0, Errors: 0, Skipped: 0` overall —
      9 in `MqttConnectedSystemTemplateDescriptorTest` (was 7) plus 3 in `ProjectStructureTest`. If
      the real total differs, report the actual number rather than forcing it to 12.

- [ ] 4. Correct the stale import count in the plan's copy-pasteable gate block (review finding 2c).
      `.agents/tasks/connected-system-descriptor-fix-plan.md` line 460 reads
      `grep -rc 'import com.appian.connectedsystems' src/main/java/                # ~47, unchanged`.
      The real figure is 45. Replace the comment with `# 45 total across src/main/java` and, since
      `grep -rc` prints a per-file count, also note the aggregate form that actually yields one
      number: `grep -rh 'import com.appian.connectedsystems' src/main/java/ | wc -l`. While in that
      block, change the `J=` path on line 448 to `1.0.2` and the `.java`-count comment to remain
      `33`. Fix the text only; do not restructure §4.
      Files: `.agents/tasks/connected-system-descriptor-fix-plan.md`
      Verify: run the aggregate command —
      `grep -rh 'import com.appian.connectedsystems' src/main/java/ | wc -l` prints `45`, matching
      what the block now claims. The coder must read the real number, not copy `45` from this plan.

- [ ] 5. Correct the false `topicFilters`/Expression claim in **both** places (review finding 2b).
      **Re-verify against the SDK bytecode first**, do not copy this plan's summary:
      ```bash
      C=~/.m2/repository/com/appian/connected-systems-core/1.9.1/connected-systems-core-1.9.1.jar
      javap -p -c -cp $C com.appian.connectedsystems.templateframework.sdk.configuration.StateGenerator
      ```
      Read `getDefaultState(PropertyDescriptor, int)`, `shouldRenderAsExpression(PropertyDescriptor, int)`
      and `generateDefaultValue(TypeReference, int)`. What they actually do: `getDefaultState` takes
      the `SystemType.EXPRESSION` branch **only** when `shouldRenderAsExpression` is true, and that
      method returns true only when `DisplayHint.EXPRESSION.equals(descriptor.getDisplayHint())`, or
      when `descriptor.getTypeRef().isLocalType()` and the level is greater than 1. Otherwise it
      calls `generateDefaultValue(descriptor.getTypeRef(), level)`, whose `isListType()` branch
      returns `new ArrayList()`. The `topicFilters` descriptor in
      `src/main/java/de/hock/mqtt/integration/MqttSubscribeIntegration.java` (lines 117–124) is a
      `ListTypePropertyDescriptor` with `itemType(SystemType.STRING)` and `isExpressionable(true)`
      and **no** `displayHint(EXPRESSION)`, so neither condition holds.
      Rewrite both notes to state that: an untouched `topicFilters` generates an **empty list**,
      `readStringList` returns an empty list, and `InputValidator` then reports the "1–10 filters"
      error — the correct outcome, reached for a different reason than was recorded. Keep the
      conclusion that leaving `readStringList` unchanged was right; it was. Make clear
      `isExpressionable(true)` is not the same thing as `displayHint(EXPRESSION)`.
      - `.agents/tasks/connected-system-descriptor-fix-plan.md` §3 step 5, line 353 and the sentences
        following it.
      - `.agents/tasks/connected-system-descriptor-fix-verification.md` §7 item 5, line 223 and the
        sentences following it.
      Files: `.agents/tasks/connected-system-descriptor-fix-plan.md`,
      `.agents/tasks/connected-system-descriptor-fix-verification.md`
      Verify: `grep -n 'Expression' .agents/tasks/connected-system-descriptor-fix-plan.md .agents/tasks/connected-system-descriptor-fix-verification.md`
      returns no surviving claim that a list descriptor's generated default is an `Expression`, and
      the `javap` output above is pasted into the task notes as the evidence the correction rests on.

- [ ] 6. Add the two deploy-checklist checks (review finding 2, plus the new first-render behavior).
      In `.agents/tasks/connected-system-descriptor-fix-plan.md` §5 (line 477 onward) and
      `.agents/tasks/connected-system-descriptor-fix-verification.md` §7, replace the existing
      one-directional Clean Session item — "Save a connected system with Clean Session left
      untouched and confirm it persists as enabled" / verification §7 item 3 — with a pair of
      checks, and make the discriminating one unmistakable:

      **The check that distinguishes the two platform behaviors.** Clear the **Clean Session**
      checkbox, save, close and reopen the connected system object, and confirm it is **still
      cleared**. The untouched-and-still-enabled case passes under *both* possible platform
      behaviors and therefore proves nothing. If Clean Session comes back **checked**, Appian is not
      round-tripping the descriptor the template returned, the `firstRender` guard is wrong, and
      both the Clean Session default and the validation suppression in item 2 must be reworked.

      **First-render check.** Open **New > Connected System > MQTT Broker** and confirm **no**
      validation errors appear before any input — in particular no "Broker host is required" and no
      broker-port bounds message. Then touch a required field and clear it (type into Broker Host,
      delete it) and confirm the error **does** appear. Errors never appearing at all is the same
      failure as Clean Session coming back checked: the freshness signal is stuck true.

      Keep the existing `NoClassDefFoundError` / `connected-systems-client` item and the
      install-log/20-field items as they are. Update the artifact filename in §5 to
      `target/mqtt-broker-connected-system-1.0.2-plugin.jar`. Fix the text; do not restructure the
      documents.
      Files: `.agents/tasks/connected-system-descriptor-fix-plan.md`,
      `.agents/tasks/connected-system-descriptor-fix-verification.md`
      Verify: both files contain the cleared-checkbox check and the no-errors-on-open check, each
      explicitly labelled as the discriminating one for its behavior, and
      `grep -rn '1\.0\.1' .agents/tasks/connected-system-descriptor-fix-*.md` returns only
      historical references (the 1.0.0→1.0.1 bump record in §3 step 7 and §7), never a current
      deploy instruction.

- [ ] 7. Create the consolidated hand-off report the previous brief named but never produced
      (review finding 2a). New file, standing on its own — a reader who opens only this file gets
      the whole story, so restate rather than link out. Required sections, in this order:
      1. **Confirmed root cause and evidence.** `ConfigurationDescriptor.getRootState()` is
         `getState().get("root")`; a brand-new connected system's state map has no `"root"` entry, so
         the old `getRootState().setType(rootType)` was `null.setType(...)` — the user's reported
         message verbatim. All three templates carried the same line. Second, independent defect:
         `PropertyState.setType(LocalTypeDescriptor)` stores a `TypeReference` without registering
         the type, so even a non-null state would have carried an unresolvable `#/types/...`
         reference.
      2. **The fix chosen and why.** Migrate to `SimpleConnectedSystemTemplate` /
         `SimpleIntegrationTemplate` rather than hand-rolling the descriptor on the low-level
         interfaces: it is Appian's documented 26.9 contract, it removes the failure class
         permanently (the plug-in never touches root-state construction again), and
         `PropertyState`'s package-private constructor means `StateGenerator` was the only public
         route anyway. The low-level fallback is retained as a documented option, not bundling.
      3. **SDK coordinates and why that pairing.** `connected-systems-core:1.9.1` (unchanged, no
         downgrade) plus `connected-systems-client:1.3.0`, both `scope provided`, neither bundled.
         Client 1.3.0 is core 1.9.1's own generation (published a day apart), declares zero
         dependencies so it cannot drag in a conflicting core, and the `Simple*` public API is
         identical across client 1.1.0/1.3.0/1.4.0 — so `${appian.sdk.client.version}` is a one-line
         swap if a live instance demands it.
      4. **The code path showing root state is now populated.** Inherited
         `getConfigurationDescriptor` → `SimpleConfiguration.from` (null-tolerant) → our
         `getConfiguration` → `setProperties` → `setPropertiesGeneric` (null-safe
         `getRootTypeName()`, `Map.put` registering the root type) → private
         `getConfigurationDescriptor` = `new StateGenerator(types).generateFromExistingState(rootType, getRootState())`
         + `withTypes(...)` + `withState(...)`. `generateFromExistingState` short-circuits a `null`
         existing state into `generateDefaultState`.
      5. **First-render behavior (this task).** Validation is gated on `!firstRender`; the form opens
         clean and validates from the second render onward. One freshness signal in the class, shared
         with the Clean Session seed. State the round-trip assumption and what breaks if it is wrong.
      6. **Invariant verification evidence.** The real numbers from item 10's gate run: one jar, 33
         `.java` entries, bare root `appian-plugin.xml`, Paho-only `META-INF/lib/`, zero
         `connected-systems` and zero `com/appian/mqtt` entries, vendor `Hock`, bytecode major
         version 61, 28 label keys, 45 SDK imports, clean code-policy sweep, test total from item 3.
      7. **The exact artifact to deploy:** `target/mqtt-broker-connected-system-1.0.2-plugin.jar`
         (the `-plugin` suffix comes from the assembly `<id>`; a jar without it is a stale build).
      8. **What the user must do to confirm**, copying item 6's checklist including both
         discriminating checks.
      9. **What remains unverifiable offline:** whether the Appian server supplies
         `connected-systems-client`; the real UI render; whether Appian round-trips the
         template-returned descriptor (Clean Session *and* validation suppression both ride on it);
         core 1.9.1 vs Appian's compatibility table (unresolved, untouched); the `META-INF/lib/`
         layout; and the `SimpleConfiguration` `HashMap` concurrency note, which is inherent to
         Appian's facade and has nothing to change in plug-in code.
      Close with the plain statement that final confirmation requires redeploying to the user's real
      26.9 instance. End with the attribution line the sibling records use: *Appian documentation
      content referenced above was rephrased for compliance with licensing restrictions.*
      Files: `.agents/tasks/connected-system-descriptor-fix.md` (new)
      Verify: the file exists, every one of the nine sections above is present, and it contains no
      sentence whose only content is a pointer to `-plan.md` or `-verification.md`. Confirm the
      artifact name it states matches `ls target/*.jar` from item 10.

- [ ] 8. Update `README.md` in lockstep. The first-render behavior is user-visible, so the README
      must describe it accurately; nothing currently *implies* errors appear immediately, so this is
      an addition for accuracy rather than a correction. Edits:
      - Line 10, `**Version:** `1.0.1`` → `1.0.2`.
      - `## Build`, the artifact block → `target/mqtt-broker-connected-system-1.0.2-plugin.jar`.
      - `## Deploy`, the `cp` command → `1.0.2`.
      - `## Configuring the Connected System`, the paragraph at line ~97 that currently ends
        "Design-time validation attaches each error to its own field and keeps the entered values,
        so a rejected save re-displays what you typed." — append: a brand-new connected system opens
        **clean**, with no validation messages before any input; validation runs from the next render
        onward (the moment you interact with the form or save), and from then on required-field and
        bounds errors are reported against their fields.
      - `## Known limitations`, update the automated-test-coverage bullet: it already names
        `MqttConnectedSystemTemplateDescriptorTest`; add that the suite asserts both halves of the
        first-render behavior — a clean first render and validation reporting on subsequent renders.
      - `## Known limitations`, **add** one bullet for the round-trip assumption: the clean first
        render and the Clean Session `true` default both depend on Appian handing the template back
        a descriptor derived from the one it last returned. If the platform supplies only persisted
        state, Clean Session cannot be turned off and validation never reports. The check that
        distinguishes the two is clearing Clean Session, saving, and reopening — unverified on a live
        instance.
      Leave every config-field table, label, default, validation rule, integration input/output and
      error category exactly as it is — none of them changed. Do not copy compliance rules into the
      README; it links the steering file on purpose.
      Also keep `docs/mqtt-plugin-guide.md` consistent: its Session section ends with "Design-time
      validation reports field-specific errors and **retains the values you entered** on a rejected
      save." (line ~100). Add one sentence that the form opens clean and validation begins on the
      next render. One sentence, no restructuring.
      Files: `README.md`, `docs/mqtt-plugin-guide.md`
      Verify: `grep -n '1\.0\.1' README.md` returns no plug-in-version or artifact-name hit, and
      `grep -n 'opens clean\|no validation messages' README.md docs/mqtt-plugin-guide.md` shows the
      new wording in both.

- [ ] 9. Keep `.kiro/steering/appian-appmarket-compliance.md` true — it is the binding invariants doc
      and currently hard-codes `1.0.1` in three places, so its own §4 gate block would fail when
      copy-pasted:
      - §1 version-format table, line 72: our `<version>` value → `1.0.2`.
      - §2 "Submission artifact", line 96 → `target/mqtt-broker-connected-system-1.0.2-plugin.jar`.
      - §4, line 148, the `J=` path → `1.0.2`. Keep the expected `.java` count at **33** — item 3
        adds only a test, and the assembly `fileSet` covers `src/main/java` only, so no new source
        ships. Keep the import-count guard at 45.
      - §5 item 2 (the resolved `Simple*` migration entry): add one sentence that design-time
        validation is now gated on the same first-render signal as the Clean Session seed, so the
        round-trip assumption governs two behaviors rather than one, and name the clear-Clean-Session
        deploy check as the discriminator.
      - §5 item 4 (test coverage), line 198: "10 assertions in two classes" → the real total from
        item 3, and mention the first-render-clean and subsequent-render-validation assertions.
      - §8 "Session audit trail": the list points only at the `appmarket-*` records. Add the real
        descriptor-fix paths — `connected-system-descriptor-fix-plan.md`,
        `connected-system-descriptor-fix-verification.md`, the new
        `connected-system-descriptor-fix.md`, `descriptor-fix-review.md` /
        `descriptor-fix-review.json`, and this task's `first-render-polish-plan.md` — so the audit
        trail names files that actually exist (the second half of review finding 2a).
      Do not touch anything else in the steering file: not §1's namespace rules, not the `com.appian`
      find-and-replace trap prose, not the SDK-version-conflict entry in §5 item 1.
      Files: `.kiro/steering/appian-appmarket-compliance.md`
      Verify: `grep -n '1\.0\.1' .kiro/steering/appian-appmarket-compliance.md` returns nothing, and
      the §4 command block copy-pasted verbatim runs clean against the new jar in item 10.

- [ ] 10. Run the full pre-submission gate and **read every number** — a green build proves nothing
      on its own. Record the real output in the item-7 report and in the task notes.

      ```bash
      cd /Users/andreas.hock/Documents/Appian-Projects/MQTT_Component
      rm -rf target
      mvn -o clean package
      mvn -o test
      J=target/mqtt-broker-connected-system-1.0.2-plugin.jar

      # Invariant 6 — exactly ONE jar
      ls target/*.jar
      # Invariant 2 — 33 sources, root-mirrored under de/hock/mqtt/**
      unzip -l $J | grep -c '\.java$'
      unzip -l $J | grep 'de/hock/mqtt/.*\.java' | head
      # Invariant 3 — bare root manifest, no directory prefix
      unzip -l $J | grep -cE ' appian-plugin.xml$'
      # Invariant 4 — Paho only under META-INF/lib/, no Appian artifact bundled
      unzip -l $J | grep 'META-INF/lib/'
      unzip -l $J | grep -c 'connected-systems'          # 0
      # Invariant 1 — namespace
      unzip -l $J | grep -c 'com/appian/mqtt'            # 0
      grep -rn 'com\.appian\.mqtt' . | grep -v '/target/' | grep -v '/.agents/' | grep -v '/.kiro/'
      grep -rh 'import com.appian.connectedsystems' src/main/java/ | wc -l    # 45
      unzip -l $J | grep -ci test                        # 0
      # Invariant 5 + version formats
      unzip -p $J appian-plugin.xml | grep -E '<version>|application-version|<vendor'
      # Invariant 7 — Java 17 bytecode
      javap -verbose -cp target/classes de.hock.mqtt.template.MqttConnectedSystemTemplate \
        | grep 'major version'
      javap -verbose -cp target/classes de.hock.mqtt.integration.MqttPublishIntegration \
        | grep 'major version'
      javap -verbose -cp target/classes de.hock.mqtt.integration.MqttSubscribeIntegration \
        | grep 'major version'
      # Invariant 8 — label keys still resolve
      grep -c '\.label=' src/main/resources/resources_en_US.properties
      # Invariant 9 — all three templates still on the Simple* hierarchy
      grep -rn 'extends Simple' src/main/java/
      # Code policies
      grep -rn 'System\.out\|System\.err\|printStackTrace\|java\.util\.logging\|System\.setProperty' src/main/java/
      ```

      Expected: one jar named `-1.0.2-plugin.jar`; `33`; a bare `appian-plugin.xml` count of `1`;
      `META-INF/lib/org.eclipse.paho.client.mqttv3-1.2.5.jar` and no root `lib/`; `0`
      `connected-systems`; `0` `com/appian/mqtt` in the jar and no source/pom hit from the namespace
      grep (only this plan and the steering/record prose may match, which the exclusions cover);
      `45` SDK imports; `0` test entries; `<version>1.0.2</version>`, `min="26.9.0.0"`,
      `name="Hock" url="https://itondemand.com"`; `major version: 61` three times; `28` label keys;
      three `extends Simple...` hits (`SimpleConnectedSystemTemplate` once,
      `SimpleIntegrationTemplate` twice); no policy-sweep match.

      Behavioral proof, item by item:
      - **Item 1, both directions.** `firstRenderOfANewConnectedSystemHasNoValidationErrors` passes
        (form opens clean) **and** `blankBrokerHostIsReportedOnEverySubsequentRender` plus
        `enteringAValueClearsItsErrorWhileOtherFieldsStillReport` pass (validation fires afterwards,
        and a supplied value clears only its own error). All three must be green; a suite where only
        the first passes has not proved the feature.
      - **Clean Session, both directions.**
        `cleanSessionDefaultsToTrueOnFirstRenderAndRespectsADeliberateFalse` still passes unchanged —
        `true` on first render, deliberate `false` preserved on the second.
      - **Root-state NPE guard still passing.** `rootStateIsPopulatedForABrandNewConnectedSystem`
        green, with its `incoming.getRootState() == null` premise assertion intact. The `Simple*`
        migration must not be regressed: no template may go back to implementing
        `ConnectedSystemTemplate` or `IntegrationTemplate` directly.
      - **Everything else still green.** `rootTypeIsRegisteredAndResolvable`,
        `allTwentyConfigFieldsArePresentInTheGeneratedState`, and both integration input-key tests.

      If the `.java` count moves off 33, a **main** source was added — report the new number and
      update both the steering file §4 and the README rather than silently accepting it. Adding a
      test file never changes it.
      Files: none
      Verify: every expectation above matches the real output; `mvn -o test` ends in
      `BUILD SUCCESS` with `Failures: 0, Errors: 0`.

---

## Out of scope — do not touch

The five known pre-existing bugs stay exactly as they are: inert `maxReconnectAttempts`; the key
passphrase that cannot unlock a private key; `MqttConnectionManager.classify` as a second divergent
error-classification path with no SSL cause-chain check; `MqttConnectionManager` embedding the
username verbatim in the connection cache fingerprint while `Redactor` treats username as a secret;
the subscribe failure path dropping its `validationErrors` map. Also leave the SDK-inherent
`SimpleConfiguration` `HashMap` concurrency note alone — there is nothing to change in plug-in code.
The missing connected-system logo assets and the dead jqwik/moquette Surefire/Failsafe wiring remain
tracked in the steering file, not fixed here. The core 1.9.1 vs Appian-table-1.2.0 divergence is
untouched and stays open.

## Code policies that bound every edit

Log4j only — no `System.out`, `System.err`, `printStackTrace()`, `java.util.logging`. No
`System.setProperty()` or any JVM/server global mutation. `Closeable` resources closed in a
guaranteed fashion, temp files deleted. No hardcoded credentials, default passwords, or key pairs
anywhere, including tests and docs.

---

**Final confirmation requires the user redeploying
`target/mqtt-broker-connected-system-1.0.2-plugin.jar` to their own Appian 26.9 instance.** Nothing
in this plan can be verified against a live platform offline: whether the server supplies
`connected-systems-client`, whether the configuration form renders all 20 fields, whether the first
render is actually clean, and whether Appian round-trips the template-returned descriptor — which
governs both the Clean Session default and the validation suppression — are all deploy-time
questions.

Appian documentation content referenced above was rephrased for compliance with licensing
restrictions.
