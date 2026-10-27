# Appian AppMarket Compliance — MQTT Broker Connected System

Project-specific rules for this plug-in, derived from two real AppMarket rejections and a verified
audit (2026-10-12). Target platform: **Appian 26.9**. Everything below was confirmed against Appian
documentation or a real build — not inferred.

> **Scope note (2026-10-27, 1.0.6).** The project was **deliberately reduced to a minimal
> smoke-test build** at `1.0.4`: one connected system with five fields and one publish integration
> with three inputs. That build was deployed and confirmed working on a live Appian 26.9 instance.
> `1.0.5` added four session fields — `connectionTimeoutSeconds`, `keepAliveSeconds`,
> `cleanSession`, `autoReconnectEnabled`, bringing the connected system to **nine**, each one
> genuinely reaching `MqttConnectOptions` — plus an ungated design-time validation probe. It
> deployed and worked too.
>
> **`1.0.6` adds two things and removes one.** (1) A **subscribe (READ) integration**,
> `MqttSubscribeIntegration`, with four inputs (`topicFilter`, `qos`, `maxWaitSeconds`,
> `maxMessageCount`) — the plug-in now has **two integration modules** and **two Paho call sites**.
> (2) **Design-time validation is gone.** The 1.0.5 probe answered the question (§5 item 7): Appian
> discards connected system field errors. `de.hock.mqtt.validation.ConfigValidator` and the whole
> `validation` package were deleted; the same rules now run at invocation time in
> `de.hock.mqtt.ConnectionConfigValidator`, called by both integrations. The connected system is
> **untouched at nine fields**. TLS and certificates, Last Will and Testament, multi-topic
> subscribe, reconnect attempt tuning and diagnostics remain removed.
>
> The complete `1.0.3` source is in git commit `d023a76`, so the richer history in `.agents/tasks/`
> describes a **superset** of what now ships — do not read a reference to a removed field as a
> description of the current build, and note that `1.0.3`'s subscribe integration is *not* what
> ships now (different inputs, different machinery). The hard constraints in §1 and §2 all still
> apply; the project-specific figures throughout were updated to the 1.0.6 reality. Records:
> `.agents/tasks/subscribe-integration-1.0.6.md` for 1.0.6,
> `.agents/tasks/validation-and-config-fields-1.0.5.md` for 1.0.5, and
> `.agents/tasks/minimal-smoke-build.md` for the reduction.

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
grep -rh 'import com.appian.connectedsystems' src/main/java/ | wc -l  # 36, across 4 files
grep -rl 'com\.appian\.connectedsystems' src/main/java | wc -l        # 4 (3 templates + validator)
```

The import count was 47, then 45 with the `Simple*` migration (2026-10-13), dropped to 22 across 2
files when the plug-in was reduced to the minimal smoke-test build at 1.0.4, was 25 across 3 files at
1.0.5, and is **36 across 4 files** at 1.0.6 — measured, not predicted: the connected system template
11, each integration 12, and `de.hock.mqtt.ConnectionConfigValidator` 1 (it imports only
`PropertyState`).

### Sources must ship *inside* the plug-in JAR

Appian policy: plug-ins must include the source code within the executable. This was the first
rejection. A separate `-sources.jar` from `maven-source-plugin` does **not** satisfy it — the sources
must be entries in the same archive as the `.class` files.

Implemented as a `<fileSet>` over `src/main/java` in the assembly descriptor, landing sources at the
archive root mirroring the package structure (`de/hock/mqtt/Foo.java` beside `Foo.class`).

At 1.0.6 that is **7 sources**: `de/hock/mqtt/package-info.java`, `de/hock/mqtt/MqttPublisher.java`,
`de/hock/mqtt/MqttSubscriber.java`, `de/hock/mqtt/ConnectionConfigValidator.java`,
`de/hock/mqtt/template/MqttConnectedSystemTemplate.java`,
`de/hock/mqtt/integration/MqttPublishIntegration.java` and
`de/hock/mqtt/integration/MqttSubscribeIntegration.java`. It was 5 at 1.0.5, 4 at 1.0.4 and 34
before the reduction. The `validation` package **no longer exists** — its one class was demoted to a
run-time check and moved to the root package at 1.0.6, so there is no `validation/` entry of any
kind in the jar.

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
`NoClassDefFoundError`. At 1.0.6 **two** classes reference Paho — `de.hock.mqtt.MqttPublisher`,
which owns `publish` and the shared `buildConnectOptions`, and `de.hock.mqtt.MqttSubscriber`, which
owns `subscribe`, `drain` and `toMessageMap` — so a classloading failure names whichever one ran.
That is an unavoidable consequence of adding subscribe, not a drift: the Paho surface is still
confined to those two, no integration class touches Paho beyond catching `MqttException`, and
`buildConnectOptions` stays in one place so the default-from-`null` logic is not duplicated. Set the
assembly `dependencySet` `<outputDirectory>` to `META-INF/lib`.

*Status: fixed and confirmed — the 1.0.4 deploy resolved Paho from `META-INF/lib/` on a live 26.9
instance.*

### Version number formats — two different fields, two different formats

This caused the second rejection.

| Field | Format | Our value |
|---|---|---|
| `<plugin-info><version>` | **three-part `x.y.z`** | `1.0.6` |
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
all — the nine connected system fields at 1.0.6 are Text, Integer, Boolean and EncryptedText only.
The table matters again the moment anything is restored from the archive.

#### Precaution, NOT a known rule: forced-flag builders are avoided on integration inputs too

Added at 1.0.6, and labelled honestly because it is a judgement call rather than a measured platform
contract. The subscribe integration's `topicFilter` is a single `TextPropertyDescriptor`, not a
`ListTypePropertyDescriptor` list of filters, and a test asserts that **no** subscribe input is a
`ListType`, `Document` or `AppianValue` descriptor.

What was actually measured, with `javap -c` against `connected-systems-core-1.9.1.jar`:

1. `ListTypePropertyDescriptor$Builder.<init>` calls `PropertyDescriptorBuilder.isExpressionable(true)`
   **and** `displayHint(DisplayHint.EXPRESSION)` — both forced in the constructor.
2. Its `build()` calls `Guard.isDisplayHintExpression(data)` and, when true, throws
   `IllegalStateException: List property descriptors cannot have display hint of expression and not
   be expressionable` if the flag was cleared.
3. `PropertyDescriptorBuilder.displayHint(DisplayHint)` **is public**, so that coupling is in
   principle escapable with `displayHint(NORMAL)` *plus* `isExpressionable(false)`. Unlike
   `DocumentPropertyDescriptor`, `build()` does **not** call `Guard.checkPropertyIsExpressionable`,
   so no unconditional guard forces the flag. That is new detail relative to the table above, which
   records the type as "effectively no" for a connected system — a conclusion that still holds for a
   *default-built* list descriptor.
4. **No evidence either way about integration inputs.** The validator that rejected the forced flags
   is `ConnectedSystemPropertiesValidator`, which is platform-side and connected-system-specific;
   neither SDK jar contains it or any integration-input validator (§5 item 9). Legality of a list
   descriptor on an integration input therefore **cannot be proven offline**.

So a list input is *very probably* fine and the 1.0.3 build declared one. It has simply never run on
a real instance, and three deploy cycles were already lost to descriptor-contract surprises, so
1.0.6 does not spend a fourth finding out. Multi-topic subscribe is a deliberate follow-up. **If a
future release wants it, that is a legitimate change — just make it the only risky thing in that
deploy.**

Verify both halves — and note the source grep is the weaker check of the two:

```bash
# Necessary: no explicit call in the template. Repo-wide total 7, all on the two integrations.
grep -c isExpressionable src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java  # 0
grep -rn isExpressionable src/main/java/ | wc -l                                               # 7
grep -rc isExpressionable src/main/java/de/hock/mqtt/integration/
#   MqttPublishIntegration:3, MqttSubscribeIntegration:4
```

The template's Javadoc deliberately avoids the literal token `isExpressionable` so the first grep
stays a clean zero rather than matching prose. **The Javadoc of `MqttSubscriber`,
`ConnectionConfigValidator` and `MqttSubscribeIntegration` must avoid it too** — all three discuss
expressionability in prose — or the per-file counts stop being 0 / 3 / 4.

Sufficient: `MqttConnectedSystemTemplateDescriptorTest.noConnectedSystemPropertyDescriptorIsExpressionable`
renders the descriptor and reads every modifier back through `PropertyDescriptor.isExpressionable()`,
which is what the platform validator actually walks. Keep that test; a grep alone would have shipped
the two document fields.

---

## 2. Build facts

- Maven. `maven-assembly-plugin:3.7.1`, execution `build-appian-plugin`, phase `package`, goal
  `single`, descriptor `src/assembly/plugin-assembly.xml`.
- **Submission artifact:** `target/mqtt-broker-connected-system-1.0.6-plugin.jar` — the `-plugin`
  suffix comes from the assembly `<id>`. A JAR without it is a stale build.
- **Exactly one JAR must land in `target/`.** Previously two were produced and *both* carried
  `appian-plugin.xml` at the root, so either looked deployable. Keep it to one.
- Java **17** (`<maven.compiler.release>17</maven.compiler.release>`, bytecode major version 61).
  Appian 26.9 requires Java 17.
- SDK: `com.appian:connected-systems-core:1.9.1`, scope `provided` (never bundle it).
- SDK: `com.appian:connected-systems-client:1.3.0`, scope `provided` (never bundle it) — the
  `Simple*` facade all three templates subclass. Added 2026-10-13; see §5 item 2.
- `org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5`, bundled.
- Tests: `org.junit.jupiter:junit-jupiter:5.10.2`, scope `test` — **and nothing else**.
  `net.jqwik:jqwik` and `io.moquette:moquette-broker` were removed at 1.0.4, together with the
  `maven-failsafe-plugin` block and the Surefire `**/*Properties.java` / `**/*IT.java` wiring. No
  file ever matched those patterns, so the configuration was advertising coverage that did not
  exist — in sources AppMarket reviewers read. Surefire's default `**/*Test.java` include covers all
  **seven** test classes at 1.0.6, so it needs no `<configuration>` block.
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
  `MqttConnectOptions.setSocketFactory()`. (Moot at 1.0.6: there is no TLS. The rule applies again
  the moment TLS is restored from the archive. The only match for a `System.setProperty` grep is a
  Javadoc sentence in `MqttPublisher` asserting the absence of such a call — prose, not a call;
  `MqttSubscriber`'s Javadoc deliberately phrases the same assurance without the token.)
- `Closeable` resources closed in a guaranteed fashion (try-with-resources / `finally`); temp files
  deleted. At 1.0.6 this covers **both** Paho call sites. `MqttPublisher` disconnects and closes its
  client in a `finally` block regardless of outcome. `MqttSubscriber` does the same and
  **unsubscribes first**, in three separate `try`/`catch (MqttException ignored)` steps inside one
  `finally`, so a timeout or a thrown exception can leave neither a subscription nor a connection
  behind. Both hold with `autoReconnectEnabled` set, so nothing outlives the request.
- **No background thread, executor or callback may outlive a request.** `MqttSubscriber` starts no
  thread and registers no `MqttCallback`: the listener handed to the three-argument
  `subscribe(filter, qos, listener)` only `offer`s a finished map into a `LinkedBlockingQueue`
  bounded to `maxMessageCount`, so Paho's own receiver thread is never blocked and overflow is
  dropped rather than queued without bound. The collection loop polls in 200 ms slices against an
  absolute `System.nanoTime` deadline, which is a deliberate bounded wait, not a busy-wait, and ends
  promptly on either the count or the deadline.
- No direct filesystem manipulation. Use `ContentService` / Document APIs for Appian documents.
  At 1.0.6 both `MqttPublisher` and `MqttSubscriber` pass `MemoryPersistence` to Paho, because
  Paho's default persistence writes to disk.
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
mvn -o test                                      # Tests run: 23, Failures: 0, Errors: 0
J=target/mqtt-broker-connected-system-1.0.6-plugin.jar

ls target/*.jar | wc -l                          # 1 — exactly ONE jar
unzip -l $J | grep -c '\.java$'                  # 7 sources
unzip -l $J | grep '\.java$' | grep -vc 'de/hock/mqtt/'   # 0 — none outside the package
unzip -l $J | grep -cE ' appian-plugin\.xml$'    # 1
unzip -l $J | awk '{print $NF}' | grep -c '^appian-plugin.xml$'   # 1 — bare root, no prefix
unzip -l $J | grep -c 'META-INF/lib/.*\.jar$'    # 1 — Paho is the only nested jar
unzip -l $J | grep -c 'META-INF/lib/org.eclipse.paho'     # 1
unzip -l $J | grep -ci 'connected-systems'       # 0, both Appian artifacts are provided
unzip -l $J | grep -c 'com/appian/mqtt'          # 0
unzip -l $J | grep -ci test                      # 0
unzip -l $J | grep -ci 'backup'                  # 0 — nothing from .backup/
unzip -l $J | grep -c 'validation/'              # 0 — the validation package is gone (1.0.6)
unzip -l $J | awk '{print $NF}' | grep -c '^THIRD-PARTY-NOTICES.md$'   # 1
unzip -p $J appian-plugin.xml | grep -E '<version>|application-version|<vendor'
#   <version>1.0.6</version> / min="26.9.0.0" / vendor name="Hock"
#   (the grep also matches two manifest comment lines that discuss the two version formats)
unzip -p $J appian-plugin.xml | grep -c 'key="de.hock.mqtt"'   # 1 — plug-in key unchanged
unzip -p $J appian-plugin.xml \
  | grep -cE 'key="Mqtt(ConnectedSystemTemplate|PublishIntegration|SubscribeIntegration)"'
#   3 — all module keys unchanged, so the build REPLACES the installed one
unzip -p $J appian-plugin.xml | grep -nE 'connected-system-template|integration-template'
#   the TWO <integration-template> elements must be nested inside the ONE
#   <connected-system-template>, not siblings of it
javap -verbose -cp target/classes de.hock.mqtt.template.MqttConnectedSystemTemplate \
  | grep 'major version'                         # 61
javap -verbose -cp target/classes de.hock.mqtt.MqttPublisher | grep 'major version'   # 61
javap -verbose -cp target/classes de.hock.mqtt.MqttSubscriber | grep 'major version'  # 61
javap -verbose -cp target/classes de.hock.mqtt.ConnectionConfigValidator \
  | grep 'major version'                         # 61
javap -verbose -cp target/classes de.hock.mqtt.integration.MqttPublishIntegration \
  | grep 'major version'                         # 61
javap -verbose -cp target/classes de.hock.mqtt.integration.MqttSubscribeIntegration \
  | grep 'major version'                         # 61

# Expressionability — the grep is necessary, the test is what actually proves it (see §1).
grep -c isExpressionable src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java  # 0
grep -rn isExpressionable src/main/java/ | wc -l                                               # 7
grep -rc isExpressionable src/main/java/ | grep -v ':0$'
#   expect exactly two lines:
#     .../integration/MqttPublishIntegration.java:3
#     .../integration/MqttSubscribeIntegration.java:4

# Namespace trap and the SDK import census (§1).
grep -rn 'com\.appian\.mqtt' . | grep -v '/target/' | grep -v '/.agents/' | grep -v '/.kiro/' \
  | grep -v '/.git/'                             # no hits
grep -rh 'import com.appian.connectedsystems' src/main/java/ | wc -l   # 36
grep -rl 'com\.appian\.connectedsystems' src/main/java | wc -l         # 4

# Code policies (§3). The only hit is a Javadoc sentence in MqttPublisher asserting the absence
# of a System.setProperty call — prose, not a call. Any other hit is a real violation.
grep -rn 'System\.out\|System\.err\|printStackTrace\|System\.setProperty\|java\.util\.logging' \
  src/main/java/

# Resource bundle: no orphan keys, no missing keys (22 keys, 16 of them field labels).
B=src/main/resources/resources_en_US.properties
sed -nE 's/^([A-Za-z][A-Za-z.]*)=.*/\1/p' $B | sort > /tmp/bundle-actual.txt
{ echo MqttConnectedSystemTemplate.name; echo MqttConnectedSystemTemplate.description
  sed -nE 's/.*public static final String [A-Z_]+ *= *"([A-Za-z]+)".*/MqttConnectedSystemTemplate.\1.label/p' \
    src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java
  echo MqttPublishIntegration.name; echo MqttPublishIntegration.description
  sed -nE 's/.*public static final String INPUT_[A-Z_]+ *= *"([A-Za-z]+)".*/MqttPublishIntegration.\1.label/p' \
    src/main/java/de/hock/mqtt/integration/MqttPublishIntegration.java
  echo MqttSubscribeIntegration.name; echo MqttSubscribeIntegration.description
  sed -nE 's/.*public static final String INPUT_[A-Z_]+ *= *"([A-Za-z]+)".*/MqttSubscribeIntegration.\1.label/p' \
    src/main/java/de/hock/mqtt/integration/MqttSubscribeIntegration.java
} | sort > /tmp/bundle-expected.txt
diff /tmp/bundle-expected.txt /tmp/bundle-actual.txt   # no output; '>' = orphan, '<' = missing
#   The three sed patterns hold only while the NINE config keys are the ONLY
#   `public static final String` constants on the template and the three/four input keys the only
#   `public static final String INPUT_*` constants on each integration. Keep result-map keys
#   ("success", "status", "topic", "qos", "messages", "messageCount", "payload", "retained",
#   "binary") as inline literals.

