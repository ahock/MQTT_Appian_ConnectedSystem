# Plan — extract generic Appian plug-in knowledge into a user-level steering file

**Deliverable of the next step:** `/Users/andreas.hock/.kiro/steering/appian-plugin-development.md` (new, the
directory exists and is empty), a few-line pointer added near the top of
`/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/.kiro/steering/appian-appmarket-compliance.md`,
and the record `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/.agents/tasks/generic-steering-extraction.md`.

**Source of truth for every claim:** `.kiro/steering/appian-appmarket-compliance.md` (952 lines, read in full for
this plan), corroborated against `.agents/tasks/*.md`, `README.md`, `src/main/java/de/hock/mqtt/**`,
`src/assembly/plugin-assembly.xml`, `src/test/java/**`, read-only `git log`, and `javap` against
`~/.m2/repository/com/appian/connected-systems-{core-1.9.1,client-1.3.0}.jar`.

**Section references** of the form §1–§8 below mean sections of that source steering file.

---

## Evidence tiers (use these four labels, and put a visible marker on every claim in the new file)

| Label | Means | Suggested inline marker |
|---|---|---|
| VERIFIED-LIVE | Observed on a live Appian 26.9 instance (the 1.0.4 / 1.0.5 / 1.0.6 deploys, 2026-10-27) | `[LIVE]` |
| APPIAN-DOCS | Stated by an Appian documentation page — name the page | `[DOCS]` |
| SDK-BYTECODE | Measured offline from a jar with `javap` (Appian SDK unless the row says otherwise), or from an offline test | `[BYTECODE]` |
| UNSUBSTANTIATED | No evidence found in the source file, the records, the code or the SDK jars | must not appear in the new file |

Do not flatten these. A reader's decision about whether to trust a line depends on which one it carries.

---

# Implementation Plan

- [ ] 1. Read the inputs before writing anything: the source steering file in full, plus
      `.agents/tasks/appmarket-packaging-audit.md` (§A claims), `.agents/tasks/connected-system-descriptor-fix.md`
      (the `Simple*` NPE), `.agents/tasks/server-side-tls-1.0.7.md` (the TLS section), and
      `src/assembly/plugin-assembly.xml` (the packaging snippets).
      Files: none written.
      Verify: nothing to run; this is the grounding step. Appendix A of this plan is the claim ledger that
      resulted — do not add a claim to the new file that is not in it.

- [ ] 2. Create `/Users/andreas.hock/.kiro/steering/appian-plugin-development.md` with the YAML front matter
      from Appendix E verbatim, the scope-and-date line, and the evidence legend above. Nothing else yet.
      Files: `/Users/andreas.hock/.kiro/steering/appian-plugin-development.md`
      Verify: `python3 -c "import yaml,sys; d=yaml.safe_load(open('/Users/andreas.hock/.kiro/steering/appian-plugin-development.md').read().split('---')[1]); print(d)"`
      prints a dict with `inclusion: auto`, `name`, `description`. Keep `inclusion: auto` — it is valid.

- [ ] 3. Write the "start here" section and the packaging sections (outline items 2–5 in Appendix E):
      the four deploy-cycle-costing failures as a one-screen checklist, jar layout (sources inside the
      executable, bare-root manifest, `META-INF/lib/`, exactly one artifact), the assembly `fileSet`
      approach with the `<build><resources>` footgun explained, the Gradle equivalent, the two version
      fields with the documentation contradiction, vendor metadata, and the namespace rules with the
      find-and-replace trap.
      Files: the new steering file.
      Verify: `grep -c 'META-INF/lib' <new file>` ≥ 1 and the YAML check from item 2 still passes; then
      the leak greps in item 8.

