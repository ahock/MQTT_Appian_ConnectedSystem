# Implementation Plan — AppMarket Compliance (Appian 26.9)

Target repo: `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component`
Not a git repository. All edits happen **in place**. Do not run any `git` command.

Prior art: `.agents/tasks/appmarket-packaging-audit.md` (read-only audit, already confirmed the build
wiring). This plan supersedes its recommendations where they differ.

---

## Ground truth established by exploration

| Fact | Evidence |
|---|---|
| Shipped artifact is **`target/mqtt-broker-connected-system-1.0.0-plugin.jar`** | `maven-assembly-plugin` exec `build-appian-plugin`, phase `package`, goal `single`, `<id>plugin</id>` + `<finalName>${project.artifactId}-${project.version}</finalName>` |
| A second jar `target/mqtt-broker-connected-system-1.0.0.jar` is produced by the implicit `default-jar` execution | audit §2.3, confirmed by `ls target/` |
| `src/main/resources/appian-plugin.xml` = **the real Appian plug-in manifest** (`<appian-plugin>` root) | read directly |
| `src/assembly/appian-plugin.xml` = **maven-assembly descriptor** (`ASSEMBLY/2.1.0` namespace), referenced by the pom `<descriptor>` | read directly + pom |
| Sources are **not** in either jar; nothing in the build would put them there | audit §3 |
| Paho is bundled at **root `lib/`**, not `META-INF/lib/` | assembly `dependencySet` `<outputDirectory>lib</outputDirectory>` |
| `src/main/java` holds **33** `.java` files (incl. 4 `package-info.java`); 48 `.class` files result | `wc -l`, audit §2.4 |
| `src/test/java` holds exactly **one** file: `ProjectStructureTest.java` (3 smoke tests). No `*Properties.java`, no `*IT.java` exist | `find src/test` |
| 174 literal `com.appian.mqtt` / `com/appian/mqtt` hits across 39 files (excl. `target/`) | repo grep |
| Policy sweep is largely **already clean**: zero `System.out`/`System.err`/`printStackTrace`/`java.util.logging`, zero `System.setProperty`, zero `java.io.File`/`java.nio.file`, zero SNAPSHOTs | greps, see item 11 |
| TLS already uses an in-memory `SSLContext` handed to Paho via `options.setSocketFactory(...)` | `ConnectOptionsFactory.java:94,166,200,255` |
| Toolchain available: Maven 3.10.0, default JDK 23, **Amazon Corretto 17 at `/Library/Java/JavaVirtualMachines/amazon-corretto-17.jdk/Contents/Home`** | `mvn -v`, `/usr/libexec/java_home -V` |
| Maven Central reachable at planning time (HTTP 200) | `curl` probe |

---

## Design decisions recorded here (made during this planning step)

**DD-1 — `@TemplateId` names stay as the simple class names.** Current values are
`MqttConnectedSystemTemplate`, `MqttPublishIntegration`, `MqttSubscribeIntegration`. They do **not**
embed `com.appian`, so no policy forces a change; changing them would break every already-deployed
connected system for zero compliance gain. Keep them, and keep the manifest `key` attributes equal
to them. (Appian only *recommends* package-style template ids.)

**DD-2 — SDK stays on `connected-systems-core:1.9.1`. Do not downgrade to 1.2.0 and do not bump to
1.10.0.** Binding per orchestrator correction §1. Appian's compatibility table names Core 1.2.0 /
Client 1.1.0 for 24.4–26.9, but 1.2.0 dates from 2019-07-02 while 1.9.1 is 2023-11-07 and 1.10.0 is
2026-02-05; the code depends on descriptor APIs (`DocumentPropertyDescriptor`,
`EncryptedTextPropertyDescriptor`, `ListTypePropertyDescriptor`, `BooleanDisplayMode`, `Choice`,
`SystemType`) that a seven-year-old artifact almost certainly lacks, and only 1.9.1 is in the local
`~/.m2`. **Change nothing.** Record the compatibility-table-vs-Maven-Central discrepancy in the final
report as an **open question for the user to confirm with Appian** — explicitly not ours to resolve.