# The full 1.0.3 source is recoverable from git, not from an archive.
git show --stat d023a76 | head -3     # "first commit", the complete 1.0.3 build
git ls-tree -r --name-only d023a76 | grep -c '\.java$'   # expect 36
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

**1.0.5 was deployed and tested successfully too (2026-10-27).** It added four session fields (nine
total) and the ungated design-time validation probe. The probe answered item 7: Appian displays
nothing. The connected system page rendered its nine fields and publishing kept working.

**1.0.6 is built on that working 1.0.5 baseline but NOT yet deployed.** It adds the subscribe (READ)
integration — second integration module, second Paho call site — and acts on item 7 by deleting
design-time validation and demoting its rules to an invocation-time check. The connected system is
unchanged at nine fields, so a design-time regression here would be surprising; diff against 1.0.5
if the configuration page misbehaves.

Items 1, 2, 5, 7 and 8 are resolved; 3, 4 and 6 are not.

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
4. **Test coverage is offline only — 23 tests in seven classes** (13 at 1.0.5, 7 at 1.0.4, 19 at
   1.0.3): 2 in `ProjectStructureTest`, 7 in `MqttConnectedSystemTemplateDescriptorTest`, 1 in
   `MqttConnectedSystemTemplateNoDesignTimeErrorsTest`, 4 in `ConnectionConfigValidatorTest`, 2 in
   `MqttPublisherConnectOptionsTest`, 3 in `MqttSubscriberTest` and 4 in
   `MqttSubscribeIntegrationInputsTest`.
   `ProjectStructureTest` checks that `appian-plugin.xml` and `resources_en_US.properties` are on
   the classpath. The others drive the real framework entry points with the empty descriptor
   Appian supplies for a new object (`ConfigurationDescriptor.builder().build()`, whose
   `getRootState()` is `null`) or call the package-private runtime seams directly:
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
   - `subscribeIntegrationRendersItsRootStateAndItsFourExpressionableInputs` and
     `subscribeInputsUseNoListDocumentOrAppianValueDescriptor` — added at 1.0.6: the four subscribe
     input keys in display order, all four reading back expressionable, and no forced-flag
     descriptor type. The second is a **precaution** on integration inputs, not a known platform
     rule (§1).
   - `brandNewConnectedSystemRendersWithNoAttachedErrors` — the 1.0.6 replacement for 1.0.5's
     ungated-probe test. A brand-new object carries no error on any of the nine fields and none on
     the root, because nothing attaches errors any more. The three other 1.0.5 validation tests
     (`validBrokerHostAndPortLeaveThoseFieldsFreeOfErrors`,
     `outOfRangeBrokerPortIsReportedOnTheBrokerPortPathNotTheRoot`,
     `aCorrectedValueClearsThePreviouslyAttachedError`) were **deleted with the machinery they
     covered**; their rules live on below.
   - `missingOrBlankBrokerHostIsReported`, `missingOrOutOfRangeBrokerPortIsReported`,
     `outOfRangeConnectionTimeoutOrKeepAliveIsReported` and
     `aValidConfigurationWithBlankOptionalsReportsNothing` — the four demoted rules, now on the
     run-time path through `ConnectionConfigValidator.firstError`, each building a real state by
     rendering the template and calling `setValueAtPath`. Keep-alive `0` is asserted accepted.
   - `configuredSessionValuesReachMqttConnectOptions` and
     `absentOrOutOfRangeSessionValuesFallBackToTheDocumentedDefaults` — the no-inert-field proof:
     each of the four session settings read back off `MqttConnectOptions` through Paho's own getters,
     plus the documented defaults (5 s connect timeout, Paho's 60 s keep-alive, clean session `true`,
     automatic reconnect `false`) and keep-alive `0` passing through as a legal disable. The
     out-of-range half is now defence in depth only — the validator rejects those values first.
   - `drainStopsAsSoonAsTheMessageCountIsReached` and `drainStopsWhenTheDeadlineHasPassed` — both
     termination conditions of the collection loop, driven with a pre-filled
     `LinkedBlockingQueue` and an absolute `System.nanoTime` deadline. **No broker, no adapter
     interface, no injected client**: `drain` is package-private and takes the queue and the deadline
     as parameters, which is the entire seam. The 1.0.3 adapter indirection was *not* reintroduced to
     make this testable.
   - `aUtf8PayloadDecodesToTextAndANonUtf8PayloadIsBase64Flagged` — `toMessageMap` with a real
     `MqttMessage`: valid UTF-8 round-trips with `binary` false, `{0xC3, 0x28, 0xFF}` comes back
     Base64 with `binary` true, an empty payload is `""`.
   - `absentInputsFallBackToTheDocumentedDefaults`,
     `outOfRangeQosMaxWaitAndMaxMessageCountAreRejected`,
     `anEmptyCollectionIsReportedAsSuccessWithMessageCountZero` and
     `executeWithoutATopicFilterReportsTheMissingInputAndContactsNoBroker` — the subscribe input
     contract: defaults 0 / 10 / 10, **rejection rather than clamping** with the offending value
     named, an empty collection as success with `messageCount` 0, and the first guard returning
     before any socket is opened.

   Config keys covered: 9. Publish input keys covered: 3. Subscribe input keys covered: 4.
   **Nothing exercises a real publish or a real subscribe**, and no test needs a broker; the
   slowest test takes ~0.3 s. The dead `net.jqwik` / `io.moquette` dependencies and the
   `**/*Properties.java` / `**/*IT.java` Surefire-Failsafe wiring that this item used to complain
   about were **removed** at 1.0.4 — see §2.
