# Server-side TLS 1.0.7 — verification note

Real pasted output of every verification command, each with its expectation and what was actually
observed. Run on 2026-10-28 against the working tree at
`/Users/andreas.hock/Documents/Appian-Projects/MQTT_Component`.

**Environment.** Apache Maven 3.10.0; `mvn -o -v` reports `Java version: 23.0.1` (Oracle, at
`~/OpenJDK/jdk-23.0.1.jdk`), while the build targets Java 17 via `--release 17`. So bytecode is
major version 61 but the tests *execute* on JDK 23. Every numeric assertion in the TLS tests is
deliberately loose (">20 accepted issuers", "contains TLSv1.2 and not TLSv1/TLSv1.1") so neither JDK
can fail it. macOS 26.7.1, arm64.

**One expectation did not hold on first run, and was fixed rather than documented away.** It is
recorded in full in §4 below.

**Nothing here proves Appian 26.9 compatibility.** Final confirmation requires the user deploying
`target/mqtt-broker-connected-system-1.0.7-plugin.jar` to a live instance and running the
deploy-and-test checklist in `server-side-tls-1.0.7.md`.

---

## 1. Build and tests

### `mvn -o clean package`

**Expected:** BUILD SUCCESS. **Actual:** BUILD SUCCESS.

### `mvn -o test`

**Expected:** `Tests run: 35, Failures: 0, Errors: 0`. **Actual:** exactly that.