**DD-3 — do not convert the templates to `SimpleConnectedSystemTemplate` / `SimpleIntegrationTemplate`.**
The three classes `implement` the low-level `ConnectedSystemTemplate` / `IntegrationTemplate`
interfaces (public SDK API). The `Simple*` hierarchy lives in `com.appian:connected-systems-client`,
which the pom never declares. Converting means a new dependency plus a rewrite of ~1,320 lines across
the three classes — out of scope for a compliance fix and high regression risk with zero automated
behavioral tests to catch breakage. Keep the interfaces; **do** correct the false Javadoc claim
(item 12) and record the divergence as a submission note.

**DD-4 — fix source inclusion in the assembly descriptor, not with a `<resources>` entry.** Only the
assembly jar is shipped, so a `<fileSet>` there keeps `target/classes` and the compile/test classpath
untouched and avoids the `<build><resources>` override foot-gun. `maven-source-plugin` is explicitly
rejected: a sibling `-sources.jar` is a second file, not source *within* the executable.

**DD-5 — sources land root-mirrored (`de/hock/mqtt/...`), beside their `.class` files.** That is the
whole point of the policy: a reviewer opening the jar sees each source next to its class. `.java`
entries are inert to the classloader, so there is no runtime risk.

**DD-6 — exactly one artifact must land in `target/`: suppress the implicit `default-jar` execution.**
Binding per orchestrator correction §5d. Today both jars carry `appian-plugin.xml` at their root, so
an operator can deploy the wrong one. Of the offered options, binding `default-jar` to phase `none`
is chosen over a classifier because a classified jar still looks deployable — skipping it leaves one
file in `target/` and makes the submission unambiguous. The assembly does not need the project
artifact (`<useProjectArtifact>false</useProjectArtifact>`). Also set
`<addMavenDescriptor>false</addMavenDescriptor>` on the assembly so the shipped jar carries no
`META-INF/maven/**`. Known cost: `mvn install`/`mvn deploy` will no longer have a main artifact to
attach; neither is used by this workflow. Item 10b carries a fallback if `package` breaks.

**DD-7 — the final "no `com.appian.mqtt` anywhere" grep is scoped to repo content**, i.e. `src/`,
`pom.xml`, `docs/`, `info.md`, `.kiro/`, and the repo root — excluding `target/` (build output) and
`.agents/` (agent artifacts: this plan and the audit intentionally quote the old name as historical
record). State that scope when reporting the grep.

---

## Plan

### Phase 1 — package rename (must complete first; everything else references the new FQNs)

- [ ] 1. Move the main source tree `src/main/java/com/appian/mqtt/**` → `src/main/java/de/hock/mqtt/**`,
      preserving all five subpackages (`diagnostics`, `integration`, `protocol`, `template`,
      `validation`) and all 4 `package-info.java` files. Then delete the now-empty
      `src/main/java/com` tree. Use directory moves (`mkdir -p src/main/java/de/hock && mv
      src/main/java/com/appian/mqtt src/main/java/de/hock/mqtt && rm -rf src/main/java/com`).
      Files: all 33 files under `src/main/java/com/appian/mqtt/**`.
      Verify: `find src/main/java -name "*.java" | wc -l` → `33`; `test ! -d src/main/java/com` succeeds.
      The build is intentionally red between items 1 and 3.

- [ ] 2. Move the test tree `src/test/java/com/appian/mqtt/ProjectStructureTest.java` →
      `src/test/java/de/hock/mqtt/ProjectStructureTest.java` and delete the empty
      `src/test/java/com` tree.
      Files: `src/test/java/com/appian/mqtt/ProjectStructureTest.java`.
      Verify: `test -f src/test/java/de/hock/mqtt/ProjectStructureTest.java && test ! -d src/test/java/com`.

- [ ] 3. Rewrite every `package` declaration, every `import`, and every Javadoc/`{@link}`/string
      reference from `com.appian.mqtt` to `de.hock.mqtt` across all 34 moved `.java` files.
      **CAUTION: `com.appiancorp.*` and `com.appian.connectedsystems.*` are Appian SDK packages —
      leave them untouched.** A safe sed is literal and anchored on the project package only:
      `grep -rl 'com\.appian\.mqtt' src/main/java src/test/java | xargs sed -i '' 's/com\.appian\.mqtt/de.hock.mqtt/g'`
      (the SDK imports read `com.appian.connectedsystems...`, so they never match). Also update the
      assertion in `ProjectStructureTest.basePackageIsPresent` — both the `startsWith("com.appian.mqtt")`
      argument and its message string — to `de.hock.mqtt`.
      Files: all 34 `.java` files under `src/main/java/de/hock/mqtt/**` and
      `src/test/java/de/hock/mqtt/ProjectStructureTest.java`.
      Verify: `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-17.jdk/Contents/Home mvn -q clean test`
      → BUILD SUCCESS, `Tests run: 3, Failures: 0, Errors: 0`.

