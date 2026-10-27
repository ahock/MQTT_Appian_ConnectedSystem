# Release record — 1.0.5: design-time validation probe + four wired session fields

Date: 2026-10-27. Target: Appian 26.9, Java 17. Baseline: **1.0.4 / commit `7c9e12b`**, deployed and
confirmed working on a live instance the same day.

Plan: `.agents/tasks/validation-and-config-fields-1.0.5-plan.md`. Everything the plan specified was
implemented; the three divergences it flagged (Part E) are restated at the end of this record and
none of them changed.

**Status: built and verified offline. NOT yet deployed.** Final confirmation requires the user
deploying `target/mqtt-broker-connected-system-1.0.5-plugin.jar`. The whole point of the release is
one observation that can only be made on a live instance — see
[Deploy-and-test checklist](#deploy-and-test-checklist).

**Git: nothing was committed.** All changes sit in the working tree; committing is the user's call.

---

## 1. What changed

Exactly two additions to the 1.0.4 baseline.

### 1.1 Design-time validation, deliberately ungated

New class `de.hock.mqtt.validation.ConfigValidator` — **one** class, `public final`, private
constructor, one public entry point `public static void validate(PropertyState rootState)`, private
bound constants, two private helpers. Chosen over the 1.0.3 four-class framework (`ConfigValidator`
+ `InputValidator` + `ValidationResult` + `ValidationResults`, plus `ConfigFields`, `LwtValidator`,
`ReconnectionValidator`), which existed to express a far larger rule set; four rules do not need it.
Chosen over inlining in the template because it keeps the bounds and the message strings out of what
is otherwise a descriptor declaration, and because it is directly testable. Cost: the in-jar source
count rises 4 → 5.

`MqttConnectedSystemTemplate.getConfiguration` now calls
`ConfigValidator.validate(configuration.toConfiguration().getRootState())` **after**
`setProperties(...)`. That ordering is required: the SDK's `setErrorsAtPath` silently no-ops when the
path does not resolve, and the nested per-field states do not exist until `setProperties` has
generated them.

There is **no** validation hook on the SDK interface to use instead. `ConnectedSystemTemplate`
declares exactly one method (`getConfigurationDescriptor`), and `SimpleConnectedSystemTemplate`'s
implementation is three steps — `SimpleConfiguration.from` → our `getConfiguration` override →
`toConfiguration()` — so `getConfiguration` is the only place validation can live.

**Validation runs on every render including the very first. That is the release.** Nothing
distinguishes render 1 from render N: Appian hands in a descriptor whose `getRootState()` is `null`,
`SimpleConfiguration.from` tolerates it, `setProperties` generates the root and all nine nested
states, and validation then runs over them. A brand-new connected system therefore opens with
"Broker host is required." and "Broker port is required." already attached.

Rationale, recorded in the `ConfigValidator` Javadoc, the `MqttConnectedSystemTemplate` Javadoc, the
README and steering §5 item 7: the open question is whether Appian displays connected system field
errors at all. The 26.9 restrictions page says `setErrors` is ignored there, but this plug-in
attaches through `PropertyState.setErrorAtPath`, a different method, so documentation cannot settle
it, and nobody has ever observed the answer — the page never rendered before 1.0.4 and 1.0.4 carried
no validation. Ungated validation converts the question into one unambiguous observation. A
first-render gate would make a clean page ambiguous between "the gate worked" and "Appian discards
our errors", which is exactly the information being sought. No gate, no freshness signal, no
`setValue` seeding; the 1.0.3 gate rested on an unverified round-trip assumption and is not coming
back.

**Rules** (each attached to its own field path, never the root):

| Field | Rule | Message |
|---|---|---|
| `brokerHost` | required, non-blank after trimming | `Broker host is required.` |
| `brokerPort` | required | `Broker port is required.` |
| `brokerPort` | 1–65535 | `Broker port must be between 1 and 65535.` |
| `connectionTimeoutSeconds` | if supplied, 1–300 | `Connection timeout must be between 1 and 300 seconds.` |
| `keepAliveSeconds` | if supplied, 0–65535 (`0` disables keep-alive) | `Keep alive interval must be between 0 and 65535 seconds.` |

Bounds 1–300 and 0–65535 are carried over from the archived 1.0.3 validator, so they are documented
values rather than new invention. The 300-second connect-timeout ceiling is justified in the
constant's Javadoc: five minutes already far exceeds what an Appian request should wait on an
unreachable broker, so the bound exists to catch a mistyped value. `clientIdentifier`, `username`,
`password`, `cleanSession` and `autoReconnectEnabled` are optional and unvalidated; an optional
field left blank reports nothing.

### 1.2 The 1.0.3 sticky-error bug, fixed

The clearing API exists. Confirmed with `javap` against the real jar
(`~/.m2/repository/com/appian/connected-systems-core/1.9.1/connected-systems-core-1.9.1.jar`):

```
$ javap -cp connected-systems-core-1.9.1.jar \
    com.appian.connectedsystems.templateframework.sdk.configuration.PropertyState | grep -i error
  public static final java.lang.String ERRORS_KEY;
  public java.util.List<java.lang.String> getErrors();
  public java.util.List<java.lang.String> getErrorsAtPath(...PropertyPath);
  public ...PropertyState addErrors(java.lang.String...);
  public ...PropertyState addErrors(java.util.Collection<java.lang.String>);
  public ...PropertyState setError(java.lang.String);
  public ...PropertyState setErrorAtPath(...PropertyPath, java.lang.String);
  public ...PropertyState setErrorsAtPath(...PropertyPath, java.util.List<java.lang.String>);
  public ...PropertyState setErrors(java.util.Collection<java.lang.String>);
  public ...PropertyState clearErrors();
  public ...PropertyState clearErrorsAtPath(...PropertyPath);
```

`validate` calls `clearErrorsAtPath(new PropertyPath(key))` for **all four** validated keys before
evaluating anything, then re-attaches with `setErrorAtPath` only. Three facts govern that shape, all
established in the plan's Part A1 from bytecode and re-confirmed here:

1. **Clearing is genuinely required.** `StateGenerator.clone` copies errors into the regenerated
   state, so `SimpleConfiguration.setProperties` preserves every error the incoming descriptor
   carried — including on a field whose value the designer has since corrected. Proven empirically
   below.
2. **`addErrors` must never follow a clear.** `clearErrorsAtPath` installs the immutable
   `Collections.emptyList()`, so `addErrors` throws `UnsupportedOperationException`.
   `setErrorAtPath` replaces the list with a fresh `ArrayList` and is safe. Only `setErrorAtPath` is
   used.
3. **Order matters.** `setErrorsAtPath` no-ops on an unresolvable path, so validation runs after
   `setProperties`.

**Empirical proof that the clear pass is load-bearing.** The four `clear(...)` calls were
temporarily commented out and the validation suite re-run:

```
[ERROR] Tests run: 4, Failures: 3, Errors: 0 -- in MqttConnectedSystemTemplateValidationTest
[ERROR]   aCorrectedValueClearsThePreviouslyAttachedError:164 the broker host error must be
          cleared once the value is corrected ==> expected: <true> but was: <false>
```

The clear pass was restored and the suite is green again. So the sticky-error bug is real on this
SDK, and `aCorrectedValueClearsThePreviouslyAttachedError` catches it.

### 1.3 Four new configuration fields — all wired

The connected system goes from five fields to **nine**. The existing five keep their positions; the
four are appended, so the required fields stay at the top.

| # | Key | Descriptor | Required | Effective default | Reaches |
|---|---|---|---|---|---|
| 1 | `brokerHost` | Text | yes | — | `MqttClient` URI |
| 2 | `brokerPort` | Integer | yes | — | `MqttClient` URI |
| 3 | `clientIdentifier` | Text | no | Paho `generateClientId()` | client id |
| 4 | `username` | Text | no | anonymous | `setUserName` |
| 5 | `password` | EncryptedText, masked | no | anonymous | `setPassword` |
| 6 | `connectionTimeoutSeconds` | Integer | no | **5 s** | `setConnectionTimeout(int)` |
| 7 | `keepAliveSeconds` | Integer | no | **60 s** (Paho's own) | `setKeepAliveInterval(int)` |
| 8 | `cleanSession` | Boolean | no | **`true`** when absent | `setCleanSession(boolean)` |
| 9 | `autoReconnectEnabled` | Boolean | no | **`false`** | `setAutomaticReconnect(boolean)` |

**No inert fields.** `MqttPublisher` gained a package-visible
`static MqttConnectOptions buildConnectOptions(String username, char[] password, Integer
connectionTimeoutSeconds, Integer keepAliveSeconds, Boolean cleanSession, Boolean
autoReconnectEnabled)` holding everything `publish` previously did between `new MqttConnectOptions()`
and the client construction. `publish` delegates to it and gained the four boxed parameters;
`MqttPublishIntegration.execute` reads the four values off the connected system state with two new
template helpers (`integerValue`, `booleanValue`) and passes them through. Every setter used has a
matching getter, so `MqttPublisherConnectOptionsTest` reads all four back without a broker.

No adapter, factory or config record was introduced — one extra static method on the class that
already owns the Paho surface. That keeps §1's invariant that exactly one class references Paho,
which is what makes a `NoClassDefFoundError` diagnostic.

**Defaults are interpreted at run time; no descriptor state is seeded.** A `null` means "not
configured". An out-of-range value also falls back to the default rather than failing the publish,
because whether the design-time message is even visible is exactly what is being probed, and a
mistyped keep-alive is not worth refusing a message over. A blank host or an out-of-range port still
fails, through the integration's four existing run-time guards, which are unchanged.
`buildConnectOptions` never throws. Keep-alive is left at Paho's default by **not calling the
setter** at all.

Paho facts, from `javap -constants` against
`org.eclipse.paho.client.mqttv3-1.2.5.jar`:

```
public static final int KEEP_ALIVE_INTERVAL_DEFAULT = 60;
public static final int CONNECTION_TIMEOUT_DEFAULT = 30;
public static final boolean CLEAN_SESSION_DEFAULT = true;
public void setKeepAliveInterval(int) throws java.lang.IllegalArgumentException;   // throws iff < 0
public void setConnectionTimeout(int);
public void setCleanSession(boolean);
public void setAutomaticReconnect(boolean);
public int getConnectionTimeout();  public int getKeepAliveInterval();
public boolean isCleanSession();    public boolean isAutomaticReconnect();
```

**`maxReconnectAttempts` and `reconnectIntervalSeconds` stay out, with evidence.** The complete
public setter list of `MqttConnectOptions` 1.2.5 contains no attempt-count setter and no
reconnect-interval setter; the only related member is `setMaxReconnectDelay(int)`, a cap on Paho's
internal exponential backoff, which is neither. Paho manages reconnect backoff itself. 1.0.3 shipped
`maxReconnectAttempts` as a configurable field that nothing enforced — a trap for designers — and
that is not being repeated.

**`autoReconnectEnabled` caveat, stated plainly** in the README, the `buildConnectOptions` Javadoc
and the field's own instruction text: one publish connects, sends and disconnects inside a single
request, so automatic reconnect has almost no room to act. It is exposed because it is a legitimate
`MqttConnectOptions` setting and more connection parameters were asked for. It starts nothing that
outlives the request — the `finally` block disconnects and closes the client either way.

**`cleanSession` honesty note.** The SDK seeds a `BOOLEAN` property to `Boolean.FALSE`
(`SystemType` static initialiser: `STRING`/`ENCRYPTED`/`PARAGRAPH` → `""`, `BOOLEAN` →
`Boolean.FALSE`, `INTEGER`/`DOUBLE` → `null`), so a brand-new connected system renders **Clean
Session unchecked**. The specified `null` → `true` rule is implemented exactly as asked and is
correct, but it only *fires* when the value is genuinely absent — for instance when a connected
system saved by 1.0.4, before the field existed, is read at run time. The README says that rather
than claiming the designer sees a default of `true`. Seeding `true` with `setValue` was rejected: it
would restore precisely the mechanism the brief forbids.

Incidentally, the same seeding is why the probe will fire at all: `brokerHost` arrives as `""`
(blank, not null) and `brokerPort` as `null`, so both required rules reject on the first render.

### 1.4 Version, resources, docs

- `pom.xml` `<version>` and `appian-plugin.xml` `<plugin-info><version>`: `1.0.4` → **`1.0.5`**.
  `<application-version min="26.9.0.0"/>` untouched — different field, four-part by design.
- Plug-in key `de.hock.mqtt` and both module keys (`MqttConnectedSystemTemplate`,
  `MqttPublishIntegration`) unchanged, so 1.0.5 **replaces** 1.0.4 rather than installing beside it.
- `src/assembly/plugin-assembly.xml` **unchanged** — its `fileSet` over `src/main/java` already picks
  up the new source file, and its `dependencySet` already targets `META-INF/lib`.
- `resources_en_US.properties`: four new labels (`Connection Timeout`, `Keep Alive Interval`,
  `Clean Session`, `Automatic Reconnect`), 12 keys → **16**, header refreshed, template description
  now mentions session settings.
- `package-info.java` rewritten: it claimed design-time validation was removed.
- `README.md`: nine-field table with effective defaults and validation rules, a new "Design-time
  validation: a deliberate probe" section, the `autoReconnectEnabled` and `cleanSession` caveats,
  version/artifact/source-count/test-count refreshed, the two now-stale "unverified" limitations
  (`META-INF/lib` layout, `connected-systems-client` on the server classpath) dropped since the
  1.0.4 deploy settled both, and `maxReconnectAttempts` / `reconnectIntervalSeconds` kept in "still
  deliberately removed" with the evidence. No TLS, subscribe, LWT or diagnostics claims
  reinstated.
- `.kiro/steering/appian-appmarket-compliance.md`: scope note, source count 4 → 5, import census
  22/2 → 25/3, version table, artifact name, the §1 nine-field sentence, `BooleanPropertyDescriptor`
  marked measured-safe in the forced-flag table, the `ConfigValidator`-must-avoid-the-token warning,
  the whole §4 command block (13 tests, 5 sources, 1.0.5, 16 keys, nine config keys in the bundle
  caveat, module-key and `ConfigValidator` bytecode checks, the namespace census and the code-policy
  grep), §5 preamble, items 2, 4, 5, 6, and **rewritten items 7 and 8**, plus §8.
- `docs/mqtt-plugin-guide.md`: banner only — 1.0.5, nine fields with the four names, and a sentence
  saying design-time validation *is* present but ungated, which contradicts the gated behaviour the
  guide body still describes near line 116. The body is left as the explicitly-labelled
  pre-reduction history it already is.

---

## 2. Verification — real output

All of it offline. Run from `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component`.

### 2.1 Build

```
$ mvn -o clean package
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
[INFO] Building jar: .../target/mqtt-broker-connected-system-1.0.5-plugin.jar
[INFO] BUILD SUCCESS
```

### 2.2 Tests — 13, all passing

```
$ mvn -o test
[INFO] Running de.hock.mqtt.MqttPublisherConnectOptionsTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.099 s
[INFO] Running de.hock.mqtt.template.MqttConnectedSystemTemplateDescriptorTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.037 s
[INFO] Running de.hock.mqtt.template.MqttConnectedSystemTemplateValidationTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.010 s
[INFO] Running de.hock.mqtt.ProjectStructureTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.004 s
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

The 13, by class:

`ProjectStructureTest` (2, unchanged)
- `pluginDescriptorIsOnClasspath`
- `defaultResourceBundleIsOnClasspath`

`MqttConnectedSystemTemplateDescriptorTest` (5, kept; counts and key list updated)
- `brandNewConnectedSystemRendersANonNullRootStateWithAResolvableRootType` — the NPE regression
  guard, unchanged
- `exactlyTheNineExpectedConfigFieldKeysArePresent` — renamed from `…FiveExpected…`; the nine keys as
  an ordered list, so a tenth field fails too
- `noConnectedSystemPropertyDescriptorIsExpressionable` — now `assertEquals(9, inspected, …)`; still
  reads every modifier back through `PropertyDescriptor.isExpressionable()`, never a source grep
- `connectedSystemFieldsUseNoListDocumentOrAppianValueDescriptor` — iterates all properties, so it
  covers the four new fields with no edit
- `publishIntegrationRendersItsRootStateAndItsThreeExpressionableInputs` — unchanged

`MqttConnectedSystemTemplateValidationTest` (4, new)
- `brandNewConnectedSystemReportsRequiredErrorsOnBrokerHostAndBrokerPortOnly` — pins the ungated
  probe, and asserts the other seven paths and the root carry nothing
- `validBrokerHostAndPortLeaveThoseFieldsFreeOfErrors`
- `outOfRangeBrokerPortIsReportedOnTheBrokerPortPathNotTheRoot` — port `99999`
- `aCorrectedValueClearsThePreviouslyAttachedError` — the sticky-error regression

`MqttPublisherConnectOptionsTest` (2, new)
- `configuredSessionValuesReachMqttConnectOptions` — `17`, `45`, `false`, `true` read back as
  `getConnectionTimeout()`, `getKeepAliveInterval()`, `isCleanSession()`, `isAutomaticReconnect()`
- `absentOrOutOfRangeSessionValuesFallBackToTheDocumentedDefaults` — all four `null` → 5 / 60 /
  `true` / `false`; then `connectionTimeoutSeconds=0` and `keepAliveSeconds=-1` → the same 5 and 60
  without throwing; then `keepAliveSeconds=0` passing through as a legal disable

"Optional fields left blank must not error" is folded into the first validation test as assertions on
the other seven paths rather than being a fourteenth test, which keeps the total at the plan's 13 and
inside the brief's "roughly 10–13".

### 2.3 Both new negative probes — the tests bite

Beyond running green, each regression guard was verified to fail when its fix is removed.

Clearing (see §1.2): removing the four `clear(...)` calls → 3 of 4 validation tests fail, naming the
corrected-value assertion.

Expressionability: `.isExpressionable(true)` temporarily added to the new `cleanSession` field →

```
[ERROR] MqttConnectedSystemTemplateDescriptorTest.noConnectedSystemPropertyDescriptorIsExpressionable
        Appian rejects a connected system template declaring any expressionable property descriptor
        (APNX-1-4198-000), but these fields read back expressionable:
        [cleanSession (BooleanPropertyDescriptor)]
```

Both temporary edits were reverted; the suite is green and `grep -c isExpressionable` on the template
is back to `0`.

### 2.4 Expressionability greps — per file

```
$ grep -c isExpressionable src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java
0
$ grep -rn isExpressionable src/main/java/ | wc -l
3
$ grep -rc isExpressionable src/main/java/ | grep -v ':0$'
src/main/java/de/hock/mqtt/integration/MqttPublishIntegration.java:3
```

Expected: `0` on the template, `3` repo-wide, exactly one non-zero line and it is the publish
integration. All three hold. `ConfigValidator`'s Javadoc deliberately avoids the literal token, as
the template's does, so the repo-wide count stays 3. The grep is the weaker of the two checks; the
test in §2.3 is what actually proves compliance.

### 2.5 Packaging invariants (a)–(l), expected vs actual

`J=target/mqtt-broker-connected-system-1.0.5-plugin.jar`

| # | Invariant | Check | Expected | Actual | |
|---|---|---|---|---|---|
| a | namespace `de.hock.mqtt`, never `com.appian.*` | `unzip -l $J \| grep -c 'com/appian/mqtt'` | 0 | 0 | ✅ |
| a | | `grep -rn 'com\.appian\.mqtt' .` (excl. target/.agents/.kiro/.git) | no hits | no hits | ✅ |
| a | SDK imports intact | `grep -rh 'import com.appian.connectedsystems' src/main/java/ \| wc -l` | ~25 | 25 | ✅ |
| a | across how many files | `grep -rl 'com\.appian\.connectedsystems' src/main/java \| wc -l` | 3 | 3 | ✅ |
| b | sources inside the jar | `unzip -l $J \| grep -c '\.java$'` | 5 | 5 | ✅ |
| b | none outside the package | `unzip -l $J \| grep '\.java$' \| grep -vc 'de/hock/mqtt/'` | 0 | 0 | ✅ |
| c | manifest is a bare root entry | `unzip -l $J \| awk '{print $NF}' \| grep -c '^appian-plugin.xml$'` | 1 | 1 | ✅ |
| d | one nested jar | `unzip -l $J \| grep -c 'META-INF/lib/.*\.jar$'` | 1 | 1 | ✅ |
| d | and it is Paho | `unzip -l $J \| grep -c 'META-INF/lib/org.eclipse.paho'` | 1 | 1 | ✅ |
| d | no Appian SDK bundled | `unzip -l $J \| grep -ci 'connected-systems'` | 0 | 0 | ✅ |
| e | three-part plug-in version | `unzip -p $J appian-plugin.xml \| grep '<version>'` | `1.0.5` | `<version>1.0.5</version>` | ✅ |
| e | four-part platform version | same, `application-version` | `min="26.9.0.0"` | `min="26.9.0.0"` | ✅ |
| f | vendor | same, `<vendor` | `name="Hock"` | `<vendor name="Hock" url="https://itondemand.com"/>` | ✅ |
| g | exactly one jar | `ls target/*.jar \| wc -l` | 1 | 1 | ✅ |
| h | bytecode major version | `javap -verbose` × 4 classes | 61 | 61, 61, 61, 61 | ✅ |
| i | bundle keys | see §2.6 | 16, no orphans, no missing | 16, clean diff | ✅ |
| j | notices at the root | `unzip -l $J \| awk '{print $NF}' \| grep -c '^THIRD-PARTY-NOTICES.md$'` | 1 | 1 | ✅ |
| k | no tests in the jar | `unzip -l $J \| grep -ci test` | 0 | 0 | ✅ |
| k | nothing from `.backup/` | `unzip -l $J \| grep -ci 'backup'` | 0 | 0 | ✅ |
| l | plug-in key unchanged | `unzip -p $J appian-plugin.xml \| grep -c 'key="de.hock.mqtt"'` | 1 | 1 | ✅ |
| l | both module keys unchanged | `… grep -cE 'key="Mqtt(ConnectedSystemTemplate\|PublishIntegration)"'` | 2 | 2 | ✅ |

Every invariant holds. The five in-jar sources:

```
$ unzip -l $J | grep '\.java$' | awk '{print $NF}'
de/hock/mqtt/MqttPublisher.java
de/hock/mqtt/integration/MqttPublishIntegration.java
de/hock/mqtt/package-info.java
de/hock/mqtt/template/MqttConnectedSystemTemplate.java
de/hock/mqtt/validation/ConfigValidator.java
```

Bytecode:

```
$ javap -verbose -cp target/classes de.hock.mqtt.template.MqttConnectedSystemTemplate | grep 'major version'
  major version: 61
$ javap -verbose -cp target/classes de.hock.mqtt.MqttPublisher | grep 'major version'
  major version: 61
$ javap -verbose -cp target/classes de.hock.mqtt.validation.ConfigValidator | grep 'major version'
  major version: 61
$ javap -verbose -cp target/classes de.hock.mqtt.integration.MqttPublishIntegration | grep 'major version'
  major version: 61
```

### 2.6 Resource bundle — 16 keys, no orphans, no missing

```
$ wc -l < /tmp/bundle-actual.txt     # keys present in resources_en_US.properties
16
$ wc -l < /tmp/bundle-expected.txt   # keys derived from the two templates' constants
16
$ diff /tmp/bundle-expected.txt /tmp/bundle-actual.txt
(no output)
```

12 of the 16 are field labels (nine connected system fields + three publish inputs); the other four
are `MqttConnectedSystemTemplate.name` / `.description` and `MqttPublishIntegration.name` /
`.description`. The derivation sed patterns hold only while the nine config keys are the **only**
`public static final String` constants on the template and the three input keys the only
`public static final String INPUT_*` constants on the integration — result-map keys stay inline
literals. That caveat is recorded in steering §4.

### 2.7 Code policies

```
$ grep -rn 'System\.out\|System\.err\|printStackTrace\|System\.setProperty\|java\.util\.logging' src/main/java/
src/main/java/de/hock/mqtt/MqttPublisher.java:29: * {@code System.setProperty} call anywhere.
```

**One hit, and it is prose, not a call** — a pre-existing Javadoc sentence in `MqttPublisher`
asserting the *absence* of such a call. Stated here rather than omitted: the steering §4 block
previously claimed "no hits" for this grep, which was already inaccurate at 1.0.4; §4 now says the
single Javadoc match is expected and any other hit is a real violation. No logging dependency was
added and no print call exists; failure detail still goes into the integration response.
`MqttPublisher` still disconnects and closes its client in a `finally` block and still passes
`MemoryPersistence`. No hardcoded credentials anywhere, including the new tests — the publisher tests
pass `null` for username and password.

### 2.8 Archived 1.0.3 source untouched

```
$ shasum -a 256 .backup/mqtt-plugin-FULL-1.0.3-src.zip
386f3cab7ab660e19325f2825071152338eb48855e6f45b8d9665afcc22f89b4  .backup/mqtt-plugin-FULL-1.0.3-src.zip
```

Matches the recorded hash. The same source is in git commit `d023a76`.

### 2.9 What could NOT be verified offline

- **Whether Appian displays the design-time errors.** That is the question the release exists to
  answer; it is observable only on a live instance.
- **Whether a nine-field descriptor still renders.** No fourth platform guard is known, the
  descriptor-conversion rules in §1 are all respected, and the two new descriptor types are measured
  non-expressionable — but the platform validators are `com.appiancorp.*` and cannot be inspected
  offline (steering §5 item 9).
- **Whether a real publish honours the four new values end-to-end.** The mapping to
  `MqttConnectOptions` is tested; the broker's reaction to it is not.

**Final confirmation requires the user deploying
`target/mqtt-broker-connected-system-1.0.5-plugin.jar`.**

---

## 3. Deploy-and-test checklist

**Step 3 is the point of this release.**

1. **Deploy and confirm the version.** Copy
   `target/mqtt-broker-connected-system-1.0.5-plugin.jar` into `APPIAN_INSTALL/_admin/plugins/`.
   Check **Admin Console → Plug-ins** shows **1.0.5**. If it still shows 1.0.4 the plug-in did not
   replace the old one and nothing below means anything — stop and check `tomcat-stdOut.log` for
   `Successfully installed Plug-in` plus the `ConnectedSystemTemplateModuleParser` lines.
2. **Open `New > Connected System > MQTT Broker`.** Confirm **nine** labelled fields render: Broker
   Host, Broker Port, Client Identifier, Username, Password, Connection Timeout, Keep Alive
   Interval, Clean Session, Automatic Reconnect. If the page fails to render at all, 1.0.5 has hit a
   fourth platform guard — report the exact message and diff against commit `7c9e12b`.
3. **THE KEY OBSERVATION. On that brand-new, untouched page, does a validation error appear under
   Broker Host and Broker Port?**
   - **YES** → Appian **does** display connected system field errors set through
     `PropertyState.setErrorAtPath`, and the restrictions page's `setErrors` warning does not cover
     that path. Design-time validation on a connected system is viable; 1.0.6 can gate it for a
     cleaner first open.
   - **NO** → Appian discards them. Design-time validation on a connected system is impossible, and
     validation belongs on the integration inputs instead.

   Either answer closes a question open since 1.0.0. **Record which one you saw** in steering §5
   item 7.
4. **If errors appeared, test clearing.** Enter a valid host and `1883`. Both errors must disappear —
   that exercises the `clearErrorsAtPath` fix for the 1.0.3 sticky-error bug. Then enter port
   `99999`: the port error must come back **on the Broker Port field alone**, with nothing on Broker
   Host and nothing at the top of the form.
5. **Confirm the runtime path did not regress.** Fill in a reachable broker, save, and publish through
   the existing **Publish MQTT Message** integration. Success is `success: true` with `status`
   `dispatched` (QoS 0) or `delivered` (QoS 1/2).
6. **Optional — exercise the new fields.** Set Keep Alive Interval to e.g. `45`, clear Clean Session,
   save, and publish again. It must still succeed. Setting Automatic Reconnect changes little by
   design (README caveat); it is exposed because it is a legitimate `MqttConnectOptions` setting.

---

## 4. Divergences from the brief

Nothing in the brief turned out to be impossible. Three points, all carried over from the plan's
Part E and all unchanged by the implementation:

1. **`cleanSession` null → `true` is implemented as specified, but is nearly unreachable at design
   time** because the SDK seeds a Boolean to `false`. A brand-new object renders Clean Session
   unchecked; the rule fires when a configuration carries no value at all. The alternative —
   seeding `true` with `setValue` — would restore the 1.0.3 mechanism the brief forbids, so the
   README states the effective default honestly instead.
2. **Test count is 13**, the top of the brief's "roughly 10–13". The brief listed five new tests;
   "optional fields left blank must not error" is folded into the ungated-probe test as additional
   assertions on the other seven paths. Same coverage.
3. **`publish(...)` now takes twelve positional parameters.** That is the honest cost of "no adapter
   or factory indirection" plus four new settings. Passing a prebuilt `MqttConnectOptions` into
   `publish` would cut it to seven but would move options construction into the integration and
   spread the Paho surface across two classes — the opposite of the invariant that exactly one class
   references Paho. The testable logic lives in `buildConnectOptions` instead, and every parameter is
   documented with its effective default.

**Fallback if the probe breaks rendering outright** rather than merely displaying or discarding
messages: drop the `ConfigValidator.validate` call from `getConfiguration`. That one-line revert
returns the template to 1.0.4 behaviour while keeping the four new fields.

---

## Deploy-and-test checklist

Final user-facing report for release **1.0.5**. Review verdict: **APPROVED**, no findings
(`.agents/tasks/validation-1.0.5-review.json`); one non-blocking wording observation about the
`autoReconnectEnabled` "no thread outlives the request" claim, recorded in the review and requiring
no code change.

### 1. What changed in 1.0.5

Two additions on top of the deployed-and-working 1.0.4 baseline (commit `7c9e12b`). First, a
**deliberately ungated design-time validation probe**: the connected system template now validates
on every render, including the very first, so a brand-new object opens with errors already attached
to Broker Host and Broker Port. That is not a UX choice — it is the experiment this release exists
to run, because nobody has yet observed whether Appian displays connected system field errors at
all. Second, **four newly wired configuration fields** — `connectionTimeoutSeconds`,
`keepAliveSeconds`, `cleanSession`, `autoReconnectEnabled` — taking the connected system from five
fields to **nine**. All four genuinely reach `MqttConnectOptions`; none is inert. TLS, certificates,
subscribe, Last Will and Testament, reconnect attempt tuning and diagnostics remain removed.

### 2. Validator shape, hook, and error clearing

- **Shape:** one class, `de.hock.mqtt.validation.ConfigValidator` — `public final`, private
  constructor, single entry point `public static void validate(PropertyState rootState)`, private
  bound constants and two private helpers. The 1.0.3 four-class framework was not restored; five
  rules do not need it.
- **Hook:** inside the existing `getConfiguration` override, called **after** `setProperties(...)`.
  There is no other hook: `ConnectedSystemTemplate` declares only
  `getConfigurationDescriptor`, and `SimpleConnectedSystemTemplate` routes everything through
  `getConfiguration`. The ordering is required because `setErrorsAtPath` silently no-ops on an
  unresolved path, and the nested per-field states do not exist until `setProperties` has generated
  them.
- **Error clearing:** `PropertyState.clearErrorsAtPath(PropertyPath)` **does exist** on
  `connected-systems-core:1.9.1` — confirmed with `javap` against the real jar — and bottoms out in
  `setErrorsAtPath(path, Collections.emptyList())`. `validate` calls it for all four validated keys
  (`brokerHost`, `brokerPort`, `connectionTimeoutSeconds`, `keepAliveSeconds`) before evaluating
  anything, then re-attaches only through `setErrorAtPath`. `addErrors` is never used after a clear,
  because a cleared path holds an immutable empty list and `addErrors` would throw
  `UnsupportedOperationException`. No fallback was needed. Clearing is load-bearing, not defensive:
  commenting the four clear calls out failed 3 of the 4 validation tests.

### 3. Verification summary

- **Build:** `mvn -o clean package` → BUILD SUCCESS, producing
  `target/mqtt-broker-connected-system-1.0.5-plugin.jar`.
- **Tests:** 13, all passing, in four classes — 2 `ProjectStructureTest`,
  5 `MqttConnectedSystemTemplateDescriptorTest`, 4 `MqttConnectedSystemTemplateValidationTest`,
  2 `MqttPublisherConnectOptionsTest`.
- **`isExpressionable`, per file:** `MqttConnectedSystemTemplate.java` → **0**; repo-wide → **3**;
  the single non-zero line is `integration/MqttPublishIntegration.java:3`, which is the deliberate
  asymmetry (integration inputs may and must be expressionable). The stronger proof is the test that
  reads every modifier back off the rendered descriptor across all nine fields; it was verified to
  fail when `.isExpressionable(true)` was temporarily added to `cleanSession`.
- **Sources inside the jar:** **5** — `package-info.java`, `MqttPublisher.java`,
  `MqttConnectedSystemTemplate.java`, `MqttPublishIntegration.java`, `ConfigValidator.java`; **0**
  outside `de/hock/mqtt/`.
- **Resource bundle:** **16** keys, 12 of them field labels (nine config + three publish inputs);
  orphan/missing diff empty.
- **Packaging invariants (a)–(l):** all green — one jar, bare-root `appian-plugin.xml`, exactly one
  nested jar and it is Paho under `META-INF/lib/`, zero bundled Appian SDK artifacts, zero
  `com/appian/mqtt` entries, zero test and zero `.backup` entries, `THIRD-PARTY-NOTICES.md` at the
  root, `<version>1.0.5</version>` three-part and `min="26.9.0.0"` four-part, vendor `Hock`, plug-in
  key and both module keys unchanged, bytecode major version 61 on all four classes.
- **Not simply green — expected vs actual, stated explicitly:**
  - Code-policy grep: expected previously recorded as "no hits", **actual 1 hit** —
    `MqttPublisher.java:29`, a Javadoc sentence asserting the *absence* of a `System.setProperty`
    call. Prose, not a call; pre-existing at 1.0.4. The steering §4 expectation was corrected to
    match, so any *other* hit is a real violation.
  - Jar on disk: expected exactly one jar named `mqtt-broker-connected-system-1.0.5-plugin.jar` —
    **confirmed independently**, 246829 bytes, dated Oct 27 16:59, and it is the only jar in
    `target/`.
  - Git: expected uncommitted working-tree changes and no new commit — **confirmed independently**.
    `HEAD` is still `7c9e12b` on `main`; 11 modified files plus 7 untracked paths, nothing staged.
  - Three things could **not** be verified offline: whether Appian displays the design-time errors,
    whether a nine-field descriptor renders on the platform, and whether a real broker honours the
    four new values end-to-end.

### 4. Judged impossible as specified, and the alternative taken

Nothing in the brief was impossible. Three points where the specified behaviour is technically
implemented but reaches less far than it reads:

1. **`cleanSession` null → `true`** is implemented exactly as asked, but the SDK seeds a Boolean
   property to `false`, so a brand-new object renders Clean Session **unchecked** and the rule only
   fires for a configuration carrying no value at all (for example one saved by 1.0.4). The
   alternative — seeding `true` via `setValue` — would restore the mechanism the brief forbids, so
   the README states the effective default honestly instead.
2. **Test count is 13**, the top of the brief's "roughly 10–13". "Optional fields left blank must
   not error" is folded into the ungated-probe test as assertions on the other seven paths rather
   than shipped as a fourteenth test. Same coverage.
3. **`publish(...)` now takes twelve positional parameters.** Passing a prebuilt
   `MqttConnectOptions` in would cut it to seven but would spread the Paho surface across two
   classes, breaking the invariant that exactly one class references Paho — which is what makes a
   `NoClassDefFoundError` diagnostic. The testable logic lives in `buildConnectOptions` instead.

### 5. Deploy-and-test checklist

**Step 3 is the point of this release.**

1. Deploy `/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component/target/mqtt-broker-connected-system-1.0.5-plugin.jar`
   (copy into `APPIAN_INSTALL/_admin/plugins/`) and confirm **Admin Console → Plug-ins shows
   1.0.5**. If it still shows 1.0.4, nothing below is meaningful — stop and check
   `tomcat-stdOut.log` for `Successfully installed Plug-in`.
2. Open **New > Connected System > MQTT Broker** and confirm **nine** labelled fields render: Broker
   Host, Broker Port, Client Identifier, Username, Password, Connection Timeout, Keep Alive
   Interval, Clean Session, Automatic Reconnect.
3. **THE KEY OBSERVATION.** On that brand-new, untouched page: do validation errors appear under
   **Broker Host** and **Broker Port**?
   - **YES** → Appian **does** display connected system errors set via
     `PropertyState.setErrorAtPath`, and the restrictions page's `setErrors` warning does not cover
     that path. Design-time validation on a connected system is viable; 1.0.6 may gate it for a
     cleaner first open.
   - **NO** → Appian discards them. Design-time validation on a connected system is impossible, and
     validation belongs on the integration inputs instead.

   Either answer is a result. **Record which one you saw** in steering §5 item 7.
4. If errors appeared: enter a valid host and port and confirm the errors **clear** (this exercises
   the `clearErrorsAtPath` fix); then enter port `99999` and confirm the port error returns **on the
   Broker Port field alone**, with nothing on Broker Host and nothing at the top of the form.
5. Fill in a working broker, save, and publish through the existing **Publish MQTT Message**
   integration to confirm nothing in the runtime path regressed (`success: true`, status
   `dispatched` for QoS 0 or `delivered` for QoS 1/2).
6. Optionally set Keep Alive Interval (e.g. `45`) and clear Clean Session, save, and confirm a
   publish still succeeds.

### 6. Status

Final confirmation requires **you deploying that jar** — the one observation this release was built
for cannot be made offline. All 1.0.5 changes sit **uncommitted in the working tree** (11 modified
files, 7 untracked paths, nothing staged, `HEAD` still `7c9e12b`); committing is your call.
