# Implementation Plan — 1.0.5: design-time validation probe + four wired config fields

Target: Appian 26.9, Java 17. Baseline: **1.0.4 / commit `7c9e12b`**, deployed and confirmed working
on a live instance 2026-10-27. Nothing in this plan may regress that.

Scope: exactly two additions. (1) design-time validation, **deliberately ungated**, shaped as a
probe for whether Appian displays connected system field errors at all. (2) four connected system
configuration fields — `connectionTimeoutSeconds`, `keepAliveSeconds`, `cleanSession`,
`autoReconnectEnabled` — each genuinely reaching `MqttConnectOptions`. Bringing the connected
system to **nine** fields.

---

## Part A — Resolved questions, with evidence

Everything in this section was established before the plan was written, by `javap` against the real
jars in `~/.m2/repository` and by a throwaway program compiled and run in `/tmp/sdkprobe` (outside
the workspace — nothing in the repo was touched). The implementer does not need to re-derive any of
it; re-run `/tmp/sdkprobe/Probe.java` if it needs confirming.

### A1. The error-clearing API exists: `PropertyState.clearErrorsAtPath(PropertyPath)`

`javap -cp connected-systems-core-1.9.1.jar
com.appian.connectedsystems.templateframework.sdk.configuration.PropertyState` — real output,
error-related members only:

