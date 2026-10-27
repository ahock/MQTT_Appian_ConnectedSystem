# Removing expressionability from the MQTT connected system descriptor (1.0.2 → 1.0.3)

Appian rejects any connected system template that declares an expressionable property descriptor, so
`New > Connected System` failed at descriptor-conversion time with APNX-1-4198-000. The change deletes
all 14 `.isExpressionable(true)` calls from `MqttConnectedSystemTemplate` and — because
`DocumentPropertyDescriptor`'s builder forces the flag on and refuses to build with it cleared — converts
the two certificate upload fields to `ParagraphPropertyDescriptor` fields holding pasted PEM, adds
`PemValidator` to report a non-PEM value (notably a document id left by a 1.0.2 object) on its own field,
and pins both halves of the asymmetric platform rule with seven new tests that read modifiers back through
the SDK's public descriptor API. Both integrations are untouched: `MqttPublishIntegration` keeps its 4
calls and `MqttSubscribeIntegration` its 3, and their file timestamps (Oct 13) confirm they were never
edited in this pass.

The over-application risk named in the brief did not materialize. The one thing to weigh is the scope
expansion: the certificate fields changed shape, which is a breaking change for existing objects and goes
beyond the plan's "delete 14 calls" item.

Watch for: the certificate-field type change is a deliberate, documented scope expansion recorded as
user-approved in the handoff, and it is a breaking migration for any 1.0.2 object (confirmed); the §11
deploy checklist asks the deployer to confirm that pasting PEM "clears" a reported error, which
contradicts the never-cleared-error limitation recorded two paragraphs above it in the same document
(confirmed); the PEM shape check is gated on `tlsEnabled`, so with TLS off a stale document id is never
reported and `toConnectionConfig` still hands its UTF-8 bytes to the TLS layer (confirmed); no standalone
record of the nine-invariant sweep exists in the repo, only the surefire XML and the built jar, so I
re-ran the jar inspections myself and all nine pass (confirmed).

**Verdict**: APPROVED

## High-level view

The removal is exactly scoped. Repo-wide `isExpressionable` is 7, all in the integration package, 0 in
the template; publish keeps `topic`/`payload`/`qos`/`retained` and subscribe keeps
`topicFilters`/`maxWaitSeconds`/`maxMessageCount` with `qos` still deliberately non-expressionable. Only
the single builder line was dropped from each of the 14 chains: all 20 fields are present with their keys,
labels, instruction text, `isRequired`, `masked`, `displayMode` and `choices` intact, and the six fields
that never declared the flag are untouched except for the two certificate fields discussed below.

Deleting the calls was necessary but not sufficient, and the coder caught that: `DocumentPropertyDescriptor`
forces the flag on in its builder, so `caCertificate` and `clientCertificate` had to stop being document
uploads and became pasted-PEM paragraph fields. That is a user-visible change the plan did not anticipate,
justified by a constraint the plan could not see, and recorded as escalated and user-approved. I verified
the forcing mechanism in SDK bytecode rather than taking the handoff's word for it.

The regression test is real, not a grep dressed up as an assertion.
`noConnectedSystemPropertyDescriptorIsExpressionable` renders the descriptor, walks
`rootType.getProperties()`, reads `PropertyDescriptor.isExpressionable()` — the same object graph the
platform validator walks — asserts 20 descriptors were inspected so it cannot pass vacuously, and reports
every offender rather than the first, which is how both document fields surfaced at once. The handoff
records it failing with `[brokerHost (TextPropertyDescriptor)]` when the flag is re-added.
`integrationInputExpressionabilityIsPinnedPerInput` pins the other half per key, asymmetry included.

The first two fixes are undisturbed. All four behavioral tests
(`cleanSessionDefaultsToTrueOnFirstRenderAndRespectsADeliberateFalse`,
`firstRenderOfANewConnectedSystemHasNoValidationErrors`, `blankBrokerHostIsReportedOnEverySubsequentRender`,
`enteringAValueClearsItsErrorWhileOtherFieldsStillReport`) plus the two root-state tests and the 20-field
test are present and passing in the recorded surefire output, unmodified.

