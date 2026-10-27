# Migration of all three MQTT templates to the Simple* SDK hierarchy to fix the null root state

The `New > Connected System` form failed with `ConfigurationDescriptor.getRootState()` returning
`null`, and the fix replaces the low-level `ConnectedSystemTemplate` / `IntegrationTemplate`
interfaces with `SimpleConnectedSystemTemplate` / `SimpleIntegrationTemplate` from a newly declared
`com.appian:connected-systems-client:1.3.0` (scope `provided`, core left at 1.9.1). The facade owns
the two things the old code got wrong: it feeds the possibly-`null` incoming state through
`StateGenerator.generateFromExistingState`, and it registers the generated root
`LocalTypeDescriptor` via `withTypes(...)`, which `PropertyState.setType(...)` never did. Because
state generation pre-populates a `BOOLEAN` property with `Boolean.FALSE`, the documented
`cleanSession = true` default would have silently flipped; the fix captures a freshness signal
before `setProperties` and seeds `Boolean.TRUE` only on first render. Everything downstream of the
descriptor — `validate`, `toConnectionConfig`, all four validators, every value-reading helper — is
untouched, bridged by `toConfiguration().getRootState()`.

**Watch for:** the deliverable report named in the task brief
(`connected-system-descriptor-fix.md`) does not exist — its content lives in two sibling files
instead (confirmed); the Clean Session first-render detection depends on Appian round-tripping a
template-seeded value back into the descriptor, and the deploy checklist only tests the
*untouched* case, not the *cleared* case, which is the direction that breaks if the assumption is
wrong (possible); a brand-new connected system now renders with broker-host and broker-port
validation errors already attached before the designer types anything (confirmed); and a
carried-forward note claiming a `ListTypePropertyDescriptor` generates an `Expression` default
contradicts the SDK bytecode (confirmed).

**Verdict**: APPROVED

## High-level view

The root cause is fixed at the mechanism, not compiled around.
`ConfigurationDescriptor.getRootState()` is `getState().get("root")`, and
`StateGenerator.generateFromExistingState` opens by short-circuiting a `null` existing state into
`generateDefaultState`, so routing through the facade guarantees a populated root state on the first
render. `SimpleConfiguration.toConfiguration()` returns the stored descriptor by reference, which is
the undocumented detail that keeps the unchanged `validate(PropertyState)` wired in — its
`setErrorAtPath` calls land on the object the framework reads back.

Core stays at 1.9.1 with no downgrade, both Appian artifacts are `provided` and neither is bundled,
and the client POM declares zero dependencies so it cannot drag in a conflicting core. The residual
risk — `provided` means the Appian server must supply the client jar — is disclosed in the README,
the verification note, and the steering file, with a fallback that does not involve bundling an
Appian artifact.

Every packaging invariant and the code-policy sweep survived the migration, re-checked against the
built jar rather than taken from the record: one jar at `1.0.1-plugin`, 33 root-mirrored `.java`
sources, bare `appian-plugin.xml`, Paho under `META-INF/lib/`, three-part version against four-part
platform minimum, 28 label keys, 20 config keys, 45 SDK imports, no forbidden namespace, no banned
logging or `System.setProperty`, TLS still via `setSocketFactory`. The five known pre-existing bugs
are untouched.

The Clean Session default is the one real behavioral trap here and it was caught, but the guard
rests on an unverified platform assumption about what Appian hands back on a re-render. If that
assumption is wrong the failure is silent and points the other way: a designer could never turn
Clean Session off.

Design-time validation now actually runs where it previously crashed, so the form opens with errors
already attached to the two required-and-empty fields. The new test codifies this as intended; the
README does not mention it.

Two inaccuracies sit in the records rather than the code: the carried-forward `Expression`-default
claim about `topicFilters`, and the plan's §4 gate block still expecting 47 SDK imports where the
real and documented figure is 45.

<details>
<summary>Issues (6)</summary>

1. **Named deliverable report absent** — the brief requires
   `.agents/tasks/connected-system-descriptor-fix.md`; only `-plan.md` and `-verification.md` exist.
   The required content (including the honest offline-unverifiable statement) is present in those
   two files, so create the named report or point the task record at the actual paths.
2. **Clean Session deploy check covers only one direction** — the checklist verifies that an
   untouched Clean Session persists as enabled, but not that a *cleared* one persists as disabled.
   The latter is the case that fails if Appian does not round-trip template-seeded values. Add it to
   §5 of the plan and §7 of the verification note.