```
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

Bytecode semantics, from `javap -p -c`:

- `clearErrorsAtPath(path)` → `setErrorsAtPath(path, Collections.emptyList())`.
- `setErrorsAtPath(path, list)` → `setErrorsAtPath0`, which resolves
  `getPropertyStateAtPath(path)` and, **if non-null**, assigns the field `errors = list` —
  a replace, not a merge. If the path resolves to `null` it silently does nothing.
- `setErrorAtPath(path, msg)` builds a fresh `ArrayList` holding `msg` and calls
  `setErrorsAtPath`, so it also replaces.
- `SimpleConfiguration` offers `setErrors(String key, List<String>)` which delegates to
  `PropertyState.setErrorsAtPath`, but **no clear method**. So the clearing API only exists on
  `PropertyState`.

**Decision: the implementation calls `PropertyState.clearErrorsAtPath(new PropertyPath(key))` for
every validated key at the start of each validation pass, then `setErrorAtPath(new
PropertyPath(key), message)` for whatever still fails.**

Two consequences that must be honoured, both measured:

1. **Clearing is genuinely required** — errors survive a re-render. `StateGenerator.clone()`
   copies them (`new PropertyState().setType(...).addErrors(old.getErrors()).setValue(...)`) and
   `generateFromExistingState` carries the root-level list across too, so
   `SimpleConfiguration.setProperties` preserves every error already in the incoming state. The
   probe confirmed it: an error attached on render 1 was still present on render 2 **after the
   value had been corrected**:

   ```
   --- attach errors, then re-render that descriptor (simulating round-trip) ---
     errors SURVIVED the round trip? (proves clearing is required)
     brokerHost value=broker.example.test [String] errors=[Broker host is required]
     brokerPort value=1883 [Integer] errors=[Broker port must be between 1 and 65535]
   ```

   That is the 1.0.3 sticky-error bug, reproduced.

2. **Never call `addErrors` on a path that was just cleared.** `clearErrorsAtPath` installs the
   immutable `Collections.emptyList()`, so `addErrors` throws:

   ```
   --- clearErrorsAtPath then setErrorAtPath on the cleared path ---
     brokerHost value=broker.example.test [String] errors=[]
     brokerPort value=1883 [Integer] errors=[]
     brokerPort value=1883 [Integer] errors=[re-attached after clear]
     addErrors after clear THREW java.lang.UnsupportedOperationException -> use setErrorAtPath, not addErrors
     root-level errors = []
   ```

   `setErrorAtPath` after a clear is safe (it replaces with a fresh `ArrayList`). Use only
   `setErrorAtPath`.

Ordering constraint: `setErrorsAtPath0` no-ops when the path does not resolve, so the whole
validation pass must run **after** `configuration.setProperties(...)` has generated the per-field
nested states.

### A2. Validator shape: one class, `de.hock.mqtt.validation.ConfigValidator`

Chosen over inlining in the template. Reasons: it keeps the bound constants and the four message
strings out of `MqttConnectedSystemTemplate`, which stays a descriptor declaration; it is directly
unit-testable against a bare `PropertyState` as well as through the render path; and it is **one**
class with one public entry point, not the 1.0.3 four-class framework (`ConfigValidator` +
`InputValidator` + `ValidationResult` + `ValidationResults`, plus `ConfigFields`, `LwtValidator`,
`ReconnectionValidator`) which existed to express a much larger rule set. Cost: the in-jar source
count rises 4 → 5. Accepted.

Shape: `public final class ConfigValidator`, private constructor, one public static method
`public static void validate(PropertyState rootState)`, private bound constants, private helpers.
No instance state, no result objects, no builder.

### A3. Validation hook: inside `getConfiguration`, which is the only hook there is

`javap -cp core com.appian.connectedsystems.templateframework.sdk.ConnectedSystemTemplate`:

```
public interface ...ConnectedSystemTemplate {
  public abstract ...ConfigurationDescriptor getConfigurationDescriptor(
      ...ConfigurationDescriptor, ...PropertyPath, ...ExecutionContext);
}
```

One method. There is **no** `validate` hook on the interface, and
`javap -p -c ...SimpleConnectedSystemTemplate` shows its `getConfigurationDescriptor` is exactly
three steps with nothing else to override:

```
SimpleConfiguration.from(descriptor, typePropertyFactory, executionContext)
→ getConfiguration(configuration, executionContext)     // our abstract override
→ SimpleConfiguration.toConfiguration()
```

So validation goes in the existing
`protected SimpleConfiguration getConfiguration(SimpleConfiguration, ExecutionContext)` override,
after `setProperties`, operating on `configuration.toConfiguration().getRootState()`
(`toConfiguration()` just returns the held descriptor, and the root state object is the live one, so
mutating it mutates what the platform receives).

**This runs on the very first render of a brand-new connected system — the probe is genuinely
ungated.** Confirmed: Appian hands in a descriptor whose `getRootState()` is `null`;
`SimpleConfiguration.from` tolerates it, `setProperties` generates the root state and all nine
nested states, and `getConfiguration` then validates them. Nothing distinguishes render 1 from
render N, which is the whole point.

No first-render gate, no freshness signal, no `setValue` seeding. The 1.0.3 gate read `cleanSession`
to infer freshness and rested on an unverified round-trip assumption; it is not coming back.

### A4. `MqttPublisher` testability refactor, and the real default values

Paho facts, from `javap -p -constants -cp org.eclipse.paho.client.mqttv3-1.2.5.jar
org.eclipse.paho.client.mqttv3.MqttConnectOptions`:

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

- **Paho's own default keep-alive interval is 60 seconds** (`KEEP_ALIVE_INTERVAL_DEFAULT`).
- **The existing hardcoded connect timeout that becomes the fallback is 5 seconds**
  (`MqttPublisher.CONNECT_TIMEOUT_SECONDS = 5`, deliberately short so an unreachable broker does not
  hang an Appian request thread). It keeps that value and its Javadoc rationale.
- All four setters have matching getters, so a built `MqttConnectOptions` is fully observable
  without a broker.
- `setKeepAliveInterval` throws `IllegalArgumentException` only for a negative argument.

**Refactor:** extract a package-visible static
`static MqttConnectOptions buildConnectOptions(String username, char[] password,
Integer connectionTimeoutSeconds, Integer keepAliveSeconds, Boolean cleanSession,
Boolean autoReconnectEnabled)` in `de.hock.mqtt.MqttPublisher`, holding everything `publish`
currently does between `new MqttConnectOptions()` and the client construction. `publish` calls it
and gains the four boxed parameters. The test class lives in package `de.hock.mqtt`, so
package-visible is enough. No adapter, no factory class, no config record — one extra static method
on the class that already owns the Paho surface.

**Runtime default interpretation (null means default; no descriptor state is ever seeded):**

| Field | Null/absent → | Out of range → | How |
|---|---|---|---|
| `connectionTimeoutSeconds` | `5` (`CONNECT_TIMEOUT_SECONDS`) | `5` | `setConnectionTimeout(5)` |
| `keepAliveSeconds` | Paho's own `60` | Paho's own `60` | **do not call the setter** |
| `cleanSession` | `true` | n/a | `setCleanSession(true)` |
| `autoReconnectEnabled` | `false` | n/a | `setAutomaticReconnect(false)` |

`buildConnectOptions` is total — it never throws. An out-of-range integer falls back to the default
rather than failing the publish, because design-time validation may turn out to be invisible (that
is what we are probing) and a bad keep-alive is not worth refusing a message over. A bad host or
port still fails, through the integration's existing run-time guards, which are unchanged.

### A5. The SDK seeds field defaults — two consequences, one of them a doc correction

`StateGenerator.getDefaultState` → `generateDefaultValue(typeRef)` → `SystemType.getDefault()`, and
the enum's static initialiser (read from bytecode) gives: `STRING` → `""`, `ENCRYPTED` → `""`,
`PARAGRAPH` → `""`, `BOOLEAN` → `Boolean.FALSE`, `INTEGER` → `null`, `DOUBLE` → `null`. The probe's
first render confirms it:

```
--- FIRST RENDER (empty descriptor, rootState==null) ---
incoming rootState = null
  brokerHost value= [String] errors=[]
  brokerPort value=null errors=[]
  keepAliveSeconds value=null errors=[]
  cleanSession value=false [Boolean] errors=[]
  autoReconnectEnabled value=false [Boolean] errors=[]
  password value= [String] errors=[]
  descriptor expressionable flags:
    brokerHost -> false (TextPropertyDescriptor)
    brokerPort -> false (IntegerPropertyDescriptor)
    keepAliveSeconds -> false (IntegerPropertyDescriptor)
    cleanSession -> false (BooleanPropertyDescriptor)
    autoReconnectEnabled -> false (BooleanPropertyDescriptor)
    password -> false (EncryptedTextPropertyDescriptor)
