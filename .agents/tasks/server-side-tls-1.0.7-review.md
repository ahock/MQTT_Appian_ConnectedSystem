# Review — server-side TLS, 1.0.7 (uncommitted working tree)

Reviewed against `.agents/tasks/server-side-tls-1.0.7-plan.md`, the release record
`server-side-tls-1.0.7.md`, the coder's evidence in `server-side-tls-1.0.7-verification.md`, and
`.kiro/steering/appian-appmarket-compliance.md`. Build and test suites were not re-run; the pasted
evidence was read instead. Narrow spot-checks are named where used, with the doubt that prompted
them.

Working tree: `HEAD 8621e99`, 18 modified files, 7 untracked (one main source, three test files,
three task records). `git status --porcelain` confirms nothing was committed.

**Verdict: APPROVED — no blocking findings.**

---

## 1. Security crux

### 1.1 Trust is restricted in mode 3, not merely configured — satisfied

`TlsSupportTest.tlsEnabledWithACaTrustsOnlyThatCa` (src/test/java/de/hock/mqtt/TlsSupportTest.java:103-131)
reads the trust manager back and asserts three things, not one:
`getAcceptedIssuers().length == 1`; `checkServerTrusted({leaf, ca}, "RSA")` succeeds for a leaf the
configured CA signed; and `assertThrows(CertificateException.class, … checkServerTrusted({unrelated}))`
for an unrelated self-signed anchor. The non-null socket-factory assertion is present but explicitly
not the point of the test. The mutation experiment is recorded in the verification note §1: inverting
the rejection assertion produced
`AssertionFailedError: a certificate the configured CA did not sign must be REJECTED`, so the test is
not vacuous.

The mechanism behind it is real restriction rather than addition:
`TlsSupport.trustManagers` (src/main/java/de/hock/mqtt/TlsSupport.java:186-213) initialises the
factory with a key store containing **only** the parsed certificates when a PEM is supplied, and
falls back to `init((KeyStore) null)` only when the PEM is blank. There is no code path that merges
the pasted CA with the default store, which is what the "exactly 1 accepted issuer" assertion pins.

Mode 2 is checked in the other direction as well: `tlsEnabledWithoutACaUsesTheDefaultTrustStoreOverAnSslUri`
asserts the default store **rejects** a throwaway self-signed certificate, so "default trust store"
is not silently "trust anything".

### 1.2 No trust-all or permissive trust manager anywhere — satisfied

Spot-check (doubt: a test helper could contain a permissive manager that the production grep would
not see, since the documented grep covers `src/main/java` only):

```
grep -rniE 'trustAll|X509TrustManager *\(|checkServerTrusted|setSSLHostnameVerifier|setSSLProperties' src/main/java/
→ no hits
```

In `src/test/java`, `checkServerTrusted` appears only as the *assertion target* in `TlsSupportTest`
(called on the JDK's own manager obtained from `TlsSupport.trustManagers`). No class in the repository
implements `X509TrustManager`; `CertificateFixtures` builds a real CA and real leaf certificates
instead, and its server side uses `KeyManagerFactory` with a genuine chain. So there is nothing of
trust-all shape that could be mistaken for production code, and no flag, property or field that can
disable verification. `TlsSupport`'s Javadoc states the absence as policy
(src/main/java/de/hock/mqtt/TlsSupport.java:63-66).

### 1.3 Hostname verification answered from bytecode, and no wrapper was written — satisfied

No `SSLSocketFactory` wrapper exists; `TlsSupport.socketFactory` returns the `SSLContext`'s own
factory unmodified, so the enumerated-override requirement does not apply. The decision rests on
measured Paho 1.2.5 bytecode, recorded in the plan §0.1 with offsets
(`SSLNetworkModuleFactory.getSupportedUriSchemes` = `{"ssl"}`;
`createNetworkModule` → `setHttpsHostnameVerificationEnabled(options.isHttpsHostnameVerificationEnabled())`;
`MqttConnectOptions` constructor `iconst_1; putfield`;
`SSLNetworkModule.start()` → `SSLParameters.setEndpointIdentificationAlgorithm("HTTPS")` before
`startHandshake()`), and the no-wrapper reasoning includes the newly found third 1.0.3 defect (the
unoverridden no-arg `createSocket()`).

The plug-in does not rely on the measured default: `MqttPublisher.buildConnectOptions` calls
`options.setHttpsHostnameVerificationEnabled(true)` unconditionally and never sets a custom
verifier (src/main/java/de/hock/mqtt/MqttPublisher.java, the `buildConnectOptions` body).
Three tests pin it: `pahoAppliesHttpsEndpointIdentificationOnSslByDefaultAndThePluginSetsItExplicitly`
asserts Paho's own default `true`, the plug-in's explicit `true`, and `getSSLHostnameVerifier() == null`;
and the two loopback handshake tests reproduce Paho's exact socket sequence (no-arg `createSocket()`,
`connect(InetSocketAddress(host, port))`, SNI parameters, endpoint-identification parameters,
`startHandshake`) with `TlsSupport.socketFactory` on the client side — matching SAN completes,
`wrong.example.com` throws `SSLHandshakeException`. The mismatch test was also proved to have teeth
(verification note §1). The residual risks are documented in README.md §"Hostname verification"
(IP-address configuration needs an IP SAN; mode 2 trusts any CA the server trusts; no off switch)
and in steering §5 item 10.