- [ ] 4. Change the pom `<groupId>` from `com.appian.mqtt` to `de.hock`. Leave `<artifactId>`
      (`mqtt-broker-connected-system`) and `<version>` (`1.0.0`) alone — neither embeds the old
      package, so the shipped filename does not change.
      Files: `pom.xml`.
      Verify: `mvn -q help:evaluate -Dexpression=project.groupId -DforceStdout` prints `de.hock`.

- [ ] 5. Fix the remaining non-Java references to the old package: the explanatory comment in
      `src/main/resources/resources_en_US.properties` (`com.appian.mqtt.validation.ConfigFields` →
      `de.hock.mqtt.validation.ConfigFields`) and the single hit in
      `.kiro/specs/mqtt-broker-connected-system/tasks.md`. The manifest's 4 hits are handled in
      item 6; do not touch `.agents/**` (see DD-7).
      Files: `src/main/resources/resources_en_US.properties`,
      `.kiro/specs/mqtt-broker-connected-system/tasks.md`.
      Verify: `JAVA_HOME=.../amazon-corretto-17.jdk/Contents/Home mvn -q clean test` still green.

### Phase 2 — manifest (depends on the new FQNs from Phase 1)

- [ ] 6. Rewrite `src/main/resources/appian-plugin.xml` to the Appian 26.9 shape: `key="de.hock.mqtt"`
      on `<appian-plugin>`; `<plugin-info>` carrying a real `<description>`,
      `<vendor name="Hock" url="https://hock.de"/>` (replace the current `name="Appian"
      url="https://www.appian.com"` — claiming Appian as vendor is itself a rejection risk),
      `<version>1.0.0.0</version>`, and `<application-version min="26.9.0.0"/>` (replacing the stale
      `18.4.0.0` floor; the user targets 26.9 only). Update all three `class=` attributes to
      `de.hock.mqtt.template.MqttConnectedSystemTemplate`,
      `de.hock.mqtt.integration.MqttPublishIntegration`,
      `de.hock.mqtt.integration.MqttSubscribeIntegration`. Keep every `key=`/`name=` attribute on
      `<connected-system-template>` and both `<integration-template>` elements exactly as-is — they
      must stay equal to the `@TemplateId` names (DD-1). Also refresh the file's header comment, which
      currently names the old FQNs. There is no `<client-api>` element in this plug-in.
      Files: `src/main/resources/appian-plugin.xml`.
      Verify: `xmllint --noout src/main/resources/appian-plugin.xml` → no output; then
      `grep -c 'de\.hock\.mqtt' src/main/resources/appian-plugin.xml` → at least 4, and
      `grep -c 'com\.appian\.mqtt' ...` → 0. Full confirmation comes from item 16 (manifest at jar root).

- [ ] 7. Verify the resource bundle against the real class simple names. Required keys —
      `MqttConnectedSystemTemplate.name/.description`, `MqttPublishIntegration.name/.description`,
      `MqttSubscribeIntegration.name/.description` — are all present and correctly cased today, and
      the rename does not change them. Confirm, do not edit (item 5 already fixed the stale comment).
      If any key is missing or miscased, add/fix it; a mismatch deploys silently but never registers
      the template.
      Files: `src/main/resources/resources_en_US.properties` (expected: no change).
      Verify: `for k in MqttConnectedSystemTemplate MqttPublishIntegration MqttSubscribeIntegration;
      do grep -q "^$k.name=" src/main/resources/resources_en_US.properties && grep -q
      "^$k.description=" src/main/resources/resources_en_US.properties || echo "MISSING $k"; done`
      → prints nothing.

### Phase 3 — packaging: sources inside the shipped jar (the actual rejection)