3. **New connected system opens with validation errors** — generated STRING defaults are `""` and
   INTEGER defaults are `null`, so `validate` attaches "Broker host is required" and the broker-port
   bounds error on first render. Decide whether to suppress validation on the first render or accept
   it as intended, and say which in the README.
4. **Carried-forward `Expression` note contradicts the SDK** — `StateGenerator.getDefaultState`
   only produces an `EXPRESSION` state when `displayHint == EXPRESSION` or for a local type below
   level 1; `topicFilters` is neither, so its generated default is an empty `ArrayList`. Correct the
   note in the plan (§3 step 5) and verification note (§7.5) before it misleads a future change.
5. **Plan §4 gate block expects a stale import count** — it still says `~47, unchanged` while the
   actual and documented count is 45. Copy-pasting that block now reads as a failure. Update it to
   45 to match §7 and the steering file.
6. **Facade mutates a plain `HashMap` on the template instance** — `setPropertiesGeneric` writes
   into `ConfigurableTemplate.typePropertyFactory`'s non-thread-safe map on every descriptor render
   and immediately reads `values()` from it. Nothing to change in plug-in code, but note it for the
   submission and watch for intermittent design-time errors under concurrent designer load.

</details>

<details>
<summary>Details</summary>

### Why the root state is now non-null, verified against core 1.9.1 bytecode

```
Appian
  └─ SimpleConnectedSystemTemplate.getConfigurationDescriptor(descriptor, path, ctx)
       ├─ SimpleConfiguration.from(descriptor, typePropertyFactory, ctx)
       │     └─ null descriptor → builder().build() + setProperties(new PropertyDescriptor[0])
       │        non-null-but-empty → stored as-is, root state never dereferenced
       ├─ getConfiguration(simpleConfig, ctx)              ← our override
       │     └─ setProperties(allProperties())
       │          └─ setPropertiesGeneric(getRootTypeName(), props)
       │               ├─ getRootTypeName(): "RootType" when root state is null   [null-safe]
       │               ├─ typePropertyFactory.getTypes().put(name, rootType)      [registration]
       │               └─ new StateGenerator(types)
       │                    .generateFromExistingState(rootType, getRootState())
       │                  └─ existing == null → generateDefaultState(...)         [the fix]
       │               └─ builder().withTypes(types).withState(generated).build()
       └─ toConfiguration() → returns that descriptor
```

The decisive instruction is the first one in the private
`generateFromExistingState(LocalTypeDescriptor, PropertyState, int)`:

```
0: aload_2
1: ifnonnull     11
4: aload_0 / aload_1 / iload_3
7: invokespecial generateDefaultState:(LocalTypeDescriptor;I)PropertyState;
10: areturn
```

The old code had no equivalent and could not have had one: `PropertyState`'s constructor is
package-private, so `StateGenerator` is the only public route to a root state from `de.hock.mqtt`.
The facade also closes a second latent defect — `setType` stores a `TypeReference` without
registering anything, so the old descriptor would have carried an unresolvable `#/types/...`
reference even with a non-null state. `withTypes(...)` is what registers it.

The root type is now named `RootType` rather than `MqttConnectedSystemConfiguration`; that name is
internal to the descriptor's type map, the i18n keys are prefixed by template *class* name, and no
connected system was ever successfully created under the old name, so nothing depends on it.

Both integrations reach their saved state the same way.
`SimpleIntegrationTemplate.execute(ConfigurationDescriptor, ConfigurationDescriptor,
ExecutionContext)` is two `SimpleConfiguration.from(...)` calls and a dispatch, never calling
`setProperties`, so the `toConfiguration().getRootState()` bridge hands back the real saved state and
`readRequest`, `readStringList`, `readInt` and `toConnectionConfig` see what they saw before.

### The Clean Session guard and the assumption underneath it

`SystemType`'s static initializer confirms the trap was real: `BOOLEAN`'s default is `iconst_0`,
i.e. `Boolean.FALSE`, while `STRING` and `ENCRYPTED` default to `""` and `INTEGER` to `null`. Since
`toConnectionConfig` reads `asBoolean(rootState, CLEAN_SESSION, true)` and relies on `null` meaning
"unset", unguarded generation would have flipped a documented protocol default. The guard:

```java
PropertyState priorState = configuration.toConfiguration().getRootState();
boolean firstRender =
    priorState == null
        || priorState.getValueAtPath(new PropertyPath(ConfigFields.CLEAN_SESSION)) == null;

configuration.setProperties(allProperties());

if (firstRender) {
  configuration.setValue(ConfigFields.CLEAN_SESSION, Boolean.TRUE);
}
```