```
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.266 s -- in de.hock.mqtt.TlsSupportTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.689 s -- in de.hock.mqtt.TlsHostnameVerificationTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.001 s -- in de.hock.mqtt.MqttPublisherConnectOptionsTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.015 s -- in de.hock.mqtt.integration.MqttSubscribeIntegrationInputsTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.004 s -- in de.hock.mqtt.template.MqttConnectedSystemTemplateNoDesignTimeErrorsTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.012 s -- in de.hock.mqtt.template.MqttConnectedSystemTemplateDescriptorTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.004 s -- in de.hock.mqtt.ProjectStructureTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.282 s -- in de.hock.mqtt.ConnectionConfigValidatorTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.317 s -- in de.hock.mqtt.MqttSubscriberTest
[INFO] Tests run: 35, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Nine test classes, 23 → 35 tests. The whole suite finishes in about two seconds; the two loopback
handshake tests are the slowest TLS ones at ~0.7 s combined.

### Both new tests were proved to have teeth

Not an assertion about the code — an experiment on the tests themselves, run and then reverted.

**`tlsEnabledWithACaTrustsOnlyThatCa`** — the mode-3 restriction proof. Its
`checkServerTrusted({unrelated})` rejection assertion was temporarily swapped for the
CA-signed leaf, i.e. made to assert that an *accepted* certificate is rejected. Observed:

```
[ERROR] de.hock.mqtt.TlsSupportTest.tlsEnabledWithACaTrustsOnlyThatCa -- FAILURE!
org.opentest4j.AssertionFailedError: a certificate the configured CA did not sign must be
REJECTED; if this passes, trust is not being enforced and the release must not ship ==>
Expected java.security.cert.CertificateException to be thrown, but nothing was thrown.
```

Restored; 5/5 pass again.

**`aBrokerCertificateForADifferentHostFailsTheHandshake`** — the hostname-mismatch proof. Its
fixture SAN was temporarily changed from `wrong.example.com` to `localhost`, i.e. made to match the
connected host. Observed:

```
[ERROR] de.hock.mqtt.TlsHostnameVerificationTest.aBrokerCertificateForADifferentHostFailsTheHandshake -- FAILURE!
org.opentest4j.AssertionFailedError: a certificate whose SAN does not cover the connected host
must be rejected ==> Expected javax.net.ssl.SSLHandshakeException to be thrown, but nothing was
thrown.
```

Restored; 3/3 pass again. Both tests therefore fail for the right reason and are not vacuous.

---

## 2. Packaging invariants (a)–(l), against the BUILT jar

`J=target/mqtt-broker-connected-system-1.0.7-plugin.jar`

| # | Check | Expected | Actual | ✓ |
|---|---|---|---|---|
| g | `ls target/*.jar \| wc -l` | 1 | **1** | ✓ |
| — | jar name | `…-1.0.7-plugin.jar` | `target/mqtt-broker-connected-system-1.0.7-plugin.jar` | ✓ |
| b | `.java` entries | 8 | **8** | ✓ |
| b | sources outside `de/hock/mqtt/` | 0 | **0** | ✓ |
| c | bare-root `appian-plugin.xml` | 1 | **1** | ✓ |
| d | nested jars under `META-INF/lib/` | 1 | **1** | ✓ |
| d | Paho present | 1 | `META-INF/lib/org.eclipse.paho.client.mqttv3-1.2.5.jar` | ✓ |
| d | `connected-systems` entries | 0 | **0** | ✓ |
| a | `com/appian/mqtt` entries | 0 | **0** | ✓ |
| k | `test` entries | 0 | **0** | ✓ |
| — | `backup` entries | 0 | **0** | ✓ |
| — | `validation/` entries | 0 | **0** | ✓ |
| j | `THIRD-PARTY-NOTICES.md` at root | 1 | **1** | ✓ |
| — | `TlsSupport` entries | 2 | `TlsSupport.class`, `TlsSupport.java` | ✓ |
| — | key/keystore entries in jar | 0 | **0** | ✓ |

The eight sources, listed verbatim from the jar:

```
de/hock/mqtt/ConnectionConfigValidator.java
de/hock/mqtt/MqttPublisher.java
de/hock/mqtt/MqttSubscriber.java
de/hock/mqtt/TlsSupport.java
de/hock/mqtt/integration/MqttPublishIntegration.java
de/hock/mqtt/integration/MqttSubscribeIntegration.java
de/hock/mqtt/package-info.java
de/hock/mqtt/template/MqttConnectedSystemTemplate.java
```

7 → 8: `TlsSupport.java` is the only addition, picked up automatically by the assembly `fileSet` over
`src/main/java` — `src/assembly/plugin-assembly.xml` needed no edit, as the plan predicted.

### Manifest (e), (f), (l)

```
### manifest version/app-version/vendor
  key as well. <plugin-info><version> is three-part x.y.z (the AppMarket validator rejects
  four-part); <application-version min=...> is the four-part Appian platform version.
        <vendor name="Hock" url="https://itondemand.com"/>
        <version>1.0.7</version>
        <application-version min="26.9.0.0"/>
### plugin key count
1
### module key count
3
### module nesting
13:  <connected-system-template> module whose nested <integration-template> elements declare the
34:    <connected-system-template key="MqttConnectedSystemTemplate"
37:        <integration-template key="MqttPublishIntegration"
40:        <integration-template key="MqttSubscribeIntegration"
43:    </connected-system-template>
```

- **(e)** `<version>` is `1.0.7` — **three-part**, which the AppMarket validator requires;
  `<application-version min="26.9.0.0"/>` stays **four-part** and was not touched. ✓
  (The first two output lines are the manifest's own comment explaining the two formats, which the
  grep pattern also matches — expected, and noted in the steering block.)
- **(f)** Vendor is `Hock` / `https://itondemand.com`, unchanged. ✓
- **(l)** Plug-in key matches once, all three module keys match, so **1.0.7 replaces the installed
  1.0.6** rather than installing alongside it. ✓
- **Both `<integration-template>` elements are still nested inside the one
  `<connected-system-template>`** (lines 37 and 40 inside 34…43), not siblings of it. ✓ This is
  brief item 6's "both integration modules still declared".

### (h) Bytecode — Java 17, major version 61

**Expected:** 61 for all seven classes. **Actual:** 61 for all seven.

```
template.MqttConnectedSystemTemplate   major version: 61
MqttPublisher   major version: 61
MqttSubscriber   major version: 61
TlsSupport   major version: 61
ConnectionConfigValidator   major version: 61
integration.MqttPublishIntegration   major version: 61
integration.MqttSubscribeIntegration   major version: 61
```

---

## 3. Expressionability and namespace

### Expressionability — the platform rule that broke three deploys

```
### expressionability: template
0
### expressionability: repo-wide
7
### expressionability: per file nonzero
src/main/java/de/hock/mqtt/integration/MqttPublishIntegration.java:3
src/main/java/de/hock/mqtt/integration/MqttSubscribeIntegration.java:4
```

**Expected:** 0 in the template, 7 repo-wide, exactly the two integration lines at 3 and 4.
**Actual:** exactly that. All three counts are **unchanged** despite two new fields, because neither
new builder call touches the modifier.

**The grep is the weaker check, and that is the point.** The sufficient proof is
`noConnectedSystemPropertyDescriptorIsExpressionable`, which renders the descriptor and reads every
modifier back through `PropertyDescriptor.isExpressionable()` — the same object graph Appian's
validator walks — now across all **eleven** fields (`assertEquals(11, inspected)`). A source grep
would not have caught the forced-expressionable document fields that broke 1.0.2, which is why
`theTwoTlsFieldsUseBooleanAndParagraphDescriptorsAndAreNotExpressionable` was added as a
field-specific guard on top.

### Namespace trap (a)

```
### namespace com.appian.mqtt (expect none)
(no hits)
```

**Expected:** no hits outside `/target/`, `/.agents/`, `/.kiro/`, `/.git/`. **Actual:** no hits. ✓

### SDK import census

```
### SDK import total
37
### SDK import files
4
### SDK imports per file
src/main/java/de/hock/mqtt/MqttSubscriber.java:0
src/main/java/de/hock/mqtt/integration/MqttPublishIntegration.java:12
src/main/java/de/hock/mqtt/integration/MqttSubscribeIntegration.java:12
src/main/java/de/hock/mqtt/template/MqttConnectedSystemTemplate.java:12
src/main/java/de/hock/mqtt/TlsSupport.java:0
src/main/java/de/hock/mqtt/MqttPublisher.java:0
src/main/java/de/hock/mqtt/ConnectionConfigValidator.java:1
src/main/java/de/hock/mqtt/package-info.java:0
```

**Expected:** 37 across 4 files; template 12, each integration 12, validator 1, rest 0.
**Actual:** exactly that. 36 → 37 is the template gaining `ParagraphPropertyDescriptor` (11 → 12).
`TlsSupport` imports **no** SDK class, which is why the file count stays at 4.

---

## 4. Code policies — including the two TLS-specific bans

### Logging

```
### logging policy (expect none)
(no hits)
```

**Expected:** no `System.out`, `System.err`, `printStackTrace`, `java.util.logging`.
**Actual:** no hits. ✓ The plug-in still logs nothing and declares no logging dependency.

### JVM-global mutation and filesystem access — brief item 4

```
### setProperty/JSSE/filesystem (expect ONE prose hit)
src/main/java/de/hock/mqtt/MqttPublisher.java:32: * {@code System.setProperty} call anywhere and still writes nothing to the filesystem — the two
```

**Expected:** exactly one hit, the Javadoc sentence in `MqttPublisher` asserting the *absence* of
such a call. **Actual:** exactly that one hit.

**There is no system-property mutation and no filesystem use.** The single match is prose, not a
call: the sentence states the plug-in makes no such call. `TlsSupport` and `MqttSubscriber`
deliberately phrase the same assurance without the tokens, which is what keeps this grep to one hit.
No `createTempFile`, no `java.io.File`, no `java.nio.file`, no `javax.net.ssl.trustStore` or
`javax.net.ssl.keyStore` anywhere in `src/main/java`.

### ⚠️ THE ONE EXPECTATION THAT DID NOT HOLD, and what was done

The TLS-surface grep was expected to name exactly two files. On the first run it named **three**:

```
### TLS surface files (expect 2)   -- FIRST RUN, FAILED EXPECTATION
src/main/java/de/hock/mqtt/MqttPublisher.java
src/main/java/de/hock/mqtt/TlsSupport.java
src/main/java/de/hock/mqtt/package-info.java      <-- unexpected
```

**Cause:** not a real violation. `package-info.java` has no code at all; its Javadoc described the
1.0.7 change using the literal token `SSLContext` ("an in-memory `SSLContext` is handed to Paho as a
socket factory"), and the grep cannot tell prose from a call site.

**Fix chosen: reword the prose, not the expectation.** The sentence now reads "the trust material is
assembled entirely in memory and handed to Paho as a socket factory", which says the same thing
without the token. This follows the pattern the project already relies on in four other Javadocs
(the template, `MqttSubscriber`, `ConnectionConfigValidator`, `MqttSubscribeIntegration`), where
wording is deliberately chosen so a documented grep stays exact. Weakening the expectation to "two
or three files" would have made the check unable to detect a genuine third call site.

After the reword:

```
### TLS surface files (expect 2)   -- AFTER FIX
src/main/java/de/hock/mqtt/MqttPublisher.java
src/main/java/de/hock/mqtt/TlsSupport.java
```

**Expected:** 2. **Actual:** 2. ✓ `setSocketFactory` is called exactly once, from
`MqttPublisher.buildConnectOptions`, so the single-place invariant holds. The build was re-run clean
after the reword: `Tests run: 35, Failures: 0, Errors: 0` / `BUILD SUCCESS`, 8 sources in the jar.

### No permissive trust manager, no custom verifier

```
### permissive trust manager (expect none)
(no hits)

### setSSLHostnameVerifier/setSSLProperties (expect none)
(no hits)
```

**Expected:** no hits for `trustAll`, `X509TrustManager (`, `checkServerTrusted`,
`setSSLHostnameVerifier`, `setSSLProperties`. **Actual:** no hits. ✓

There is no trust-everything manager anywhere in `src/main/java`, not even behind a flag, and no
custom `HostnameVerifier` — which Paho would run *only* when the HTTPS flag is false and would
therefore be contradictory.

---

## 5. No key material anywhere — brief item 8

```
### private key headers in repo
./.agents/tasks/server-side-tls-1.0.7-plan.md:1045:grep -rniE 'BEGIN (RSA |EC |)PRIVATE KEY|BEGIN ENCRYPTED PRIVATE KEY' . \
./.kiro/steering/appian-appmarket-compliance.md:444:grep -rniE 'BEGIN (RSA |EC |DSA )?PRIVATE KEY|BEGIN ENCRYPTED PRIVATE KEY' . \

### key/store files on disk (expect none)
(end of find)        <- find printed nothing

### key/keystore entries in jar
0
```

**Expected:** no private key text, no key/store files, 0 such entries in the jar.
**Actual:** no key material. The two hits are **the plan and the steering file quoting this very
grep command** — the search pattern itself, not a key. No `.pem`, `.key`, `.p12`, `.pfx`, `.jks`,
`.keystore` or `.truststore` file exists anywhere in the repository or the jar.

**How the tests avoid needing any.** `CertificateFixtures` generates every key pair and certificate
at run time inside the test JVM, by hand-writing X.509 DER and signing it with
`java.security.Signature` — because no public JDK API creates a certificate and
`sun.security.x509.CertAndKeyGen` is not exported from `java.base`. Private keys exist only as
in-memory `PrivateKey` objects and are never serialised, printed or stored. The in-memory server key
store in `serverSocketFactory` uses a **randomly generated single-use** passphrase rather than a
literal, so no string in the repository can be mistaken for a credential. No `--add-exports`, no
BouncyCastle, no `keytool` subprocess, nothing written to disk — the plan's two fallbacks were not
needed.

---

## 6. Resource bundle — no orphans, no missing keys

```
### bundle diff (expect empty)
(no diff)
### bundle keys: 24
### field labels: 18
```

**Expected:** empty diff, 24 keys, 18 field labels. **Actual:** exactly that. ✓

22 → 24: `MqttConnectedSystemTemplate.tlsEnabled.label` and
`MqttConnectedSystemTemplate.caCertificate.label`. 16 → 18 field labels. The diff is computed by
deriving the expected key set from the `public static final String` constants in the three template
classes and comparing it against the keys actually present, so a `>` line would be an orphan and a
`<` line a missing key. Neither appeared.

`TlsSupport.PEM_CERTIFICATE_HEADER` is package-private and in a different file, so it cannot leak
into this check.

---

## 7. `THIRD-PARTY-NOTICES.md` — confirmed unchanged

```
### THIRD-PARTY-NOTICES.md unchanged?
(empty above = unchanged)
```

**Expected:** no change needed. **Actual:** `git diff --stat` for that path is empty — the file was
not modified. ✓

All cryptography comes from the JDK (`CertificateFactory`, `KeyStore`, `TrustManagerFactory`,
`SSLContext`, `Signature`, `KeyPairGenerator`). **No new third-party dependency was added**, so the
only bundled third-party artefact is still Eclipse Paho 1.2.5 under its permissive EDL-1.0 option.
Confirmed rather than silently left alone, as the plan required.

---

## 8. Plain `tcp://` unchanged when TLS is off — brief item 6

Not provable by grep, so it is pinned by tests:

- **`tlsDisabledProducesATcpUriAndNoSocketFactory`** asserts `brokerUri(FALSE, …)` and
  `brokerUri(null, …)` both yield `tcp://host:1883`, and — the load-bearing half — that
  `buildConnectOptions(FALSE, <a real CA PEM>, …).getSocketFactory()` is **`null`**. A CA left
  behind in a saved configuration cannot drag TLS into a plain connection.
- **`pahoAppliesHttpsEndpointIdentificationOnSslByDefaultAndThePluginSetsItExplicitly`** separately
  asserts a TLS-off options object still has a null socket factory.
- **`MqttPublisherConnectOptionsTest`**'s two tests are otherwise unchanged in substance — only the
  two new leading arguments were added — and still pass, so the session-setting mapping did not
  move.
- `brokerUri` is the single place the scheme is chosen, and `isTlsEnabled` the single place the
  `null`-means-false rule lives, so the URI and the socket factory cannot disagree.

An existing connected system saved by 1.0.6 carries no value for either new key, so `tlsEnabled`
reads `null` → `false` and it keeps connecting over `tcp://` untouched. **Deploy step 2 is the real
confirmation of this** and is the most important regression check in the release.

---

## 9. Working tree — nothing unintended touched, nothing committed

```
 .kiro/steering/appian-appmarket-compliance.md      | 367 ++++++++++++++++-----
 README.md                                          | 246 +++++++++++---
 docs/mqtt-plugin-guide.md                          |  22 +-
 info.md                                            |  18 +-
 pom.xml                                            |   2 +-
 .../de/hock/mqtt/ConnectionConfigValidator.java    |  66 ++++
 src/main/java/de/hock/mqtt/MqttPublisher.java      |  77 ++++-
 src/main/java/de/hock/mqtt/MqttSubscriber.java     |  28 +-
 .../mqtt/integration/MqttPublishIntegration.java   |  20 +-
 .../mqtt/integration/MqttSubscribeIntegration.java |  16 +
 src/main/java/de/hock/mqtt/package-info.java       |  12 +-
 .../mqtt/template/MqttConnectedSystemTemplate.java |  65 +++-
 src/main/resources/appian-plugin.xml               |   7 +-
 src/main/resources/resources_en_US.properties      |  10 +-
 .../hock/mqtt/ConnectionConfigValidatorTest.java   |  99 +++++-
 .../hock/mqtt/MqttPublisherConnectOptionsTest.java |  15 +-
 .../MqttConnectedSystemTemplateDescriptorTest.java |  56 +++-
 ...nectedSystemTemplateNoDesignTimeErrorsTest.java |   6 +-
```

New files (untracked): `src/main/java/de/hock/mqtt/TlsSupport.java`,
`src/test/java/de/hock/mqtt/CertificateFixtures.java`,
`src/test/java/de/hock/mqtt/TlsSupportTest.java`,
`src/test/java/de/hock/mqtt/TlsHostnameVerificationTest.java`, plus the three
`.agents/tasks/server-side-tls-1.0.7*` records.

`src/assembly/plugin-assembly.xml` and `THIRD-PARTY-NOTICES.md` are **untouched**, as intended.
`pom.xml` changed by exactly one line (the version); no dependency was added or altered.

**Nothing was committed, pushed, branched, amended, reset or stashed.** The user owns git. The tip of
`main` when this change was written was `8621e99`; only read-only git commands were used.

---

## 10. What this verification does NOT establish

Stated plainly, because a green build proves less than it looks like it does.

1. **Appian 26.9 compatibility is not proven.** Final confirmation requires the user deploying
   `target/mqtt-broker-connected-system-1.0.7-plugin.jar` to a live instance and running the
   deploy-and-test checklist. 1.0.7 is **not yet deployed**; the known-good reference remains
   1.0.6 / `68e15e8`.
2. **Paho's `ssl` `NetworkModuleFactory` has never resolved through Appian's plug-in classloader.**
   It is discovered by a `ServiceLoader` over
   `META-INF/services/org.eclipse.paho.client.mqttv3.spi.NetworkModuleFactory`, listed in the same
   file as the `tcp` factory that already works on a live instance, so it should be free — but it is
   the one new platform assumption in this release. The symptom of failure would be an
   `IllegalArgumentException` on the `ssl://` URI at connect time. **Deploy step 3 clears it.**
3. **Mode 2 cannot be tested offline against the real trust store.**
   `TrustManagerFactory.init((KeyStore) null)` uses the Appian server JVM's own `cacerts`, or a JSSE
   trust store an administrator configured. A broker whose CA is publicly trusted in general but
   absent from *that* store fails in mode 2 and works in mode 3.
4. **No test performs a real publish or a real subscribe**, over TLS or otherwise. The handshake
   tests exercise TLS against a loopback `SSLServerSocket` with no MQTT behind it; Paho is
   deliberately not instantiated, because its client starts non-daemon threads.
5. **The negative case has not been observed end to end.** Deploy step 5 — TLS on with the *wrong*
   CA pasted must **fail** — is the single most important runtime check in this release. A success
   there would mean trust is not being enforced and the release must not ship. Offline, the
   equivalent is `tlsEnabledWithACaTrustsOnlyThatCa`, which was proved to have teeth (§1).
6. **Whether integration input errors display at design time remains untested**, deliberately. That
   is a separate question from the connected system finding, and probing two things in one deploy
   would muddy both.
