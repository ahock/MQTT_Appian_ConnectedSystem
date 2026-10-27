# First-render validation suppression, record corrections, and the 1.0.2 bump

Design-time validation in `MqttConnectedSystemTemplate.getConfiguration` is now gated on the
`firstRender` boolean the method already computed for the Clean Session seed, so a brand-new MQTT
connected system opens with no validation messages and begins reporting from the second render
onward. No validator class was touched — the change is purely about *when* `validate(PropertyState)`
is invoked. Around that, three factual errors in the descriptor-fix records were corrected, the
consolidated hand-off report the previous brief named was actually written, both deploy checklists
gained the discriminating Clean Session check plus a first-render check, and the plug-in went
`1.0.1` → `1.0.2` in lockstep across `pom.xml`, `appian-plugin.xml`, README, the user guide and the
steering file.

Watch for: validation is keyed on "first render", not "untouched", so an immediate save on a
never-touched new object may persist a fully blank configuration if Appian does not re-render before
saving — and neither deploy checklist covers that path (possible). The records claim the SDK's
error-stickiness characteristic is "unreachable from a clean first render", which holds for the
render-1 → render-2 transition but not from render 2 onward (likely). Everything the brief enumerated
— item 1, 2a, 2b, 2c, 3, the version formats, README lockstep, and the jar invariants — is present
and backed by recorded evidence.

**Verdict**: APPROVED

## High-level view

The gate reuses the single pre-existing freshness signal rather than introducing a parallel one, and
the signal is still captured *before* `setProperties` so that a state-generated `Boolean.FALSE`
cannot be mistaken for a designer's deliberate `false`. That ordering is load-bearing for both the
Clean Session seed and the new gate, and it is intact. `ConfigValidator`, `LwtValidator`,
`ReconnectionValidator` and `CertificateLoader` are untouched, and `validate(PropertyState)` keeps
its signature and body.

The whole feature rides on an assumption that cannot be settled offline: that Appian hands the
template back a descriptor derived from the one it last returned. If the platform supplies only
persisted object state, `cleanSession` reads `null` forever, the signal sticks at `true`, and two
behaviors fail together — Clean Session can never be cleared and validation never reports at all.
This change doubles the blast radius of that assumption, and the records, Javadoc, README and both
deploy checklists all now say so in those terms. The discriminating check (clear Clean Session, save,
reopen, confirm still cleared) replaced the old untouched-persists-as-enabled check, which passed
under both platform behaviors and proved nothing.

The suite proves both halves by test rather than by assertion in prose. The former
`blankBrokerHostIsReportedAgainstItsField` was renamed to
`blankBrokerHostIsReportedOnEverySubsequentRender` and now carries a clean-first-render assertion
*and* its original non-empty-errors assertion on the second render; two tests were added for the
clean first render across all 20 fields and for a supplied value clearing only its own error. The
coder ran the gate-removal negative check and recorded that exactly those three tests fail without
it.

The gate narrows but does not remove a pre-existing SDK characteristic: errors attached on one render
appear to survive into the next through `StateGenerator.generateFromExistingState`. The records call
this unreachable from a clean first render, which is true only for the first transition — a designer
who fixes a field flagged on render 2 may still see the stale message on render 3. Correctly left
unfixed as out of scope, but the characterization is narrower than written.

Scope is tight. Only `MqttConnectedSystemTemplate.java`, its test, `pom.xml`, `appian-plugin.xml`,
README, the user guide, the steering file and the task records were modified. No validator, no
integration, no `resources_en_US.properties`, and none of the five known pre-existing bugs were
touched.

<details>
<summary>Issues (3)</summary>

1. **Immediate-save path uncovered** — validation is gated on "first render", not "untouched", so
   clicking Save on a never-touched new connected system may persist a blank configuration if Appian
   does not re-render before saving (possible). Add "open New > Connected System and save without
   touching anything; confirm it is rejected" to both deploy checklists.
2. **Error-stickiness claim is too broad** — §5 of `connected-system-descriptor-fix.md` states the
   error-cloning characteristic is "unreachable from a clean first render"; it is unreachable only on
   the render-1 → render-2 transition (likely). Narrow the sentence and add a deploy observation:
   fix a field flagged on the second render and confirm its message clears.