```

Consequences:

1. **The probe will fire.** `brokerHost` arrives as `""` (blank, not null) and `brokerPort` as
   `null`, so both required rules reject on the very first render. Good — the observation the
   release exists for is reachable.
2. **`BooleanPropertyDescriptor` is non-expressionable by default**, like Text/Integer/Encrypted.
   The four new fields are safe on a connected system; no builder forces the flag.
3. **Doc correction, flagged in Part E.** Because `BOOLEAN` seeds `Boolean.FALSE`, a brand-new
   connected system shows **Clean Session unchecked**. The brief's "`cleanSession` null → true"
   rule is implemented as specified and is correct — but it only *fires* when the value is genuinely
   absent, i.e. when an existing 1.0.4-era connected system (saved before the field existed) is read
   at run time. The README must say that honestly rather than claiming the designer sees a default
   of true.

### A6. `maxReconnectAttempts` / `reconnectIntervalSeconds` stay out — evidence

The complete public setter list of `MqttConnectOptions` 1.2.5 contains no attempt-count setter and no
reconnect-interval setter. The only related member is `setMaxReconnectDelay(int)`, a cap on Paho's
internal exponential backoff — neither an attempt count nor a fixed interval. Paho manages reconnect
backoff itself. The brief's exclusion is correct and is now evidence-backed; do not add either field.

---

## Part B — Exact figures the implementation must hit

| Figure | 1.0.4 (verified) | 1.0.5 (target) |
|---|---|---|
| `<version>` in `pom.xml` and `appian-plugin.xml` | `1.0.4` | **`1.0.5`** |
| Submission artifact | `...-1.0.4-plugin.jar` | **`target/mqtt-broker-connected-system-1.0.5-plugin.jar`** |
| `.java` sources in jar | 4 | **5** |
| Connected system fields | 5 | **9** |
| Publish integration inputs | 3 | 3 (unchanged) |
| Resource bundle keys | 12 (8 labels) | **16 (12 labels)** |
| Tests | 7 in 2 classes | **13 in 4 classes** |
| `grep -rn isExpressionable src/main/java/ \| wc -l` | 3 | **3** (unchanged) |
| …on the template | 0 | **0** |
| `grep -rh 'import com.appian.connectedsystems' src/main/java/ \| wc -l` | 22 | **~25 — report actual** |
| `grep -rl 'com\.appian\.connectedsystems' src/main/java \| wc -l` | 2 | **3** |
| `<application-version min=...>` | `26.9.0.0` | `26.9.0.0` (do not touch) |
| Vendor | `Hock` / `https://itondemand.com` | unchanged |
| Nested jars under `META-INF/lib/` | 1 (Paho) | 1 (Paho) |
| Bytecode major version | 61 | 61 |

**The five in-jar sources:** `de/hock/mqtt/package-info.java`, `de/hock/mqtt/MqttPublisher.java`,
`de/hock/mqtt/template/MqttConnectedSystemTemplate.java`,
`de/hock/mqtt/integration/MqttPublishIntegration.java`, and the new
`de/hock/mqtt/validation/ConfigValidator.java`. No `validation/package-info.java` — `template/` and
`integration/` do not have one either, so adding one would break the project's own convention and
push the count to 6 for no benefit.

**The four new bundle keys** (16 total):
`MqttConnectedSystemTemplate.connectionTimeoutSeconds.label`,
`MqttConnectedSystemTemplate.keepAliveSeconds.label`,
`MqttConnectedSystemTemplate.cleanSession.label`,
`MqttConnectedSystemTemplate.autoReconnectEnabled.label`.

**The nine fields, in the order the template must declare them** (the existing five keep their
positions; the four are appended, so required fields stay at the top):

| # | Key | Descriptor | Required | Effective default | Validation |
|---|---|---|---|---|---|
| 1 | `brokerHost` | Text | yes | — | non-blank after trim |
| 2 | `brokerPort` | Integer | yes | — | 1–65535 |
| 3 | `clientIdentifier` | Text | no | Paho `generateClientId()` | none |
| 4 | `username` | Text | no | anonymous | none |
| 5 | `password` | EncryptedText, masked | no | anonymous | none |
| 6 | `connectionTimeoutSeconds` | Integer | no | 5 s | if supplied, 1–300 |
| 7 | `keepAliveSeconds` | Integer | no | 60 s (Paho) | if supplied, 0–65535 |
| 8 | `cleanSession` | Boolean | no | unchecked; `true` when absent | none |
| 9 | `autoReconnectEnabled` | Boolean | no | `false` | none |

Bounds 1–300 and 0–65535 are carried over from the archived 1.0.3 `ConfigValidator` (`TIMEOUT_MIN/
MAX`, `KEEP_ALIVE_MIN/MAX`), so they are already documented values rather than new invention.
`keepAliveSeconds = 0` disables MQTT keep-alive and must be accepted.

**Messages** (short, field-scoped, each attached to its own path, never the root):
`Broker host is required.` / `Broker port is required.` /
`Broker port must be between 1 and 65535.` /
`Connection timeout must be between 1 and 300 seconds.` /
`Keep alive interval must be between 0 and 65535 seconds.`

**The 13 tests:**

`ProjectStructureTest` (2, unchanged) — `pluginDescriptorIsOnClasspath`,
`defaultResourceBundleIsOnClasspath`.

`MqttConnectedSystemTemplateDescriptorTest` (5, kept, counts updated) —
`brandNewConnectedSystemRendersANonNullRootStateWithAResolvableRootType`,
`exactlyTheNineExpectedConfigFieldKeysArePresent` (renamed from `…FiveExpected…`),
`noConnectedSystemPropertyDescriptorIsExpressionable` (`inspected == 9`),
`connectedSystemFieldsUseNoListDocumentOrAppianValueDescriptor`,
`publishIntegrationRendersItsRootStateAndItsThreeExpressionableInputs`.