- [ ] 4. Write the descriptor and template sections (outline items 6–8): the `Simple*` base-class rule with
      the precise NPE mechanism, the expressionability contract with the correct validator method name
      (Appendix A, row D1 — the brief's name is wrong), the forced-flag table reproduced verbatim from
      Appendix B, the no-document-upload consequence, the pasted-text workaround naming
      `ParagraphPropertyDescriptor`, and why a source grep is insufficient with the read-back test
      snippet from Appendix D.
      Files: the new steering file.
      Verify: the table in the new file has the same six rows and same four columns as Appendix B; the
      string `checkPropertiesModifiers` appears and `expressionableGuard` does not.

- [ ] 5. Write the SDK-dependency, validation, resource-bundle and code-policy sections (outline items
      9–12): both SDK artifacts at scope `provided`, the compatibility-table discrepancy with the advice
      not to downgrade, Java 17, the design-time-validation finding with the probe method and the
      error-clearing sharp edges, the bundle key contract and the icon file-naming convention, and the
      enforced code policies.
      Files: the new steering file.
      Verify: every claim in this block traces to a row in Appendix A; no row marked UNSUBSTANTIATED is
      present.

- [ ] 6. Write the TLS section (outline item 13) from Appendix A rows I1–I7: the in-memory `SSLContext`
      recipe, the two Appian policies that each forbid a different shortcut, no permissive trust manager,
      no certificate material in messages, `SSLContext.getInstance("TLS")` rather than a pinned version,
      and the socket-factory-wrapper lesson with the single sanctioned Eclipse Paho mention.
      Files: the new steering file.
      Verify: `grep -ci paho <new file>` returns exactly 1.

- [ ] 7. Write the working-discipline section (outline item 14), the copy-paste verification block from
      Appendix D, the AppMarket submission section from Appendix A rows K1–K7, and the reference-page list
      restricted to Appendix C, closing with the licensing rephrase note.
      Files: the new steering file.
      Verify: `grep -oE 'https?://[^ )`"]+' <new file> | sort -u` — every line must also appear in
      Appendix C.

- [ ] 8. Run the leak sweep over the new file and fix every hit: project nouns, counts, SHAs, jar names.
      Files: the new steering file.
      Verify:
      `grep -niE 'mqtt|paho|broker|topic|qos|subscri|publish|de\.hock|itondemand|1\.0\.[0-9]|68e15e8|d023a76|8621e99' <new file>`
      — the only acceptable hits are the one Paho mention, the words "publish"/"subscribe" used generically
      about integration request policies if needed, and the SDK version numbers `1.9.1` / `1.3.0` / `1.2.0`
      which are platform facts, not project versions. Justify each surviving hit in the record.

- [ ] 9. Add the pointer to the project steering file: a few lines near the top (immediately after the
      intro paragraph, before the 1.0.6 scope note) saying the platform-wide constraints also live at
      `~/.kiro/steering/appian-plugin-development.md`, that this file stays authoritative for this
      project's namespace, field inventory, counts, version history and verification commands, and that a
      platform-wide rule change must be made in both. Change nothing else.
      Files: `.kiro/steering/appian-appmarket-compliance.md`
      Verify: `git diff --stat .kiro/steering/appian-appmarket-compliance.md` shows insertions only and no
      deletions; `git diff` shows one contiguous added block; `grep -c '^## ' .kiro/steering/appian-appmarket-compliance.md`
      is unchanged at 8. Do not commit.

- [ ] 10. Write the record `.agents/tasks/generic-steering-extraction.md`: the generic/project-specific
      split, the corrections and omissions from Appendix A (rows A11, C5-part, F2-part, D1, H2 at minimum),
      the pasted output of verification steps 1–7 of the brief, three spot-checked factual claims with the
      command output that confirmed them (Appendix F has three already run and ready to re-run), the new
      file's line count and section outline.
      Files: `.agents/tasks/generic-steering-extraction.md`
      Verify: the record contains real pasted command output, not descriptions of it.

---

## Appendix A — Claim ledger

Generic wording is given in the "Generic claim" column; the writer may rephrase for flow but must not
reintroduce project nouns. "Brief" = the task brief's items A–K.

### A. AppMarket submission policies

| # | Generic claim | Evidence | Tier |
|---|---|---|---|
| A1 | A plug-in must ship its Java sources **inside** the deployable jar, as entries beside the `.class` files. A separate `-sources.jar` does not satisfy the policy — this was a real rejection reason. | §1 "Sources must ship *inside* the plug-in JAR"; `appmarket-packaging-audit.md` lines 10, 164 citing the submission policies page | APPIAN-DOCS (submission policies) |
| A2 | Plug-in packages must not be named `com.appiancorp.*` or `com.appian.*`; both are reserved. A submission using one gets rejected. | §1 "Namespace: never `com.appian.*`", citing the submission policies page; it was a real rejection | APPIAN-DOCS |
| A3 | Vendor metadata in the manifest must name the real author, never Appian, and must not use an `*.appian.com` URL. Claiming Appian as vendor is misattribution and a review risk. | §1 "Vendor must be the real author"; `appmarket-packaging-audit.md` line 239 | APPIAN-DOCS |
| A4 | Logging goes through Log4j only. No `System.out`, `System.err`, `printStackTrace()`, `java.util.logging`. `org.apache.log4j.Logger` at scope `provided` is the sanctioned route; the server supplies it. | §3 bullet 1 | APPIAN-DOCS for the rule; the "`provided` works" inference rests on A-tier VERIFIED-LIVE for SDK artifacts (F1), so present it as a reasoned route, not a tested one |
| A5 | No `System.setProperty()` and no other JVM- or server-global mutation. | §3 bullet 2 | APPIAN-DOCS |
| A6 | No direct filesystem manipulation. Use `ContentService` / the Document APIs for Appian documents. | §3 | APPIAN-DOCS |
| A7 | `Closeable` resources must be released in a guaranteed fashion (try-with-resources or `finally`), and temp files deleted. | §3 | APPIAN-DOCS |
| A8 | No hardcoded credentials, default passwords or key pairs anywhere — including tests and documentation. Use the Secure Credentials Store. | §3 | APPIAN-DOCS |
| A9 | No copyleft third-party libraries, and the submitter must hold full rights to redistribute everything bundled. Ship a third-party notice naming the license actually relied on when a dependency is dual-licensed. | §3 ("No copyleft third-party libraries"), §2 (`THIRD-PARTY-NOTICES.md` at the archive root); `appmarket-packaging-audit.md` line 284 states the full-rights requirement and cites the submission policies page | APPIAN-DOCS |
| A10 | Do not use the Administrator `ServiceContext`; use the context handed to the initial constructor. | §3 last bullet | APPIAN-DOCS |
| A11 | **Brief claim "Appian reserves the right to retain the source" — UNSUBSTANTIATED. OMIT.** A repo-wide grep for `retain the source` / `reserves the right` / `right to retain` across the steering file, every `.agents/tasks/*.md` and `README.md` returns nothing. Nothing in the project's evidence says this. | `grep -rniE 'retain (the\|its )?source\|reserves the right\|right to retain' --include='*.md' .` → no hits | UNSUBSTANTIATED |
| A12 | No background thread, executor or callback may outlive a request. | §3 bullet "No background thread, executor or callback may outlive a request", under the heading "Code policies Appian enforces … From the submission policies" | APPIAN-DOCS — but the source file does not quote the page for this bullet specifically. Phrase it as a policy the project treated as binding, and mark it `[DOCS]` with the submission-policies page named. |

### B. The two version fields

| # | Generic claim | Evidence | Tier |
|---|---|---|---|
| B1 | `<plugin-info><version>` must be a **three-part** `x.y.z` value. The AppMarket validator rejects a four-part value with a message stating the valid format is `x.y.z`. This was a real rejection. | §1 "Version number formats" | APPIAN-DOCS (versioning page) + observed validator rejection |
| B2 | `<application-version min="…">` is a **four-part** Appian platform version (e.g. `26.9.0.0`). Two fields in the same manifest, two different formats. | §1 version table | APPIAN-DOCS |
| B3 | **Appian's own configuration-files page shows a four-part value in its reference example, and that example fails the AppMarket validator.** Trust the versioning page; do not copy the config-files example. Say this explicitly — it is the trap a developer falls into by doing the obvious thing. | §1, naming both pages | APPIAN-DOCS (contradiction between two Appian pages, both named) |
| B4 | The plug-in version must increase for Appian to deploy a new build over an installed copy. | §1 "Bump `<version>` on every release"; `connected-system-descriptor-fix.md` line 274 and `minimal-smoke-build.md` line 683 assert it as the reason each release bumped | APPIAN-DOCS — caveat for the record: the project never attempted a same-version redeploy, so the negative case is untested. State the rule, do not claim it was measured. Do **not** link the UI-SDK versioning page that states it most plainly; that URL is not in the source file (Appendix C). |

### C. Packaging mechanics

| # | Generic claim | Evidence | Tier |
|---|---|---|---|
| C1 | `appian-plugin.xml` must be a **bare root entry** in the jar, with no directory prefix, or Appian ignores the plug-in entirely. | §1 "Manifest at the archive root"; §4 check `grep -c '^appian-plugin.xml$'` | APPIAN-DOCS (configuration files page) |
| C2 | Bundled third-party jars go under `META-INF/lib/`, not root `lib/`. The plug-in classloader resolves nested jars from `META-INF/lib/`; a library at root `lib/` is not on the path and fails at runtime with `NoClassDefFoundError`. Anonymised incident: this project originally shipped its bundled client library at root `lib/`. | §1 "Nested dependencies go in `META-INF/lib/`"; §5 baseline — the 1.0.4 deploy resolved the bundled library from `META-INF/lib/` on a live 26.9 instance | VERIFIED-LIVE (the correct placement works); the root-`lib/` failure itself was never deployed, so call it "would very likely have failed", as the source does |
| C3 | Produce **exactly one** deployable artifact. Two jars both carrying a root manifest means either looks deployable and an operator can install the wrong one. | §2 "Exactly one JAR must land in `target/`" | project-measured packaging rule; tier `[BYTECODE]`-equivalent offline observation — mark as an offline-verified build invariant, not an Appian contract |
| C4 | Include sources with a Maven assembly `fileSet` over `src/main/java` landing at the archive root mirroring the package structure. **Do not** use a `<build><resources>` entry: declaring `<resources>` replaces the default resource root, so `src/main/resources` must then be re-declared or the manifest and the resource bundle silently vanish from the jar; it also copies `.java` into `target/classes` and onto the test classpath. Never include `src/test/java` in the distributable. | §1 "Sources must ship inside"; `src/assembly/plugin-assembly.xml` (read — `fileSet` over `src/main/java`, `dependencySet` with `<outputDirectory>META-INF/lib</outputDirectory>`, `useProjectArtifact=false`, `scope runtime`); `appmarket-packaging-audit.md` option comparison | offline-verified build fact |
| C5 | Appian's documented connected-system-plug-in toolchain is **Gradle**; Maven works but you get none of Appian's boilerplate and must assemble the jar layout yourself. | **Not in the source file** — `grep -ni gradle` over the steering file and every `.agents/tasks/*.md` returns zero hits. Confirmed independently from Appian documentation instead: the develop-first-connected-system page walks through Gradle build tooling in the IDE, and the SDK version compatibility page refers to the SDK version living in the project's dependency file, giving `build.gradle` as the example. | APPIAN-DOCS (both pages are in Appendix C, so they may be linked). **Correction to the brief:** this is substantiated by Appian's docs, not by this project's records — the writer must not imply the project proved it. |
| C6 | Gradle equivalent for source inclusion: add the main source set's Java to the jar task, e.g. `jar { from sourceSets.main.allJava }` (or a `from('src/main/java')` copy spec). | Standard Gradle usage; **not exercised by this project** — the project is Maven-only. | Mark explicitly as "standard Gradle, untested here". Do not present it as a verified Appian recipe. |

### D. Connected system descriptor contract

| # | Generic claim | Evidence | Tier |
|---|---|---|---|
| D1 | **No** property descriptor on a connected system template may be expressionable. Appian rejects the template at descriptor-conversion time, so the configuration form never renders at all — error text "Property Descriptors in Connected System Template must not be Expressionable", code `APNX-1-4198-000`. | §1, which names the throw site precisely: `ConnectedSystemPropertiesValidator.checkPropertiesModifiers`, reached via `ConfigurationDescriptorToValueConverter.apply` ← `GetConnectedSystemConfigurationDescriptorFunction.eval`. Authority: the restrictions-and-limitations page. | VERIFIED-LIVE (it was the third deploy failure) + APPIAN-DOCS. **Correction to the brief:** the brief calls the method `expressionableGuard`. The source file names **`checkPropertiesModifiers`** on `ConnectedSystemPropertiesValidator`. Use the real name, or name only the class. |
| D2 | Integration **input** descriptors are exempt — they may be expressionable, and this project's integration inputs deliberately are. Never "clean up" expressionability repo-wide. | §1; test `publishIntegrationRendersItsRootStateAndItsThreeExpressionableInputs` | VERIFIED-LIVE (those integrations render and run) |
| D3 | The non-obvious consequence: **some SDK descriptor builders set the expressionable flag on themselves**, which makes those descriptor types unusable on a connected system template no matter what your source says. Deleting `.isExpressionable(true)` is necessary but not sufficient. | §1 table, measured with `javap -p -c` against `connected-systems-core-1.9.1.jar` | SDK-BYTECODE. Re-confirmed for this plan: `DocumentPropertyDescriptor$DocumentPropertyDescriptorBuilder.<init>` calls `PropertyDescriptorBuilder.isExpressionable(Z)` and its build path calls `Guard.checkPropertyIsExpressionable(Map)`. |
| D4 | Reproduce the table in Appendix B exactly: which builders force the flag, which forcings can be cleared, and which types are therefore unusable. | §1 table | SDK-BYTECODE |
| D5 | Because the document-upload descriptor is unusable, file-like input (certificates, keys, long blobs) on a connected system must be **pasted text**. `DisplayHint` offers only `NORMAL` and `EXPRESSION`, so a multi-line text input is its own descriptor type: `ParagraphPropertyDescriptor`. There is no document-upload field available on a connected system template with this SDK. | §1; re-confirmed for this plan: `ParagraphPropertyDescriptor$ParagraphPropertyDescriptorBuilder.<init>` calls only `PropertyDescriptorBuilder.type(...)` and puts `paragraphHeight`, and its `build()` invokes no `Guard` | SDK-BYTECODE + VERIFIED-LIVE (a Paragraph field renders on a live 26.9 connected system) |
| D6 | `DocumentPropertyDescriptor.builder()…isExpressionable(false).build()` throws `IllegalStateException` saying DOCUMENT descriptors must be expressionable. | §1; `connected-system-descriptor-fix.md` line 474 records the probe | SDK-BYTECODE |
| D7 | A source grep for the builder call is **not** sufficient proof. The reliable check renders the descriptor and reads the modifier back through the SDK's public `PropertyDescriptor.isExpressionable()` accessor — the same thing the platform validator walks. Keep both: the grep as a cheap necessary condition, the test as the sufficient one. | §1 "Sufficient:"; test code read at `src/test/java/de/hock/mqtt/template/MqttConnectedSystemTemplateDescriptorTest.java:167` | SDK-BYTECODE / offline test. Generic snippet in Appendix D. |
| D8 | Optional but worth one sentence: a list-type descriptor on an **integration input** is probably legal but could not be proven offline, because the validator that enforces the connected-system rule is platform-side and absent from both SDK jars. If you want one, make it the only risky thing in that deploy. | §1 "Precaution, NOT a known rule"; §5 item 9 | honest UNSUBSTANTIATED-by-design — include it **as** an open question, labelled as such, not as a rule |

### E. Template base classes

| # | Generic claim | Evidence | Tier |
|---|---|---|---|
| E1 | Extend the SDK's `Simple*` base classes (`SimpleConnectedSystemTemplate`, `SimpleIntegrationTemplate`) from the SDK **client** artifact. Do not implement the low-level `ConnectedSystemTemplate` / `IntegrationTemplate` interfaces. | §5 item 2 | VERIFIED-LIVE (the fix deployed and rendered; the low-level path crashed) |
| E2 | The failure mode, stated precisely because the error message is opaque: on the low-level path the implementer owns root-state construction. `ConfigurationDescriptor.getRootState()` is only `getState().get("root")`, which is `null` for a brand-new object, so creating a new connected system fails with an Appian expression-evaluation error of the form "Cannot invoke `PropertyState.setType(...)` because the return value of `ConfigurationDescriptor.getRootState()` is null". | §5 item 2, quoting the observed message | VERIFIED-LIVE |
| E3 | It cannot be fixed from outside the SDK package: `PropertyState`'s constructor is package-private, so the only public route to a root state is `StateGenerator` — which is exactly what `SimpleConfiguration.setProperties` runs (`generateFromExistingState` plus `withTypes`, so the local type is registered too). | §5 item 2 | SDK-BYTECODE |
| E4 | Worth including: `StateGenerator` seeds field defaults itself — Boolean → `Boolean.FALSE`, Integer → `null`, Text/EncryptedText/Paragraph → `""`. So a brand-new object renders a Boolean field **unchecked**, and a "no value at all" branch in your runtime code only ever fires for a configuration saved before the field existed. | §5 item 2 ("measured as `BOOLEAN` → `Boolean.FALSE` …") | SDK-BYTECODE |

### F. SDK dependencies

| # | Generic claim | Evidence | Tier |
|---|---|---|---|
| F1 | Two artifacts are needed: `com.appian:connected-systems-core` and `com.appian:connected-systems-client` (the `Simple*` facade). **Both at scope `provided`.** The Appian server supplies both on the plug-in classpath. | §2; §5 baseline and item 2 — a live 26.9 instance ran the build with the client at `provided` and no `NoClassDefFoundError` | VERIFIED-LIVE |
| F2 | Never bundle either artifact. Verify with a jar listing check that no `connected-systems` entry is present. | §2 "never bundle it"; §4 check `unzip -l $J \| grep -ci 'connected-systems'` → 0 | offline-verified invariant. **Correction to the brief:** the brief says bundling "causes classloader conflicts". The source file does not measure that; it says the server supplies them and the artifact must never be bundled. State the rule plus the reason (the platform already provides these classes), and do not assert a measured conflict. |
| F3 | Appian's SDK compatibility table names a core version that is years old on Maven Central (core 1.2.0, dated 2019, listed for every release from 24.4 through 26.9), while a much newer core (1.9.1, 2023) paired with client 1.3.0 runs correctly on Appian 26.9. **Do not downgrade to match the table** — you will likely break compilation on a seven-year-old artifact. The discrepancy is unexplained and worth asking Appian about before a submission; it blocks nothing. | §2 "Do not 'fix' the SDK version to 1.2.0"; §5 item 1 | VERIFIED-LIVE (the pairing ran on a live 26.9 instance) + APPIAN-DOCS (the table itself) |
| F4 | Appian 26.9 requires **Java 17**; compile with `release 17` and check the bytecode major version is 61. | §2; §4 `javap -verbose … \| grep 'major version'` → 61 | APPIAN-DOCS (developer setup page) + VERIFIED-LIVE |

### G. Design-time validation on a connected system

| # | Generic claim | Evidence | Tier |
|---|---|---|---|
| G1 | **Appian does not display design-time errors on a connected system template.** Errors attached through `PropertyState.setErrorAtPath` / `setErrorsAtPath` are silently discarded. Do not build design-time validation there — it is dead code that looks like working validation. | §5 item 7, "Observed result on a live Appian 26.9 instance: NO error message appeared" | VERIFIED-LIVE |
| G2 | `isRequired` is the **only** design-time guard Appian honours on a connected system. | §5 item 7 consequences | VERIFIED-LIVE |
| G3 | Therefore validate configuration at **run time**, inside the integration, and surface the problem through the integration response. | §5 item 7; §1/§5 record of the demotion | VERIFIED-LIVE (the demoted design deployed and worked) |
| G4 | The documentation alone could not settle it: the restrictions page says `setErrors()` is ignored on a connected system, but the path in question (`setErrorAtPath` → `setErrorsAtPath`) is a different method, so the docs left it open. The finding extends the documented restriction to those methods too. | §5 item 7 opening | APPIAN-DOCS (the ambiguity) + VERIFIED-LIVE (the resolution) |
| G5 | **How it was established, because the method generalises:** the validation was deployed deliberately **ungated**, so it ran on the very first render of a brand-new object and would necessarily have attached errors. A gate would have made a clean page ambiguous between "the gate worked" and "Appian discards our errors". Design platform experiments so **both** outcomes are informative. | §5 item 7 | VERIFIED-LIVE |
| G6 | Sharp edges that bind again the moment anyone attaches descriptor errors anywhere: (a) errors **persist across renders** unless explicitly cleared, because the state generator's clone copies them into the regenerated state; (b) `clearErrorsAtPath` installs the immutable `Collections.emptyList()`, so a subsequent `addErrors` throws `UnsupportedOperationException` — use `setErrorAtPath`, which replaces the list; (c) `setErrorsAtPath` silently no-ops when the path does not resolve, so validation must run **after** the per-field states have been generated. | §5 item 8, all three measured facts; `PropertyState.clearErrorsAtPath` confirmed present on core 1.9.1 with `javap` and bottoming out in `setErrorsAtPath(path, Collections.emptyList())` | SDK-BYTECODE. The brief's rendering of (b) and the persistence point matches the source; no correction needed. |
| G7 | Whether **integration input** errors display is a separate and still untested question. Do not assume either answer. | §5 item 7; §5 item 4 | open question — label it |
| G8 | Attaching errors does not break rendering, so this is a cosmetic dead end rather than a hazard. | §5 item 7 | VERIFIED-LIVE |

### H. Resource bundle and icons

| # | Generic claim | Evidence | Tier |
|---|---|---|---|
| H1 | `resources_en_US.properties` needs `[TemplateSimpleClassName].name` and `.description` for every registered template, and `[TemplateSimpleClassName].[fieldKey].label` for every field and every integration input. Keys are case-sensitive and derive from the **simple class name**, not the FQN — so a package rename does not touch them, but a class rename does. | §4 bundle check; `appmarket-packaging-audit.md` line 312 citing the configuration-files page for the `.name`/`.description` pattern; `2026-10-12-132655-review.md` line 52 on the rename/simple-name relationship | APPIAN-DOCS |
| H2 | Two distinct silent-failure modes, which the brief conflates. (a) **Registration** depends on the manifest's `key=`/`name=` attributes agreeing with the template's `@TemplateId` name — a mismatch there is what silently breaks registration. (b) **Labels** depend on the bundle keys — a mismatch there leaves the template registered but unlabelled. Both deploy without complaint. | `2026-10-12-132655-review.md` line 13 ("the one thing that would silently break registration … all three `key=`/`name=` attributes still equal the `@TemplateId` names") | the agreement requirement is documented/structural; neither failure was deliberately induced, so present the consequences as reasoned, not observed. **Correction to the brief:** do not attribute "the template never registers" to a bundle-key mismatch. |
| H3 | Prune the bundle to match the surface exactly. Orphan keys and missing keys are both bugs and both worth a programmatic check in the build or a test. | §4 bundle diff block | offline-verified practice |
| H4 | Worth adding (generic, and in the source): a connected system renders iconless unless you ship `src/main/resources/<your/package>/<YourTemplateClass>_40px.png`, `_50px.png` and `_80px.png`. This is separate from the AppMarket listing icon. | §5 item 3; `appmarket-packaging-audit.md` line 308 cites Appian's project-structure page for the path shape | APPIAN-DOCS |

### I. TLS / cryptography

| # | Generic claim | Evidence | Tier |
|---|---|---|---|
| I1 | Build TLS as an **in-memory** `SSLContext` and hand its socket factory to the client library: parse the PEM with `CertificateFactory`, load the certificates into `KeyStore.getInstance(KeyStore.getDefaultType())` opened empty with `load(null, null)`, feed a `TrustManagerFactory`, then `SSLContext.getInstance("TLS")`, then `getSocketFactory()`. | §3 TLS bullet; implemented in `src/main/java/de/hock/mqtt/TlsSupport.java` (read for this plan — exactly this sequence) | VERIFIED offline (the implementation and its tests); not yet deploy-verified — the source file says so plainly, so the new file must not claim live TLS verification |
| I2 | Two different Appian policies forbid the two usual shortcuts, and it is worth naming which forbids which: the ban on `System.setProperty` / JVM-global mutation rules out the `javax.net.ssl.trustStore` / `keyStore` system properties (which would also change TLS behaviour for every other plug-in on the server), and the ban on direct filesystem manipulation rules out writing a temporary key store file. | §3 TLS bullet, which makes exactly this split | APPIAN-DOCS (both policies) |
| I3 | Never add a permissive / "trust all certificates" trust manager, not even behind a flag. A blank CA means the JVM default trust store and a supplied CA means that CA alone, which covers both legitimate cases. | §3 TLS bullet; §4 greps for `trustAll` / `checkServerTrusted` expecting no hits | project rule grounded in the policies; mark as a rule, not a measurement |
| I4 | Never put certificate or key material, or any fragment of it, into an error message or an integration response. A parse failure may say parsing failed and name the expected PEM header; it must not echo the field contents. Test it by asserting no short run of the input appears in any message, and choose test inputs that share no words with your fixed messages so a substring scan cannot mistake overlap for a leak. | §3 bullet; §5 item 4 test descriptions | offline-verified practice |
| I5 | Prefer `SSLContext.getInstance("TLS")` over pinning a protocol version. Pinning an older version *excludes* newer ones — on a current JDK 17 runtime only TLS 1.3 and 1.2 are offered, so pinning 1.2 drops 1.3. | §3 TLS bullet; the offline test asserting a produced socket offers TLS 1.2 but neither 1.0 nor 1.1 | SDK-BYTECODE / offline test |
| I6 | **Socket-factory-wrapper trap.** If you wrap a `SocketFactory`, override **every** overload and first find out which one your client library actually calls. A real example: Eclipse Paho's TCP network module calls the **no-argument** `createSocket()` and then `connect(new InetSocketAddress(host, port), timeout)`; `javax.net.SocketFactory`'s inherited no-arg implementation throws `SocketException("Unconnected sockets not implemented")`. A wrapper in this project overrode six overloads but not that one, so every TLS connection it attempted would have failed. | §5 item 10, third corollary | SDK-BYTECODE (`javap -p -c` against the client library jar and `javax.net.SocketFactory`). The brief's description matches the source. **This is the one sanctioned Eclipse Paho mention.** |
| I7 | Generalise the same investigation: before you build a wrapper or a verifier, check with `javap` whether the library already does the job. In this project the premise the whole release was planned against — that the library's HTTPS hostname-verification flag applied only to its WebSocket transport — turned out to be **false**, the default was already on, and the wrapper would have been a mistake. Hostname verification ended up being the library's job and it worked. | §5 item 10 opening and its bytecode trace | SDK-BYTECODE. Keep this generic — it is the lesson, and it needs no second Paho mention (phrase it as "the client library"). |

### J. Working discipline

| # | Generic claim | Evidence | Tier |
|---|---|---|---|
| J1 | A green build and a passing test suite prove **nothing** about platform-contract compliance. Four of this project's failures were invisible offline. | §4 opening ("a green build alone proves nothing"); §5 history of three design-time failures plus two AppMarket rejections | VERIFIED-LIVE. Note for accuracy: the source records **two AppMarket rejections** plus **three successive design-time deploy failures** before the first build that rendered (1.0.2 → 1.0.3 was the third). Phrase the count carefully; "four failed live deploys" from the brief's framing is defensible only if you count the AppMarket validator rejection of the version format as a deploy. Prefer naming the kinds: two submission rejections, three design-time render failures. |
| J2 | The hard ceiling on offline verification: the validators that enforce these contracts are `com.appiancorp.*` **platform** classes and are not in the SDK jars. The core jar contains zero `com/appiancorp/` entries; the client jar contains only its own simplified-SDK classes. SDK-side guards and the state generator are the limit of what you can inspect. Everything beyond that is discoverable only by deploying. Know where the ceiling is. | §5 item 9; re-confirmed for this plan: `unzip -l connected-systems-core-1.9.1.jar \| grep -c 'com/appiancorp/'` → 0 | SDK-BYTECODE |
| J3 | Deploy incrementally, changing **one** thing per release, so a failure localises. The recovery pattern that worked: reduce the plug-in to a minimal smoke build that proves install + bundled-library loading + configuration-page rendering + one working integration, then add features back one release at a time with a deploy test each. | §5 scope note and baseline; `minimal-smoke-build.md` | VERIFIED-LIVE |
| J4 | Keep a known-good version and its commit, so there is always a diff target when something breaks. | §5 ("use commit X as the diff target") | practice; include the rule, not the SHA |
| J5 | Design experiments so both outcomes are informative — the ungated-validation probe (G5) is the worked example. | §5 item 7 | VERIFIED-LIVE |
| J6 | Offline test patterns that actually catch platform-contract bugs: (a) drive `getConfigurationDescriptor` with `ConfigurationDescriptor.builder().build()`, whose `getRootState()` is `null`, which is what Appian hands a brand-new object — this catches the whole null-root-state class; (b) read descriptor modifiers back through the SDK accessors instead of grepping source; (c) assert the expected field keys as an **ordered list**, so an added field fails too; (d) for a trust manager, prove it **restricts** trust (one accepted issuer, a leaf under it accepted, an unrelated certificate rejected) rather than asserting a factory is non-null; (e) verify a test has teeth by inverting its assertion once and watching it fail. | §5 item 4 test inventory; test helpers read at `src/test/java/de/hock/mqtt/template/MqttConnectedSystemTemplateDescriptorTest.java:68-107` | offline-verified practice |
| J7 | When documentation is ambiguous, inspect the jars with `javap -p -c`. Several of this project's answers came from bytecode, and one documented premise turned out to be wrong. | §1 table, §5 items 2, 8, 9, 10 | SDK-BYTECODE |
| J8 | Optional, generic, and verified: a blocking wait inside an integration request does work — a live 26.9 instance did not time out a ten-second wait. Whether Appian's own integration timeout fires before a longer wait is **unknown**; the 30-second figure in this project is its own self-imposed cap, not an Appian limit. | §5 1.0.6 deploy findings; `subscribe-integration-1.0.6.md` line 497 | VERIFIED-LIVE for the short wait, open question beyond it. If included, state both halves or omit entirely. |
| J9 | Optional, generic, verified: a second `<integration-template>` nested inside the same `<connected-system-template>` element registers and is offered to designers alongside the first. The nesting matters — they are children of the connected-system element, not siblings of it. | §5 1.0.6 deploy findings; §4 manifest nesting check | VERIFIED-LIVE |

### K. Submission process

| # | Generic claim | Evidence | Tier |
|---|---|---|---|
| K1 | Submissions go through the AppMarket Submissions site, `https://forum.appian.com/suite/sites/appmarket-submissions/page/home` — an Appian-built site on `forum.appian.com` needing an Appian Community login. It handles public listings, private org-only submissions, and new versions of existing listings. | §6 | APPIAN-DOCS / site fact |
| K2 | Listing type for this kind of plug-in: Plug-in (Connected System). | §6 | APPIAN-DOCS |
| K3 | Beyond the jar the form wants a mandatory square app icon — 180×180 px, `.jpg` or `.png`, sRGB, under 1024 KB, full-bleed, no transparency. | §6 | APPIAN-DOCS |
| K4 | Also 4–8 screenshots, 16:9, at least 1280×720, showing the Appian top navigation bar with realistic sample data; plus a description and the compatible Appian version(s). | §6 | APPIAN-DOCS |
| K5 | "Compatible Version(s)" means versions you have **actually tested**. Deploy before claiming one. | §6 | APPIAN-DOCS |
| K6 | Appian highly encourages pre-approval before a full submission — cheaper than another rejection round trip. | §6 | APPIAN-DOCS |
| K7 | Typical review turnaround is about 10 business days absent revisions; the contact address is `appmarket@appian.com`. | §6 | APPIAN-DOCS |

---

## Appendix B — the descriptor expressionability table, in generic form

Reproduce these six rows and four columns. Caption it as measured with `javap -p -c` against
`connected-systems-core:1.9.1` and mark it `[BYTECODE]`.

| Descriptor type | Builder sets the expressionable flag | Can the flag be cleared? | Usable on a connected system template? |
|---|---|---|---|
| `DocumentPropertyDescriptor` | yes, in the constructor | **no** — `Guard.checkPropertyIsExpressionable` in `checkValidState` throws | **no** |
| `AppianValuePropertyDescriptor` | yes, in the constructor | **no** — same guard | **no** |
| `FolderPropertyDescriptor` | yes, in the constructor | yes, no guard | only if explicitly cleared |
| `TimestampPropertyDescriptor` | yes, in the constructor | yes, no guard | only if explicitly cleared |
| `ListTypePropertyDescriptor` | yes, plus `displayHint(EXPRESSION)` | coupled — its own guard rejects an `EXPRESSION` hint with the flag cleared | effectively no (for a default-built descriptor) |
| Text, Paragraph, Boolean, Integer, Double, EncryptedText, LocalType | no — the base builder seeds `false` | n/a | yes |

Two footnotes to carry with it:

- `DocumentPropertyDescriptor.builder()…isExpressionable(false).build()` throws `IllegalStateException`
  stating DOCUMENT descriptors must be expressionable. There is therefore **no document-upload field
  available on a connected system template** with this SDK (D6).
- For the list row, the coupling is in principle escapable: `PropertyDescriptorBuilder.displayHint(...)`
  is public, so `displayHint(NORMAL)` plus `isExpressionable(false)` builds, and unlike the document
  descriptor there is no unconditional guard. Whether a list descriptor is legal on an **integration
  input** could not be proven offline (D8).

---

## Appendix C — URL allowlist

These are the **only** URLs that may appear in the new file. Every one is taken verbatim from the source
steering file (`grep -oE 'https?://[^ )\`"]+'`). No other URL may appear — not a differently-versioned copy
of the same Appian page, not a Community thread, not Maven Central, not the UI-SDK versioning page that
states the version-increment rule (B4). If a claim needs a page that is not on this list, state the claim
without a link.

| URL | What it is | Note |
|---|---|---|
| `https://docs.appian.com/suite/help/26.9/Shared_Components.html` | AppMarket submission policies | authority for section A |
| `https://docs.appian.com/suite/help/26.9/csp-configuration-files.html` | Configuration files / manifest | **contains the stale four-part version example** — say so |
| `https://docs.appian.com/suite/help/26.9/versioning.html` | Versioning | the correct authority for three-part `x.y.z` |
| `https://docs.appian.com/suite/help/26.9/restrictions-and-limitations.html` | Restrictions and limitations | authority for the expressionability ban and the ignored `setErrors` |
| `https://docs.appian.com/suite/help/26.9/integration-sdk-versions.html` | SDK version compatibility | **the table whose core version is years stale** — say so |
| `https://docs.appian.com/suite/help/25.4/project-structure.html` | Project structure | note: the source file cites the **25.4** path, so use exactly this |
| `https://docs.appian.com/suite/help/26.9/developer-setup.html` | Developer setup (Java 17) | |
| `https://docs.appian.com/suite/help/26.9/develop-first-connected-system.html` | Quick start / template contract | also the Gradle-toolchain reference for C5 |
| `https://docs.appian.com/suite/help/26.9/Appian_Plug-ins.html` | Appian Suite plug-ins | |
| `https://forum.appian.com/suite/sites/appmarket-submissions/page/home` | AppMarket Submissions site | section K |

Excluded even though present in the source file: the project's GitHub remote and the project vendor URL.

Close the reference section with the same licensing line the source file uses: a note that Appian
documentation content was rephrased for compliance with licensing restrictions. Keep any verbatim
quotation from an Appian page under 30 consecutive words — the two short strings worth quoting are the
AppMarket validator's version-format message and the connected-system expressionability error text, both
of which are already short.

---

## Appendix D — generic verification commands for the new file

Present these as a copy-paste block with placeholders, and say explicitly that expected counts are
project-specific so the reader substitutes their own.

### Jar layout

```bash
J=target/<your-artifact>-<version>-plugin.jar       # the assembly <id> gives the suffix

ls target/*.jar | wc -l                                            # 1 — exactly ONE deployable jar
unzip -l $J | awk '{print $NF}' | grep -c '^appian-plugin.xml$'     # 1 — bare root, no prefix
unzip -l $J | grep -c '\.java$'                                     # == count of files under src/main/java
unzip -l $J | grep '\.java$' | grep -vc '<your/namespace>/'         # 0 — no sources outside your package
unzip -l $J | grep -ci test                                         # 0 — no test classes or sources
unzip -l $J | grep -c 'META-INF/lib/.*\.jar$'                       # one per bundled dependency
unzip -l $J | grep -ci 'connected-systems'                          # 0 — the SDK is 'provided', never bundled
unzip -p $J appian-plugin.xml | grep -E '<version>|application-version|<vendor'
#   three-part x.y.z in <version>, four-part platform version in application-version min=,
#   and a vendor that is you
javap -verbose -cp target/classes <your.namespace>.<YourTemplate> | grep 'major version'   # 61 = Java 17
```

### Expressionability — the grep is necessary, the test is what proves it

```bash
# Necessary, and cheap. Keep your template's Javadoc free of the literal token so this stays clean.
grep -c isExpressionable src/main/java/<your/namespace>/<YourConnectedSystemTemplate>.java   # 0
```

```java
// Sufficient: render the descriptor and read every modifier back through the SDK's own accessor,
// which is what the platform validator walks. A builder can set the flag without your source saying so.
ConfigurationDescriptor rendered = new <YourConnectedSystemTemplate>()
    .getConfigurationDescriptor(ConfigurationDescriptor.builder().build(), new PropertyPath(), null);
LocalTypeDescriptor rootType =
    rendered.getTypes().get(rendered.getRootState().getType().getUnqualifiedTypeName());
assertNotNull(rootType, "the root state's type must resolve in the descriptor's type map");

List<String> offenders = new ArrayList<>();
for (PropertyDescriptor<?> d : rootType.getProperties()) {
  if (d.isExpressionable()) {
    offenders.add(d.getKey() + " (" + d.getClass().getSimpleName() + ")");
  }
}
assertTrue(offenders.isEmpty(),
    "Appian rejects a connected system template declaring any expressionable property descriptor "
        + "(APNX-1-4198-000), but these read back expressionable: " + offenders);
```

The same `ConfigurationDescriptor.builder().build()` argument — whose `getRootState()` is `null`, exactly
what Appian hands a brand-new object — is also the null-root-state regression guard (J6a).

### Banned-API sweep

```bash
grep -rn 'System\.out\|System\.err\|printStackTrace\|java\.util\.logging' src/main/java/    # no hits
grep -rnE 'System\.setProperty|javax\.net\.ssl\.(trustStore|keyStore)|createTempFile|File\.createTemp|java\.io\.File|java\.nio\.file' \
  src/main/java/                                                                            # no hits
grep -rniE 'trustAll|checkServerTrusted' src/main/java/                                     # no hits
```

A Javadoc sentence asserting the absence of one of these calls will match its own grep. Either phrase the
assurance without the token, or record the expected prose hit so the check stays meaningful.

### No credential or key material anywhere

```bash
grep -rniE 'BEGIN (RSA |EC |DSA )?PRIVATE KEY|BEGIN ENCRYPTED PRIVATE KEY' . \
  --exclude-dir=.git --exclude-dir=target                                                   # no hits
find . -path ./.git -prune -o -path ./target -prune -o \
  \( -name '*.jks' -o -name '*.p12' -o -name '*.pfx' -o -name '*.key' -o -name '*.pem' \
     -o -name '*.keystore' -o -name '*.truststore' \) -print                                # nothing
unzip -l $J | grep -ciE '\.(jks|p12|pfx|key|pem|keystore|truststore)$'                      # 0
```

Generate every certificate and key your tests need at run time inside the test JVM, with a randomly
generated single-use passphrase if a key store is unavoidable, so no repository literal can be mistaken
for a credential.

### Namespace

```bash
# Your own packages must not start with com.appian / com.appiancorp...
grep -rn 'package com\.appian' src/main/java/                                               # no hits
# ...but the SDK's own packages DO. Never find-and-replace the bare string "com.appian":
grep -rh 'import com.appian.connectedsystems' src/main/java/ | wc -l                        # your SDK imports
```

### Resource bundle — no orphans, no missing keys

```bash
B=src/main/resources/resources_en_US.properties
sed -nE 's/^([A-Za-z][A-Za-z.]*)=.*/\1/p' $B | sort > /tmp/bundle-actual.txt
# Build /tmp/bundle-expected.txt from your descriptor key constants:
#   <TemplateSimpleClassName>.name, .description, and <TemplateSimpleClassName>.<fieldKey>.label
#   for every connected system field and every integration input.
diff /tmp/bundle-expected.txt /tmp/bundle-actual.txt    # no output; '>' = orphan, '<' = missing
```

### The deploy check no offline command replaces

Copy the jar to `APPIAN_INSTALL/_admin/plugins/` and watch the server log for the successful-install line
and the connected-system template registration lines before claiming compatibility with any Appian version.

---

## Appendix E — front matter, outline, and the staleness line

### Front matter — use verbatim

```yaml
---
inclusion: auto
name: Appian Plug-in Development
description: Activate when building, debugging, packaging or submitting an Appian plug-in — connected system templates, integration templates, smart services, the Appian Integration SDK (connected-systems-core, connected-systems-client), appian-plugin.xml manifests and resource bundles, property descriptors, plug-in JAR packaging, or an AppMarket submission. Covers the platform contracts that fail only at deploy time.
---
```

`inclusion: auto` is intentional and correct. Do not change it to `always` or `fileMatch`.

### Staleness line — put it immediately under the title

State: gathered against **Appian 26.9** with Integration SDK **core 1.9.1 / client 1.3.0** on **Java 17**;
evidence collected **2026-10-12 to 2026-10-28**; live-instance findings come from deploys on **2026-10-27**.
Add one sentence telling the reader to re-verify anything marked `[DOCS]` against their own platform
version, because two of the pages cited here were wrong or stale at 26.9.

### Recommended section outline

1. Title, staleness line, evidence legend.
2. **Start here: the failures that cost a deploy cycle.** A short checklist with jumps — expressionable
   descriptor on a connected system; low-level template interfaces instead of `Simple*`; bundled library
   at root `lib/`; four-part plug-in version; design-time validation that never displays.
3. **Packaging the JAR.** Sources inside the executable; bare-root manifest; `META-INF/lib/`; exactly one
   artifact; the assembly `fileSet` with the `<build><resources>` footgun; the Gradle equivalent and the
   note that Appian's own toolchain is Gradle.
4. **The manifest.** The two version fields and their two formats, with the documentation contradiction
   named; vendor metadata; module keys and `@TemplateId` agreement; integration templates nested inside
   the connected-system element.
5. **Namespace.** The reserved prefixes, and the find-and-replace trap created by the SDK using one of them.
6. **Template base classes.** `Simple*` from the client artifact; the null-root-state NPE mechanism and
   its opaque error message; why it cannot be fixed outside the SDK package; `StateGenerator`'s default
   seeding.
7. **Property descriptors.** The expressionability ban and what it costs; the forced-flag table; no
   document upload, so pasted text and `ParagraphPropertyDescriptor`; grep is necessary, the read-back
   test is sufficient; the open integration-input list-descriptor question.
8. **Validation: design time versus run time.** The probe result; `isRequired` as the only honoured guard;
   validate at run time through the integration response; the error-clearing sharp edges kept as a
   conditional warning; the untested integration-input question.
9. **SDK dependencies and versions.** Both artifacts `provided`; the compatibility-table discrepancy and
   the advice not to downgrade; Java 17 and bytecode major version 61.
10. **Resource bundle and icons.** Key shapes; the two silent-failure modes kept distinct; prune
    programmatically; the three icon files and their path shape.
11. **Code policies Appian enforces.** The banned-API list, resource and thread discipline, credentials,
    licensing of bundled libraries, the Administrator `ServiceContext`.
12. **TLS without touching the JVM or the disk.** The in-memory recipe; which policy forbids which
    shortcut; no permissive trust manager; never echo key material; `"TLS"` not a pinned version; the
    socket-factory-wrapper trap and the "check what your library already does" lesson.
13. **Working discipline.** What offline verification cannot prove and where the ceiling is; one change
    per deploy and the smoke-build recovery pattern; keep a known-good reference; design two-sided
    experiments; the offline test patterns that catch real contract bugs; `javap` as the tool of record.
14. **Copy-paste verification block** (Appendix D).
15. **AppMarket submission.**
16. **Reference pages** (Appendix C only) and the licensing rephrase note.

Target length follows the content; roughly 300–420 lines is the expected landing zone. Do not pad section
13 — it is the most valuable part and should stay pointed.

---

## Appendix F — three spot-checks already run, for the record

Re-run these and paste the output into `.agents/tasks/generic-steering-extraction.md` (brief verification
step 6). All three were executed while writing this plan and confirmed the source file's claims.

```bash
CORE=~/.m2/repository/com/appian/connected-systems-core/1.9.1/connected-systems-core-1.9.1.jar

# 1. The document descriptor really does force the flag AND guard it (Appendix B row 1).
javap -p -c -cp $CORE \
  'com.appian.connectedsystems.templateframework.sdk.configuration.DocumentPropertyDescriptor$DocumentPropertyDescriptorBuilder' \
  | grep -E 'isExpressionable|checkPropertyIsExpressionable'
#   observed: PropertyDescriptorBuilder.isExpressionable:(Z)... in <init>
#             Guard.checkPropertyIsExpressionable:(Ljava/util/Map;)V on the build path

# 2. The paragraph descriptor forces nothing and guards nothing (Appendix B last row, D5).
javap -p -c -cp $CORE \
  'com.appian.connectedsystems.templateframework.sdk.configuration.ParagraphPropertyDescriptor$ParagraphPropertyDescriptorBuilder' \
  | grep -E 'type|paragraphHeight|Guard|isExpressionable'
#   observed: PropertyDescriptorBuilder.type(...) and ldc "paragraphHeight"; no Guard, no isExpressionable

# 3. The platform validators are genuinely absent from the SDK (J2).
unzip -l $CORE | grep -c 'com/appiancorp/'      # observed: 0
```

---

## Appendix G — exclusion list

None of the following may appear in `/Users/andreas.hock/.kiro/steering/appian-plugin-development.md`.

**Domain vocabulary.** MQTT, Paho (except the single sanctioned mention below), broker, topic, topic
filter, QoS, retained message, clean session, keep-alive, last will and testament, publish/subscribe as
MQTT operations, `ssl://` and `tcp://` scheme examples, `MqttConnectOptions` and every other `Mqtt*` type.

**Identity.** The `de.hock.mqtt` package, the `de.hock` groupId, the plug-in key, the vendor name, the
vendor URL, the GitHub remote.

**Project class names.** Every class under `src/main/java/de/hock/mqtt/**` and every test class name —
including the publisher, subscriber, TLS helper, run-time validator, connected system template and both
integration templates. Describe roles generically ("the connected system template", "a run-time
configuration validator").

**Counts and inventories.** Numbers of source files, fields, descriptors, integration inputs, tests, test
classes, SDK imports, resource-bundle keys, validator rules, or Paho call sites. Field and input key
inventories. Specific default values and ranges.

**History and artifacts.** Plug-in version numbers `1.0.0`–`1.0.7`, commit SHAs (`d023a76`, `fc506d8`,
`f14c5b6`, `68e15e8`, `8621e99`, `4bef5ec`, `91c9e81`), jar filenames, `target/` paths, the absolute
workspace path, this project's deploy checklists, release records and `.agents/tasks/*` filenames, and the
project `README.md`.

**Open items that are only this project's.** Missing artwork for this plug-in, this project's
`isImportCustomizable` decision as a decision (the underlying "should" from the restrictions page is
generic and may be stated as such), mutual TLS being deferred here, and which release is currently
known-good.

Permitted and expected: SDK coordinates and versions (`connected-systems-core` 1.9.1,
`connected-systems-client` 1.3.0, the documented 1.2.0/1.1.0 table figures), Appian platform versions,
Java 17 and bytecode major version 61, Appian error codes and messages, SDK class and method names, and
the placeholder-bearing commands in Appendix D.

**The one sanctioned exception:** Eclipse Paho may be named **exactly once**, in the TLS
socket-factory-wrapper lesson (ledger row I6), because the point is to verify which `createSocket`
overload *your* client library calls and a concrete library makes it land. Row I7 covers the same
investigation and must say "the client library" instead. Verify with
`grep -ci paho <new file>` returning 1.