5. **RESOLVED 2026-10-27: the project is now a git repository** on branch `main`, remote
   `https://github.com/ahock/MQTT_Appian_ConnectedSystem.git`. Two commits:
   - `d023a76` *first commit* — the complete **1.0.3** build, all 36 `.java` files. Restoring any
     removed feature starts here: `git checkout d023a76 -- src pom.xml README.md`.
   - `7c9e12b` *Reduction of 1.0.3 to initial working plugin* — the 1.0.4 minimal smoke build, and
     the known-good reference point to diff against when design-time rendering breaks.
   The 1.0.5 changes were committed subsequently; `4bef5ec` is the deployed-and-working 1.0.5 and is
   now the better reference point for anything design-time. The 1.0.6 changes sit in the working
   tree; committing them is the user's call.
   The former `.backup/mqtt-plugin-FULL-1.0.3-src.zip` was deleted in commit `91c9e81` as
   redundant: `d023a76` holds the same source as diffable files. Recovery is therefore always via
   git, never an archive.
6. **`isImportCustomizable` on the encrypted field.** The restrictions page says an
   `EncryptedTextPropertyDescriptor` *should* set `isImportCustomizable(true)`; at 1.0.6 `password`
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
   - Whether INTEGRATION input errors display is a separate, untested question, and 1.0.6
     deliberately does **not** probe it — two experiments in one deploy test would muddy both.
     1.0.3's `InputValidator` is the reference if that is pursued:
     `git show d023a76:src/main/java/de/hock/mqtt/validation/InputValidator.java`
   - Attaching errors does NOT break rendering, so this is a cosmetic dead end, not a hazard.

   **CONSEQUENCE ACTED ON AT 1.0.6.** `de.hock.mqtt.validation.ConfigValidator` was the dead code
   this finding condemned — it ran on every render and its results were discarded, while looking
   like working validation. It is **deleted**, along with the whole `validation` package and every
   `clearErrorsAtPath` / `setErrorAtPath` call in the plug-in. The template's
   `getConfiguration` now only declares its nine properties. The rules were correct, so they were
   demoted rather than dropped: `de.hock.mqtt.ConnectionConfigValidator.firstError(PropertyState)`
   returns the first problem as a sentence or `null`, and **both** integrations call it before any
   broker contact and wrap the result in an `Invalid configuration` error response. The class sits in
   the root package, beside the two Paho call sites that consume its verdict, because a `validation`
   package would still read as design-time. One user-visible consequence, documented in the README:
   an out-of-range `connectionTimeoutSeconds` or `keepAliveSeconds` now **fails** the integration,
   where 1.0.5 silently fell back to a default.

