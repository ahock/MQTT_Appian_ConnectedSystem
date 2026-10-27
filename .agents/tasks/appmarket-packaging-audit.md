# AppMarket Packaging Audit — MQTT Broker Connected System

**Scope:** read-only audit of `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component`.
**Date of audit run:** 2026-10-12. Build and tests were executed (they write only to `target/` and `~/.m2`); no project file was modified.

---

## 1. Summary answer

**The jar does not contain the Java sources today, and nothing in the build would ever put them there.** `mvn package` produces two archives; both contain only `.class` files, `appian-plugin.xml`, and `resources_en_US.properties`. There is no `maven-source-plugin`, no `src/main/java` resource entry, and no assembly `fileSet` over `src/main/java`. Appian's policy is explicit: [plug-ins must include the source code within the executable](https://docs.appian.com/suite/help/25.2/Shared_Components.html) — so the rejection is accurate and reproducible.

**Minimal correct fix:** add one `<fileSet>` over `src/main/java` to the existing assembly descriptor `src/assembly/appian-plugin.xml`. Exact XML in section 5. The assembly jar is already the designated distributable, the plugin is already wired, and this keeps `.java` files out of `target/classes` and off the compile/test classpath.

**However, source inclusion is not the only AppMarket blocker in this repo.** Three further findings are at least as serious, and two of them look like hard rejections:

| # | Finding | Severity |
|---|---|---|
| A | Plug-in Java packages / groupId / plugin key are all `com.appian.*`, and `<vendor name="Appian" url="https://www.appian.com"/>`. Appian policy: *plug-in packages must not be named `com.appiancorp.*` or `com.appian.*`*. Also misrepresents Appian as the vendor. | **Hard blocker** |
| B | Paho is bundled at `lib/org.eclipse.paho.client.mqttv3-1.2.5.jar`. Appian's plug-in framework loads nested jars from **`META-INF/lib/`**, not root `lib/`. The plug-in will very likely fail to load / `NoClassDefFoundError` at runtime. | **Likely deploy blocker** |
| C | Both templates implement the low-level `ConnectedSystemTemplate` / `IntegrationTemplate` interfaces. Appian's descriptor contract documents that these classes must subclass `SimpleConnectedSystemTemplate` / `SimpleIntegrationTemplate` — which exist in `com.appian:connected-systems-client`, an artifact this pom never declares. A source comment asserting those classes don't exist in this SDK version is factually wrong. | **Needs verification; possible deploy blocker** |

Plus: runtime behavior is untested (1 test class, 3 smoke assertions), no logo assets, no LICENSE/NOTICE for the bundled third-party jar, no git repository, and `info.md` is empty (0 bytes).

---

## 2. Current packaging — what the build produces today

### 2.1 `pom.xml` (verbatim coordinates)

File: `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/pom.xml`

- Coordinates: `com.appian.mqtt:mqtt-broker-connected-system:1.0.0`, `<packaging>jar</packaging>`
- `<finalName>${project.artifactId}-${project.version}</finalName>`
- `<maven.compiler.release>17</maven.compiler.release>`, `project.build.sourceEncoding=UTF-8`

Dependencies:

| groupId:artifactId | version | scope |
|---|---|---|
| `com.appian:connected-systems-core` | `1.9.1` (property `appian.sdk.version`) | `provided` |
| `org.eclipse.paho:org.eclipse.paho.client.mqttv3` | `1.2.5` (property `paho.version`) | compile (default) |
| `org.junit.jupiter:junit-jupiter` | `5.10.2` | `test` |
| `net.jqwik:jqwik` | `1.8.4` | `test` |
| `io.moquette:moquette-broker` | `0.17` | `test` |

Build plugins — all four, in pom order:

1. `org.apache.maven.plugins:maven-compiler-plugin:3.13.0` — `<release>17</release>`. No executions beyond defaults.
2. `org.apache.maven.plugins:maven-surefire-plugin:3.2.5` — includes `**/*Test.java`, `**/*Properties.java`; excludes `**/*IT.java`.
3. `org.apache.maven.plugins:maven-failsafe-plugin:3.2.5` — includes `**/*IT.java`; one execution binding goals `integration-test` and `verify`.
4. `org.apache.maven.plugins:maven-assembly-plugin:3.7.1` — `<descriptor>src/assembly/appian-plugin.xml</descriptor>`; execution id `build-appian-plugin`, phase `package`, goal `single`.

