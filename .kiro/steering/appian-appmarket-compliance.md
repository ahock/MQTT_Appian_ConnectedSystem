# Appian AppMarket Compliance — MQTT Broker Connected System

Project-specific rules for this plug-in, derived from two real AppMarket rejections and a verified
audit (2026-10-12). Target platform: **Appian 26.9**. Everything below was confirmed against Appian
documentation or a real build — not inferred.

> **Scope note (2026-10-27, 1.0.4).** The project was **deliberately reduced to a minimal
> smoke-test build** at `1.0.4`: one connected system with five fields (`brokerHost`, `brokerPort`,
> `clientIdentifier`, `username`, `password`) and one publish integration with three inputs
> (`topic`, `payload`, `qos`). TLS and certificates, the subscribe integration, Last Will and
> Testament, reconnection and session tuning, diagnostics and design-time validation were all
> removed. The complete `1.0.3` source is archived at `.backup/mqtt-plugin-FULL-1.0.3-src.zip`, so
> the richer history in `.agents/tasks/` describes a **superset** of what now ships — do not read a
> reference to a removed field as a description of the current build. The hard constraints in §1
> and §2 all still apply; the project-specific figures throughout were updated to the reduced
> reality. The reduction record is `.agents/tasks/minimal-smoke-build.md`.

---

## 1. Hard constraints — breaking any of these gets the submission rejected

### Namespace: never `com.appian.*`