3. **"or saves" is an unverified platform claim** — the README, Javadoc and records all assert
   validation fires "the moment you interact with the form or save it". The interaction half follows
   from re-rendering; the save half is an assumption (possible). Either qualify it or fold it into
   finding 1's checklist item.

</details>

<details>
<summary>Details</summary>

### One freshness signal, captured before `setProperties`

The gate is two lines on top of state the method already held:

```java
PropertyState priorState = configuration.toConfiguration().getRootState();
boolean firstRender =
    priorState == null
        || priorState.getValueAtPath(new PropertyPath(ConfigFields.CLEAN_SESSION)) == null;

configuration.setProperties(allProperties());

if (firstRender) {
  configuration.setValue(ConfigFields.CLEAN_SESSION, Boolean.TRUE);
}
...
if (!firstRender) {
  validate(configuration.toConfiguration().getRootState());
}
```

The capture point is the part that can silently break on a later edit. `setProperties` pre-populates
booleans with `Boolean.FALSE`, so moving the `priorState` read below it would make a generated `false`
indistinguishable from a deliberate one, breaking the Clean Session seed and the gate at once. Both
the inline comment and the Javadoc state this.

### The round-trip assumption now governs two behaviors

```
Appian                          template
  │ descriptor (root state null) ──────────►  firstRender = true
  │                                           seed cleanSession = TRUE
  │ ◄───── descriptor w/ cleanSession=TRUE    skip validate()
  │
  │ descriptor derived from ours ──────────►  firstRender = false
  │                                           validate() → errors per field
```

The left column is the unverified half. If Appian re-enters with persisted object state instead of a
descriptor derived from the template's own return value, `cleanSession` is `null` on every pass and
the arrow never reaches the second row: Clean Session is re-seeded `TRUE` forever and `validate()`
never runs. The discriminating deploy check is correctly identified in all four places it appears
(class Javadoc, `connected-system-descriptor-fix.md` §8 item 3, plan §5 item 2, verification §7
item 3) and the superseded check is explicitly labelled as proving nothing.

What no checklist covers is the save-without-interaction path. The gate keys on render count, not on
whether the designer touched anything, so if the platform commits a save without an intervening
`getConfigurationDescriptor` call, a brand-new object with no broker host and no port would pass
design time unvalidated. The runtime would still fail, and the integrations have their own
`InputValidator`, so this is a design-time UX gap rather than a correctness hole — but it is one
observation away from being settled and belongs beside the other two checks.

### Error stickiness survives beyond the first transition

`connected-system-descriptor-fix.md` §5 records that with the gate temporarily removed,
`enteringAValueClearsItsErrorWhileOtherFieldsStillReport` failed on *"a supplied broker host must not
be reported as an error"* — an error attached on one render survived into the next even after the
field was given a value. The conclusion drawn is that this is pre-existing, out of scope, and
"unreachable from a clean first render".

The first two hold. The third covers only render 1 → render 2, because render 1 now attaches nothing.
From render 2 onward errors do exist, so a designer who fills in the `brokerPort` that render 2
flagged may still see that message on render 3. The passing test cannot distinguish this, because it
only ever exercises the first transition. The behavior is correctly left alone as out of scope, but
the sentence should be narrowed and the observation added to the deploy list.

### Record corrections and version lockstep

The import count in the plan's §4 gate block reads `45` in both the per-file and the aggregate form,
and `grep -rh 'import com.appian.connectedsystems' src/main/java/ | wc -l` independently returns
`45`. The `topicFilters`/Expression correction appears in
plan §3 step 5 and verification §7 item 6, both stating that `getDefaultState` takes the
`SystemType.EXPRESSION` branch only when `shouldRenderAsExpression` is true — requiring
`DisplayHint.EXPRESSION` or a local type above level 1 — and that the `isListType()` branch of
`generateDefaultValue` returns `new ArrayList()`. Both keep the conclusion that leaving
`readStringList` unchanged was correct, and both spell out that `isExpressionable(true)` is not
`displayHint(EXPRESSION)`. `connected-system-descriptor-fix.md` is a genuine standalone report across
nine sections plus a follow-up, not a stub of links, and the steering file's §8 audit trail now names
the descriptor-fix records that actually exist.

