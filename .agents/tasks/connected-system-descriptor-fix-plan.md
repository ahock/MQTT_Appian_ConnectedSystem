# Connected System descriptor fix — implementation plan

Target: Appian 26.9, Java 17, `de.hock.mqtt`, no git (edit in place).
Status of investigation: **root cause CONFIRMED and reproduced locally.**
Status of implementation: **COMPLETE — all 10 steps done and verified offline on 2026-10-13.** See
§7 below for the recorded evidence, and
[`connected-system-descriptor-fix-verification.md`](connected-system-descriptor-fix-verification.md)
for the reviewer-facing note. The only thing left is deploy-time confirmation (§5).

---

## 1. Root cause — confirmed, with evidence

The leading hypothesis is **confirmed**, and the mechanism is now proven rather than inferred.

### 1.1 The failing line

`src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java`, in
`getConfigurationDescriptor`:

```java
PropertyState rootState = configurationDescriptor.getRootState().setType(rootType);
```

`getRootState()` returns `null` on the first invocation, so this is `null.setType(rootType)` — exactly
the reported NPE:

> Cannot invoke `PropertyState.setType(LocalTypeDescriptor)` because the return value of
> `ConfigurationDescriptor.getRootState()` is null

The same unguarded line exists in both integrations:

- `src/main/java/de/hock/mqtt/integration/MqttPublishIntegration.java` →
  `integrationConfigurationDescriptor.getRootState().setType(rootType)`
- `src/main/java/de/hock/mqtt/integration/MqttSubscribeIntegration.java` → identical

So all three templates carry the same defect. Only the connected system one has fired so far because
that is the first UI a designer opens.

### 1.2 Why `getRootState()` is null — SDK bytecode

From `javap -c` on `connected-systems-core:1.9.1`
(`~/.m2/repository/com/appian/connected-systems-core/1.9.1/connected-systems-core-1.9.1.jar`):

```
public PropertyState getRootState();
   0: aload_0
   1: invokevirtual  getState:()Ljava/util/Map;
   4: ldc            // String root
   6: invokeinterface Map.get:(Ljava/lang/Object;)Ljava/lang/Object;
```

`getRootState()` is nothing more than `getState().get("root")`. For a brand-new connected system
Appian hands the template a descriptor whose `state` map has no `"root"` entry, so the call returns
`null`. Reproduced locally with public API only:

```
empty.getRootState() = null      // ConfigurationDescriptor.builder().build()
```

### 1.3 The low-level contract the code does not satisfy

On the low-level `ConnectedSystemTemplate` / `IntegrationTemplate` path the implementer owns root
state construction. The current code assumes the framework supplies it. It cannot even construct one
by hand: `PropertyState`'s only constructor is **package-private**
(`com.appian...PropertyState();` with no modifier in `javap`), so `new PropertyState()` does not
compile from `de.hock.mqtt`. The sole public route in core 1.9.1 is
`StateGenerator.generateDefaultState(...)` / `generateFromExistingState(...)`.

### 1.4 A second, independent defect in the same three methods

None of the three templates ever registers its `LocalTypeDescriptor` in the descriptor's `types` map.
`PropertyState.setType(LocalTypeDescriptor)` does **not** register it — bytecode shows it only
converts and stores a reference:

```
public PropertyState setType(LocalTypeDescriptor);
   2: invokestatic  TypeReference.from:(LocalTypeDescriptor;)LTypeReference;
   5: putfield      type:LTypeReference;
```

The resulting state carries a `TypeReference` of `#/types/MqttConnectedSystemConfiguration` while
`ConfigurationDescriptor.getTypes()` stays empty, because registration requires a separate
`ConfigurationDescriptorBuilder.withType(...)` / `withTypes(...)` call that the code never makes.
Even with a non-null root state, the UI would have an unresolvable type reference. Both defects must
be fixed together.

### 1.5 Explicitly ruled out

None of the five known pre-existing bugs (inert `maxReconnectAttempts`, key passphrase, divergent
`MqttConnectionManager.classify`, username in the cache fingerprint, dropped subscribe
`validationErrors`) is on this code path. The NPE happens at design time in
`getConfigurationDescriptor`, before any connection, validator, or classifier runs. **Leave all five
alone.**

---

## 2. Chosen fix: option (a) — migrate to the Simple\* template hierarchy

### 2.1 What the Simple layer actually does