**There is no `maven-jar-plugin` configuration block** (the default `jar:3.5.1:jar` execution runs implicitly), **no `maven-shade-plugin`, no `maven-source-plugin`, and no `<build><resources>` override.**

### 2.2 Build run — it succeeds

```
$ cd /Users/andreas.hock/Documents/Appian-Projects/MQTT_Component && mvn -DskipTests package
[INFO] --- resources:3.5.0:resources (default-resources) @ mqtt-broker-connected-system ---
[INFO] Copying 2 resources from src/main/resources to target/classes
[INFO] --- compiler:3.13.0:compile (default-compile) @ mqtt-broker-connected-system ---
[INFO] Nothing to compile - all classes are up to date.
[INFO] --- jar:3.5.1:jar (default-jar) @ mqtt-broker-connected-system ---
[INFO] --- assembly:3.7.1:single (build-appian-plugin) @ mqtt-broker-connected-system ---
[INFO] Reading assembly descriptor: src/assembly/appian-plugin.xml
[INFO] Building jar: /Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/target/mqtt-broker-connected-system-1.0.0-plugin.jar
[INFO] BUILD SUCCESS
[INFO] Total time:  01:48 min
```

Note: only **2** resources are copied to `target/classes` (`appian-plugin.xml`, `resources_en_US.properties`). That number is the direct evidence that no `.java` file ever reaches the output directory.

Toolchain actually used: `Apache Maven 3.10.0`, `Java version: 23.0.1, vendor: Oracle Corporation` (`/Users/andreas.hock/OpenJDK/jdk-23.0.1.jdk`). `docs/mqtt-plugin-guide.md:199-201` instructs building with Corretto 17 via `JAVA_HOME`; the build also succeeds on JDK 23 because `<release>17</release>` is set. Two transient repository warnings appeared (`repo1.maven.org` DNS failure, `repo.eclipse.org` connect timeout) but did not fail the build.

### 2.3 Two artifacts are produced

```
target/mqtt-broker-connected-system-1.0.0.jar          78068 bytes   (default jar:jar)
target/mqtt-broker-connected-system-1.0.0-plugin.jar  293564 bytes   (assembly, id "plugin")
```

The `-plugin` classifier comes from the assembly `<id>plugin</id>`. `docs/mqtt-plugin-guide.md:204-206` designates the `-plugin` jar as the deployable one and explicitly says not to deploy the plain jar.

**Both** jars contain `appian-plugin.xml` at the archive root, so both look deployable to an operator. That ambiguity is worth removing.

### 2.4 Contents of the distributable (`-plugin.jar`), abridged

```
$ unzip -l target/mqtt-broker-connected-system-1.0.0-plugin.jar
       25  META-INF/MANIFEST.MF
     2285  appian-plugin.xml
     3527  resources_en_US.properties
     4268  com/appian/mqtt/MqttConnectionConfig.class
      ... 46 further .class entries across com/appian/mqtt{,/diagnostics,/integration,/protocol,/template,/validation} ...
   241861  lib/org.eclipse.paho.client.mqttv3-1.2.5.jar
---------
   409849   52 files
```

- `appian-plugin.xml` at root: **yes**, correct.
- `.java` entries: **zero**.
- Paho: bundled as a **nested jar under root `lib/`**, not shaded, not under `META-INF/lib/`.
- `connected-systems-core` is `provided`, correctly absent.
- `MANIFEST.MF` is 25 bytes — bare `Manifest-Version` only; no vendor/version attributes. Not required, but the plain jar's manifest (99 bytes) plus `META-INF/maven/...` metadata are richer, which is a mild oddity given the assembly jar is the shipped one.

### 2.5 Contents of the plain jar

Same 48 `.class` files, same two resources, plus `META-INF/maven/com.appian.mqtt/mqtt-broker-connected-system/{pom.xml,pom.properties}`, no `lib/`. 55 entries, no `.java`.

---

## 3. Does the jar contain sources today? **No.**

Evidence, three independent ways:

1. **Build config:** no `maven-source-plugin`, no `<resources>` block adding `src/main/java`, no assembly `fileSet` over `src/main/java`. The only `fileSet` in `src/assembly/appian-plugin.xml` is `${project.build.outputDirectory}` → `/`, i.e. `target/classes`, which holds compiled output and copied resources only.
2. **Build log:** `Copying 2 resources from src/main/resources to target/classes` — the only resource copy that happens.
3. **Archive listing:** `unzip -l` on both artifacts shows 0 entries ending in `.java` (section 2.4 / 2.5).

For scale: `src/main/java` holds **33 `.java` files / 4,548 lines / 264 KB**, so a correct fix should add 33 `.java` entries to the archive.

---

## 4. Is the assembly descriptor wired in? Yes — and the filename collision is real

**Wiring:** `maven-assembly-plugin:3.7.1`, execution `build-appian-plugin`, phase `package`, goal `single`, `<descriptor>src/assembly/appian-plugin.xml</descriptor>`. Confirmed live in the build log: `Reading assembly descriptor: src/assembly/appian-plugin.xml`.

**Descriptor contents** (`/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/assembly/appian-plugin.xml`):

- `<id>plugin</id>`, `<formats><format>jar</format></formats>`, `<includeBaseDirectory>false</includeBaseDirectory>`
- One `fileSet`: `<directory>${project.build.outputDirectory}</directory>` → `<outputDirectory>/</outputDirectory>`
- One `dependencySet`: `<outputDirectory>lib</outputDirectory>`, `<useProjectArtifact>false</useProjectArtifact>`, `<scope>runtime</scope>`
- **No reference to `src/main/java` anywhere.**

Resulting artifact: `${finalName}-${assembly.id}.jar` = `mqtt-broker-connected-system-1.0.0-plugin.jar`.

**Filename collision — flagged as requested.** Two files are both named `appian-plugin.xml` but have completely unrelated roles and schemas:

| Path | Role | Schema |
|---|---|---|
| `src/main/resources/appian-plugin.xml` | the real Appian plug-in manifest, lands at jar root | `<appian-plugin>` |
| `src/assembly/appian-plugin.xml` | Maven assembly descriptor, never shipped | `http://maven.apache.org/ASSEMBLY/2.1.0` |

This is a maintenance trap (grep hits, editor tab confusion, and a reviewer reading the wrong file). Recommend renaming the assembly descriptor to `src/assembly/plugin-assembly.xml` and updating the `<descriptor>` path. Cosmetic, zero functional risk, but it directly reduces the chance of a future mistake in exactly the file that needs to change.

---

## 5. Recommended fix — exact XML

### 5.1 Option comparison

| Option | Where sources land | Side effects | Verdict |
|---|---|---|---|
| **(a)** `maven-resources-plugin` `copy-resources` into `${project.build.outputDirectory}` | both jars | needs a new plugin + execution bound to `process-resources`; `.java` files pollute `target/classes`, so they are on the surefire/failsafe test classpath | More config than (b) for the same result. No. |
| **(b)** extra `<resource>` in `<build><resources>` for `src/main/java` | both jars | **gotcha:** declaring `<resources>` overrides the default, so `src/main/resources` must be re-declared or the descriptor and i18n bundle silently vanish from the jar; `.java` in `target/classes` and on the test classpath | Works, 8 lines, but has a foot-gun and wider blast radius. Second choice. |
| **(c)** assembly `<fileSet>` over `src/main/java` | **only the `-plugin.jar`** (the designated distributable) | none — `target/classes` stays clean, compile/test classpaths untouched, no new plugin, no new execution | **Recommended.** |
| **(d)** `maven-source-plugin` | a **separate** `mqtt-broker-connected-system-1.0.0-sources.jar` | does **not** place sources inside the component file | **Does not satisfy the requirement.** Appian's policy is sources *within the executable*; a sibling sources jar is a second file that the AppMarket submission does not carry. Use only as an extra, never as the fix. |

### 5.2 The change — add one `fileSet`

File to edit: `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/src/assembly/appian-plugin.xml`. Insert inside the existing `<fileSets>` element, after the `${project.build.outputDirectory}` fileSet:

```xml
        <!-- Appian AppMarket policy: "Plug-ins must include the source code within the
             executable." Ship the .java sources next to their compiled .class files so a
             reviewer can read the source from the submitted plug-in jar itself.
             Only src/main/java is included: test sources stay out of the distributable. -->
        <fileSet>
            <directory>${project.basedir}/src/main/java</directory>
            <outputDirectory>/</outputDirectory>
            <includes>
                <include>**/*.java</include>
            </includes>
        </fileSet>
```

That is the whole fix. No `pom.xml` change is required.

**Optional companion** (recommended — lets a reviewer rebuild the artifact, and documents the third-party license situation; see blocker D):

```xml
        <!-- Build files, so the submitted jar is self-contained for review/rebuild. -->
        <fileSet>
            <directory>${project.basedir}</directory>
            <outputDirectory>META-INF/source</outputDirectory>
            <includes>
                <include>pom.xml</include>
                <include>src/assembly/*.xml</include>
            </includes>
        </fileSet>
```

### 5.3 Placement decision: archive root, mirroring the package structure

With `<outputDirectory>/</outputDirectory>`, `MqttPublishIntegration.java` lands at `com/appian/mqtt/integration/MqttPublishIntegration.java`, directly beside `MqttPublishIntegration.class`.

Why root-mirrored rather than a dedicated `src/` prefix:

- A human reviewer opening the jar sees each source immediately next to its class — no hunting, no mapping step. That is the single purpose of the policy.
- Zero runtime risk: the JVM classloader never resolves `.java` entries, and `javac` never sees the jar's interior. Adding sources cannot change behavior.
- `appian-plugin.xml` must stay at the root (per [Appian plug-in docs](https://docs.appian.com/suite/help/25.4/Appian_Plug-ins.html)); the source tree starts at `com/`, so there is no collision.

If a flat jar listing is preferred for tidiness, `<outputDirectory>src/main/java</outputDirectory>` is an equally policy-compliant alternative — it just costs the reviewer one extra navigation step. Either is acceptable; pick one and state it in the submission notes.

### 5.4 Tradeoffs explicitly asked about

- **`package-info.java`:** `src/main/java` contains four (`com/appian/mqtt/`, `diagnostics/`, `protocol/`, `validation/`). These are genuine sources that already compile to `package-info.class` entries present in the jar, so including them is correct, not pollution.
- **Test sources:** `src/test/java/com/appian/mqtt/ProjectStructureTest.java` is in a different root and is **not** matched by option (c). Good — shipping tests is not required and would raise questions about the untested-code story in section 6.E.
- **Generated sources:** `target/generated-sources/` exists but holds no project code (annotation-processor scaffolding only); option (c) ignores it by construction.
- **Why not (b):** besides the `<resources>` override foot-gun, putting `.java` in `target/classes` means surefire and failsafe run with 33 `.java` files on the classpath and the plain jar also grows. Harmless in practice, but a wider change than the problem needs.

### 5.5 How to verify the fix

```bash
cd /Users/andreas.hock/Documents/Appian-Projects/MQTT_Component
mvn -o clean package
unzip -l target/mqtt-broker-connected-system-1.0.0-plugin.jar | grep -c '\.java$'      # expect 33
unzip -l target/mqtt-broker-connected-system-1.0.0-plugin.jar | grep 'appian-plugin.xml'  # expect root entry
mvn -o test                                                                            # expect 3/3 green
```

Expected outcome: `BUILD SUCCESS`, 33 `.java` entries, `appian-plugin.xml` still at the archive root, `resources_en_US.properties` still at the root, tests unchanged at 3 passing.

---

## 6. Other AppMarket blockers observed

Ordered by severity.

### A. `com.appian.*` namespace and "Appian" as vendor — hard blocker

[Appian's plug-in policies](https://docs.appian.com/suite/help/25.2/Shared_Components.html) state that plug-in packages must not be named `com.appiancorp.*` or `com.appian.*`. This repo uses that namespace everywhere:

- `pom.xml`: `<groupId>com.appian.mqtt</groupId>`
- every Java package: `com.appian.mqtt`, `com.appian.mqtt.diagnostics`, `.integration`, `.protocol`, `.template`, `.validation` (33 files)
- `src/main/resources/appian-plugin.xml`: `key="com.appian.mqtt"`
- descriptor classes: `com.appian.mqtt.template.MqttConnectedSystemTemplate`, `com.appian.mqtt.integration.{MqttPublishIntegration,MqttSubscribeIntegration}`

Compounding it, the manifest declares `<vendor name="Appian" url="https://www.appian.com"/>` — the submission would claim Appian itself as the vendor. Fix requires a package rename to a namespace the submitter controls (e.g. `com.<yourorg>.mqtt`), matching groupId, plugin key, descriptor `class` attributes, and the `resources_en_US.properties` keys if the simple class names change (they need not). This is a mechanical but repo-wide rename, and it must happen before submission regardless of the source-code issue.

### B. Paho bundled at `lib/` instead of `META-INF/lib/` — likely deploy blocker

Current: `lib/org.eclipse.paho.client.mqttv3-1.2.5.jar` (from `<outputDirectory>lib</outputDirectory>` in the assembly `dependencySet`).

Appian's documentation consistently places nested plug-in libraries in `META-INF/lib/` — the [plug-in docs](https://docs.appian.com/suite/help/25.4/Appian_Plug-ins.html) instruct including log4j "in your plug-in's META-INF/lib/ directory", and Appian Community guidance for custom plug-ins says to add required JARs to `src/META-INF/lib/`. A jar at root `lib/` is not on the plug-in classloader's search path, so `org.eclipse.paho.*` would fail to resolve at runtime — the classes are referenced directly by `PahoMqttClientAdapter`, `ConnectOptionsFactory`, and both integrations.

Suggested change in the same assembly descriptor (one line):

```xml
        <dependencySet>
            <outputDirectory>META-INF/lib</outputDirectory>
            <useProjectArtifact>false</useProjectArtifact>
            <scope>runtime</scope>
        </dependencySet>
```

I could not execute this against an Appian instance, so treat it as high-confidence-from-docs rather than empirically proven. It is cheap to change and matches every Appian example I found.

### C. Templates implement the low-level interfaces, not the documented `Simple*` base classes

Declared in `src/main/resources/appian-plugin.xml` and verified present on disk — all three classes exist at the expected paths and the fully-qualified names match exactly, no mismatch:

| Descriptor element | `class` attribute | File | Annotations / supertype |
|---|---|---|---|
| `connected-system-template key="MqttConnectedSystemTemplate"` | `com.appian.mqtt.template.MqttConnectedSystemTemplate` | `src/main/java/com/appian/mqtt/template/MqttConnectedSystemTemplate.java` | `@TemplateId(name="MqttConnectedSystemTemplate")`; `implements ConnectedSystemTemplate` |
| nested `integration-template key="MqttPublishIntegration"` | `com.appian.mqtt.integration.MqttPublishIntegration` | `src/main/java/com/appian/mqtt/integration/MqttPublishIntegration.java` | `@TemplateId(name="MqttPublishIntegration")`, `@IntegrationTemplateType(IntegrationTemplateRequestPolicy.WRITE)`; `implements com.appian.connectedsystems.templateframework.sdk.IntegrationTemplate` |
| nested `integration-template key="MqttSubscribeIntegration"` | `com.appian.mqtt.integration.MqttSubscribeIntegration` | `src/main/java/com/appian/mqtt/integration/MqttSubscribeIntegration.java` | `@TemplateId(name="MqttSubscribeIntegration")`, `@IntegrationTemplateType(IntegrationTemplateRequestPolicy.READ)`; `implements IntegrationTemplate` |

The descriptor's shape (nested `integration-template` inside `connected-system-template`, `plugin-info` with description/vendor/version/`application-version min="18.4.0.0"`) matches [Appian's documented manifest example](https://docs.appian.com/suite/help/25.3/csp-configuration-files.html) exactly. Good.

What does not match: that same page specifies the `connected-system-template` `class` "Must be a subclass of `SimpleConnectedSystemTemplate`" and `integration-template` `class` "Must be a subclass of `SimpleIntegrationTemplate`".

The Javadoc in `MqttConnectedSystemTemplate.java` claims "there is no `SimpleConnectedSystemTemplate` in this SDK version". **That claim is wrong.** I verified by listing archive contents:

- `com.appian:connected-systems-core:1.9.1` (the only SDK dependency, present at `~/.m2/repository/com/appian/connected-systems-core/1.9.1/`) contains 99 classes across `com/appian/connectedsystems/templateframework/sdk/**` — and no `simplified` package. Same for `1.2.0` and `1.10.0`.
- `com.appian:connected-systems-client:1.1.0` **does** contain `com/appian/connectedsystems/simplified/sdk/SimpleConnectedSystemTemplate.class`, `SimpleIntegrationTemplate.class`, `SimpleTestableConnectedSystemTemplate.class`, `SimpleClientApi.class`, and `configuration/SimpleConfiguration.class`.

So the `Simple*` hierarchy lives in an artifact the pom never declares. The current code compiles and is self-consistent against the low-level interfaces, and those interfaces are public SDK API — so it may well deploy fine. But it diverges from the documented contract, it loses `SimpleConfiguration`'s ergonomics, and the in-code justification for that divergence is false. Worth a connectivity test on a real instance before submitting, and the comment should be corrected either way.

Related: **SDK version choice is ambiguous.** Appian's [SDK Version Compatibility table](https://docs.appian.com/suite/help/26.9/integration-sdk-versions.html) lists Core **1.2.0** / Client **1.1.0** for every Appian release from 24.4 through 26.9. Maven Central's `com.appian:connected-systems-core` publishes `0.1.0, 1.0.0, 1.0.1, 1.0.2, 1.1.0, 1.1.1, 1.2.0, 1.8.2, 1.9.0, 1.9.1, 1.10.0` with `<latest>1.10.0</latest>`; by `Last-Modified`, `1.2.0` is from 2019-07-02 while `1.9.1` is 2023-11-07 and `1.10.0` is 2026-02-05. So the pinned `1.9.1` is a recent artifact, not a stale one — but it is *not* the version the compatibility table names for any supported Appian release, and the [troubleshooting guide](https://docs.appian.com/suite/help/25.1/troubleshoot-common-issues.html) specifically says to match Core/Client versions to that table for the target environment. Decide deliberately which generation to target and record it; do not leave this implicit.

### D. No license/notice material for the bundled third-party jar

The distributable bundles Paho `1.2.5`, whose own jar carries only `about.html` (no `LICENSE`/`NOTICE`). The plug-in jar itself has no `LICENSE`, `NOTICE`, or attribution file anywhere — `find` over the repo (excluding `target/`) returns no `LICENSE*`, `NOTICE*`, or `*.png`. Appian requires full rights to distribute everything contained in the plug-in and [prohibits copyleft libraries whose terms extend to linked software](https://docs.appian.com/suite/help/25.2/Shared_Components.html). Eclipse Paho is dual-licensed (EPL / EDL); the permissive EDL option is the one to rely on and to state explicitly. Add a `LICENSE`/`THIRD-PARTY-NOTICES` file to the jar and name the chosen Paho license in the submission.

### E. Runtime behavior is unverified, and the repo says so

`mvn -o test` result:

```
[INFO] Running com.appian.mqtt.ProjectStructureTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.026 s
[INFO] BUILD SUCCESS
```

That is the entire suite: `src/test/java/com/appian/mqtt/ProjectStructureTest.java` is the only test file, and its three tests assert that the base package resolves and that `appian-plugin.xml` and `resources_en_US.properties` are on the classpath. Nothing exercises publish, subscribe, TLS, LWT, reconnection, validation, or diagnostics.

`docs/mqtt-plugin-guide.md:214-220` states this plainly: property-based, unit, and embedded-broker integration tests "were deferred" and broker behavior "has **not** been exercised by an automated test."

**On jqwik:** `net.jqwik:jqwik:1.8.4` is declared and surefire includes `**/*Properties.java`, but **no `*Properties.java` file exists** anywhere under `src/test`. The `.jqwik-database` file at the repo root (4 bytes, gitignored, dated Oct 7) is a leftover from a jqwik run that no longer has any tests behind it. Same story for `io.moquette:moquette-broker:0.17` and maven-failsafe: configured for `**/*IT.java`, and there are no `*IT.java` files. Three test dependencies and two plugin configurations are currently dead weight — harmless to the build, but misleading about coverage.

Not strictly an AppMarket policy violation, but "plug-ins must only do what they advertise to do" is a review criterion, and a reviewer who reads the shipped sources (which they will, once sources are included) will see the untested paths.

### F. Smaller items

- **Two root-level `appian-plugin.xml` carriers.** Both `…-1.0.0.jar` and `…-1.0.0-plugin.jar` contain the manifest at root, so either could be deployed by mistake. Consider `<addMavenDescriptor>false</addMavenDescriptor>` plus either skipping `default-jar` or classifying it, so exactly one artifact looks deployable.
- **`<application-version min="18.4.0.0"/>`** is the documented floor, not a real compatibility claim. Appian removes plug-ins lacking clearly documented version compatibility, and a plug-in built against Core 1.9.1 and Java 17 cannot actually run on 18.4. Raise it to the minimum release you have tested.
- **No connected system logos.** [Appian's project structure page](https://docs.appian.com/suite/help/26.9/project-structure.html) shows `src/main/resources/com/<org>/<Template>_40px.png`, `_50px.png`, `_80px.png`. None exist in this repo, so the connected system renders without an icon — a routine review comment.
- **`info.md` is 0 bytes** and the repo is **not a git repository** (`git -C … log` → `fatal: not a git repository`). No version history, no commit trail for a submission that will be source-reviewed.
- **No `java-version` element** in `plugin-info`. Optional, but stating it documents the Java 17 requirement.
- Plugin key `com.appian.mqtt`, name "MQTT Broker Connected System", and version `1.0.0.0` are otherwise well-formed, not placeholders. Descriptor `description` and `vendor` are present (vendor value notwithstanding — see blocker A).
- **`resources_en_US.properties` is complete and consistent.** Every registered template has both `.name` and `.description` (`MqttConnectedSystemTemplate`, `MqttPublishIntegration`, `MqttSubscribeIntegration`), matching the [documented `[MyTemplateClass].name` / `.description` pattern](https://docs.appian.com/suite/help/25.3/csp-configuration-files.html). Field labels follow `[Template].[fieldKey].label`. No stale or missing keys found for the declared modules.

---

## 7. What could not be verified, and why

- **That Appian accepts the fix.** Policy compliance was read from [Appian's published policies](https://docs.appian.com/suite/help/25.2/Shared_Components.html); the exact packaging the reviewer expects (root-mirrored vs. a `src/` prefix) is not specified in the docs. Both satisfy "source code within the executable"; no authoritative statement distinguishes them.
- **Runtime deployment.** No Appian instance is available from here, so the `lib/` vs `META-INF/lib/` classloading failure (blocker B) and the `Simple*` base-class contract (blocker C) are reasoned from documentation and jar inspection, not observed. Both need one deploy-and-test cycle on a real environment.
- **MQTT functional behavior.** Not exercised — no broker, and no tests exist to exercise it (section 6.E).
- **Which SDK generation Appian wants for the target release.** The compatibility table (Core 1.2.0) and Maven Central (`latest` = 1.10.0, with 1.9.1 pinned here) disagree. Resolving this needs either Appian's confirmation or the target environment's own version, neither of which is observable from this repo.
- **The post-fix archive listing.** This audit is read-only; the `unzip -l | grep -c '\.java$'` → 33 expectation in section 5.5 is a prediction from the assembly semantics, not a recorded run.
- **Transient network flakiness.** Two Maven repository warnings occurred during `mvn package` (`repo1.maven.org` DNS resolution failure, `repo.eclipse.org` connect timeout). The build succeeded from the local `~/.m2` cache, so a clean-machine build has not been proven from here.

---

*Sources: Appian documentation pages linked inline — [AppMarket submission policies](https://docs.appian.com/suite/help/25.2/Shared_Components.html), [Appian Suite Plug-ins](https://docs.appian.com/suite/help/25.4/Appian_Plug-ins.html), [CSP Configuration Files](https://docs.appian.com/suite/help/25.3/csp-configuration-files.html), [CSP Project Structure](https://docs.appian.com/suite/help/26.9/project-structure.html), [SDK Version Compatibility](https://docs.appian.com/suite/help/26.9/integration-sdk-versions.html), [Troubleshoot Common Issues](https://docs.appian.com/suite/help/25.1/troubleshoot-common-issues.html). Content was rephrased for compliance with licensing restrictions.*