- [ ] 8. Rename the assembly descriptor `src/assembly/appian-plugin.xml` →
      `src/assembly/plugin-assembly.xml` (it is an assembly descriptor, not a plug-in manifest; the
      identical filename collides with the real manifest and is a standing maintenance trap) and
      update the pom's `<descriptor>` to `src/assembly/plugin-assembly.xml`.
      Files: `src/assembly/appian-plugin.xml` → `src/assembly/plugin-assembly.xml`, `pom.xml`.
      Verify: `JAVA_HOME=.../amazon-corretto-17.jdk/Contents/Home mvn -q clean package` succeeds and
      the build log line reads `Reading assembly descriptor: src/assembly/plugin-assembly.xml`
      (drop `-q` or use `mvn clean package | grep 'assembly descriptor'` to see it).

- [ ] 9. In `src/assembly/plugin-assembly.xml`, add a second `<fileSet>` inside the existing
      `<fileSets>`, after the `${project.build.outputDirectory}` one, so `.java` sources ship
      root-mirrored next to their classes (DD-4, DD-5):
      ```xml
      <!-- Appian AppMarket policy: the plug-in must contain its own source code.
           src/main/java only - test sources are deliberately excluded. -->
      <fileSet>
          <directory>${project.basedir}/src/main/java</directory>
          <outputDirectory>/</outputDirectory>
          <includes>
              <include>**/*.java</include>
          </includes>
      </fileSet>
      ```
      Do **not** add `maven-source-plugin` and do **not** add a competing artifact.
      Files: `src/assembly/plugin-assembly.xml`.
      Verify: `mvn -q clean package` then
      `jar tf target/mqtt-broker-connected-system-1.0.0-plugin.jar | grep -c '\.java$'` → `33`, and
      `jar tf ... | grep '\.java$' | grep -vc '^de/hock/mqtt/'` → `0`.

- [ ] 10. In the same descriptor, change the `dependencySet` `<outputDirectory>` from `lib` to
      `META-INF/lib`. Appian's plug-in classloader resolves nested jars from `META-INF/lib/`; a jar at
      root `lib/` is off that search path, so Paho (`org.eclipse.paho.*`, referenced directly by
      `PahoMqttClientAdapter`, `ConnectOptionsFactory`, and both integrations) would fail to resolve at
      runtime. Keep `<useProjectArtifact>false</useProjectArtifact>` and `<scope>runtime</scope>` so
      `connected-systems-core` (scope `provided`) stays excluded. This project **bundles** Paho as a
      nested jar — it does not shade; keep it that way (no shade plugin is configured and shading would
      rewrite package names in a sources-bearing jar, making sources and classes disagree).
      Files: `src/assembly/plugin-assembly.xml`.
      Verify: `jar tf target/mqtt-broker-connected-system-1.0.0-plugin.jar | grep '^META-INF/lib/'` →
      exactly `META-INF/lib/org.eclipse.paho.client.mqttv3-1.2.5.jar`; and
      `jar tf ... | grep -c '^lib/'` → `0`.

- [ ] 10b. Make exactly one artifact land in `target/` (DD-6). In `pom.xml`, add a
      `maven-jar-plugin` block (pin version `3.4.1`) whose only execution overrides the implicit one:
      `<execution><id>default-jar</id><phase>none</phase></execution>`. Add
      `<addMavenDescriptor>false</addMavenDescriptor>` inside an `<archive>` element in the
      `maven-assembly-plugin` `<configuration>` so the shipped jar carries no `META-INF/maven/**`.
      **Fallback, only if `mvn clean package` then fails because the project artifact file is null:**
      revert the `phase none` override, keep `default-jar`, and instead give the plain jar
      `<excludes><exclude>appian-plugin.xml</exclude></excludes>` plus
      `<addMavenDescriptor>false</addMavenDescriptor>` in `maven-jar-plugin`, so the plain jar no
      longer looks like a deployable plug-in. Report which of the two you ended on and why.
      Files: `pom.xml`.
      Verify: `mvn -q clean package` succeeds and `ls target/*.jar` lists **exactly one** file,
      `target/mqtt-broker-connected-system-1.0.0-plugin.jar` (on the fallback path: two jars, but
      `jar tf target/mqtt-broker-connected-system-1.0.0.jar | grep -c appian-plugin.xml` → `0`).
      Also `jar tf target/mqtt-broker-connected-system-1.0.0-plugin.jar | grep -c '^META-INF/maven/'`
      → `0`.

### Phase 4 — policy sweep and documentation