8. **A reported field error is never cleared — fixed at 1.0.5, then MADE MOOT at 1.0.6.** The
   clear-then-attach machinery this item describes was **deleted** along with `ConfigValidator`
   (item 7), because Appian never displayed any of it. Nothing in the plug-in now calls
   `clearErrorsAtPath` or `setErrorAtPath`, and `aCorrectedValueClearsThePreviouslyAttachedError` is
   gone with them. The three measured facts below are kept as **history**, and they bind again the
   moment anyone attaches descriptor errors anywhere — which on a connected system means never, and
   on an integration input means only after testing whether those display at all.
   `PropertyState.clearErrorsAtPath(PropertyPath)` exists on core 1.9.1 (confirmed with `javap`) and
   bottoms out in `setErrorsAtPath(path, Collections.emptyList())`.

   Three measured facts to keep:
   - **Clearing is genuinely required.** Errors survive a re-render because `StateGenerator.clone`
     copies them into the regenerated state, so `SimpleConfiguration.setProperties` preserves
     whatever the incoming descriptor carried. Removing the clear pass failed 3 of the 4 validation
     tests that existed at 1.0.5.
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
> 1.0.4** (2026-10-27), extended at **1.0.5** with four session fields and an ungated design-time
> validation probe, and extended again at **1.0.6** with the subscribe integration — which also
> deleted design-time validation, because the probe's answer was that Appian never displayed it.
> Everything below 1.0.6 records how the platform and packaging constraints were discovered on the
> *full* plug-in, so it describes a **superset** of what now ships: certificate fields, LWT,
> reconnect attempt tuning, multi-topic subscribe and diagnostics all appear in these records and
> none of them exist in the current build. In particular, the subscribe integration described in the
> pre-reduction records is **not** the one that ships at 1.0.6 — different inputs, different ranges,
> no adapter layer. The complete `1.0.3` source is in git commit `d023a76` (36 `.java` files); the
> former `.backup/` zip was deleted in `91c9e81` as redundant. The hard constraints in §1 and §2 are
> what carried forward and still bind.

- `subscribe-integration-1.0.6-plan.md` — the plan behind 1.0.6: the seven up-front decisions with
  their reasoning, the `javap` evidence on `ListTypePropertyDescriptor`, the figure table, and the
  thirteen ordered implementation steps.
- `subscribe-integration-1.0.6.md` — the 1.0.6 release record: the subscribe integration's inputs,
  output shape and teardown path, the `ConfigValidator` demotion with what replaced each rule, the
  behaviour change on out-of-range session values, the full pasted verification output, and the
  deploy-and-test checklist. **Start here for what currently ships.**
- `validation-and-config-fields-1.0.5-plan.md` — the plan behind 1.0.5: the resolved SDK questions
  with `javap` evidence, the exact target figures, and the ordered implementation steps.
- `validation-and-config-fields-1.0.5.md` — the 1.0.5 release record: the four new fields with their
  wiring and effective defaults, the ungated-probe decision, the error-clearing fix, the full
  verification output, and the deploy-and-test checklist.
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