Documentation moved in lockstep and is accurate per input rather than smoothed over: README line 96 now
states that no connected system field is expressionable, the security note no longer promises an SCS
reference on the connected system, publish documents all four inputs as expression-capable while subscribe
documents the `qos` exception, the TLS table shows both certificate fields as multi-line pasted PEM, and
the migration break from 1.0.2 appears in both the TLS section and the limitations list. The steering file
carries the new hard constraint with the measured per-descriptor-type table, and the test count reads 19
everywhere (README, steering, handoff) against the recorded 3 + 16.

Version and packaging are clean: `1.0.3` in both `pom.xml` and `appian-plugin.xml`, three-part in the
manifest, `<application-version min="26.9.0.0"/>` untouched, and all nine jar invariants confirmed against
the built artifact — one jar, 34 `.java` entries all under `de/hock/mqtt/`, bare-root `appian-plugin.xml`,
Paho only under `META-INF/lib/`, no bundled SDK, no `com/appian/mqtt`, no test entries, Hock vendor,
bytecode major 61, 45 SDK imports intact.

<details>
<summary>Issues (5)</summary>

1. **Scope expansion beyond the plan** — the two certificate fields changed from document upload to pasted
   PEM and `PemValidator` was added; justified by the SDK's forced expressionable flag (independently
   verified in bytecode) and recorded as user-approved, but it is a breaking migration for any 1.0.2
   object. No action beyond confirming the escalation record is what the user agreed to.
2. **Contradictory deploy check in §11** — the checklist asks the deployer to confirm that pasting the PEM
   "clears" the non-PEM error on save, while the same document records that `validate` never calls
   `clearErrorsAtPath`. Reword the check to "reopen the object and confirm no error is reported" so a
   deployer does not read a known limitation as a failed deploy.
3. **PEM shape check is TLS-gated** — with `tlsEnabled` false a stale document id in a certificate field is
   never reported, and `toConnectionConfig` still passes its UTF-8 bytes to the TLS layer, so if
   design-time validation turns out to be ignored on connected systems (open item 7) the only symptom is a
   `CONFIGURATION` error on first connect. Acceptable as-is; worth a line in the limitations list.
4. **No recorded invariant sweep in the repo** — only `target/surefire-reports/*` and the built jar survive
   as evidence; the nine-invariant output lives outside the workspace. Capture it in a verification note so
   the next reviewer does not have to re-derive it.
5. **Private key field is still single-line encrypted text** — the README now presents the three TLS
   material fields as consistently text-based, but only the two certificate fields are multi-line
   paragraphs; whether a multi-line PEM private key pastes cleanly into `EncryptedTextPropertyDescriptor`
   is unverified (possible). Add it to the deploy checks rather than changing code.

</details>

<details>
<summary>Details</summary>

## The forced-flag trap, verified independently

The interesting part of this change is the half the plan did not predict. With the 14 explicit calls gone,
`grep -c isExpressionable` on the template reads 0 while the rendered descriptor still carried two
expressionable fields. I confirmed the mechanism rather than taking the handoff's word for it:

```
DocumentPropertyDescriptor$DocumentPropertyDescriptorBuilder.<init>:
  12: aload_0
  13: iconst_1
  14: invokespecial PropertyDescriptorBuilder.isExpressionable:(Z)...

checkValidState():
  34: invokestatic  Guard.checkPropertyIsExpressionable:(Ljava/util/Map;)V
```

The builder seeds the flag and the guard refuses to build without it, so the SDK requires what the
platform forbids. That makes the source grep the weaker of the two checks, which is exactly how the
steering file now frames it, and it is the reason the brief's "only delete the 14 calls" instruction could
not have produced a working descriptor on its own.

The resulting field-type change is user-visible: a designer who previously picked a document now pastes
PEM text. The handoff argues the old path was dead code — `toConnectionConfig` could only produce bytes
when the saved value already carried them, and a document reference is an opaque id this SDK offers no
accessor to resolve — so TLS with a custom CA or client certificate never worked and is expected to work
for the first time in 1.0.3. That reasoning holds against the code I read: the whole runtime chain consumes
`byte[]` and never resolves a document.

## Migration path and where it leaks

`PemValidator` turns the migration failure into a field-scoped message naming the expected `-----BEGIN
CERTIFICATE-----` header and the upgrade case, and `aLegacyDocumentReferenceInACertificateFieldIsReportedAsNonPem`
drives it with the bare numeric id a stored document reads back as.