Both handshake tests carry `@Timeout(30)`, use a daemon accept thread, close the server socket in
`finally`, and do not instantiate Paho, so no non-daemon thread leaks into the suite.

### 1.4 No certificate or key material in any message — satisfied

`TlsSupport` throws only two fixed sentences (`PARSE_FAILED`, `NO_CERTIFICATES`) with the original
exception as cause (TlsSupport.java:88-96, 149-181). `ConnectionConfigValidator` rules 6 and 7 build
messages from literals plus the PEM header constant and **discard** the caught exception's message
(ConnectionConfigValidator.java, rules 5-7 block); the bare-number clause names "Appian document id"
without repeating the digits. Both integrations add a `catch (GeneralSecurityException)` clause
*ahead* of the existing catch, returning a fixed sentence with no `e.getMessage()`
(MqttPublishIntegration.java:185-192, MqttSubscribeIntegration.java:234-241).

The tests enforce it rather than assert it loosely: `malformedCaCertificateIsRejectedWithoutEchoingItsContent`
scans every 6-character run of the input against the thrown message for five malformed inputs, and the
three validator tests assert `/opt/certs/broker-ca.crt`, `d2hvbGx5` and `279401` are absent from their
messages. The bogus inputs were chosen so they share no English with the fixed sentences, which keeps
the scan strict instead of weakening it.

The surviving `catch (MqttException | RuntimeException e)` branch still includes `e.getMessage()`,
which for a TLS failure is Paho/JSSE wording naming the configured host and the PKIX outcome. It
cannot carry the pasted field's contents, it is the approved trade-off in the plan §3.3, and README
documents the two recognisable messages. Not a finding.

### 1.5 Negotiated protocol, not pinned — satisfied

`SSLContext.getInstance("TLS")` (TlsSupport.java:220). `tlsEnabledWithoutACaUsesTheDefaultTrustStoreOverAnSslUri`
creates a socket from the produced factory and asserts `TLSv1.2` is enabled and neither `TLSv1` nor
`TLSv1.1` is, which pins the decision without hard-coding a version list that a JDK update could break.

### 1.6 Everything in memory, no JVM-global mutation — satisfied

Spot-check (doubt: the verification note reports one prose hit, and a second prose hit had already
been found and reworded once during implementation, so the file set could have moved again):

```
grep -rnE 'System\.setProperty|javax\.net\.ssl\.(trustStore|keyStore)|createTempFile|File\.createTemp|java\.io\.File|java\.nio\.file' src/main/java/
→ src/main/java/de/hock/mqtt/MqttPublisher.java:32   (Javadoc prose asserting the absence)

grep -rn 'SSLContext\|setSocketFactory\|TrustManagerFactory' src/main/java/ | cut -d: -f1 | sort -u
→ de/hock/mqtt/MqttPublisher.java, de/hock/mqtt/TlsSupport.java
```

Both match the documented expectation exactly, so the `package-info.java` reword described in the
verification note §4 is in place. The trust store is created with `load(null, null)` and never
written; the PEM is read through a `ByteArrayInputStream` over a string already in memory, so there
is no closeable OS resource (TlsSupport.java:197-207). `MemoryPersistence` is still passed at both
Paho call sites, and both still disconnect/close in `finally` (subscribe also unsubscribes first) —
unchanged by this diff.

---

## 2. Platform and packaging

### 2.1 Expressionability — satisfied

Spot-check (the greps are cheap and are the documented necessary half):
template `0`, repo-wide `7`, per-file `MqttPublishIntegration:3` / `MqttSubscribeIntegration:4` —
all three unchanged despite two new fields, because neither new builder call touches the modifier.
The sufficient half is the descriptor test: `noConnectedSystemPropertyDescriptorIsExpressionable`
now asserts `assertEquals(11, inspected)` and still reads each modifier back through
`PropertyDescriptor.isExpressionable()`, and the new
`theTwoTlsFieldsUseBooleanAndParagraphDescriptorsAndAreNotExpressionable` adds the field-specific
guard, reading the rendered root type and asserting both descriptor classes and both modifiers.

### 2.2 Descriptor types — satisfied