`MqttConnectedSystemTemplateValidationTest` (4, new) —
`brandNewConnectedSystemReportsRequiredErrorsOnBrokerHostAndBrokerPortOnly`,
`validBrokerHostAndPortLeaveThoseFieldsFreeOfErrors`,
`outOfRangeBrokerPortIsReportedOnTheBrokerPortPathNotTheRoot`,
`aCorrectedValueClearsThePreviouslyAttachedError`.

`MqttPublisherConnectOptionsTest` (2, new) — `configuredSessionValuesReachMqttConnectOptions`,
`absentOrOutOfRangeSessionValuesFallBackToTheDocumentedDefaults`.

"Optional fields left blank must not error" is covered inside
`brandNewConnectedSystemReportsRequiredErrorsOnBrokerHostAndBrokerPortOnly` (it asserts the other
seven field paths and the root carry no errors) rather than as a fourteenth test, which keeps the
total inside the brief's 10–13.

---

## Part C — Ordered implementation plan

Each item leaves the tree buildable. Items 1–3 are the code; 4–6 the tests; 7–11 the docs; 12 the
verification gate.

- [ ] 1. **Add `de.hock.mqtt.validation.ConfigValidator`** — one `public final class`, private
      constructor, one public entry point
      `public static void validate(PropertyState rootState)`. It must: (a) return immediately if
      `rootState == null`; (b) `clearErrorsAtPath(new PropertyPath(key))` for **all four validated
      keys** (`brokerHost`, `brokerPort`, `connectionTimeoutSeconds`, `keepAliveSeconds`) before
      evaluating anything; (c) re-attach with `setErrorAtPath` only. Never `addErrors` (A1.2).
      Private constants `PORT_MIN=1`, `PORT_MAX=65535`, `TIMEOUT_MIN=1`, `TIMEOUT_MAX=300`,
      `KEEP_ALIVE_MIN=0`, `KEEP_ALIVE_MAX=65535`. Reads values through the template's
      `textValue`/`integerValue` helpers (item 2). Optional integer fields: a `null` value is
      accepted silently; a non-null value outside its bounds is reported.
      Javadoc must record: the deliberate ungated probe and its rationale (one unambiguous
      observation; a gate would make a clean page ambiguous between "gate worked" and "Appian
      discards our errors"), the clear-then-attach contract and why `addErrors` is forbidden after a
      clear, and that errors survive `setProperties` because `StateGenerator.clone` copies them.
      **The Javadoc must not contain the literal token `isExpressionable`** — the repo-wide grep of
      exactly 3 depends on it.
      Files: `src/main/java/de/hock/mqtt/validation/ConfigValidator.java` (new)
      Verify: `mvn -o clean compile` succeeds (tests arrive in item 4).

- [ ] 2. **Extend `MqttConnectedSystemTemplate` to nine fields and call the validator.** Add four
      `public static final String` key constants (`CONNECTION_TIMEOUT_SECONDS`, `KEEP_ALIVE_SECONDS`,
      `CLEAN_SESSION`, `AUTO_RECONNECT_ENABLED`) and the four descriptors in positions 6–9 per the
      Part B table — two `IntegerPropertyDescriptor`, two `BooleanPropertyDescriptor`, all
      `isRequired(false)`, each with `instructionText` naming its effective default. Add
      `public static Integer integerValue(PropertyState, String)` (null when unset/blank/unparseable)
      and `public static Boolean booleanValue(PropertyState, String)` (null when unset; tolerant of
      `Boolean` and of `"true"`/`"false"` text). Keep `textValue` and `intValue` as they are — the
      integration still uses `intValue` for `qos` and `brokerPort`.
      After `setProperties(...)`, call
      `ConfigValidator.validate(configuration.toConfiguration().getRootState())` and return.
      **Never call `isExpressionable` here** and **never add another `public static final String`
      constant** — the §4 bundle check greps this file for exactly that shape, so any extra one
      becomes a phantom expected label key. Rewrite the class Javadoc: replace the "No design-time
      validation" paragraph with the ungated-probe rationale; update the five-field prose to nine and
      the descriptor-type list to include `BooleanPropertyDescriptor`; keep avoiding the literal
      token `isExpressionable` in prose.
      Files: `src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java`
      Verify: `mvn -o clean compile` succeeds; `grep -c isExpressionable` on this file returns `0`.

- [ ] 3. **Wire the four values into Paho.** In `MqttPublisher`, extract
      `static MqttConnectOptions buildConnectOptions(String username, char[] password, Integer
      connectionTimeoutSeconds, Integer keepAliveSeconds, Boolean cleanSession, Boolean
      autoReconnectEnabled)` implementing the Part A4 default table exactly — `setConnectionTimeout`
      with the configured value or `CONNECT_TIMEOUT_SECONDS` (5), `setKeepAliveInterval` **only**
      when the value is non-null and within 0–65535, `setCleanSession(cleanSession == null ? true :
      cleanSession)`, `setAutomaticReconnect(autoReconnectEnabled == null ? false :
      autoReconnectEnabled)`, plus the existing both-or-neither credential handling. The method
      never throws. Widen `publish(...)` with the same four boxed parameters, delegate to
      `buildConnectOptions`, and change nothing else — `MemoryPersistence`, `setTimeToWait`, and the
      disconnect-and-close `finally` block all stay byte-for-byte equivalent in behaviour. In
      `MqttPublishIntegration.execute`, read the four values off the connected system state with the
      new helpers and pass them through; leave the four existing run-time guards and the response
      shape untouched. Javadoc every new parameter with its effective default, and state in the
      `autoReconnectEnabled` Javadoc that the connect→publish→disconnect lifecycle gives it almost
      no room to act and that it must not spawn a thread outliving the request.
      Files: `src/main/java/de/hock/mqtt/MqttPublisher.java`,
      `src/main/java/de/hock/mqtt/integration/MqttPublishIntegration.java`
      Verify: `mvn -o clean compile` succeeds; no `System.out`/`System.err`/`printStackTrace`/
      `System.setProperty` introduced (`grep -rn 'System\.\(out\|err\|setProperty\)\|printStackTrace'
      src/main/java/` returns nothing).

- [ ] 4. **Update `MqttConnectedSystemTemplateDescriptorTest` for nine fields.** Extend
      `CONFIG_FIELD_KEYS` to the nine keys in declaration order, rename
      `exactlyTheFiveExpectedConfigFieldKeysArePresent` →
      `exactlyTheNineExpectedConfigFieldKeysArePresent` and its message, change `assertEquals(5,
      inspected, …)` to `assertEquals(9, inspected, …)` in
      `noConnectedSystemPropertyDescriptorIsExpressionable`. The expressionability test must keep
      reading `PropertyDescriptor.isExpressionable()` back off the rendered descriptor — never a
      source grep. `connectedSystemFieldsUseNoListDocumentOrAppianValueDescriptor` iterates all
      properties so it covers the new fields with no edit; leave it alone. Do not touch
      `brandNewConnectedSystemRendersANonNullRootStateWithAResolvableRootType` or the publish test.
      Files: `src/test/java/de/hock/mqtt/template/MqttConnectedSystemTemplateDescriptorTest.java`
      Verify: `mvn -o test` — 9 tests run, all pass.

- [ ] 5. **Add `MqttConnectedSystemTemplateValidationTest`** (4 tests, package
      `de.hock.mqtt.template`). All four drive the real framework entry point
      `getConfigurationDescriptor(ConfigurationDescriptor.builder().build(), new PropertyPath(),
      null)` and read errors back with `PropertyState.getErrorsAtPath`. Re-render by mutating the
      *returned* descriptor's root state (`setValueAtPath`) and feeding that descriptor back in —
      the sequence `/tmp/sdkprobe/Probe.java` exercises.
      1. `brandNewConnectedSystemReportsRequiredErrorsOnBrokerHostAndBrokerPortOnly` — pins the
         ungated probe: non-empty errors on `brokerHost` and `brokerPort`, empty on the other seven
         paths and on the root. Javadoc must say plainly that this test exists to stop anyone
         "fixing" the deliberate behaviour.
      2. `validBrokerHostAndPortLeaveThoseFieldsFreeOfErrors` — valid host and `1883`, no errors on
         either path.
      3. `outOfRangeBrokerPortIsReportedOnTheBrokerPortPathNotTheRoot` — port `99999` with a valid
         host: `brokerPort` errors non-empty, `brokerHost` errors empty, `getErrors()` on the root
         empty.
      4. `aCorrectedValueClearsThePreviouslyAttachedError` — render once (errors present), correct
         both values, re-render, assert both paths are clean. This is the 1.0.3 sticky-error
         regression; it fails if the `clearErrorsAtPath` loop is dropped.
      Files: `src/test/java/de/hock/mqtt/template/MqttConnectedSystemTemplateValidationTest.java`
      (new)
      Verify: `mvn -o test` — 13 tests run (11 after item 5 if item 6 is not yet in; final total 13),
      all pass.

- [ ] 6. **Add `MqttPublisherConnectOptionsTest`** (2 tests, package `de.hock.mqtt` so
      `buildConnectOptions` is reachable). No broker, no network.
      1. `configuredSessionValuesReachMqttConnectOptions` — pass `17`, `45`, `Boolean.FALSE`,
         `Boolean.TRUE` and assert `getConnectionTimeout()==17`, `getKeepAliveInterval()==45`,
         `isCleanSession()==false`, `isAutomaticReconnect()==true`. This is the no-inert-field proof.
      2. `absentOrOutOfRangeSessionValuesFallBackToTheDocumentedDefaults` — all four `null` →
         `getConnectionTimeout()==MqttPublisher.CONNECT_TIMEOUT_SECONDS` (5),
         `getKeepAliveInterval()==MqttConnectOptions.KEEP_ALIVE_INTERVAL_DEFAULT` (60),
         `isCleanSession()==true`, `isAutomaticReconnect()==false`; and a second pass with
         `connectionTimeoutSeconds=0` and `keepAliveSeconds=-1` falling back to the same 5 and 60
         without throwing.
      Files: `src/test/java/de/hock/mqtt/MqttPublisherConnectOptionsTest.java` (new)
      Verify: `mvn -o test` — **Tests run: 13, Failures: 0, Errors: 0**.

- [ ] 7. **Bump the version in both places.** `pom.xml` `<version>1.0.4</version>` → `1.0.5`, and
      `appian-plugin.xml` `<plugin-info><version>1.0.4</version>` → `1.0.5`. Leave
      `<application-version min="26.9.0.0"/>` alone — different field, four-part by design. Leave
      the plug-in key `de.hock.mqtt` and both module keys (`MqttConnectedSystemTemplate`,
      `MqttPublishIntegration`) untouched so 1.0.5 replaces 1.0.4 instead of installing beside it.
      Update the `appian-plugin.xml` comment's "five-field broker configuration" to nine. No change
      to `src/assembly/plugin-assembly.xml` — its `fileSet` over `src/main/java` already picks up the
      new source file, and the `dependencySet` already targets `META-INF/lib`.
      Files: `pom.xml`, `src/main/resources/appian-plugin.xml`
      Verify: `mvn -o clean package` produces exactly one jar, named
      `target/mqtt-broker-connected-system-1.0.5-plugin.jar`.

- [ ] 8. **Add the four resource-bundle keys** and refresh the file header (16 keys, nine connected
      system field keys, "1.0.4" → "1.0.5"). Labels: `Connection Timeout`, `Keep Alive Interval`,
      `Clean Session`, `Automatic Reconnect` — these must match the `label(...)` strings in the
      template exactly. Also refresh
      `MqttConnectedSystemTemplate.description` so it mentions session settings.
      Files: `src/main/resources/resources_en_US.properties`
      Verify: the §4 orphan/missing diff (command block in item 12) prints nothing.

- [ ] 9. **Update `README.md`.** Replace the five-field table with the nine-field table from Part B,
      including key, label, type, required, effective default and validation rule per field. Rewrite
      the "There is no design-time validation in this build" section into a clearly-worded note that
      validation runs on **every** render including the first, that this is deliberate for 1.0.5 as a
      probe for whether Appian displays connected system errors at all, and that it will be refined
      in 1.0.6 once the answer is known. Document the `autoReconnectEnabled` caveat plainly (it only
      affects the brief life of one publish call, and spawns nothing that outlives the request), and
      the `cleanSession` honesty note from A5.3 (new object renders unchecked because the SDK seeds a
      Boolean to `false`; `true` applies only when the value is absent entirely). Move
      `connectionTimeoutSeconds`, `keepAliveSeconds`, `cleanSession`, `autoReconnectEnabled` and
      "Design-time validation" **out** of "What was deliberately removed", and keep
      `maxReconnectAttempts` / `reconnectIntervalSeconds` in it with the A6 evidence for why they are
      not coming back. Update: version `1.0.4` → `1.0.5` throughout, artifact name, "four `.java`
      sources" → five, "renders five fields" → nine in the deploy checks, and the test count 7 → 13
      with the new class names. Also drop the two now-stale "Known limitations" bullets claiming the
      `META-INF/lib/` layout and the `connected-systems-client` server classpath are unverified —
      the 1.0.4 deploy settled both. Keep it a minimal-build README: no TLS, subscribe, LWT or
      diagnostics claims.
      Files: `README.md`
      Verify: manual read-through; no remaining `1.0.4` reference except where it is deliberately
      historical (`grep -n '1\.0\.4' README.md`).

- [ ] 10. **Update `.kiro/steering/appian-appmarket-compliance.md`,** keeping every §1 hard
      constraint intact. Scope note: 1.0.4 → 1.0.5, five fields → nine (naming the four additions),
      and design-time validation is now **present as an ungated probe** rather than removed.
      §1: sources 4 → 5 with the new path listed; import count `22 across 2 files` → the actual
      (expect ~25 across 3 files); version table `1.0.4` → `1.0.5`; the "five surviving connected
      system fields are Text, Integer and EncryptedText only" sentence → nine fields using Text,
      Integer, Boolean and EncryptedText, with `BooleanPropertyDescriptor` added to the safe row of
      the forced-flag table and a note that it was measured non-expressionable; add the warning that
      `ConfigValidator`'s Javadoc must also avoid the literal token `isExpressionable` so the
      repo-wide count stays 3.
      §2: artifact name → `1.0.5-plugin.jar`.
      §4: rewrite the command block to the Part B figures — `Tests run: 13`, `J=…1.0.5-plugin.jar`,
      `.java` count `5`, `<version>1.0.5</version>`, bundle comment `16 keys, 12 of them field
      labels`, and add a `javap … de.hock.mqtt.validation.ConfigValidator | grep 'major version'` →
      `61` line. The three expressionability greps keep their expected values (`0`, `3`, the single
      line `…/integration/MqttPublishIntegration.java:3`); extend the caveat under the bundle sed to
      note it holds only while the **nine** config keys are the only `public static final String`
      constants on the template.
      §5 item 2: amend the "Superseded at 1.0.4" paragraph — `cleanSession` returns as a field at
      1.0.5 but the first-render gate and the `setValue` seed deliberately do **not**, and the
      round-trip assumption is no longer needed at all because `StateGenerator` seeds defaults
      itself (`BOOLEAN`→`false`, `INTEGER`→`null`, `STRING`→`""`, measured).
      §5 item 4: 7 tests in 2 classes → 13 in 4, with the new names and "config keys covered: 9".
      §5 item 7: **rewrite.** 1.0.5 probes the question ungated. State the settling observation
      verbatim: open `New > Connected System > MQTT Broker` on a fresh object — a visible
      "Broker host is required." means Appian **does** render errors set via `setErrorAtPath` and the
      restrictions page's `setErrors` warning does not cover that path; no message means Appian
      discards them, design-time validation on a connected system is impossible, and validation
      belongs on the integration inputs. Record which was observed.
      §5 item 8: **rewrite.** No longer "resolved by removal" — resolved by implementation.
      `clearErrorsAtPath(PropertyPath)` exists on `PropertyState` (signature in A1), is called for
      every validated path before re-attaching, and is regression-tested by
      `aCorrectedValueClearsThePreviouslyAttachedError`. Add the measured facts that errors survive
      `setProperties` (because `StateGenerator.clone` copies them) and that `addErrors` after
      `clearErrorsAtPath` throws `UnsupportedOperationException`.
      §8: add the new record file to the audit trail list.
      Files: `.kiro/steering/appian-appmarket-compliance.md`
      Verify: every §4 command in the file, run verbatim, produces the stated expected value
      (item 12).

- [ ] 11. **Write the release record and fix the guide banner.** New
      `.agents/tasks/validation-and-config-fields-1.0.5.md`: what changed and why, the four new
      fields with their wiring and effective defaults, the ungated-probe decision with its rationale,
      the error-clearing fix with the `javap` evidence, the SDK-seeded-defaults finding, pasted
      verification output, and the deploy-and-test checklist from Part D. Keep it a record, not a
      second README.
      `docs/mqtt-plugin-guide.md`: surgical banner edit only — `1.0.4` → `1.0.5`, "five fields" →
      nine with the four names added, and replace "design-time validation [is] not present" with a
      sentence saying 1.0.5 *does* carry design-time validation but **ungated**, which directly
      contradicts the gated first-render behaviour the guide body describes at lines 110–112. Leave
      the rest of the guide as the explicitly-labelled pre-reduction history it already is.
      Files: `.agents/tasks/validation-and-config-fields-1.0.5.md` (new),
      `docs/mqtt-plugin-guide.md`
      Verify: `grep -n '1\.0\.4\|five fields' docs/mqtt-plugin-guide.md` shows no stale claim in the
      banner.

- [ ] 12. **Run the full verification gate and paste real output.** Nothing is done until every line
      below has been run and its actual value compared against the expected one.
      Files: none (read-only)
      Verify: the block below, in full.

```bash
cd /Users/andreas.hock/Documents/Appian-Projects/MQTT_Component
mvn -o clean package                             # BUILD SUCCESS
mvn -o test                                      # Tests run: 13, Failures: 0, Errors: 0
J=target/mqtt-broker-connected-system-1.0.5-plugin.jar

ls target/*.jar | wc -l                          # 1   (g)
unzip -l $J | grep -c '\.java$'                  # 5   (b)
unzip -l $J | grep '\.java$' | grep -vc 'de/hock/mqtt/'            # 0   (b)
unzip -l $J | awk '{print $NF}' | grep -c '^appian-plugin.xml$'    # 1   (c) bare root
unzip -l $J | grep -c 'META-INF/lib/.*\.jar$'    # 1   (d)
unzip -l $J | grep -c 'META-INF/lib/org.eclipse.paho'              # 1   (d)
unzip -l $J | grep -ci 'connected-systems'       # 0   (d)
unzip -l $J | grep -c 'com/appian/mqtt'          # 0   (a)
unzip -l $J | grep -ci test                      # 0   (k)
unzip -l $J | grep -ci 'backup'                  # 0   (k)
unzip -l $J | awk '{print $NF}' | grep -c '^THIRD-PARTY-NOTICES.md$'   # 1   (j)
unzip -p $J appian-plugin.xml | grep -E '<version>|application-version|<vendor'
#   <version>1.0.5</version>  (e) / min="26.9.0.0"  (e) / vendor name="Hock"  (f)
unzip -p $J appian-plugin.xml | grep -c 'key="de.hock.mqtt"'                   # 1   (a)(l)
unzip -p $J appian-plugin.xml | grep -cE 'key="Mqtt(ConnectedSystemTemplate|PublishIntegration)"'  # 2  (l)
for c in de.hock.mqtt.template.MqttConnectedSystemTemplate de.hock.mqtt.MqttPublisher \
         de.hock.mqtt.validation.ConfigValidator de.hock.mqtt.integration.MqttPublishIntegration; do
  javap -verbose -cp target/classes $c | grep 'major version'; done   # 61 x4   (h)

# Namespace trap (a)
grep -rn 'com\.appian\.mqtt' . | grep -v '/target/' | grep -v '/.agents/' | grep -v '/.kiro/'  # no hits
grep -rh 'import com.appian.connectedsystems' src/main/java/ | wc -l   # report actual (~25)
grep -rl 'com\.appian\.connectedsystems' src/main/java | wc -l         # 3

# Expressionability: grep is necessary, the test is what proves it.
grep -c isExpressionable src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java  # 0
grep -rn isExpressionable src/main/java/ | wc -l                                               # 3
grep -rc isExpressionable src/main/java/ | grep -v ':0$'
#   exactly one line: .../integration/MqttPublishIntegration.java:3

# Code policies
grep -rn 'System\.out\|System\.err\|printStackTrace\|System\.setProperty\|java\.util\.logging' src/main/java/  # no hits

# Resource bundle: 16 keys, no orphans, no missing.  (i)
B=src/main/resources/resources_en_US.properties
sed -nE 's/^([A-Za-z][A-Za-z.]*)=.*/\1/p' $B | sort > /tmp/bundle-actual.txt
wc -l < /tmp/bundle-actual.txt                   # 16
{ echo MqttConnectedSystemTemplate.name; echo MqttConnectedSystemTemplate.description
  sed -nE 's/.*public static final String [A-Z_]+ *= *"([A-Za-z]+)".*/MqttConnectedSystemTemplate.\1.label/p' \
    src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java
  echo MqttPublishIntegration.name; echo MqttPublishIntegration.description
  sed -nE 's/.*public static final String INPUT_[A-Z_]+ *= *"([A-Za-z]+)".*/MqttPublishIntegration.\1.label/p' \
    src/main/java/de/hock/mqtt/integration/MqttPublishIntegration.java
} | sort > /tmp/bundle-expected.txt
diff /tmp/bundle-expected.txt /tmp/bundle-actual.txt   # no output

# Archived full build untouched and out of the jar.
shasum -a 256 .backup/mqtt-plugin-FULL-1.0.3-src.zip
#   386f3cab7ab660e19325f2825071152338eb48855e6f45b8d9665afcc22f89b4
```

Then state plainly: **final confirmation requires the user deploying
`target/mqtt-broker-connected-system-1.0.5-plugin.jar`** — everything above is offline evidence.

---

## Part D — Deploy-and-test checklist for the user

Step 3 is the point of this release.

1. **Deploy and confirm the version.** Copy
   `target/mqtt-broker-connected-system-1.0.5-plugin.jar` into
   `APPIAN_INSTALL/_admin/plugins/`. Check **Admin Console → Plug-ins** shows **1.0.5**. If it still
   shows 1.0.4, the plug-in did not replace the old one and nothing below means anything — stop and
   check `tomcat-stdOut.log` for `Successfully installed Plug-in` and the
   `ConnectedSystemTemplateModuleParser` lines.
2. **Open `New > Connected System > MQTT Broker`.** Confirm **nine** labelled fields render: Broker
   Host, Broker Port, Client Identifier, Username, Password, Connection Timeout, Keep Alive
   Interval, Clean Session, Automatic Reconnect. If the page fails to render at all, 1.0.5 has hit a
   fourth platform guard — report the exact message and diff against commit `7c9e12b`.
3. **THE KEY OBSERVATION. On that brand-new, untouched page, does a validation error appear under
   Broker Host and Broker Port?**
   - **YES** → Appian **does** display connected system field errors set through
     `PropertyState.setErrorAtPath`, and the restrictions page's `setErrors` warning does not cover
     that path. Design-time validation on a connected system is viable; 1.0.6 can gate it for a
     cleaner first-open experience.
   - **NO** → Appian discards them. Design-time validation on a connected system is impossible, and
     validation belongs on the integration inputs instead.
   Either answer closes a question open since 1.0.0. **Record which one you saw.**
4. **If errors appeared, test clearing.** Enter a valid host and `1883`. The two errors must
   disappear — that exercises the `clearErrorsAtPath` fix for the 1.0.3 sticky-error bug. Then enter
   port `99999`: the port error must come back **on the Broker Port field alone**, with nothing on
   Broker Host and nothing at the top of the form.
5. **Confirm the runtime path did not regress.** Fill in a reachable broker, save, and publish
   through the existing **Publish MQTT Message** integration. Success response should be
   `success: true` with `status` `dispatched` (QoS 0) or `delivered` (QoS 1/2).
6. **Optional — exercise the new fields.** Set Keep Alive Interval to e.g. `45`, clear Clean
   Session, save, and publish again. It must still succeed. Setting Automatic Reconnect changes
   little by design (see the README caveat); it is exposed because it is a legitimate
   `MqttConnectOptions` setting.

---

## Part E — Divergences from the brief, and the smallest alternatives

Nothing in the brief is impossible. Three points need stating.

1. **`cleanSession` null → true is implementable but nearly unreachable at design time.** The SDK
   seeds a `BOOLEAN` property to `Boolean.FALSE` (`SystemType` static initialiser; confirmed by the
   probe), so a brand-new connected system renders Clean Session **unchecked**, not checked. The
   null→true rule is implemented exactly as specified and is the right behaviour — it fires when a
   connected system saved by 1.0.4, which had no such field, is read at run time. **Alternative
   rejected:** seeding `true` with `setValue` would restore precisely the 1.0.3 mechanism the brief
   forbids. **Action:** implement as specified; state the effective default honestly in the README
   (plan item 9) rather than claiming the designer sees `true`.

2. **Test count lands at 13, the top of the brief's "roughly 10–13".** The brief lists five new
   tests; "optional fields left blank must not error" is folded into
   `brandNewConnectedSystemReportsRequiredErrorsOnBrokerHostAndBrokerPortOnly` as additional
   assertions on the other seven paths, rather than becoming a fourteenth test. Same coverage,
   inside the stated range.

3. **`publish(...)` grows to twelve positional parameters.** That is the honest cost of "no adapter
   or factory indirection" plus four new settings. **Alternative considered and rejected:** passing
   a prebuilt `MqttConnectOptions` into `publish` would cut it to seven, but it moves options
   construction into the integration and spreads the Paho surface across two classes — the opposite
   of the §1 invariant that exactly one class references Paho, which is what makes a
   `NoClassDefFoundError` diagnostic. **Action:** keep one `publish` entry point, Javadoc every
   parameter with its effective default, and put the testable logic in `buildConnectOptions`.

Also worth flagging, not a divergence: attaching errors on the first render is new platform
behaviour for this plug-in. If it turns out to break rendering outright rather than just displaying
or discarding messages, the fallback is to drop the `ConfigValidator.validate` call from
`getConfiguration` — a one-line revert that returns the template to 1.0.4 behaviour while keeping
the four new fields. Commit `7c9e12b` is the known-good reference to diff against.