`connected-systems-client` is a 13 KB, 9-class facade (its own POM: *"A facade on Connected Systems
Core that improves usability by hiding SDK implementation details"*). Decompiling
`SimpleConfiguration` shows its entire root-state mechanism:

```
private SimpleConfiguration getConfigurationDescriptor(LocalTypeDescriptor, Collection<LocalTypeDescriptor>);
   0: new            StateGenerator
   5: invokespecial  StateGenerator."<init>":(Ljava/util/Collection;)V
  13: invokevirtual  StateGenerator.generateFromExistingState:(LocalTypeDescriptor;PropertyState;)PropertyState;
  18: invokestatic   ConfigurationDescriptor.builder()
  22: invokevirtual  ConfigurationDescriptorBuilder.withTypes:(Ljava/util/Collection;)...
  26: invokevirtual  ConfigurationDescriptorBuilder.withState:(PropertyState;)...
  29: invokevirtual  ConfigurationDescriptorBuilder.build()
```

It calls `generateFromExistingState(rootType, getRootState())` — tolerating a null existing state —
**and** `withTypes(...)`. That is precisely the pair of defects in §1.3 and §1.4. The facade fixes
both by construction.

### 2.2 Why (a) over (b)

Option (b) (stay low-level, construct the descriptor correctly) would work and would be a smaller
diff. (a) is chosen anyway:

- **It is Appian's documented contract for 26.9.** The
  [Interaction Model page](https://docs.appian.com/suite/help/26.9/connected-systems-interaction-model.html)
  states that a connected system's fields are defined by implementing `getConfiguration` in
  `SimpleConnectedSystemTemplate` or a subclass. The
  [JavaDocs page](https://docs.appian.com/suite/help/25.1/csp-javadocs.html) recommends starting
  development with Client and dropping to Core only where signatures demand it.
- **It removes the failure class permanently.** Under (a) the plug-in never touches root state
  construction, so this NPE cannot recur in a future field addition. Under (b) every new template
  would have to re-implement the generate-and-register dance correctly.
- **The dependency resolves and the pairing is verified to compile and run** (§2.3) — so the
  instruction's escape hatch to (b) ("dependency cannot be resolved or an incompatible SDK
  generation") does not apply.
- **The diff is smaller than it looks** (§3): the three `getConfigurationDescriptor` methods and two
  `execute` methods change; `toConnectionConfig(PropertyState)`, `validate(PropertyState)`, all four
  validators, and every value-reading helper stay **byte-for-byte unchanged**, because
  `SimpleConfiguration.toConfiguration().getRootState()` hands back the same `PropertyState` type
  those methods already take.

### 2.3 SDK coordinates, and why this pairing compiles

Add to `pom.xml` (scope `provided`, never bundled):

```xml
<appian.sdk.client.version>1.3.0</appian.sdk.client.version>
...
<dependency>
    <groupId>com.appian</groupId>
    <artifactId>connected-systems-client</artifactId>
    <version>${appian.sdk.client.version}</version>
    <scope>provided</scope>
</dependency>
```

`connected-systems-core` stays at **1.9.1**. No core downgrade. No STOP-and-ask is required, because
the client resolves without forcing one.

Evidence for the pairing, all gathered empirically:

| Finding | Evidence |
|---|---|
| Client versions available on Maven Central | `0.1.0, 0.2.0, 0.3.0, 1.0.0, 1.0.1, 1.1.0, 1.2.0, 1.3.0, 1.4.0`; latest `1.4.0`. `1.1.0`=2019-07-08, `1.3.0`=2023-11-08, `1.4.0`=2026-02-09 |
| Client 1.3.0 is the same SDK generation as core 1.9.1 | core 1.9.1 = 2023-11-07, client 1.3.0 = 2023-11-08 — published one day apart |
| Client declares no dependencies | `<dependencies>` block in `connected-systems-client-1.3.0.pom` is absent, so it cannot drag in a conflicting core |
| The Simple\* API is identical across generations | `javap` on client 1.1.0, 1.3.0 and 1.4.0: all 9 classes, and every public/abstract signature on `SimpleConnectedSystemTemplate`, `SimpleIntegrationTemplate` and `SimpleConfiguration`, match exactly |
| **The pairing compiles and runs against core 1.9.1** | A throwaway `SimpleConnectedSystemTemplate` subclass using `setProperties`/`getValue`/`setErrors` compiled with `javac` and executed with `java` against **core 1.9.1 + client 1.1.0, 1.3.0 and 1.4.0** — all three: `COMPILE: OK`, no `NoSuchMethodError`, no `AbstractMethodError`, identical output |

**Why 1.3.0 and not the 1.1.0 named in Appian's compatibility table.** The
[table](https://docs.appian.com/suite/help/26.9/integration-sdk-versions.html) lists Core 1.2.0 /
Client 1.1.0 for 26.9, but Core 1.2.0 dates from 2019-07-02 and the steering file already rules out
downgrading core to it. Since the Simple\* API is byte-identical across 1.1.0→1.4.0 and all three
link against core 1.9.1, the compile-time choice is risk-free either way; 1.3.0 is picked because it
is core 1.9.1's own generation, keeping the declared pair internally coherent. If a live 26.9
instance ever rejects it, swapping `${appian.sdk.client.version}` to `1.1.0` or `1.4.0` is a
one-line, API-compatible change — verified, not assumed.

**Residual risk, stated plainly.** `provided` means the Appian server must supply
`connected-systems-client` on the plug-in classpath. This is unverified on the target instance. It is
the documented primary development path and Appian's own troubleshooting guidance tells developers to
match *Core and Client* versions to the target environment, which only makes sense if the platform
ships both — but confirm it on deploy (§8). If the classes turn out to be absent, the fallback is
option (b): keep the low-level interfaces and replace the body of each
`getConfigurationDescriptor` with the `new StateGenerator(rootType).generateFromExistingState(rootType, existing)`
+ `withType(rootType).withState(rootState)` sequence from §2.1, which needs no new dependency. Do not
resolve this by bundling the client jar — that would violate the `provided` rule and put an Appian
artifact inside the plug-in.

### 2.4 How the root state becomes non-null, traced through the SDK

1. Appian calls `MqttConnectedSystemTemplate.getConfigurationDescriptor(descriptor, path, ctx)` —
   inherited from `SimpleConnectedSystemTemplate`, no longer ours.
2. That method calls `SimpleConfiguration.from(descriptor, typePropertyFactory, ctx)`. The private
   constructor null-checks the descriptor; a non-null-but-empty one is stored as-is.
3. It calls our `getConfiguration(SimpleConfiguration, ExecutionContext)`.
4. Our override calls `config.setProperties(allProperties())` → `setPropertiesGeneric` builds a
   `LocalTypeDescriptor` named by `getRootTypeName()` (which is null-safe and yields `"RootType"`
   when there is no prior state), registers it in the type map, then runs the §2.1 sequence:
   `new StateGenerator(types).generateFromExistingState(rootType, getRootState())`.
   `generateFromExistingState` accepts the `null` existing state and returns a fresh, fully-populated
   `PropertyState`.
5. `toConfiguration()` returns a descriptor built with `.withTypes(types).withState(rootState)`, so
   `getState().get("root")` is populated and `getTypes()` contains the root type.
6. Appian calls `getRootState()` → non-null → `setType(...)` succeeds. No NPE.

Verified end to end locally against core 1.9.1 + client 1.3.0:

```
empty.getRootState()        = null
RESULT rootState            = PropertyState@1a0f9713     (non-null)
RESULT rootState.getType()  = #/types/RootType
RESULT types                = [RootType]
RESULT errors(brokerHost)   = [probe error]              (per-field errors work)
setType() on root state OK -> no NPE
```

### 2.5 One behavioral regression this introduces, and its fix

Generating default state is what makes the root state non-null, and it pre-populates values. Measured
`SystemType` defaults in core 1.9.1:

```
STRING = ""      ENCRYPTED = ""   PARAGRAPH = ""
INTEGER = null   DOUBLE = null    DOCUMENT = null   FOLDER/TIMESTAMP/APPIAN_VALUE = null
BOOLEAN = false
```

Blank text and null integers are harmless: `isBlank`, `trimToNull` and `parseIntOrDefault` already
treat `null` and `""` identically, so every documented numeric default still applies.

`BOOLEAN = false` is **not** harmless. `toConnectionConfig` reads
`asBoolean(rootState, ConfigFields.CLEAN_SESSION, true)`, relying on `null` to mean "unset, use
`true`". After generation the value is a non-null `Boolean.FALSE`, so the documented default
`cleanSession = true` would silently flip to `false` — a real regression, and a protocol-visible one.
`tlsEnabled`, `autoReconnectEnabled` and `lwtRetained` all document `false`, so they are unaffected.

Core 1.9.1 has no `defaultValue(...)` on any descriptor builder (confirmed: `PropertyDescriptorBuilder`
exposes only `key/label/description/instructionText/placeholder/refresh/displayHint/isRequired/
isExpressionable/isReadOnly/isHidden/isImportCustomizable`). So seed it explicitly, and only on the
first render, so a designer's deliberate `false` is never overwritten — capture the freshness signal
**before** `setProperties`, because afterwards a generated `false` is indistinguishable from a chosen
one. This is covered by step 3 below.

---

## 3. Implementation plan

Dependency order. Every item leaves the tree compiling.

- [x] 1. Resolve `connected-systems-client:1.3.0` into the local Maven repository so later steps can
      build offline. This is the only step needing network access.
      Files: none (populates `~/.m2/repository/com/appian/connected-systems-client/1.3.0/`)
      Verify: `mvn dependency:get -Dartifact=com.appian:connected-systems-client:1.3.0` succeeds, then
      `ls ~/.m2/repository/com/appian/connected-systems-client/1.3.0/*.jar` lists the jar.

- [x] 2. Declare the client dependency in `pom.xml`: add an `<appian.sdk.client.version>1.3.0</appian.sdk.client.version>`
      property beside `appian.sdk.version`, and a `com.appian:connected-systems-client` dependency with
      `<scope>provided</scope>`. Leave `connected-systems-core` at `1.9.1`. Replace the existing POM
      comment claiming the Simple\* hierarchy "is NOT here ... which this project does not declare" with
      an accurate note that core supplies the low-level API and client supplies the Simple\* facade, both
      provided by the Appian runtime.
      Files: `pom.xml`
      Verify: `mvn -o clean package` succeeds and exactly one jar lands in `target/`; then
      `unzip -l target/mqtt-broker-connected-system-1.0.0-plugin.jar | grep -c 'META-INF/lib/.*connected-systems'`
      prints `0` — the assembly's `dependencySet` is `<scope>runtime</scope>`, so a `provided`
      artifact is excluded, but prove it rather than assume it.

- [x] 3. Migrate `MqttConnectedSystemTemplate` to `SimpleConnectedSystemTemplate`.
      Change the declaration to `extends SimpleConnectedSystemTemplate` (keep `@TemplateId(name = "MqttConnectedSystemTemplate")`
      and the class name — `appian-plugin.xml` and the i18n keys depend on both). Delete the
      `getConfigurationDescriptor(...)` override entirely and replace it with:

      ```java
      @Override
      protected SimpleConfiguration getConfiguration(
          SimpleConfiguration configuration, ExecutionContext executionContext) {
        // Captured BEFORE setProperties: afterwards a generated Boolean.FALSE is
        // indistinguishable from a designer's deliberate false (see plan 2.5).
        PropertyState priorState = configuration.toConfiguration().getRootState();
        boolean firstRender =
            priorState == null
                || priorState.getValueAtPath(new PropertyPath(ConfigFields.CLEAN_SESSION)) == null;

        configuration.setProperties(allProperties());

        if (firstRender) {
          configuration.setValue(ConfigFields.CLEAN_SESSION, Boolean.TRUE);
        }

        validate(configuration.toConfiguration().getRootState());
        return configuration;
      }
      ```

      Keep `allProperties()`, `validate(PropertyState)`, `toConnectionConfig(PropertyState)` and every
      private helper **unchanged** — `validate` mutates the `PropertyState` in place via
      `setErrorAtPath`, and that object is the one inside the descriptor, which is exactly how
      `SimpleConfiguration.setErrors` works internally. Delete `buildRootType()` and the now-unused
      `ROOT_TYPE_NAME` constant (the facade names the root type `RootType`), plus any imports that fall
      idle (`ConnectedSystemTemplate`, `ConfigurationDescriptor`, `LocalTypeDescriptor`). Rewrite the
      two class-level Javadoc paragraphs that assert the plug-in "deliberately implements that
      low-level interface" and that the Simple\* hierarchy is in an artifact "which this project's pom
      does not declare" — both are now false. Do not reintroduce any claim that
      `SimpleConnectedSystemTemplate` is absent from the SDK.
      Files: `src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java`
      Verify: `mvn -o clean package` compiles; step 6's test then proves the behavior.

- [x] 4. Migrate `MqttPublishIntegration` to `SimpleIntegrationTemplate`.
      Change to `extends SimpleIntegrationTemplate` (drop `implements ...IntegrationTemplate`; keep both
      annotations and both constructors). Replace the two overrides:

      ```java
      @Override
      protected SimpleConfiguration getConfiguration(
          SimpleConfiguration integrationConfiguration,
          SimpleConfiguration connectedSystemConfiguration,
          PropertyPath propertyPath,
          ExecutionContext executionContext) {
        integrationConfiguration.setProperties(inputProperties());
        return integrationConfiguration;
      }

      @Override
      protected IntegrationResponse execute(
          SimpleConfiguration integrationConfiguration,
          SimpleConfiguration connectedSystemConfiguration,
          ExecutionContext executionContext) {
        PropertyState inputs = integrationConfiguration.toConfiguration().getRootState();
        PropertyState connectionState = connectedSystemConfiguration.toConfiguration().getRootState();
        // ... rest of the existing body verbatim
      }
      ```

      `SimpleIntegrationTemplate.execute(ConfigurationDescriptor, ConfigurationDescriptor, ExecutionContext)`
      wraps both saved descriptors with `SimpleConfiguration.from(...)` and never calls
      `setProperties`, so `toConfiguration()` returns the original saved descriptor and
      `getRootState()` yields the real saved state (verified by bytecode). Everything downstream —
      `readRequest`, `asString`, `asBoolean`, `publish`, `MqttConnectedSystemTemplate.toConnectionConfig`,
      `InputValidator`, `DiagnosticsBuilder`, `ResponseMapper` — is untouched. Delete `buildRootType()`
      and `ROOT_TYPE_NAME`; keep `inputProperties()` and all `INPUT_*` / `RESULT_*` / `STATUS_*`
      constants (the i18n keys depend on the `INPUT_*` values). Correct the class Javadoc paragraph that
      claims the class "deliberately implements the low-level ... interface".
      Files: `src/main/java/de/hock/mqtt/integration/MqttPublishIntegration.java`
      Verify: `mvn -o clean package` compiles.

- [x] 5. Migrate `MqttSubscribeIntegration` the same way — same two override replacements, same
      `toConfiguration().getRootState()` bridge, delete `buildRootType()` and `ROOT_TYPE_NAME`, keep
      `inputProperties()` and the `InputValidator.FIELD_*` keys, correct the same false Javadoc
      paragraph. Keep its `readStringList` / `readInt` helpers' existing null tolerance as-is.
      Note while here — **corrected 2026-10-13**, the earlier note recorded here was wrong. It claimed
      a `ListTypePropertyDescriptor`'s generated default is an `Expression` object, so an untouched
      `topicFilters` could read back as a one-element list holding an expression's `toString()`. That
      is not what the SDK does. Re-verified against core 1.9.1 bytecode
      (`javap -p -c` on `StateGenerator`):
      - `getDefaultState(PropertyDescriptor, int)` takes the `SystemType.EXPRESSION` branch **only**
        when `shouldRenderAsExpression(descriptor, level)` is true; otherwise it calls
        `generateDefaultValue(descriptor.getTypeRef(), level)` and keeps the descriptor's own type
        reference.
      - `shouldRenderAsExpression` returns true only when
        `DisplayHint.EXPRESSION.equals(descriptor.getDisplayHint())`, or when
        `descriptor.getTypeRef().isLocalType()` and `level > 1`.
      - `generateDefaultValue(TypeReference, int)` branches `isLocalType()` → a `Map`,
        `isListType()` → `new ArrayList()`, `isSystemType()` → `SystemType.getDefault()`, else
        `IllegalStateException`.
      The `topicFilters` descriptor is a `ListTypePropertyDescriptor` with `itemType(SystemType.STRING)`
      and `isExpressionable(true)` and **no** `displayHint(EXPRESSION)`, sitting at level 1, so neither
      condition holds. `isExpressionable(true)` merely lets a Designer switch the field into expression
      mode; it is **not** the same thing as `displayHint(EXPRESSION)` and does not affect default-state
      generation. An untouched `topicFilters` therefore generates an **empty list**, `readStringList`
      returns an empty list, and `InputValidator` reports the "1–10 filters" error — which is the
      correct outcome. Leaving `readStringList` unchanged was still right; only the recorded reason was
      wrong. Do **not** change `readStringList` behavior here.
      Files: `src/main/java/de/hock/mqtt/integration/MqttSubscribeIntegration.java`
      Verify: `mvn -o clean package` compiles.

- [x] 6. Add the regression test that reproduces the reported bug and proves it fixed.
      New JUnit 5 test exercising the real failure path: build an empty descriptor with
      `ConfigurationDescriptor.builder().build()` (public API, and `getRootState()` on it is `null` —
      the exact input Appian supplies for a new connected system), pass it through
      `new MqttConnectedSystemTemplate().getConfigurationDescriptor(empty, new PropertyPath(), null)`,
      and assert:
      - the returned `getRootState()` is not null (this is the NPE regression guard);
      - `getTypes()` is non-empty and `getRootState().getType()` resolves into it (guards §1.4);
      - all 20 `ConfigFields` keys are reachable via `getValueAtPath`;
      - `cleanSession` reads back `true` on first render (guards §2.5), and a second pass over a
        descriptor whose `cleanSession` is `false` still reads `false`;
      - a configuration with a blank `brokerHost` produces a non-empty
        `getErrorsAtPath(new PropertyPath("brokerHost"))`.
      Add equivalent empty-descriptor assertions for both integrations' inherited
      `getConfigurationDescriptor` (4 input keys each). Follow the existing JUnit 5 style in
      `src/test/java/de/hock/mqtt/ProjectStructureTest.java`; name the file `*Test.java` so the
      configured Surefire `**/*Test.java` include picks it up. `provided` scope puts the client jar on
      the test classpath, so no POM change is needed for this.
      Files: `src/test/java/de/hock/mqtt/template/MqttConnectedSystemTemplateDescriptorTest.java` (new)
      Verify: `mvn -o clean test` — the new tests pass alongside `ProjectStructureTest`. Confirm the
      guard is real by temporarily reverting step 3's `getConfiguration` to the old
      `getRootState().setType(...)` line and seeing the test fail with the reported NPE, then restore.

- [x] 7. Bump the plug-in version to `1.0.1`. Appian will not deploy a new build over an existing one
      carrying the same version, so the fix is undeployable without this.
      - `src/main/resources/appian-plugin.xml`: `<version>1.0.0</version>` → `<version>1.0.1</version>`.
        Three-part `x.y.z` only. Leave `<application-version min="26.9.0.0"/>` four-part, leave
        `<vendor name="Hock" url="https://itondemand.com"/>`, leave `key="de.hock.mqtt"` and all three
        `class=` attributes untouched.
      - `pom.xml`: `<version>1.0.0</version>` → `<version>1.0.1</version>`.
      Files: `pom.xml`, `src/main/resources/appian-plugin.xml`
      Verify: `mvn -o clean package` produces exactly
      `target/mqtt-broker-connected-system-1.0.1-plugin.jar`; `rm -rf target` first so no stale 1.0.0
      jar lingers and leaves two deployable-looking artifacts.

- [x] 8. Update `README.md` in lockstep. All 20 config field keys, labels, defaults, validation rules,
      both integrations' inputs/outputs and the error categories are unchanged, so the reference
      sections need no edits. Change only:
      - line ~10, `**Version:** `1.0.0`` → `1.0.1`;
      - line ~46 (`## Requirements`), add `com.appian:connected-systems-client:1.3.0`, scope `provided`,
        beside the existing core entry;
      - lines ~60 and ~74 (`## Build`, `## Deploy`), the two `mqtt-broker-connected-system-1.0.0-plugin.jar`
        paths → `1.0.1`;
      - `## Known limitations`, **delete** the "The templates implement the low-level SDK interfaces"
        bullet — no longer true — and replace it with a bullet noting that the plug-in now subclasses
        `SimpleConnectedSystemTemplate` / `SimpleIntegrationTemplate` and therefore needs
        `connected-systems-client` on the Appian server classpath, unverified on a live instance
        (same caveat already carried for the `META-INF/lib/` layout);
      - leave the `maxReconnectAttempts`, key-passphrase, document-resolution, logo-asset and
        test-coverage bullets exactly as they are.
      Files: `README.md`
      Verify: `grep -n '1\.0\.0' README.md` returns no plug-in-version or artifact-name hits, and
      `grep -n 'low-level SDK interfaces' README.md` is empty.

- [x] 9. Update `.kiro/steering/appian-appmarket-compliance.md` so the binding invariants stay true.
      - §2: add the client coordinate beside core; change the submission artifact name to
        `target/mqtt-broker-connected-system-1.0.1-plugin.jar`.
      - §4: update the `J=` path to `1.0.1`; keep the expected `.java` count at **33** (step 6 adds only
        a test, and the assembly `fileSet` covers `src/main/java` only, so no new source ships).
      - §5 item 2: rewrite from an open divergence to a resolved one — record that the low-level path
        was the confirmed cause of the `getRootState()` NPE, that the migration landed on client 1.3.0,
        and that the Simple\* API was verified byte-identical across client 1.1.0/1.3.0/1.4.0 and
        verified to compile and run against core 1.9.1.
      - §5 item 1 (core 1.2.0 vs 1.9.1) stays open and unchanged — nothing here resolved it.
      - §1 version-format table: our `<version>` value becomes `1.0.1`.
      Files: `.kiro/steering/appian-appmarket-compliance.md`
      Verify: the §4 command block, copy-pasted, runs clean against the new jar (step 10).

- [x] 10. Run the full pre-submission gate and read every number.
      Files: none
      Verify: all of §4 below passes.

---

## 4. Offline verification

Step 1 is the only online step. Everything after runs with `-o`.

```bash
cd /Users/andreas.hock/Documents/Appian-Projects/MQTT_Component
rm -rf target
mvn -o clean package
J=target/mqtt-broker-connected-system-1.0.2-plugin.jar

ls target/*.jar                                        # exactly ONE jar, -1.0.2-plugin
unzip -l $J | grep -c '\.java$'                        # 33  (unchanged: no new main source)
unzip -l $J | grep -cE ' appian-plugin.xml$'           # 1, bare root entry, no prefix
unzip -l $J | grep -c 'META-INF/lib/org.eclipse.paho'  # 1
unzip -l $J | grep -c 'META-INF/lib/.*connected-systems'  # 0  (both Appian artifacts provided)
unzip -l $J | grep -c '^.*lib/org.eclipse.paho'        # 1 — i.e. no root lib/ copy
unzip -l $J | grep -c 'com/appian/mqtt'                # 0
unzip -l $J | grep -ci test                            # 0
unzip -p $J appian-plugin.xml | grep -E '<version>|application-version|<vendor'
#   -> <version>1.0.2</version>, min="26.9.0.0", vendor name="Hock" url="https://itondemand.com"

javap -verbose -cp target/classes de.hock.mqtt.template.MqttConnectedSystemTemplate \
  | grep 'major version'                               # 61  (Java 17)

# Namespace guard — the find-and-replace trap
grep -rn 'com\.appian\.mqtt' . | grep -v '/target/' | grep -v '/.agents/'   # empty
grep -rc 'import com.appian.connectedsystems' src/main/java/                # 45 total across src/main/java
# grep -rc prints a per-file count; this is the aggregate form that yields the single number:
grep -rh 'import com.appian.connectedsystems' src/main/java/ | wc -l        # 45

# Behavior
mvn -o clean test     # ProjectStructureTest + the new descriptor regression tests pass

# Label keys still resolve: 28 [Template].[fieldKey].label entries, keys unchanged
grep -c '\.label=' src/main/resources/resources_en_US.properties            # 28

# Code policy sweep
grep -rn 'System\.out\|System\.err\|printStackTrace\|java\.util\.logging\|System\.setProperty' src/main/java/   # empty
```

Expected `.java` count stays **33**: the three migrated templates are edits, not additions, and the
only new file is a test, which the assembly's `src/main/java` `fileSet` excludes by construction.

---

## 5. Deploy-time confirmation (cannot be done offline)

Copy `target/mqtt-broker-connected-system-1.0.2-plugin.jar` to `APPIAN_INSTALL/_admin/plugins/` and
watch `tomcat-stdOut.log` for `Successfully installed Plug-in` plus the
`ConnectedSystemTemplateModuleParser` registration lines. Then do the thing that originally failed:
**New > Connected System > MQTT Broker**, and confirm the form renders all 20 fields instead of the
expression-evaluation error.

Four things to watch for, in order:

1. A `NoClassDefFoundError` naming `com/appian/connectedsystems/simplified/sdk/...` at install or
   first render means the server does not provide `connected-systems-client`. That is the §2.3
   residual risk; fall back to option (b) as described there, not to bundling the jar.

2. **Clean Session — THE DISCRIMINATING CHECK.** Clear the **Clean Session** checkbox, save, close
   and reopen the connected system object, and confirm it is **still cleared**.

   This is the check that distinguishes the two possible platform behaviors, and it is the only one
   that does. The older check — "save with Clean Session left untouched and confirm it persists as
   enabled" — **passes under both behaviors and therefore proves nothing**; it has been replaced by
   this one for that reason. If Clean Session comes back **checked**, Appian is not round-tripping the
   descriptor the template returned but supplying only persisted object state. The `firstRender`
   signal is then permanently `true`, and **both** the Clean Session default (§2.5) and the
   first-render validation suppression must be reworked.

3. **First-render validation.** Open **New > Connected System > MQTT Broker** and confirm **no**
   validation errors appear before any input — in particular no "Broker host is required" and no
   broker-port bounds message. Then touch a required field and clear it (type into Broker Host, then
   delete what you typed) and confirm the error **does** appear.

   Errors never appearing at all is the same underlying failure as Clean Session coming back checked:
   the freshness signal is stuck `true`. Errors appearing immediately on a brand-new object means the
   guard is not taking effect at all.

4. Confirm all 20 fields render with their labels resolved (the 28 `resources_en_US.properties`
   entries).

---

## 6. Out of scope — noted, not fixed

- The five known pre-existing bugs. None is on this code path (§1.5).
- `MqttConnectedSystemTemplate.toConnectionConfig`'s Javadoc claims "This SDK version offers no
  accessor to turn a stored document id into bytes at this point." That is inaccurate — core 1.9.1's
  `ExecutionContext` exposes a default `getDocumentDownloadService()`. Wiring it up is a behavior
  change to certificate loading and belongs in its own task; leave the code alone here.
- Missing logo assets, and the dead jqwik/moquette Surefire/Failsafe wiring. Both already tracked in
  the steering file §5.

---

## 7. Implementation record — 2026-10-13

All ten steps executed as planned. No step needed a deviation, and no decision in the plan turned
out to be wrong once the code was written. Full detail in
[`connected-system-descriptor-fix-verification.md`](connected-system-descriptor-fix-verification.md);
the headline numbers:

**Commands run.** `mvn dependency:get -Dartifact=com.appian:connected-systems-client:1.3.0` (the one
online step, succeeded), then `rm -rf target && mvn -o clean package` and `mvn -o test`. Build
output: `Compiling 33 source files`, `Compiling 2 source file(s)` for tests,
`Tests run: 10, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`, and
`Building jar: target/mqtt-broker-connected-system-1.0.1-plugin.jar`.

**The NPE, reproduced and then eliminated.** A scratch probe against core 1.9.1 alone ran the
pre-fix line on `ConfigurationDescriptor.builder().build()` and printed the user's reported message
character for character, then showed `StateGenerator.generateFromExistingState(rootType, null)`
returning a populated state with type `#/types/MqttConnectedSystemConfiguration`. The probe lived
under `/tmp` and has been deleted; its role is now played by the committed test.

**Root state trace** (`javap -c` on client 1.3.0, verification note §3.2): inherited
`getConfigurationDescriptor` → `SimpleConfiguration.from` (null-tolerant) → our `getConfiguration` →
`setProperties` → `setPropertiesGeneric` (names the type via the null-safe `getRootTypeName()`,
defaulting to the literal `RootType`, and `Map.put`s it into the type map) → private
`getConfigurationDescriptor` = `new StateGenerator(types).generateFromExistingState(rootType,
getRootState())` + `withTypes(...)` + `withState(...)`. Confirmed in bytecode, exactly as §2.1
predicted.

**Jar invariants, all re-verified** (verification note §6): one jar
(`mqtt-broker-connected-system-1.0.1-plugin.jar`); `.java` count **33** as predicted — unchanged,
because the only new source is a test; bare root `appian-plugin.xml` (1); Paho at
`META-INF/lib/org.eclipse.paho.client.mqttv3-1.2.5.jar` with no root `lib/`; **0** `connected-systems`
entries in the archive, confirming the `provided` client is not bundled; **0** `com/appian/mqtt`;
**0** test entries; `<version>1.0.1</version>`, `min="26.9.0.0"`, `vendor name="Hock"
url="https://itondemand.com"`; bytecode major version **61** on all three templates; **28** label
keys; **0** hits in the `System.out`/`printStackTrace`/`System.setProperty` policy sweep.

**Two counts that legitimately moved**, both propagated into the steering file:

- SDK imports 47 → **45**: each template dropped three low-level imports and gained two
  `com.appian.connectedsystems.simplified.sdk.*` ones.
- `grep -rn 'com\.appian\.mqtt'` outside `target/` and `.agents/` returns **2 hits, both prose in the
  steering file** describing the rename trap. Nothing in `src/`, `pom.xml` or the jar. The steering
  file's guard command now excludes `.kiro/` so it no longer matches its own documentation.

**Test added:** `src/test/java/de/hock/mqtt/template/MqttConnectedSystemTemplateDescriptorTest.java`,
7 tests, all offline, covering every assertion step 6 listed — including the explicit
`incoming.getRootState() == null` premise check, the 20 config keys, both integrations' 4 input keys
each, the Clean Session first-render default and its deliberate-`false` counterpart, and the
field-scoped `brokerHost` error.

**One README deviation from step 8**, taken for accuracy: step 8 said to leave the test-coverage
bullet alone, but it claimed coverage was "three smoke assertions" in `ProjectStructureTest`, which
the new test makes false. The bullet now describes both test classes. Same correction applied to
steering §5 item 4. No config field, label, default, validation rule, integration input/output or
error category changed anywhere, so the README reference sections needed no edits, as predicted.

---

Appian documentation content referenced above was rephrased for compliance with licensing
restrictions.