Appian policy forbids plug-in packages named `com.appiancorp.*` or `com.appian.*`
([submission policies](https://docs.appian.com/suite/help/26.9/Shared_Components.html)). This project
was originally `com.appian.mqtt` and was rejected for it.

- Our namespace is **`de.hock.mqtt`**. Maven groupId is **`de.hock`**.
- Plug-in manifest `key` is **`de.hock.mqtt`**.

**Critical trap:** the Appian SDK's own packages are `com.appiancorp.*` **and
`com.appian.connectedsystems.*`**. A find-and-replace of the string `com.appian` will silently
corrupt every SDK import in `src/main/java`. Only ever rename the exact token `com.appian.mqtt`.
Verify after any rename:

```bash
# Only this file's own prose should match; no hit may be in src/, pom.xml or the built jar.
grep -rn 'com\.appian\.mqtt' . | grep -v '/target/' | grep -v '/.agents/' | grep -v '/.kiro/'
grep -rh 'import com.appian.connectedsystems' src/main/java/ | wc -l  # ~22, across 2 files
grep -rl 'com\.appian\.connectedsystems' src/main/java | wc -l        # 2 (both templates)
```

The import count was 47, then 45 with the `Simple*` migration (2026-10-13), and dropped to **22
across 2 files** when the plug-in was reduced to the minimal smoke-test build at 1.0.4.

### Sources must ship *inside* the plug-in JAR

Appian policy: plug-ins must include the source code within the executable. This was the first
rejection. A separate `-sources.jar` from `maven-source-plugin` does **not** satisfy it — the sources
must be entries in the same archive as the `.class` files.

Implemented as a `<fileSet>` over `src/main/java` in the assembly descriptor, landing sources at the
archive root mirroring the package structure (`de/hock/mqtt/Foo.java` beside `Foo.class`).

At 1.0.4 that is **4 sources**: `de/hock/mqtt/package-info.java`, `de/hock/mqtt/MqttPublisher.java`,
`de/hock/mqtt/template/MqttConnectedSystemTemplate.java` and
`de/hock/mqtt/integration/MqttPublishIntegration.java`. It was 34 before the reduction.

Do **not** solve this with a `<build><resources>` block in `pom.xml`. Declaring `<resources>`
replaces the default resource root, so `src/main/resources` must then be re-declared or
`appian-plugin.xml` and `resources_en_US.properties` silently vanish from the JAR. It also puts
`.java` files into `target/classes` and onto the test classpath. The assembly `fileSet` avoids all of
that.

Never include `src/test/java` in the distributable.

### Manifest at the archive root

`appian-plugin.xml` must be a root entry with no directory prefix, or Appian ignores the plug-in
entirely. Verify the listing shows a bare `appian-plugin.xml`.

### Nested dependencies go in `META-INF/lib/`, not `lib/`

Appian's plug-in classloader resolves bundled JARs from `META-INF/lib/`. This project originally
shipped Paho at root `lib/`, which would very likely have failed at runtime with
`NoClassDefFoundError`. At 1.0.4 exactly one class references Paho —
`de.hock.mqtt.MqttPublisher` — so a classloading failure names it and nothing else. Set the
assembly `dependencySet` `<outputDirectory>` to `META-INF/lib`.

*Status: fixed, but never validated against a live Appian instance. Confirm on deploy.*

### Version number formats — two different fields, two different formats

This caused the second rejection.

| Field | Format | Our value |
|---|---|---|
| `<plugin-info><version>` | **three-part `x.y.z`** | `1.0.4` |
| `<application-version min=...>` | four-part Appian platform version | `26.9.0.0` |

Appian's [configuration files page](https://docs.appian.com/suite/help/26.9/csp-configuration-files.html)
shows `1.0.0.0` in its reference example, and the AppMarket validator **rejects** that with
"The version number format is invalid. Valid version number format is x.y.z". The
[versioning page](https://docs.appian.com/suite/help/26.9/versioning.html) is the correct authority —
it uses three-part semver throughout (`1.1.3 → 1.2.0`, `1.1.3 → 1.1.4`). Trust the versioning page;
do not copy the config-files example.

Bump `<version>` on every release, or the new build will not deploy over the old one.

### Vendor must be the real author

Never `name="Appian"` or `url="*.appian.com"`. The original manifest claimed Appian as its own vendor,
which is misattribution and a review risk. Current value:
`<vendor name="Hock" url="https://itondemand.com"/>`.

### Connected system property descriptors must never be expressionable

This caused the third deploy-time failure (1.0.2 → 1.0.3). Appian rejects a connected system
template that declares **any** expressionable property descriptor, at descriptor-conversion time, so
the configuration form never renders:

```
Property Descriptors in Connected System Template must not be Expressionable (APNX-1-4198-000)
```

thrown from `ConnectedSystemPropertiesValidator.checkPropertiesModifiers` via
`ConfigurationDescriptorToValueConverter.apply` ←
`GetConnectedSystemConfigurationDescriptorFunction.eval`. Authority: the 26.9
[restrictions and limitations page](https://docs.appian.com/suite/help/26.9/restrictions-and-limitations.html).

**Integration input descriptors are not subject to this** — they may be expressionable, and a
`ListTypePropertyDescriptor` input **must** be. Never "clean up" expressionability repo-wide.

**The deeper lesson, which is the reusable part: some SDK descriptor builders force the flag on
themselves, which makes those descriptor types unusable on a connected system template entirely.**
Deleting `.isExpressionable(true)` from the source is therefore *necessary but not sufficient*, and
a source grep cannot prove compliance. Measured against `connected-systems-core:1.9.1`:

| Descriptor type | Builder sets the flag | Can it be cleared? | Usable on a connected system? |
|---|---|---|---|
| `DocumentPropertyDescriptor` | yes, in the constructor | **no** — `Guard.checkPropertyIsExpressionable` in `checkValidState` throws | **no** |
| `AppianValuePropertyDescriptor` | yes, in the constructor | **no** — same guard | **no** |
| `FolderPropertyDescriptor` | yes, in the constructor | yes, no guard | only if explicitly cleared |
| `TimestampPropertyDescriptor` | yes, in the constructor | yes, no guard | only if explicitly cleared |
| `ListTypePropertyDescriptor` | yes, plus `displayHint(EXPRESSION)` | coupled — its own guard rejects `EXPRESSION` hint with the flag cleared | effectively no |
| Text, Paragraph, Boolean, Integer, Double, EncryptedText, LocalType | no — base builder seeds `false` | n/a | yes |

`DocumentPropertyDescriptor.builder()...isExpressionable(false).build()` throws
`IllegalStateException: DOCUMENT property descriptors must be expressionable`. That is why the
`caCertificate` and `clientCertificate` upload fields became `ParagraphPropertyDescriptor` fields
holding pasted PEM in 1.0.3. There is **no document-upload field available on a connected system
template** with this SDK. `ParagraphPropertyDescriptor` is the only multi-line text input, since
`DisplayHint` offers only `NORMAL` and `EXPRESSION`.

**The table and the lesson above are the reusable finding and stay.** The two certificate fields it
describes no longer exist at 1.0.4, and `ParagraphPropertyDescriptor` is no longer used by this
plug-in at all — the five surviving connected system fields are Text, Integer and EncryptedText
only. The table matters again the moment anything is restored from the archive.

Verify both halves — and note the source grep is the weaker check of the two:

```bash
# Necessary: no explicit call in the template. Repo-wide total 3, all on the publish integration.
grep -c isExpressionable src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java  # 0
grep -rn isExpressionable src/main/java/ | wc -l                                               # 3
grep -rc isExpressionable src/main/java/de/hock/mqtt/integration/   # MqttPublishIntegration:3
```

The template's Javadoc deliberately avoids the literal token `isExpressionable` so the first grep
stays a clean zero rather than matching prose.

Sufficient: `MqttConnectedSystemTemplateDescriptorTest.noConnectedSystemPropertyDescriptorIsExpressionable`
renders the descriptor and reads every modifier back through `PropertyDescriptor.isExpressionable()`,
which is what the platform validator actually walks. Keep that test; a grep alone would have shipped
the two document fields.

---

## 2. Build facts

- Maven. `maven-assembly-plugin:3.7.1`, execution `build-appian-plugin`, phase `package`, goal
  `single`, descriptor `src/assembly/plugin-assembly.xml`.
- **Submission artifact:** `target/mqtt-broker-connected-system-1.0.4-plugin.jar` — the `-plugin`
  suffix comes from the assembly `<id>`. A JAR without it is a stale build.
- **Exactly one JAR must land in `target/`.** Previously two were produced and *both* carried
  `appian-plugin.xml` at the root, so either looked deployable. Keep it to one.
- Java **17** (`<maven.compiler.release>17</maven.compiler.release>`, bytecode major version 61).
  Appian 26.9 requires Java 17.
- SDK: `com.appian:connected-systems-core:1.9.1`, scope `provided` (never bundle it).
- SDK: `com.appian:connected-systems-client:1.3.0`, scope `provided` (never bundle it) — the
  `Simple*` facade both templates subclass. Added 2026-10-13; see §5 item 2.
- `org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5`, bundled.
- Tests: `org.junit.jupiter:junit-jupiter:5.10.2`, scope `test` — **and nothing else**.
  `net.jqwik:jqwik` and `io.moquette:moquette-broker` were removed at 1.0.4, together with the
  `maven-failsafe-plugin` block and the Surefire `**/*Properties.java` / `**/*IT.java` wiring. No
  file ever matched those patterns, so the configuration was advertising coverage that did not
  exist — in sources AppMarket reviewers read. Surefire's default `**/*Test.java` include covers
  both remaining test classes, so it needs no `<configuration>` block.
- `THIRD-PARTY-NOTICES.md` ships at the archive root, naming Paho's permissive EDL-1.0 option of its
  dual EPL-2.0 / EDL-1.0 license. Keep it current when dependencies change.

### Do not "fix" the SDK version to 1.2.0

Appian's [compatibility table](https://docs.appian.com/suite/help/26.9/integration-sdk-versions.html)
lists Core 1.2.0 / Client 1.1.0 for every release from 24.4 through 26.9. On Maven Central, **1.2.0
dates from 2019-07-02**, while 1.9.1 is 2023-11-07 and 1.10.0 is 2026-02-05. Downgrading to 1.2.0
moves onto a seven-year-old artifact and will likely break compilation. **Stay on 1.9.1** until
Appian or the target environment confirms otherwise. This contradiction is unresolved — see §5.

---

## 3. Code policies Appian enforces

From the [submission policies](https://docs.appian.com/suite/help/26.9/Shared_Components.html).
Current sweep is clean; keep it that way.

- Logging through **Log4j only**. No `System.out`, `System.err`, `printStackTrace()`,
  `java.util.logging`. This plug-in satisfies the rule by logging **nothing at all** — it declares
  no logging dependency and makes no print call, and failure detail goes into the integration
  response instead. That is a deliberate choice, not an oversight: adding log4j even at `provided`
  scope would add another unverified server-classpath assumption to a build whose purpose is to
  eliminate them. If logging is wanted later, `org.apache.log4j.Logger` at `provided` scope is the
  only sanctioned route.
- **No `System.setProperty()`** or any JVM/server global mutation. Relevant here: MQTT-over-TLS is
  commonly implemented by setting `javax.net.ssl.*` system properties — never do that. Build an
  in-memory `SSLContext` and hand the socket factory to Paho via
  `MqttConnectOptions.setSocketFactory()`. (Moot at 1.0.4: there is no TLS. The rule applies again
  the moment TLS is restored from the archive.)
- `Closeable` resources closed in a guaranteed fashion (try-with-resources / `finally`); temp files
  deleted. At 1.0.4 this is `MqttPublisher`, which disconnects and closes its client in a
  `finally` block.
- No direct filesystem manipulation. Use `ContentService` / Document APIs for Appian documents.
  At 1.0.4 `MqttPublisher` passes `MemoryPersistence` to Paho, because Paho's default persistence
  writes to disk.
- No hardcoded credentials, default passwords, or key pairs anywhere, including tests and docs. Use
  the Secure Credentials Store.
- No copyleft third-party libraries.
- Do not use the Administrator `ServiceContext`; use the context from the initial constructor.

---

## 4. Pre-submission verification

Run all of this and read the output — a green build alone proves nothing.

```bash
cd /Users/andreas.hock/Documents/Appian-Projects/MQTT_Component
mvn -o clean package                             # BUILD SUCCESS
mvn -o test                                      # Tests run: 7, Failures: 0, Errors: 0
J=target/mqtt-broker-connected-system-1.0.4-plugin.jar

ls target/*.jar | wc -l                          # 1 — exactly ONE jar
unzip -l $J | grep -c '\.java$'                  # 4 sources
unzip -l $J | grep '\.java$' | grep -vc 'de/hock/mqtt/'   # 0 — none outside the package
unzip -l $J | grep -cE ' appian-plugin\.xml$'    # 1
unzip -l $J | awk '{print $NF}' | grep -c '^appian-plugin.xml$'   # 1 — bare root, no prefix
unzip -l $J | grep -c 'META-INF/lib/.*\.jar$'    # 1 — Paho is the only nested jar
unzip -l $J | grep -c 'META-INF/lib/org.eclipse.paho'     # 1
unzip -l $J | grep -ci 'connected-systems'       # 0, both Appian artifacts are provided
unzip -l $J | grep -c 'com/appian/mqtt'          # 0
unzip -l $J | grep -ci test                      # 0
unzip -l $J | grep -ci 'backup'                  # 0 — nothing from .backup/
unzip -l $J | awk '{print $NF}' | grep -c '^THIRD-PARTY-NOTICES.md$'   # 1
unzip -p $J appian-plugin.xml | grep -E '<version>|application-version|<vendor'
#   <version>1.0.4</version> / min="26.9.0.0" / vendor name="Hock"
javap -verbose -cp target/classes de.hock.mqtt.template.MqttConnectedSystemTemplate \
  | grep 'major version'                         # 61
javap -verbose -cp target/classes de.hock.mqtt.MqttPublisher | grep 'major version'   # 61

# Expressionability — the grep is necessary, the test is what actually proves it (see §1).
grep -c isExpressionable src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java  # 0
grep -rn isExpressionable src/main/java/ | wc -l                                               # 3
grep -rc isExpressionable src/main/java/ | grep -v ':0$'
#   expect exactly one line: .../integration/MqttPublishIntegration.java:3

# Resource bundle: no orphan keys, no missing keys (12 keys, 8 of them field labels).
B=src/main/resources/resources_en_US.properties
sed -nE 's/^([A-Za-z][A-Za-z.]*)=.*/\1/p' $B | sort > /tmp/bundle-actual.txt
{ echo MqttConnectedSystemTemplate.name; echo MqttConnectedSystemTemplate.description
  sed -nE 's/.*public static final String [A-Z_]+ *= *"([A-Za-z]+)".*/MqttConnectedSystemTemplate.\1.label/p' \
    src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java
  echo MqttPublishIntegration.name; echo MqttPublishIntegration.description
  sed -nE 's/.*public static final String INPUT_[A-Z_]+ *= *"([A-Za-z]+)".*/MqttPublishIntegration.\1.label/p' \
    src/main/java/de/hock/mqtt/integration/MqttPublishIntegration.java
} | sort > /tmp/bundle-expected.txt
diff /tmp/bundle-expected.txt /tmp/bundle-actual.txt   # no output; '>' = orphan, '<' = missing
#   The two sed patterns hold only while the five config keys are the ONLY
#   `public static final String` constants on the template and the three input keys the only
#   `public static final String INPUT_*` constants on the integration. Keep result-map keys
#   ("success", "status", "topic", "qos") as inline literals.

# The full 1.0.3 source archive must stay untouched and out of the jar.
shasum -a 256 .backup/mqtt-plugin-FULL-1.0.3-src.zip
#   386f3cab7ab660e19325f2825071152338eb48855e6f45b8d9665afcc22f89b4   (136397 bytes, 74 entries)
```

Then deploy to a real 26.9 instance before claiming compatibility: copy the JAR to
`APPIAN_INSTALL/_admin/plugins/` and watch for `Successfully installed Plug-in` plus the
`ConnectedSystemTemplateModuleParser` registration lines in `tomcat-stdOut.log`.

---

## 5. Known divergences and open questions

Carry these forward. Item 2 is resolved; the rest are not.

1. **SDK version conflict** (§2). Appian's table says Core 1.2.0; we ship against 1.9.1. Needs
   confirmation from Appian or the target environment. Nothing below resolved this.
2. **RESOLVED 2026-10-13: templates now subclass the `Simple*` hierarchy.** Implementing the
   low-level `ConnectedSystemTemplate` / `IntegrationTemplate` interfaces was the confirmed cause of
   a design-time crash: `New > Connected System` failed with *"Cannot invoke
   PropertyState.setType(...) because the return value of
   ConfigurationDescriptor.getRootState() is null"*. On the low-level path the implementer owns root
   state construction, and `getRootState()` is only `getState().get("root")` — `null` for a new
   connected system. `PropertyState`'s constructor is package-private, so the sole public route is
   `StateGenerator`, which is exactly what `SimpleConfiguration.setProperties` runs
   (`generateFromExistingState` plus `withTypes`, so the local type is registered too). Both
   templates extend `SimpleConnectedSystemTemplate` / `SimpleIntegrationTemplate` from
   `com.appian:connected-systems-client:1.3.0`, scope `provided`; `connected-systems-core` stays at
   **1.9.1** — no downgrade. Client 1.3.0 is core 1.9.1's own generation (published one day apart)
   and declares no dependencies, so it cannot drag in a conflicting core; the `Simple*` public API is
   identical across client 1.1.0/1.3.0/1.4.0, so swapping `${appian.sdk.client.version}` is a
   one-line change if a live instance demands it. Open residual risk: `provided` means the Appian
   server must supply the client artifact — unverified on a live instance. A
   `NoClassDefFoundError` naming `com/appian/connectedsystems/simplified/sdk/...` means it does not;
   the fallback is to keep the `Simple*` shape's logic but construct the descriptor by hand with
   `StateGenerator` + `withType` + `withState` on the low-level interfaces. Do **not** fall back by
   bundling the client jar. A code comment once claimed `SimpleConnectedSystemTemplate` doesn't exist
   in this SDK — false; don't reintroduce it. The `Simple*` resolution is still load-bearing and
   must not be undone. **Superseded at 1.0.4:** the first-render gate and the Clean Session seed
   that a 2026-10-13 note added here were both **removed**, together with `cleanSession` as a field.
   The round-trip assumption they rested on therefore no longer governs any behavior, and the
   "clear the Clean Session checkbox, save, reopen" deploy check no longer applies. It becomes
   relevant again only if design-time validation is restored from the archive.
3. **No connected system logos.** Appian expects
   `src/main/resources/de/hock/mqtt/MqttConnectedSystemTemplate_{40,50,80}px.png`. Without them the
   connected system renders iconless. Separate from the AppMarket listing icon (§6).
4. **Test coverage is design-time only — 7 tests in two classes** (2 in `ProjectStructureTest`, 5 in
   `MqttConnectedSystemTemplateDescriptorTest`), down from 19 at 1.0.3.
   `ProjectStructureTest` checks that `appian-plugin.xml` and `resources_en_US.properties` are on
   the classpath. `MqttConnectedSystemTemplateDescriptorTest` drives both templates'
   `getConfigurationDescriptor` with the empty descriptor Appian supplies for a new object
   (`ConfigurationDescriptor.builder().build()`, whose `getRootState()` is `null`) and asserts:
   - `brandNewConnectedSystemRendersANonNullRootStateWithAResolvableRootType` — the NPE regression
     guard, and the single most valuable test here.
   - `exactlyTheFiveExpectedConfigFieldKeysArePresent` — the five config keys as an **ordered list**,
     so a sixth field fails too. Verified by temporarily adding one.
   - `noConnectedSystemPropertyDescriptorIsExpressionable` — reads every modifier back through
     `PropertyDescriptor.isExpressionable()` and names each offender. It is what caught the two
     forced-expressionable document fields a source grep could not see, and it fails if
     `.isExpressionable(true)` is reintroduced; verified by temporarily re-adding it to `brokerHost`,
     which failed naming `brokerHost (TextPropertyDescriptor)`.
   - `connectedSystemFieldsUseNoListDocumentOrAppianValueDescriptor` — `RefreshPolicy.NEVER`, not
     hidden, and no `List`/`Document`/`AppianValue` descriptor type.
   - `publishIntegrationRendersItsRootStateAndItsThreeExpressionableInputs` — the three input keys
     in display order, and all three expressionable, which pins the deliberate asymmetry with the
     connected system.

   Config keys covered: 5. Publish input keys covered: 3. **Nothing exercises a real publish**, and
   no test needs a broker. The dead `net.jqwik` / `io.moquette` dependencies and the
   `**/*Properties.java` / `**/*IT.java` Surefire-Failsafe wiring that this item used to complain
   about were **removed** at 1.0.4 — see §2.
5. **Not a git repository.** No history for a submission Appian source-reviews and reserves the right
   to retain.
6. **`isImportCustomizable` on the encrypted field.** The restrictions page says an
   `EncryptedTextPropertyDescriptor` *should* set `isImportCustomizable(true)`; at 1.0.4 `password`
   is the only such field and it leaves the flag at the builder default `false`
   (`clientPrivateKey` and `keyPassphrase` no longer exist). It is a "should", it played no part in
   any of the three deploy failures, and enabling it changes cross-environment deployment
   behavior — so it is carried forward, not fixed.
7. **Whether design-time validation works at all on a connected system is unresolved — and MOOT at
   1.0.4.** The restrictions page says `setErrors()` is ignored on a connected system, while the
   template used `PropertyState.setErrorAtPath`, which decompiles to a delegation to
   `setErrorsAtPath` — a different method — so the documentation cannot settle it. At 1.0.4 the
   template calls neither: design-time validation was removed outright, `isRequired` is the only
   design-time guard, and the publish integration re-checks host, port, topic and QoS at run time.
   The question therefore blocks nothing today. Keep the finding for whoever restores validation
   from the archive; resolve it then, by observation — on a saved object, clear a required field and
   look for the message.
8. **A reported field error is never cleared — RESOLVED BY REMOVAL at 1.0.4.** The old `validate`
   only attached errors and never called the SDK's `clearErrorsAtPath`, so an error set on one
   render survived the next even after the value was corrected. There is no validation and no
   attached error at 1.0.4, so there is nothing to clear. The defect is still real in the archived
   full build: fix it there before restoring validation.
9. **The platform validators cannot be inspected offline.**
   `ConnectedSystemPropertiesValidator` and its siblings are `com.appiancorp.*` — platform-side, not
   SDK-side. `connected-systems-core-1.9.1.jar` contains 0 `com/appiancorp/` entries and 0
   validation classes; `connected-systems-client-1.3.0.jar` holds only its nine
   `com/appian/connectedsystems/simplified/sdk/**` classes. SDK-side guards (`Guard`,
   `checkValidState`, `StateGenerator`) are the limit of what can be checked here, which is how the
   forced-expressionable types in §1 were found. Everything the *platform* enforces beyond that is
   discoverable only by deploying.

---

## 6. Submission process

- **Submissions site:** https://forum.appian.com/suite/sites/appmarket-submissions/page/home
  (an Appian-built site on `forum.appian.com`; needs an Appian Community login). Handles public
  listings, private org-only submissions, and new versions of existing listings.
- Listing type: **Plug-in (Connected System)**.
- Beyond the JAR the form wants: a mandatory square app icon (180×180 px, `.jpg`/`.png`, sRGB,
  <1024 KB, full-bleed, no transparency), 4–8 screenshots at 16:9 and ≥1280×720 showing the Appian
  top navigation bar with realistic sample data, a description, and compatible Appian version(s).
- "Compatible Version(s)" means versions you have **actually tested**. Deploy before claiming 26.9.
- Appian **highly encourages pre-approval** before a full submission. Cheaper than another rejection
  round trip.
- Typical review: ~10 business days absent revisions. Contact: `appmarket@appian.com`.

---

## 7. Reference pages (Appian 26.9)

- [AppMarket submission policies](https://docs.appian.com/suite/help/26.9/Shared_Components.html)
- [Configuration files / manifest](https://docs.appian.com/suite/help/26.9/csp-configuration-files.html)
  — note the stale `1.0.0.0` version example
- [Versioning](https://docs.appian.com/suite/help/26.9/versioning.html) — authority on `x.y.z`
- [SDK version compatibility](https://docs.appian.com/suite/help/26.9/integration-sdk-versions.html)
- [Project structure](https://docs.appian.com/suite/help/25.4/project-structure.html)
- [Developer setup (Java 17)](https://docs.appian.com/suite/help/26.9/developer-setup.html)
- [Quick start / template contract](https://docs.appian.com/suite/help/26.9/develop-first-connected-system.html)
- [Appian Suite plug-ins](https://docs.appian.com/suite/help/26.9/Appian_Plug-ins.html)

Appian documentation content was rephrased for compliance with licensing restrictions.

---

## 8. Session audit trail

Full evidence lives in `.agents/tasks/`.

> **Read this first.** The project was **deliberately reduced to a minimal smoke-test build at
> 1.0.4** (2026-10-27). Everything below records how the platform and packaging constraints were
> discovered on the *full* plug-in, so it describes a **superset** of what now ships: certificate
> fields, LWT, reconnection, subscribe, diagnostics and design-time validation all appear in these
> records and none of them exist in the current build. The complete `1.0.3` source is archived at
> `.backup/mqtt-plugin-FULL-1.0.3-src.zip` (136397 bytes, 74 entries, sha256
> `386f3cab7ab660e19325f2825071152338eb48855e6f45b8d9665afcc22f89b4`). The hard constraints in §1
> and §2 are what carried forward and still bind.

- `minimal-smoke-build-plan.md` — the plan behind the 1.0.4 reduction: the delete/keep lists, the
  resolved judgement calls, and the verification block now mirrored in §4.
- `minimal-smoke-build.md` — the reduction record: every deleted and kept file with a reason, the
  five config fields and three publish inputs as implemented, the invariant evidence with real
  command output, the deploy-and-test checklist, and how to restore the full build. **Start here
  for what currently ships.**
- `minimal-smoke-build-review.json` / `minimal-smoke-build-review.md` — independent review of the
  reduction.

Earlier records, all pre-reduction:

- `appmarket-packaging-audit.md` — read-only audit proving zero `.java` entries in the original JARs,
  with the option comparison behind the assembly-`fileSet` decision.
- `appmarket-compliance-plan.md` — the 21-item remediation plan.
- `appmarket-compliance-review.json` / `2026-10-12-132655-review.md` — independent review verdict.
- `task-appmarket-compliance/` — per-feature task records.
- `connected-system-descriptor-fix.md` — the consolidated hand-off report for the descriptor NPE fix
  and the first-render validation change: root cause, chosen fix, SDK coordinates, invariant
  evidence, the artifact to deploy, and the deploy checklist. **Start here.**
- `connected-system-descriptor-fix-plan.md` — the 10-step plan behind the `Simple*` migration,
  including the offline verification gate (§4) and the deploy checks (§5).
- `connected-system-descriptor-fix-verification.md` — the reviewer-facing verification note with real
  command output, the SDK bytecode trace, and the open deploy-time questions (§7).
- `descriptor-fix-review.md` / `descriptor-fix-review.json` — independent review of that fix.
- `first-render-polish-plan.md` — the plan for the first-render validation suppression, the three
  record corrections, and the `1.0.1` → `1.0.2` bump.
- `expressionable-fix-plan.md` — the plan behind the third fix (expressionability) and the
  `1.0.2` → `1.0.3` bump. Its findings 3 and 4 are superseded in one respect: the SDK-side
  `Guard`/builder bytecode *was* readable and revealed the forced-expressionable descriptor types,
  which the plan did not anticipate. See §11 of `connected-system-descriptor-fix.md`.
- `readme-review.md` / `readme-review.json` — independent review of the README.