`BooleanPropertyDescriptor` for `tlsEnabled`, `ParagraphPropertyDescriptor` for `caCertificate`,
inserted after `brokerPort` (MqttConnectedSystemTemplate.java, `getConfiguration`). No `Document`,
`AppianValue`, `ListType`, `Folder` or `Timestamp` descriptor appears on the connected system;
`connectedSystemFieldsUseNoListDocumentOrAppianValueDescriptor` now sweeps eleven fields. The value
accessors `textValue` / `booleanValue` are type-tolerant, so the PARAGRAPH value reads back as text.

### 2.3 `Simple*` base classes — unchanged

Template still `extends SimpleConnectedSystemTemplate`; both integrations still
`extends SimpleIntegrationTemplate`. The diff touches no class declaration.

### 2.4 Dependencies — unchanged

`pom.xml` diff is exactly one line (`1.0.6` → `1.0.7`). Four dependencies remain: core `1.9.1`
provided, client `1.3.0` provided, Paho `1.2.5` bundled, JUnit `5.10.2` test. `maven.compiler.release`
still 17. No crypto library was added, so `THIRD-PARTY-NOTICES.md` is correctly untouched (confirmed
in the note §7 rather than silently skipped).

### 2.5 Version and manifest identity — satisfied

`pom.xml` `1.0.7`; `appian-plugin.xml` `<version>1.0.7</version>` three-part, with
`<application-version min="26.9.0.0"/>` untouched four-part and
`<vendor name="Hock" url="https://itondemand.com"/>` unchanged. Plug-in key `de.hock.mqtt` and the
three module keys are untouched in the diff, so 1.0.7 replaces the installed 1.0.6, and the two
`<integration-template>` elements remain nested inside the single `<connected-system-template>`
(note §2 lines 34-43).

### 2.6 Jar invariants — evidence read, one cheap confirmation

The note's table covers all required checks: one jar, 8 `.java` entries all under `de/hock/mqtt/`,
bare-root `appian-plugin.xml`, Paho the only `META-INF/lib/` jar, zero `connected-systems`, zero
`com/appian/mqtt`, zero `test`, zero `validation/`, `THIRD-PARTY-NOTICES.md` present, zero
key/keystore entries, major version 61 for all seven classes. Spot-check (doubt: "exactly one jar"
and the artifact name are the two claims a stale build would break):
`ls target/*.jar` → `target/mqtt-broker-connected-system-1.0.7-plugin.jar`, single entry.
The claimed 8 sources match the real file list (`find src/main/java -name '*.java'` → the same eight
paths), and the source count is updated in README, steering §1 and the records.

### 2.7 Resource bundle — satisfied

Spot-check (doubt: two new constants plus a new package-private constant in `TlsSupport` could
perturb the derived key set). Ran the steering §4 derivation: diff empty, 24 keys, 18 field labels.
`TlsSupport.PEM_CERTIFICATE_HEADER` is package-private in another file and cannot leak into the
check. Header comment in the properties file updated from nine/22/16 to eleven/24/18.

---

## 3. Behaviour and regression

### 3.1 Mode 1 unchanged — satisfied, and pinned from two directions

`TlsSupport.brokerUri` returns `tcp://` for both `FALSE` and `null`, and
`buildConnectOptions` installs a socket factory **only** inside `if (TlsSupport.isTlsEnabled(tlsEnabled))`.
`tlsDisabledProducesATcpUriAndNoSocketFactory` asserts the `tcp://` URI for both inputs and — the
load-bearing half — a `null` socket factory with a *real* CA PEM present, so a stale certificate
cannot drag TLS into a plain connection. `pahoAppliesHttpsEndpointIdentification…` repeats the
null-factory assertion. The two `MqttPublisherConnectOptionsTest` tests changed only by gaining
`Boolean.FALSE, null` as leading arguments; every session assertion is untouched, so the 1.0.6
mapping is proved not to have moved. An existing 1.0.6 configuration carries no value for either new
key, and `isTlsEnabled(null) == false` keeps it on `tcp://`.

### 3.2 Mode 2 — satisfied

`ssl://host:port` plus `init((KeyStore) null)`, with no CA forced; >20 accepted issuers asserted
loosely, and a throwaway certificate rejected. The Javadoc records that `init((KeyStore) null)`
*reads* the server's JSSE configuration, which is a read and never a write.

### 3.3 Validator — satisfied

Rules 1-4 (host, port 1-65535, connect timeout, keep-alive) keep their exact position and wording;
the diff only appends. Rules 5-7 are added with clear messages, and the early `if (!hasCa) return null;`
means a blank certificate changes nothing. No `setErrorAtPath` / `clearErrorsAtPath` call is
introduced anywhere. Both integrations call `ConnectionConfigValidator.firstError(connection)` before
any broker contact — verified in the unchanged guard blocks (publish: blank topic → `firstError` →
QoS range; subscribe: blank filter → `firstError` → input ranges) — so every TLS rule runs before a
socket is opened. Rule 5 as a hard error is the plan's explicit judgement call with its trade-off
documented in README §"What a failure looks like"; honored as approved.