The exposure is what "first render" means in production. The signal is only reliable if Appian passes
back a descriptor derived from the one the template last returned. If the platform instead supplies
only persisted object state, `cleanSession` reads `null` on every render, `firstRender` is
permanently true, and the seed overwrites a designer's cleared checkbox on every round trip — Clean
Session becomes impossible to disable.

The test covers the mechanism but not the assumption: it mutates the descriptor the first render
returned and feeds it back, which *is* the round-trip behavior being assumed. The deploy checklist
has the same asymmetry — saving with Clean Session untouched and confirming it persists as enabled
passes under both platform behaviors. The discriminating check is to clear the checkbox, save,
reopen, and confirm it is still cleared.

One adjacent question settled: `shouldRenderAsExpression` returns true only for
`displayHint == EXPRESSION` or a local type below level 1. No descriptor here sets that hint
(`displayMode(CHECKBOX)` is a different property), so `isExpressionable(true)` does not turn
generated defaults into `Expression` states and the coder's measured defaults table applies as
written.

### Design-time validation now fires on an empty form

`validate` runs unconditionally at the end of `getConfiguration`, and the generated state hands it
`""` for `brokerHost` and `null` for `brokerPort`. `ConfigValidator.validateConnectionFields`
rejects a blank host outright and treats a blank port as a bounds failure, so a brand-new connected
system renders with two field-scoped errors attached before any input. The new
`blankBrokerHostIsReportedAgainstItsField` test asserts exactly this, so it is deliberate rather
than an oversight — but the README's field reference does not mention it, and the old code crashed
rather than rendering, so there is no prior behavior to compare against.

### Carried-forward `topicFilters` note does not match the SDK

Both the plan (§3 step 5) and the verification note (§7.5) record that "a
`ListTypePropertyDescriptor`'s generated default is an `Expression` object, so an untouched
`topicFilters` can read back as a one-element list containing an expression's `toString()`", framed
as a pre-existing characteristic left alone on purpose. Leaving `readStringList` alone was right, but
the stated reason does not hold on this path. `createPropertyState` with a `null` old state goes to
`getDefaultState`, which only produces an `EXPRESSION`-typed state when the display hint demands it;
otherwise it calls `generateDefaultValue(descriptor.getTypeRef(), level)`, whose list branch is

```
20: TypeReference.isListType:()Z
27: new java/util/ArrayList
34: areturn
```

So an untouched `topicFilters` generates an empty list, `readStringList` returns an empty list, and
`InputValidator` reports the "1–10 filters" error — the correct outcome, reached for a different
reason than recorded. The note matters because it is what a future change to list inputs will
consult.

### Shared mutable type map on the template instance

`ConfigurableTemplate` holds `typePropertyFactory` in a `final` field, and
`TypePropertyFactory.types` is a plain `HashMap`. Every `getConfigurationDescriptor` call does
`types.put(rootTypeName, freshLocalTypeDescriptor)` and then reads `types.values()` to feed
`StateGenerator` and `withTypes`. Appian registers one template instance per plug-in module, so two
designers rendering the same form concurrently write the same key with different value objects on a
map that is not safe for concurrent mutation; a resize race could yield a types collection missing
the root type the generated state references. Duplicate names — which `StateGenerator`'s constructor
rejects with `IllegalStateException` — cannot arise this way, since the key is always identical.
Nothing is fixable in plug-in code; the exposure is inherent to Appian's documented facade.

### SDK pairing: no silent downgrade

`connected-systems-core` stays at `1.9.1`, `connected-systems-client` enters at `1.3.0`, both
`provided`, neither in the archive (zero `connected-systems` entries; the assembly's
`dependencySet` is `<scope>runtime</scope>`). The client POM contains zero `<dependency>` elements,
so it cannot pull a conflicting core even transitively — and `provided` scope is not transitive
anyway. Disassembling the client against core 1.9.1 resolved every cross-jar symbol, and the three
abstract signatures the templates override match exactly:

```
SimpleConnectedSystemTemplate: getConfiguration(SimpleConfiguration, ExecutionContext)
SimpleIntegrationTemplate:     getConfiguration(SimpleConfiguration, SimpleConfiguration,
                                                PropertyPath, ExecutionContext)
SimpleIntegrationTemplate:     execute(SimpleConfiguration, SimpleConfiguration, ExecutionContext)
```