- [ ] 11. Record the policy-sweep result; only one real code fix is needed. Confirmed clean by grep
      over `src/`: no `System.out`/`System.err`/`printStackTrace()`/`java.util.logging`; no
      `System.setProperty` or any JVM-global mutation (TLS is built as an in-memory `SSLContext` and
      handed to Paho via `options.setSocketFactory(...)` at `ConnectOptionsFactory.java:94` — exactly
      the compliant pattern); no `java.io.File`/`java.nio.file` and therefore **no temp keystore
      files and no filesystem access** anywhere, including `CertificateLoader`, which parses
      certificates and PKCS#8 keys purely from byte arrays; no hardcoded credentials (the only
      `password`/`keyPassphrase` literals are *field-key name* constants in `ConfigFields`); no
      SNAPSHOT versions. **There is no logging framework in the project at all** — nothing routes
      anywhere, so there is nothing to re-route to Log4j; do not invent a logging stack. Report all of
      this; make no change for this item.
      Files: none.
      Verify: `grep -rn "System\.out\|System\.err\|printStackTrace\|java\.util\.logging\|System\.setProperty\|java\.io\.File\|java\.nio\.file" src/main/java`
      → no matches (the only `javax.net.ssl.*` hits are legitimate imports).

- [ ] 12. Close the one unclosed `Closeable` and correct the false SDK Javadoc, both in
      `MqttConnectedSystemTemplate.java`. (a) In `resolveDocumentBytes(...)` the
      `java.io.InputStream in = doc.getInputStream();` (~line 517) is read but never closed — convert
      to try-with-resources, preserving the existing `null`-stream early return and the
      `IOException → return null` behavior. (b) The class Javadoc asserts "there is no
      `SimpleConnectedSystemTemplate` in this SDK version"; that is wrong — it lives in
      `com.appian:connected-systems-client`, an artifact this pom does not declare. Reword to state
      that accurately and reference DD-3 as the deliberate choice. Optionally wrap the two
      `new ByteArrayInputStream(...)` uses (`CertificateLoader.java:138`,
      `ConnectOptionsFactory.java:193`) in try-with-resources for strict policy cleanliness; these
      hold no OS resource.
      Files: `src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java` (and optionally
      `.../validation/CertificateLoader.java`, `.../protocol/ConnectOptionsFactory.java`).
      Verify: `JAVA_HOME=.../amazon-corretto-17.jdk/Contents/Home mvn -q clean test` → BUILD SUCCESS,
      3/3 tests pass.

- [ ] 13. Confirm the Java 17 toolchain settings. `pom.xml` already sets
      `<maven.compiler.release>17</maven.compiler.release>` and the compiler plugin consumes it via
      `<release>`; `release` supersedes `source`/`target`, so do **not** add redundant
      `maven.compiler.source`/`target` properties. No change expected — confirm and report.
      Files: `pom.xml` (expected: no change beyond item 4 and item 8).
      Verify: item 17's `javap` check (`major version: 61`).

- [ ] 14. Add `THIRD-PARTY-NOTICES.md` at the repo root naming the one bundled third-party library —
      `org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5`, dual-licensed **EPL-2.0 / EDL-1.0**,
      with EDL-1.0 (BSD-3-clause style, permissive) as the license relied upon for redistribution.
      **No copyleft library is bundled**; `connected-systems-core` is `provided` and never shipped.
      Ship the file at the jar root by adding a third `<fileSet>` to
      `src/assembly/plugin-assembly.xml`: `<directory>${project.basedir}</directory>`,
      `<outputDirectory>/</outputDirectory>`, `<includes><include>THIRD-PARTY-NOTICES.md</include></includes>`.
      Files: `THIRD-PARTY-NOTICES.md` (new), `src/assembly/plugin-assembly.xml`.
      Verify: `mvn -q clean package` then
      `jar tf target/mqtt-broker-connected-system-1.0.0-plugin.jar | grep '^THIRD-PARTY-NOTICES.md$'`
      → one match.