### 3.4 Paho confinement, single `buildConnectOptions`, closeables — satisfied

`grep -rc 'org\.eclipse\.paho' src/main/java` → `MqttPublisher:4`, `MqttSubscriber:5`, each
integration `1` (the `MqttException` import only). `TlsSupport` holds no Paho reference, so the
"exactly two call sites" invariant survives, and `setSocketFactory` is called from the one shared
`buildConnectOptions`, which `MqttSubscriber` still delegates to. No duplication of the
default-from-`null` logic. Teardown paths are untouched by this diff.

### 3.5 Logging — satisfied

`grep -rn 'System\.out\|System\.err\|printStackTrace\|java\.util\.logging' src/main/java/` → no
hits; no logging dependency added.

### 3.6 Tests — satisfied

35 tests in nine classes per the pasted Surefire output (5 + 3 + 2 + 4 + 1 + 8 + 2 + 7 + 3 = 35).
All 23 previous tests survive: the diff shows only a rename
(`exactlyTheNine…` → `exactlyTheEleven…`), count updates (9 → 11), key-list extensions and the two
new leading arguments in `MqttPublisherConnectOptionsTest`. No test needs a broker; the only socket
activity is the two loopback binds on `127.0.0.1:0`, which are documented in README and steering §5
item 4. `CertificateFixtures` generates keys per call, keeps private keys as in-memory objects, and
uses a random single-use passphrase; no `.pem/.key/.p12/.jks` file exists in the repo or the jar, and
the only "BEGIN … PRIVATE KEY" hits in the tree are the grep patterns inside the plan and the
steering file.

### 3.7 Documentation lockstep — satisfied

README carries a new `## TLS` section with the two fields and why PEM text rather than a document
upload, the three modes with when to use each, the port convention and the explicit statement that
the plug-in never rewrites the port, the hostname-verification finding as measured plus three stated
residual risks, "Mutual TLS is not supported", the designer-visible message table, and the
what-it-does-not-do list. The eleven-field table, the version, the artifact name, the eight-source
count, "renders eleven fields", the 35-tests-in-nine-classes breakdown and the rewritten
"Runtime behavior" bullet are all updated. `docs/mqtt-plugin-guide.md` gains a 1.0.7 banner and a
pointer under its own TLS table to `README.md#tls` (anchor exists). `info.md` is at 1.0.7 with
"**Mutual** TLS … remain deliberately absent". The steering file updates every figure I checked
(37/4 imports, 8 sources, 1.0.7 artifact and manifest version, eleven fields, nine test classes,
24 bundle keys, the §4 block) and adds the in-memory-`SSLContext` §3 rule and §5 item 10; the §1 and
§2 hard constraints are intact, and the deletions in that diff are figure replacements, not relaxed
rules — including the credential bullet, which is preserved and extended to bind the TLS tests. The
release record exists with the deploy-and-test checklist and the three 1.0.3 TLS defects.

### 3.8 Honesty of the evidence — satisfied

Every claim I sampled is reproducible against the real tree: eight main sources, 37 SDK imports
across 4 files, expressionability 0/7/3/4, TLS surface exactly two files, the single prose hit for
the banned-token grep, 24 bundle keys with an empty derived diff, one jar named `…-1.0.7-plugin.jar`,
and dependency coordinates unchanged. The note also reports, rather than hides, the one expectation
that failed on first run (the `package-info.java` `SSLContext` prose hit) and says the prose was
reworded instead of the expectation weakened — which the current grep confirms. Section 10 lists
what the verification does not establish, including that 1.0.7 is not deployed and that the
wrong-CA rejection has not been seen end to end.

---

## 4. Non-blocking observations (no action required for approval)

1. `pom.xml:88` still comments "covers all **seven** test classes"; there are now nine. The figure is
   prose in a file AppMarket reviewers read, and steering §2 already says nine, so the two disagree.
   Cosmetic, outside the brief's documentation list, and not a correctness or policy breach.
2. The verification note §9 pastes `package-info.java | 12 +-` where the tree now shows 13. The paste
   predates the reword described in the note's own §4; the substantive claim (TLS surface = two files)
   holds.
3. Deploy-gated risks remain, and are recorded honestly rather than claimed away: Paho's `ssl`
   `NetworkModuleFactory` resolving through Appian's plug-in classloader, mode 2 against the Appian
   server's own trust store, and the wrong-CA rejection observed end to end. The known-good reference
   is correctly left at 1.0.6 / `68e15e8`, and no SHA is invented for this change.