The unresolved divergence that core 1.9.1 contradicts Appian's compatibility table is carried
forward unchanged in the steering file — this task did not touch it.

### Invariants, re-checked against the jar rather than the record

One jar (`mqtt-broker-connected-system-1.0.1-plugin.jar`), 33 `.java` entries, one bare
`appian-plugin.xml`, one `META-INF/lib/org.eclipse.paho...` with no root `lib/`, zero
`connected-systems`, zero `com/appian/mqtt`, zero `test`. The manifest carries three-part
`<version>1.0.1</version>` against four-part `min="26.9.0.0"`, `key="de.hock.mqtt"`, vendor
`Hock`/`https://itondemand.com`, and its three `class=` attributes still name the unrenamed classes.
`resources_en_US.properties` holds 28 `.label=` keys prefixed by template class name, `ConfigFields`
holds 20 keys, and the SDK import count is 45, matching the updated steering guard.

No `System.out`/`System.err`/`printStackTrace`/`java.util.logging`/`System.setProperty` anywhere in
`src`. The `javax.net.ssl.*` hits are imports and types in `ConnectOptionsFactory` and
`ExceptionCategoryTranslator`, not system properties — TLS reaches Paho through
`options.setSocketFactory(buildSslSocketFactory(config))`. `resolveDocumentBytes` closes the
document stream with try-with-resources.

### Test coverage

`MqttConnectedSystemTemplateDescriptorTest` is well-aimed: seven tests all starting from
`ConfigurationDescriptor.builder().build()` — the exact input whose `getRootState()` is `null`, with
that premise asserted rather than assumed — driving the real inherited `getConfigurationDescriptor`
on all three templates. Between them they pin the NPE guard, type registration and resolvability,
the 20 config keys, the Clean Session default in both directions, a field-scoped validation error,
and the eight integration input keys the i18n bundle depends on.

Not tested: that Appian round-trips template-seeded values, which is the premise the Clean Session
guard rests on; anything at runtime — publish, subscribe, TLS, LWT, reconnection, diagnostics; and
whether the Appian server supplies `connected-systems-client`. The jqwik and moquette dependencies
with their Surefire/Failsafe wiring still advertise coverage that does not exist, which the README
and steering file both disclose.

### Scope

The five known pre-existing bugs — inert `maxReconnectAttempts`, key passphrase, divergent
`MqttConnectionManager.classify`, username in the connection fingerprint, dropped subscribe
`validationErrors` — are untouched, and the reasoning for excluding them (none sits on the
design-time descriptor path) holds. The inaccurate `toConnectionConfig` Javadoc about document
resolution was identified and deferred rather than fixed in passing.

</details>

<details>
<summary>File map</summary>

- `pom.xml` — version `1.0.1`; new `appian.sdk.client.version` property and
  `connected-systems-client` dependency at `provided`; corrected comment about the `Simple*`
  hierarchy.
- `src/main/resources/appian-plugin.xml` — `<version>` bumped to `1.0.1`; everything else unchanged.
- `src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java` — now extends
  `SimpleConnectedSystemTemplate`; `getConfigurationDescriptor` override replaced by
  `getConfiguration`; Clean Session seeded on first render; `buildRootType()`/`ROOT_TYPE_NAME`
  deleted; Javadoc corrected.
- `src/main/java/de/hock/mqtt/integration/MqttPublishIntegration.java` — now extends
  `SimpleIntegrationTemplate`; both overrides re-signatured; state read via
  `toConfiguration().getRootState()`.
- `src/main/java/de/hock/mqtt/integration/MqttSubscribeIntegration.java` — same migration.
- `src/test/java/de/hock/mqtt/template/MqttConnectedSystemTemplateDescriptorTest.java` — new, seven
  regression tests over all three templates.
- `README.md` — version and artifact paths to `1.0.1`; client dependency documented; low-level-SDK
  limitation replaced with the server-classpath caveat; test-coverage bullet updated.
- `.kiro/steering/appian-appmarket-compliance.md` — version table, import-count guard 47→45,
  artifact name, §4 gate, §5 item 2 marked resolved.
- `.agents/tasks/connected-system-descriptor-fix-plan.md`,
  `.agents/tasks/connected-system-descriptor-fix-verification.md` — plan with implementation record,
  and the reviewer-facing evidence note.

No git repository, so there is no diff to link; the review is against the working tree and the
coder's recorded command output.

</details>