The gap is the gate. The check runs only when `tlsEnabled` is true, matching the existing certificate-load
gate, so an upgraded object with TLS off carries a silent non-PEM value. `toConnectionConfig` is not
gated the same way — `resolveCertificateBytes` returns the id string's UTF-8 bytes regardless — so the
value reaches `MqttConnectionConfig` and fails at `CertificateFactory` on the first connect attempt, as a
`CONFIGURATION`-category error rather than a design-time message. With TLS off the certificates are unused,
so the practical blast radius is small, but it is the same failure mode the validator exists to prevent,
and it is the behavior that remains if the unresolved `setErrors`-ignored question turns out to cover
`setErrorAtPath`.

The `Document` branch retained in `resolveCertificateBytes` is now unreachable through the descriptor
(nothing can store a `Document` in a paragraph field) and is documented as retained so an upgraded object
cannot throw.

## Test coverage

Recorded surefire output: `ProjectStructureTest` 3 tests, `MqttConnectedSystemTemplateDescriptorTest` 16,
all passing, no failures or errors. The expressionability pair does what the brief required — reads the
modifier through `PropertyDescriptor.isExpressionable()` on the rendered descriptor, per key, with
`orElseThrow` on a missing key so a rename fails loudly instead of passing. `expressionable(...)` is a
helper over the real accessor, not a fallback approximation, so the brief's "confirm the fallback is not
faked" condition does not arise.

`pastedPemCertificateReachesTheTlsLayerAsBytes` is the strongest new test: pasted PEM → UTF-8 bytes →
`MqttConnectionConfig` → `ConnectOptionsFactory.build`, asserting a non-null socket factory, which
`buildTrustManagers` only produces if `CertificateFactory` really parsed the text. The fixture is a
certificate only, no private key or passphrase anywhere, which keeps the no-hardcoded-credentials policy
intact.

`validPastedPemIsNotReportedAsAnError` deliberately asserts the boundary rather than papering over it: it
proves valid PEM is not reported as malformed, distinguishes the client-certificate-without-key error from
a PEM shape error by matching on the message text, and asserts `brokerHost` still reports so the whole test
cannot pass on a render where validation never ran.

Not tested: publish, subscribe, LWT, reconnection or diagnostics at runtime; whether Appian accepts the
descriptor at all (only a deploy can settle that); whether the platform honors `setErrorAtPath` on a
connected system. The dead jqwik/moquette Surefire/Failsafe wiring is still declared with no matching
files, and both the README and steering file now say so explicitly.

</details>

<details>
<summary>File map</summary>

Not a git repository; changed files identified by modification time (Oct 14) against an otherwise Oct 12–13
tree.

- `src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java` — 14 `.isExpressionable(true)`
  calls deleted; both certificate fields `Document` → `Paragraph` with PEM instruction text;
  `PemValidator` wired into `validate` behind the TLS gate; `resolveDocumentBytes` renamed
  `resolveCertificateBytes`; class Javadoc records the platform rule and the forced-flag trap.
- `src/main/java/de/hock/mqtt/validation/PemValidator.java` — new; PEM header shape check producing
  field-scoped results, blank stays valid.
- `src/test/java/de/hock/mqtt/template/MqttConnectedSystemTemplateDescriptorTest.java` — 7 tests added
  (expressionability both halves, modifier rules, certificate field type, PEM-to-TLS handoff, legacy
  document reference, valid PEM).
- `src/main/resources/resources_en_US.properties` — the two certificate labels gained "(PEM)"; 28 label
  keys unchanged.
- `src/main/resources/appian-plugin.xml`, `pom.xml` — `1.0.2` → `1.0.3`.
- `README.md` — expressionability claims corrected (lines 96, security notes), per-input expression
  support documented, TLS table and migration break updated, test count 19.
- `.kiro/steering/appian-appmarket-compliance.md` — new §1 hard constraint with the per-descriptor-type
  table, version and test-count bumps, §4 checks, §5 open items.
- `.agents/tasks/connected-system-descriptor-fix.md` — §11 appended; §1–§10 intact.
- Untouched, confirmed: `MqttPublishIntegration.java`, `MqttSubscribeIntegration.java`,
  `CertificateLoader.java`, `ConfigFields.java`, all other validators.

</details>