- [ ] 15. Targeted doc fixes only — do **not** rewrite `docs/mqtt-plugin-guide.md` or `info.md`
      wholesale. In the guide: the "Build and install" section (~lines 196–210) must say the nested
      Paho jar is bundled under `META-INF/lib/` (not `lib/`), and must state unambiguously that
      `target/mqtt-broker-connected-system-1.0.0-plugin.jar` is the artifact to deploy and submit —
      and drop the now-obsolete sentence warning against deploying the plain jar, since item 10b
      stops it being built (keep the warning only if you ended on 10b's fallback path). Fix any
      package name, class FQN, or plug-in-key reference the rename invalidated (the current guide has
      no `com.appian.mqtt` hits — re-grep to confirm after Phase 1). `info.md` is 0 bytes; give it a
      short AppMarket-facing summary (plug-in name, key `de.hock.mqtt`, vendor Hock, version 1.0.0.0,
      minimum Appian 26.9.0.0, the two integrations, bundled Paho + its license). Keep it brief.
      Files: `docs/mqtt-plugin-guide.md`, `info.md`.
      Verify: `grep -rn 'com\.appian\.mqtt\|bundles the Paho client under .lib/' docs/ info.md` → no matches.

---

## Mandatory verification checklist (run all of it, in order, and report actual output)

Build with Corretto 17 to match the documented toolchain (the default JDK here is 23; `release=17`
makes either work):

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-17.jdk/Contents/Home
cd /Users/andreas.hock/Documents/Appian-Projects/MQTT_Component
```

- [ ] 16. **Build.** `mvn -q clean package` must succeed. Allow generous time for dependency
      downloads (Maven Central answered HTTP 200 at planning time, but the audit saw transient
      `repo1.maven.org` DNS and `repo.eclipse.org` timeout warnings; `~/.m2` is warm, and `mvn -o
      clean package` is the offline fallback). **If the network is unavailable, say so explicitly in
      the report — do not fabricate build output.**
      Expected: `BUILD SUCCESS`, and `target/mqtt-broker-connected-system-1.0.0-plugin.jar` exists.

- [ ] 17. **Tests.** `mvn test` — report actual pass/fail counts. Expected: `Tests run: 3, Failures:
      0, Errors: 0, Skipped: 0` from `de.hock.mqtt.ProjectStructureTest`. Note for the report: jqwik
      (`net.jqwik:jqwik:1.8.4`) and Moquette are declared and surefire/failsafe are configured for
      `**/*Properties.java` and `**/*IT.java`, but **no such test files exist** — the suite is three
      smoke assertions, nothing more. If a test fails, fix rename-caused breakage; if a failure
      exposes a genuine pre-existing bug, **report it — never delete or disable the test.**

- [ ] 18. **Archive contents.** `jar tf target/mqtt-broker-connected-system-1.0.0-plugin.jar` —
      paste the listing (or a representative excerpt plus counts) and confirm every one of:
      (a) `appian-plugin.xml` at the **archive root**, no directory prefix — if it is not at the root
      Appian silently ignores the plug-in;
      (b) `resources_en_US.properties` present at the root;
      (c) `.java` sources present under `de/hock/mqtt/...` mirroring packages — expect **33**;
      (d) `.class` files present for the same classes — expect **48**;
      (e) **no** test sources and no test classes (`grep -c 'ProjectStructureTest'` → `0`);
      (f) **no** remaining `com/appian/mqtt` paths (→ `0`);
      (g) Paho present as the nested jar `META-INF/lib/org.eclipse.paho.client.mqttv3-1.2.5.jar`, and
      `^lib/` entries → `0`. **State explicitly that the project bundles (does not shade) Paho.**
      Also confirm `connected-systems-core` is absent (scope `provided`).

      ```bash
      J=target/mqtt-broker-connected-system-1.0.0-plugin.jar
      jar tf $J | grep -x 'appian-plugin.xml'
      jar tf $J | grep -x 'resources_en_US.properties'
      jar tf $J | grep -c '\.java$'                    # 33
      jar tf $J | grep -c '\.class$'                   # 48
      jar tf $J | grep -c 'ProjectStructureTest'       # 0
      jar tf $J | grep -c 'com/appian/mqtt'            # 0
      jar tf $J | grep '^META-INF/lib/'                # the Paho nested jar
      jar tf $J | grep -c '^lib/'                      # 0
      ```

- [ ] 19. **Bytecode level is Java 17.**
      ```bash
      cd /tmp && rm -rf jarchk && mkdir jarchk && cd jarchk \
        && unzip -o -q /Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/target/mqtt-broker-connected-system-1.0.0-plugin.jar 'de/hock/mqtt/template/MqttConnectedSystemTemplate.class' \
        && javap -verbose de/hock/mqtt/template/MqttConnectedSystemTemplate.class | grep 'major version'
      ```
      Expected: `major version: 61`. Clean up `/tmp/jarchk` afterwards.

- [ ] 20. **Final rename grep.** Prove no `com.appian.mqtt` / `com/appian/mqtt` string survives in
      repo content, while Appian's own SDK packages are untouched (DD-7 scope: excludes `target/` and
      `.agents/`).
      ```bash
      grep -rn 'com\.appian\.mqtt\|com/appian/mqtt' src pom.xml docs info.md .kiro THIRD-PARTY-NOTICES.md   # expect no matches
      grep -rn 'com\.appian\.connectedsystems\|com\.appiancorp' src/main/java | wc -l                        # expect >0, unchanged SDK imports
      find src -type d -name appian                                                                           # expect no output
      ```

- [ ] 21. **Submission artifact statement.** Run `ls -la target/*.jar` and paste the result. After
      item 10b there must be **exactly one**: state unambiguously in the final report that the file
      to upload to the AppMarket is
      **`/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/target/mqtt-broker-connected-system-1.0.0-plugin.jar`**
      (the `maven-assembly-plugin` output, assembly id `plugin`). If you ended on 10b's fallback path
      and two jars exist, say so explicitly and state that the plain
      `target/mqtt-broker-connected-system-1.0.0.jar` is the `default-jar` output, no longer carries
      `appian-plugin.xml`, and must not be submitted or deployed.

---

## Submission notes to carry into the final report (no code change)

1. **SDK version divergence (DD-2) — OPEN QUESTION FOR THE USER.** Shipping against
   `connected-systems-core:1.9.1` (unchanged). Appian's published compatibility table names Core
   1.2.0 / Client 1.1.0 for every release 24.4–26.9, yet Maven Central dates 1.2.0 at 2019-07-02,
   1.9.1 at 2023-11-07, and 1.10.0 at 2026-02-05. Present this contradiction to the user as something
   to confirm with Appian before submission; it is not resolvable from this repo.
2. **Template base classes (DD-3).** The three registered classes implement the low-level
   `ConnectedSystemTemplate` / `IntegrationTemplate` interfaces rather than extending the documented
   `Simple*` base classes, which live in the undeclared `connected-systems-client` artifact.
3. **Request policies are already correct.** `MqttPublishIntegration` is
   `@IntegrationTemplateType(IntegrationTemplateRequestPolicy.WRITE)` (it mutates external state) and
   `MqttSubscribeIntegration` is `READ` (it retrieves data). Verified at
   `MqttPublishIntegration.java:78` and `MqttSubscribeIntegration.java:69`. No change needed.
4. **`@TemplateId` migration consequence (DD-1).** Template ids are unchanged, so **existing deployed
   connected systems keep working**. The plug-in **`key` does change** (`com.appian.mqtt` →
   `de.hock.mqtt`), which Appian treats as a different plug-in: an existing installation must be
   removed and the new plug-in deployed, and connected-system objects bound to the old key will need
   to be re-pointed. Call this out prominently to the user.
5. **Test coverage is thin and the repo says so.** Three smoke assertions; publish/subscribe, TLS,
   LWT, reconnection, validation, and diagnostics have no automated coverage. Once sources ship inside
   the jar, a reviewer will read them. Not a policy violation, but worth disclosing.
6. **No connected-system logo assets** (`*_40px.png` / `_50px.png` / `_80px.png` under
   `src/main/resources/`). The connected system renders without an icon — a routine review comment,
   not a blocker. Out of scope here; needs artwork from the user.
7. **No git repository.** The submission will be source-reviewed with no commit history available.

## Known gaps / assumptions

- Vendor URL `https://hock.de` is an assumption — the repo states no vendor URL anywhere. If the user
  has a different one, it is a one-line manifest change.
- `META-INF/lib/` vs root `lib/` for Paho, and the `Simple*` base-class contract, are reasoned from
  Appian documentation and jar inspection. Neither can be proven without one deploy-and-test cycle on
  a real 26.9 instance.
- Appian does not specify whether reviewers expect root-mirrored or `src/`-prefixed source entries;
  both satisfy "source within the executable". DD-5 picks root-mirrored and the report should say so.
