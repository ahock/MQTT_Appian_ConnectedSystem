# Appian AppMarket Compliance — MQTT Broker Connected System

Project-specific rules for this plug-in, derived from two real AppMarket rejections and a verified
audit (2026-10-12). Target platform: **Appian 26.9**. Everything below was confirmed against Appian
documentation or a real build — not inferred.

> **Scope note (2026-10-27, 1.0.5).** The project was **deliberately reduced to a minimal
> smoke-test build** at `1.0.4`: one connected system with five fields (`brokerHost`, `brokerPort`,
> `clientIdentifier`, `username`, `password`) and one publish integration with three inputs
> (`topic`, `payload`, `qos`). That build was deployed and confirmed working on a live Appian 26.9
> instance.
>
> **`1.0.5` adds exactly two things to it.** (1) Four session fields —
> `connectionTimeoutSeconds`, `keepAliveSeconds`, `cleanSession`, `autoReconnectEnabled` — bringing
> the connected system to **nine** fields, each one genuinely reaching `MqttConnectOptions`.
> (2) Design-time validation, which is therefore **present again, but ungated**: it runs on every
> render including the first, deliberately, as a probe for whether Appian displays connected system
> field errors at all (§5 item 7). TLS and certificates, the subscribe integration, Last Will and
> Testament, reconnect attempt tuning and diagnostics remain removed.
>
> The complete `1.0.3` source is in git commit `d023a76` and archived at
> `.backup/mqtt-plugin-FULL-1.0.3-src.zip`, so the richer history in `.agents/tasks/` describes a
> **superset** of what now ships — do not read a reference to a removed field as a description of
> the current build. The hard constraints in §1 and §2 all still apply; the project-specific figures
> throughout were updated to the 1.0.5 reality. Records: `.agents/tasks/minimal-smoke-build.md` for
> the reduction, `.agents/tasks/validation-and-config-fields-1.0.5.md` for 1.0.5.

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
grep -rh 'import com.appian.connectedsystems' src/main/java/ | wc -l  # 25, across 3 files
grep -rl 'com\.appian\.connectedsystems' src/main/java | wc -l        # 3 (both templates + validator)
```

The import count was 47, then 45 with the `Simple*` migration (2026-10-13), dropped to 22 across 2
files when the plug-in was reduced to the minimal smoke-test build at 1.0.4, and is **25 across 3
files** at 1.0.5 — `de.hock.mqtt.validation.ConfigValidator` is the third file.

### Sources must ship *inside* the plug-in JAR

Appian policy: plug-ins must include the source code within the executable. This was the first
rejection. A separate `-sources.jar` from `maven-source-plugin` does **not** satisfy it — the sources
must be entries in the same archive as the `.class` files.

Implemented as a `<fileSet>` over `src/main/java` in the assembly descriptor, landing sources at the
archive root mirroring the package structure (`de/hock/mqtt/Foo.java` beside `Foo.class`).

At 1.0.5 that is **5 sources**: `de/hock/mqtt/package-info.java`, `de/hock/mqtt/MqttPublisher.java`,
`de/hock/mqtt/template/MqttConnectedSystemTemplate.java`,
`de/hock/mqtt/integration/MqttPublishIntegration.java` and the new
`de/hock/mqtt/validation/ConfigValidator.java`. It was 4 at 1.0.4 and 34 before the reduction.
There is deliberately no `validation/package-info.java` — `template/` and `integration/` have none
either, so adding one would break the project's own convention and raise the count for no benefit.

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
`NoClassDefFoundError`. At 1.0.5 exactly one class still references Paho —
`de.hock.mqtt.MqttPublisher`, which owns both `publish` and `buildConnectOptions` — so a
classloading failure names it and nothing else. Keeping the Paho surface in that one class is why
options construction was extracted there rather than into the integration. Set the assembly
`dependencySet` `<outputDirectory>` to `META-INF/lib`.

*Status: fixed and confirmed — the 1.0.4 deploy resolved Paho from `META-INF/lib/` on a live 26.9
instance.*

### Version number formats — two different fields, two different formats

This caused the second rejection.

| Field | Format | Our value |
|---|---|---|
| `<plugin-info><version>` | **three-part `x.y.z`** | `1.0.5` |
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
| Text, Paragraph, **Boolean**, Integer, Double, EncryptedText, LocalType | no — base builder seeds `false` | n/a | yes |

`BooleanPropertyDescriptor` was measured non-expressionable when the two Boolean fields arrived at
1.0.5: the rendered descriptor reads back `false` for both, like Text, Integer and EncryptedText.

`DocumentPropertyDescriptor.builder()...isExpressionable(false).build()` throws
`IllegalStateException: DOCUMENT property descriptors must be expressionable`. That is why the
`caCertificate` and `clientCertificate` upload fields became `ParagraphPropertyDescriptor` fields
holding pasted PEM in 1.0.3. There is **no document-upload field available on a connected system
template** with this SDK. `ParagraphPropertyDescriptor` is the only multi-line text input, since
`DisplayHint` offers only `NORMAL` and `EXPRESSION`.

**The table and the lesson above are the reusable finding and stay.** The two certificate fields it
describes no longer exist, and `ParagraphPropertyDescriptor` is no longer used by this plug-in at
all — the nine connected system fields at 1.0.5 are Text, Integer, Boolean and EncryptedText only.
The table matters again the moment anything is restored from the archive.

Verify both halves — and note the source grep is the weaker check of the two:

```bash
# Necessary: no explicit call in the template. Repo-wide total 3, all on the publish integration.
grep -c isExpressionable src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java  # 0
grep -rn isExpressionable src/main/java/ | wc -l                                               # 3
grep -rc isExpressionable src/main/java/de/hock/mqtt/integration/   # MqttPublishIntegration:3
```

The template's Javadoc deliberately avoids the literal token `isExpressionable` so the first grep
stays a clean zero rather than matching prose. **`ConfigValidator`'s Javadoc must avoid it too**, or
the repo-wide count stops being 3.

Sufficient: `MqttConnectedSystemTemplateDescriptorTest.noConnectedSystemPropertyDescriptorIsExpressionable`
renders the descriptor and reads every modifier back through `PropertyDescriptor.isExpressionable()`,
which is what the platform validator actually walks. Keep that test; a grep alone would have shipped
the two document fields.

---

## 2. Build facts

- Maven. `maven-assembly-plugin:3.7.1`, execution `build-appian-plugin`, phase `package`, goal
  `single`, descriptor `src/assembly/plugin-assembly.xml`.
- **Submission artifact:** `target/mqtt-broker-connected-system-1.0.5-plugin.jar` — the `-plugin`
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
  exist — in sources AppMarket reviewers read. Surefire's default `**/*Test.java` include covers all
  four test classes at 1.0.5, so it needs no `<configuration>` block.
- `THIRD-PARTY-NOTICES.md` ships at the archive root, naming Paho's permissive EDL-1.0 option of its
  dual EPL-2.0 / EDL-1.0 license. Keep it current when dependencies change.

### Do not "fix" the SDK version to 1.2.0

Appian's [compatibility table](https://docs.appian.com/suite/help/26.9/integration-sdk-versions.html)
lists Core 1.2.0 / Client 1.1.0 for every release from 24.4 through 26.9. On Maven Central, **1.2.0
dates from 2019-07-02**, while 1.9.1 is 2023-11-07 and 1.10.0 is 2026-02-05. Downgrading to 1.2.0
moves onto a seven-year-old artifact and will likely break compilation. **Stay on 1.9.1.** A live
Appian 26.9 instance ran the 1.0.4 build on core 1.9.1 + client 1.3.0 successfully on 2026-10-27, so
the pairing is proven against the real target and the table's 1.2.0 figure should not be chased. The
documentation discrepancy itself is still unexplained — see §5 item 1.

---

## 3. Code policies Appian enforces

From the [submission policies](https://docs.appian.com/suite/help/26.9/Shared_Components.html).
Current sweep is clean; keep it that way.

- Logging through **Log4j only**. No `System.out`, `System.err`, `printStackTrace()`,
  `java.util.logging`. This plug-in satisfies the rule by logging **nothing at all** — it declares
  no logging dependency and makes no print call, and failure detail goes into the integration
  response instead. That is a deliberate choice, not an oversight: at the time it was made, adding
  log4j even at `provided` scope would have added another untested server-classpath assumption to a
  build whose purpose was to eliminate them. The 1.0.4 deploy has since shown the server does supply
  `provided` Appian SDK artifacts, so `org.apache.log4j.Logger` at `provided` scope is the sanctioned
  route whenever logging is wanted — it is simply not needed yet.
- **No `System.setProperty()`** or any JVM/server global mutation. Relevant here: MQTT-over-TLS is
  commonly implemented by setting `javax.net.ssl.*` system properties — never do that. Build an
  in-memory `SSLContext` and hand the socket factory to Paho via
  `MqttConnectOptions.setSocketFactory()`. (Moot at 1.0.5: there is no TLS. The rule applies again
  the moment TLS is restored from the archive. The only match for a `System.setProperty` grep is a
  Javadoc sentence in `MqttPublisher` asserting the absence of such a call — prose, not a call.)
- `Closeable` resources closed in a guaranteed fashion (try-with-resources / `finally`); temp files
  deleted. At 1.0.5 this is still `MqttPublisher`, which disconnects and closes its client in a
  `finally` block regardless of outcome — including when `autoReconnectEnabled` is set, so nothing
  outlives the request.
- No direct filesystem manipulation. Use `ContentService` / Document APIs for Appian documents.
  At 1.0.5 `MqttPublisher` passes `MemoryPersistence` to Paho, because Paho's default persistence
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
mvn -o test                                      # Tests run: 13, Failures: 0, Errors: 0
J=target/mqtt-broker-connected-system-1.0.5-plugin.jar

ls target/*.jar | wc -l                          # 1 — exactly ONE jar
unzip -l $J | grep -c '\.java$'                  # 5 sources
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
#   <version>1.0.5</version> / min="26.9.0.0" / vendor name="Hock"
unzip -p $J appian-plugin.xml | grep -c 'key="de.hock.mqtt"'   # 1 — plug-in key unchanged
unzip -p $J appian-plugin.xml | grep -cE 'key="Mqtt(ConnectedSystemTemplate|PublishIntegration)"'
#   2 — both module keys unchanged, so the build REPLACES the installed one
javap -verbose -cp target/classes de.hock.mqtt.template.MqttConnectedSystemTemplate \
  | grep 'major version'                         # 61
javap -verbose -cp target/classes de.hock.mqtt.MqttPublisher | grep 'major version'   # 61
javap -verbose -cp target/classes de.hock.mqtt.validation.ConfigValidator | grep 'major version' # 61
javap -verbose -cp target/classes de.hock.mqtt.integration.MqttPublishIntegration \
  | grep 'major version'                         # 61

# Expressionability — the grep is necessary, the test is what actually proves it (see §1).
grep -c isExpressionable src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java  # 0
grep -rn isExpressionable src/main/java/ | wc -l                                               # 3
grep -rc isExpressionable src/main/java/ | grep -v ':0$'
#   expect exactly one line: .../integration/MqttPublishIntegration.java:3

# Namespace trap and the SDK import census (§1).
grep -rn 'com\.appian\.mqtt' . | grep -v '/target/' | grep -v '/.agents/' | grep -v '/.kiro/' \
  | grep -v '/.git/'                             # no hits
grep -rh 'import com.appian.connectedsystems' src/main/java/ | wc -l   # 25
grep -rl 'com\.appian\.connectedsystems' src/main/java | wc -l         # 3

# Code policies (§3). The only hit is a Javadoc sentence in MqttPublisher asserting the absence
# of a System.setProperty call — prose, not a call. Any other hit is a real violation.
grep -rn 'System\.out\|System\.err\|printStackTrace\|System\.setProperty\|java\.util\.logging' \
  src/main/java/

# Resource bundle: no orphan keys, no missing keys (16 keys, 12 of them field labels).
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
#   The two sed patterns hold only while the NINE config keys are the ONLY
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

**Baseline confirmed working 2026-10-27.** Version **1.0.4** was deployed to a live Appian 26.9
instance and tested successfully: the plug-in installs, the Connected System configuration page
renders its five fields (after three successive design-time failures), and the bundled Eclipse Paho
client loads and publishes from inside the Appian plug-in classloader. Treat 1.0.4 / commit
`7c9e12b` as the known-good reference point — when a later change breaks design-time rendering,
diff against it rather than re-deriving the constraints in §1.

What that deploy proved, each of which had been an unverified assumption:
- `connected-systems-client` at scope `provided` **is** supplied by the Appian server (item 2).
- core 1.9.1 + client 1.3.0 **do** work on Appian 26.9, despite the compatibility table (item 1).
- Paho resolves from `META-INF/lib/` — the original root `lib/` placement was indeed wrong (§1).
- Shipping `.java` sources inside the jar does not interfere with deployment (§1).
- No **fourth** descriptor guard lurks beyond the three already fixed.

**1.0.5 is built on that baseline but NOT yet deployed.** It adds four session fields (nine total)
and ungated design-time validation. Item 8 is resolved by implementation; item 7 is now an open
question with a deliberate experiment attached, waiting on one observation from the user's deploy.

Items 1, 2, 5 and 8 are resolved; 3, 4, 6 and 7 are not — 7 is *being probed* by 1.0.5.

1. **SDK version conflict** (§2) — **settled in practice 2026-10-27.** Appian's compatibility table
   names Core 1.2.0 for every release from 24.4 through 26.9, while we ship against **1.9.1**. The
   1.0.4 build deployed and ran correctly on a live Appian 26.9 instance, so core 1.9.1 paired with
   client 1.3.0 is demonstrably compatible with 26.9 and the documented 1.2.0 figure (a 2019
   artifact) should not be chased. The documentation discrepancy itself remains unexplained and is
   worth a question to Appian before an AppMarket submission, but it blocks nothing.
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
   one-line change if a live instance demands it. **Residual risk CLOSED 2026-10-27: a real Appian
   26.9 instance does supply `connected-systems-client` on the plug-in classpath.** 1.0.4 deployed
   and ran with the client at scope `provided` and no `NoClassDefFoundError`, so `provided` is the
   correct scope and the artifact must never be bundled. Had it been missing, the symptom would have
   been a `NoClassDefFoundError` naming `com/appian/connectedsystems/simplified/sdk/...`, and the
   fallback would have been to keep the `Simple*` shape's logic while constructing the descriptor by
   hand with `StateGenerator` + `withType` + `withState` on the low-level interfaces — never by
   bundling the client jar. A code comment once claimed `SimpleConnectedSystemTemplate` doesn't exist
   in this SDK — false; don't reintroduce it. The `Simple*` resolution is still load-bearing and
   must not be undone. **Superseded at 1.0.4, and again at 1.0.5:** the first-render gate and the
   Clean Session seed that a 2026-10-13 note added here were **removed** at 1.0.4 together with
   `cleanSession` as a field. At 1.0.5 `cleanSession` is a field again, but the gate and the
   `setValue` seed deliberately are **not**, and the round-trip assumption they rested on is no
   longer needed at all: `StateGenerator` seeds the defaults itself — measured as `BOOLEAN` →
   `Boolean.FALSE`, `INTEGER` → `null`, `STRING`/`ENCRYPTED`/`PARAGRAPH` → `""`. One consequence
   worth stating in docs rather than hiding: because a Boolean seeds to `false`, a brand-new
   connected system renders **Clean Session unchecked**, so the `null` → `true` rule in
   `MqttPublisher` fires only for a configuration that carries no value at all (for instance one
   saved by 1.0.4, before the field existed). The "clear the Clean Session checkbox, save, reopen"
   deploy check still does not apply.
3. **No connected system logos.** Appian expects
   `src/main/resources/de/hock/mqtt/MqttConnectedSystemTemplate_{40,50,80}px.png`. Without them the
   connected system renders iconless. Separate from the AppMarket listing icon (§6).
4. **Test coverage is offline only — 13 tests in four classes** (7 at 1.0.4, 19 at 1.0.3):
   2 in `ProjectStructureTest`, 5 in `MqttConnectedSystemTemplateDescriptorTest`, 4 in
   `MqttConnectedSystemTemplateValidationTest` and 2 in `MqttPublisherConnectOptionsTest`.
   `ProjectStructureTest` checks that `appian-plugin.xml` and `resources_en_US.properties` are on
   the classpath. The three others drive the real framework entry points with the empty descriptor
   Appian supplies for a new object (`ConfigurationDescriptor.builder().build()`, whose
   `getRootState()` is `null`) or call `MqttPublisher.buildConnectOptions` directly:
   - `brandNewConnectedSystemRendersANonNullRootStateWithAResolvableRootType` — the NPE regression
     guard, and the single most valuable test here.
   - `exactlyTheNineExpectedConfigFieldKeysArePresent` — the nine config keys as an **ordered list**,
     so a tenth field fails too. Verified at 1.0.4 by temporarily adding one.
   - `noConnectedSystemPropertyDescriptorIsExpressionable` — reads every modifier back through
     `PropertyDescriptor.isExpressionable()` and names each offender, now across all nine fields. It
     is what caught the two forced-expressionable document fields a source grep could not see, and it
     fails if `.isExpressionable(true)` is reintroduced; re-verified at 1.0.5 by temporarily adding
     it to `cleanSession`, which failed naming `cleanSession (BooleanPropertyDescriptor)`.
   - `connectedSystemFieldsUseNoListDocumentOrAppianValueDescriptor` — `RefreshPolicy.NEVER`, not
     hidden, and no `List`/`Document`/`AppianValue` descriptor type.
   - `publishIntegrationRendersItsRootStateAndItsThreeExpressionableInputs` — the three input keys
     in display order, and all three expressionable, which pins the deliberate asymmetry with the
     connected system.
   - `brandNewConnectedSystemReportsRequiredErrorsOnBrokerHostAndBrokerPortOnly` — pins the
     **deliberately ungated** probe (item 7): a brand-new object carries errors on the two required
     fields and nothing on the other seven or the root. Do not "fix" this without changing the
     decision on purpose.
   - `validBrokerHostAndPortLeaveThoseFieldsFreeOfErrors` and
     `outOfRangeBrokerPortIsReportedOnTheBrokerPortPathNotTheRoot` — the happy path, and that a
     message lands on its own field path rather than the top of the form.
   - `aCorrectedValueClearsThePreviouslyAttachedError` — the 1.0.3 sticky-error regression (item 8).
     Verified by temporarily removing the `clearErrorsAtPath` pass, which failed 3 of the 4
     validation tests.
   - `configuredSessionValuesReachMqttConnectOptions` and
     `absentOrOutOfRangeSessionValuesFallBackToTheDocumentedDefaults` — the no-inert-field proof:
     each of the four session settings read back off `MqttConnectOptions` through Paho's own getters,
     plus the documented defaults (5 s connect timeout, Paho's 60 s keep-alive, clean session `true`,
     automatic reconnect `false`) and keep-alive `0` passing through as a legal disable.

   Config keys covered: 9. Publish input keys covered: 3. **Nothing exercises a real publish**, and
   no test needs a broker. The dead `net.jqwik` / `io.moquette` dependencies and the
   `**/*Properties.java` / `**/*IT.java` Surefire-Failsafe wiring that this item used to complain
   about were **removed** at 1.0.4 — see §2.
5. **RESOLVED 2026-10-27: the project is now a git repository** on branch `main`, remote
   `https://github.com/ahock/MQTT_Appian_ConnectedSystem.git`. Two commits:
   - `d023a76` *first commit* — the complete **1.0.3** build, all 36 `.java` files. Restoring any
     removed feature starts here: `git checkout d023a76 -- src pom.xml README.md`.
   - `7c9e12b` *Reduction of 1.0.3 to initial working plugin* — the 1.0.4 minimal smoke build, and
     the known-good reference point to diff against when design-time rendering breaks.
   The 1.0.5 changes sit in the working tree; committing them is the user's call.
   `.backup/mqtt-plugin-FULL-1.0.3-src.zip` is now redundant (it predates the repo and was caught by
   `d023a76`, so the `.backup/` ignore rule cannot untrack it); `d023a76` holds the same source as
   diffable files. Nothing had been pushed as of this note.
6. **`isImportCustomizable` on the encrypted field.** The restrictions page says an
   `EncryptedTextPropertyDescriptor` *should* set `isImportCustomizable(true)`; at 1.0.5 `password`
   is the only such field and it leaves the flag at the builder default `false`
   (`clientPrivateKey` and `keyPassphrase` no longer exist). It is a "should", it played no part in
   any of the three deploy failures, and enabling it changes cross-environment deployment
   behavior — so it is carried forward, not fixed.
7. **ANSWERED 2026-10-27 — Appian does NOT display design-time errors on a connected system
   template. Do not build design-time validation there.**

   The restrictions page says `setErrors()` is ignored on a connected system. This template attaches
   through `PropertyState.setErrorAtPath`, which decompiles to a delegation to `setErrorsAtPath` — a
   different method — so the documentation could not settle whether that path was ignored too.

   1.0.5 settled it by experiment: `ConfigValidator.validate` ran on **every** render including the
   first, deliberately ungated, so a brand-new object attached errors to `brokerHost` and
   `brokerPort`. The gate was omitted on purpose, because a gate would have made a clean first page
   ambiguous between "the gate worked" and "Appian discards our errors".

   **Observed result on a live Appian 26.9 instance: NO error message appeared.** The page rendered
   normally and the plug-in worked; the messages were simply discarded. The `setErrors` restriction
   therefore covers `setErrorAtPath` / `setErrorsAtPath` as well.

   **Consequences, binding on future work:**
   - `isRequired` is the ONLY design-time guard available on a connected system. Appian does render
     that one.
   - Configuration validation must happen at RUN time, inside the integration, surfacing through the
     integration response — never at design time on the connected system.
   - Whether INTEGRATION input errors display is a separate, untested question. 1.0.3's
     `InputValidator` is the reference if that is pursued:
     `git show d023a76:src/main/java/de/hock/mqtt/validation/InputValidator.java`
   - `de.hock.mqtt.validation.ConfigValidator`, added at 1.0.5, is therefore **dead code**: it runs
     on every render and its results are thrown away. Remove it, or demote it to a run-time check
     called by the publisher. Leaving it as-is is misleading — it looks like working validation.
   - Attaching errors does NOT break rendering, so this is a cosmetic dead end, not a hazard.

8. **A reported field error is never cleared — RESOLVED BY IMPLEMENTATION at 1.0.5.**
   Note: with item 7 now answered, this fix is correct but currently moot on the connected
   system, since no attached error is displayed at all. It stays relevant for the archived full
   build and for any future integration-input validation.
   `PropertyState.clearErrorsAtPath(PropertyPath)` exists on core 1.9.1 (confirmed with `javap`) and
   bottoms out in `setErrorsAtPath(path, Collections.emptyList())`. `ConfigValidator.validate` calls
   it for every validated key — `brokerHost`, `brokerPort`, `connectionTimeoutSeconds`,
   `keepAliveSeconds` — before evaluating anything, then re-attaches only what still fails.
   Regression-tested by `aCorrectedValueClearsThePreviouslyAttachedError`.

   Three measured facts to keep:
   - **Clearing is genuinely required.** Errors survive a re-render because `StateGenerator.clone`
     copies them into the regenerated state, so `SimpleConfiguration.setProperties` preserves
     whatever the incoming descriptor carried. Removing the clear pass fails 3 of the 4 validation
     tests.
   - **Never call `addErrors` after a clear.** `clearErrorsAtPath` installs the immutable
     `Collections.emptyList()`, so `addErrors` throws `UnsupportedOperationException`. Use
     `setErrorAtPath`, which replaces the list with a fresh one.
   - **Order matters.** `setErrorsAtPath` silently no-ops when the path does not resolve, so
     validation must run after `setProperties` has generated the nested per-field states.

   The defect is still real in the archived full build: fix it there before restoring that
   validation framework.
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
> 1.0.4** (2026-10-27), then extended at **1.0.5** with four session fields and ungated design-time
> validation. Everything below 1.0.5 records how the platform and packaging constraints were
> discovered on the *full* plug-in, so it describes a **superset** of what now ships: certificate
> fields, LWT, reconnect attempt tuning, subscribe and diagnostics all appear in these records and
> none of them exist in the current build. The complete `1.0.3` source is in git commit `d023a76`
> and archived at `.backup/mqtt-plugin-FULL-1.0.3-src.zip` (136397 bytes, 74 entries, sha256
> `386f3cab7ab660e19325f2825071152338eb48855e6f45b8d9665afcc22f89b4`). The hard constraints in §1
> and §2 are what carried forward and still bind.

- `validation-and-config-fields-1.0.5-plan.md` — the plan behind 1.0.5: the resolved SDK questions
  with `javap` evidence, the exact target figures, and the ordered implementation steps.
- `validation-and-config-fields-1.0.5.md` — the 1.0.5 release record: the four new fields with their
  wiring and effective defaults, the ungated-probe decision, the error-clearing fix, the full
  verification output, and the deploy-and-test checklist. **Start here for what currently ships.**
- `validation-1.0.5-review.json` / `validation-1.0.5-review.md` — independent review of 1.0.5.
- `minimal-smoke-build-plan.md` — the plan behind the 1.0.4 reduction: the delete/keep lists, the
  resolved judgement calls, and the verification block now mirrored in §4.
- `minimal-smoke-build.md` — the 1.0.4 reduction record: every deleted and kept file with a reason,
  the five config fields and three publish inputs as it shipped, the invariant evidence with real
  command output, its deploy checklist, and how to restore the full build.
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