The two version fields keep their two different formats: `<version>1.0.2</version>` (three-part, what
the AppMarket validator demands) and `<application-version min="26.9.0.0"/>` untouched, alongside
`key="de.hock.mqtt"` and `<vendor name="Hock" url="https://itondemand.com"/>`. `pom.xml` is `1.0.2`,
and the only `.jar` in `target/` is `mqtt-broker-connected-system-1.0.2-plugin.jar`. Surviving
`1.0.1` strings are confined to historical narration in the task records.

### Invariant evidence, and one spot-check

The recorded gate run in `connected-system-descriptor-fix.md` §6 and verification §8 covers every
required invariant with real output: one jar, 33 `.java` entries all under `de/hock/mqtt/**`, a bare
root `appian-plugin.xml` (count 1), `META-INF/lib/org.eclipse.paho.client.mqttv3-1.2.5.jar` with no
root `lib/`, zero `connected-systems` and zero `com/appian/mqtt` entries, zero test entries, `major
version: 61` on all three templates, 28 label keys, three `extends Simple...` hits, and a clean
code-policy sweep. `Tests run: 12, Failures: 0` — 9 in the descriptor test, 3 in
`ProjectStructureTest`. The `.java` count stayed at 33 because only a test file changed and the
assembly `fileSet` covers `src/main/java` only.

The one doubt worth closing was whether the jar sitting in `target/` was built from the restored
source rather than the `if (true)` negative-check variant. The shipped source inside the archive
carries `if (!firstRender)` at line 155 with the `validate(...)` call beneath it, so the deployable
artifact matches the reviewed code.

### Documentation lockstep

README no longer leaves the impression that errors appear immediately: a brand-new connected system
"opens clean", validation runs from the next render onward, and the existing sentence about errors
attached per field with entered values retained is preserved rather than replaced. The test-coverage
bullet names both halves, a new limitation bullet states the round-trip assumption and names the
clearing-Clean-Session check as the discriminator, and `docs/mqtt-plugin-guide.md` got the matching
sentence. Every config-field table, label, default, validation rule and integration input/output is
unchanged.

One wording carry-over spans README, the class Javadoc and the records: validation fires "the moment
you interact with the form or save it". Interaction implies a re-render and therefore follows from the
gate; save does not necessarily, which is the same uncertainty as finding 1.

</details>

<details>
<summary>File map</summary>

No git repository, so this review reads the working tree directly; modification times confirm the
touched set.

- `src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java` — `validate(...)` wrapped in
  `if (!firstRender)`; class Javadoc and `getConfiguration`'s `@return` updated for the new behavior
  and the round-trip assumption.
- `src/test/java/de/hock/mqtt/template/MqttConnectedSystemTemplateDescriptorTest.java` — `hasErrors`
  helper; `blankBrokerHostIsReportedAgainstItsField` renamed and rewritten as
  `blankBrokerHostIsReportedOnEverySubsequentRender`; two tests added (7 → 9).
- `pom.xml`, `src/main/resources/appian-plugin.xml` — `1.0.1` → `1.0.2`.
- `README.md`, `docs/mqtt-plugin-guide.md` — version and artifact paths, clean-first-render paragraph,
  test-coverage bullet, new round-trip limitation.
- `.kiro/steering/appian-appmarket-compliance.md` — version/artifact/`J=` paths, §5 item 2 first-render
  sentence, §5 item 4 test count, §8 audit trail paths.
- `.agents/tasks/connected-system-descriptor-fix.md` — new consolidated hand-off report (9 sections
  plus follow-up §10).
- `.agents/tasks/connected-system-descriptor-fix-plan.md`,
  `.agents/tasks/connected-system-descriptor-fix-verification.md` — import count 45, `topicFilters`
  correction, both deploy-checklist additions, 1.0.2 artifact paths, re-verification section.

</details>
