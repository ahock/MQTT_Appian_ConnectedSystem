package de.hock.mqtt.template;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.appian.connectedsystems.templateframework.sdk.configuration.AppianValuePropertyDescriptor;
import com.appian.connectedsystems.templateframework.sdk.configuration.ConfigurationDescriptor;
import com.appian.connectedsystems.templateframework.sdk.configuration.DocumentPropertyDescriptor;
import com.appian.connectedsystems.templateframework.sdk.configuration.ListTypePropertyDescriptor;
import com.appian.connectedsystems.templateframework.sdk.configuration.LocalTypeDescriptor;
import com.appian.connectedsystems.templateframework.sdk.configuration.ParagraphPropertyDescriptor;
import com.appian.connectedsystems.templateframework.sdk.configuration.PropertyDescriptor;
import com.appian.connectedsystems.templateframework.sdk.configuration.PropertyPath;
import com.appian.connectedsystems.templateframework.sdk.configuration.PropertyState;
import com.appian.connectedsystems.templateframework.sdk.configuration.RefreshPolicy;
import de.hock.mqtt.MqttConnectionConfig;
import de.hock.mqtt.integration.MqttPublishIntegration;
import de.hock.mqtt.integration.MqttSubscribeIntegration;
import de.hock.mqtt.protocol.ConnectOptionsFactory;
import de.hock.mqtt.validation.ConfigFields;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the design-time configuration descriptor.
 *
 * <p>These reproduce the reported failure: opening <em>New &gt; Connected System</em> handed the
 * template a {@link ConfigurationDescriptor} whose root {@link PropertyState} was {@code null}, and
 * the template dereferenced it, so the configuration form failed with an expression-evaluation
 * error. {@code ConfigurationDescriptor.builder().build()} is exactly that input — its
 * {@code getRootState()} is {@code null} — so each test here drives the real framework entry point
 * ({@code getConfigurationDescriptor}, inherited from the {@code Simple*} base classes) with it.
 *
 * <p>The suite also covers both halves of the first-render behavior: a brand-new connected system
 * renders with no validation errors at all, and validation reports against its fields on every
 * subsequent render — including clearing only the error of a field that was filled in.
 *
 * <p>A third concern is pinned here as well: the platform's <em>descriptor-modifier</em> rules,
 * which are enforced only when a real Appian instance converts the descriptor. The
 * expressionability rule is deliberately asymmetric — a connected system template must declare no
 * expressionable property descriptor (Appian rejects the descriptor outright with "Property
 * Descriptors in Connected System Template must not be Expressionable"), while integration input
 * descriptors are permitted to be expressionable and a list-typed input is required to be. Both
 * halves are asserted, per field key, by reading the modifier back through the SDK's public
 * {@link PropertyDescriptor} accessors — the same object graph Appian's validator walks — rather
 * than by inspecting source text.
 *
 * <p>No Appian runtime is involved: the SDK's configuration model is plain Java, and the
 * {@code ExecutionContext} is unused on this path, so it is passed as {@code null}.
 */
class MqttConnectedSystemTemplateDescriptorTest {

  /** Every configuration field the Connected System declares, in descriptor order. */
  private static final List<String> CONFIG_FIELD_KEYS =
      List.of(
          ConfigFields.BROKER_HOST,
          ConfigFields.BROKER_PORT,
          ConfigFields.CLIENT_IDENTIFIER,
          ConfigFields.CONNECTION_TIMEOUT_SECONDS,
          ConfigFields.USERNAME,
          ConfigFields.PASSWORD,
          ConfigFields.TLS_ENABLED,
          ConfigFields.CA_CERTIFICATE,
          ConfigFields.CLIENT_CERTIFICATE,
          ConfigFields.CLIENT_PRIVATE_KEY,
          ConfigFields.KEY_PASSPHRASE,
          ConfigFields.AUTO_RECONNECT_ENABLED,
          ConfigFields.MAX_RECONNECT_ATTEMPTS,
          ConfigFields.RECONNECT_INTERVAL_SECONDS,
          ConfigFields.LWT_TOPIC,
          ConfigFields.LWT_PAYLOAD,
          ConfigFields.LWT_QOS,
          ConfigFields.LWT_RETAINED,
          ConfigFields.CLEAN_SESSION,
          ConfigFields.KEEP_ALIVE_SECONDS);

  /**
   * A throwaway self-signed X.509 certificate, used to prove that pasted PEM is really parsed.
   *
   * <p>This is a <em>certificate</em> only — public key material, not a credential. No private key,
   * keystore, or passphrase is present here or anywhere in the repository, and the key pair it was
   * generated from was discarded. It is valid solely so {@code CertificateFactory} accepts it.
   */
  private static final String TEST_CERTIFICATE_PEM =
      """
      -----BEGIN CERTIFICATE-----
      MIIDMzCCAhugAwIBAgIINcr9TnQc86owDQYJKoZIhvcNAQEMBQAwSDELMAkGA1UE
      BhMCREUxEDAOBgNVBAoTB0V4YW1wbGUxEDAOBgNVBAsTB1Rlc3RpbmcxFTATBgNV
      BAMTDG1xdHQtdGVzdC1jYTAeFw0yNjEwMTQxNDE1NDNaFw0zNjEwMTExNDE1NDNa
      MEgxCzAJBgNVBAYTAkRFMRAwDgYDVQQKEwdFeGFtcGxlMRAwDgYDVQQLEwdUZXN0
      aW5nMRUwEwYDVQQDEwxtcXR0LXRlc3QtY2EwggEiMA0GCSqGSIb3DQEBAQUAA4IB
      DwAwggEKAoIBAQCrn6uNQTqGbJBunKnfq2nCvfqHhDJS0aOKAgQkkkpHKFItZX9l
      Ur2sAvSYKuWdHCn3RUNA3bfe7rPA5SyrkEyj4IQiJx94A5NbdYy8hvrxbABBu6nF
      bpJHeNcchM9BKHVsGsAT9moMdA7ndTP8aufI2l2Qr62ohfN1aUNQ7uK3l3Ri+sWR
      EEFYAWCkNvSSmDcNusvXltiooV7edGhdJLkAx8Tquw7qPZ9c8SH9NujHnsrxw7cd
      NG3YGrnNHzSCe17qjR19HkG7kTqVH7W3rWoMoh1ssCps7615kjK7oMsBR1amiYDF
      Ptw16HamTM+P5Dj2NzwAvdbwqtdTYOqOMjHrAgMBAAGjITAfMB0GA1UdDgQWBBRZ
      7vveAYVLJSei0a6tPPPC0YrIrTANBgkqhkiG9w0BAQwFAAOCAQEAC6FQduwKC2o2
      sTRswmFggdqkuaxojJThM3hTVJDkEEbjAuUkA5RzSi1eEhDHQ2txN8EFvxtaKonD
      5iMhL6VLg6XOqq2+fxZOrkuxaa6bCfk2BxMTEwkB6jrOHAUD7JwgNMa1FE9xmgVI
      /6cL6tGe7AfHU20fzdXhnciS4WlKBhm3s4/brQNI1VWW+d1ln4ObiUPVvvbPPsGm
      TcCdZwKFwq/V9QS4NpxjB9vusUzJGmHSVVM7DHcR7hsFeXit+jvX2oJQ7rNcsYPW
      /KYNhMC9ZsxWG4OOL8iSW4L728yubffvT+yRjzMutUeKIltF47tzAhXO4LACcv6p
      ruGmgVaOcQ==
      -----END CERTIFICATE-----
      """;

  private static ConfigurationDescriptor emptyDescriptor() {
    return ConfigurationDescriptor.builder().build();
  }

  private static ConfigurationDescriptor renderConnectedSystem(ConfigurationDescriptor incoming) {
    return new MqttConnectedSystemTemplate()
        .getConfigurationDescriptor(incoming, new PropertyPath(), null);
  }

  /** Resolves the root {@link LocalTypeDescriptor} a rendered descriptor registered. */
  private static LocalTypeDescriptor rootTypeOf(ConfigurationDescriptor rendered) {
    LocalTypeDescriptor rootType =
        rendered.getTypes().get(rendered.getRootState().getType().getUnqualifiedTypeName());
    assertNotNull(rootType, "the root state's type must resolve in the descriptor's type map");
    return rootType;
  }

  /** The Connected System's root type, rendered from the descriptor Appian supplies for a new object. */
  private static LocalTypeDescriptor connectedSystemRootType() {
    return rootTypeOf(renderConnectedSystem(emptyDescriptor()));
  }

  /**
   * Reads the expressionable modifier back through the SDK's public descriptor API, failing loudly
   * on a renamed key rather than silently passing.
   */
  private static boolean expressionable(LocalTypeDescriptor rootType, String key) {
    PropertyDescriptor<?> descriptor =
        rootType
            .getProperty(key)
            .orElseThrow(() -> new AssertionError("no such input descriptor: " + key));
    return descriptor.isExpressionable();
  }

  /**
   * Whether a field carries a validation error. {@code getErrorsAtPath} may return {@code null} or
   * an empty list for a field with no errors, so both are tolerated.
   */
  private static boolean hasErrors(PropertyState state, String fieldKey) {
    List<String> errors = state.getErrorsAtPath(new PropertyPath(fieldKey));
    return errors != null && !errors.isEmpty();
  }

  @Test
  void rootStateIsPopulatedForABrandNewConnectedSystem() {
    ConfigurationDescriptor incoming = emptyDescriptor();
    // Guards the premise: this is the state Appian supplies on New > Connected System.
    assertNotNull(incoming, "incoming descriptor");
    assertTrue(incoming.getRootState() == null, "a new connected system has no root state yet");

    ConfigurationDescriptor rendered = renderConnectedSystem(incoming);

    assertNotNull(
        rendered.getRootState(),
        "root state must be generated, or the configuration UI fails with an NPE on getRootState()");
  }

  @Test
  void rootTypeIsRegisteredAndResolvable() {
    ConfigurationDescriptor rendered = renderConnectedSystem(emptyDescriptor());

    String rootTypeName = rendered.getRootState().getType().getUnqualifiedTypeName();
    assertNotNull(rootTypeName, "root state must carry a type reference");
    assertFalse(rendered.getTypes().isEmpty(), "the descriptor must register its local types");
    assertTrue(
        rendered.getTypes().containsKey(rootTypeName),
        "the root state's type reference must resolve in the descriptor's type map");
  }

  @Test
  void allTwentyConfigFieldsArePresentInTheGeneratedState() {
    ConfigurationDescriptor rendered = renderConnectedSystem(emptyDescriptor());
    PropertyState rootState = rendered.getRootState();
    LocalTypeDescriptor rootType =
        rendered.getTypes().get(rootState.getType().getUnqualifiedTypeName());

    assertEquals(20, CONFIG_FIELD_KEYS.size(), "the Connected System declares 20 fields");
    for (String key : CONFIG_FIELD_KEYS) {
      assertTrue(rootType.getPropertyKeys().contains(key), "root type must declare field " + key);
      assertNotNull(
          rootState.getPropertyStateAtPath(new PropertyPath(key)),
          "generated state must contain field " + key);
    }
  }

  @Test
  void cleanSessionDefaultsToTrueOnFirstRenderAndRespectsADeliberateFalse() {
    ConfigurationDescriptor firstRender = renderConnectedSystem(emptyDescriptor());
    assertEquals(
        Boolean.TRUE,
        firstRender.getRootState().getValueAtPath(new PropertyPath(ConfigFields.CLEAN_SESSION)),
        "Clean Session defaults to true (Req 8.1)");

    // A Designer clears the checkbox, then the form re-renders.
    firstRender
        .getRootState()
        .setValueAtPath(new PropertyPath(ConfigFields.CLEAN_SESSION), Boolean.FALSE);
    ConfigurationDescriptor secondRender = renderConnectedSystem(firstRender);

    assertEquals(
        Boolean.FALSE,
        secondRender.getRootState().getValueAtPath(new PropertyPath(ConfigFields.CLEAN_SESSION)),
        "a deliberate false must not be overwritten by the default");
  }

  @Test
  void firstRenderOfANewConnectedSystemHasNoValidationErrors() {
    ConfigurationDescriptor rendered = renderConnectedSystem(emptyDescriptor());
    PropertyState rootState = rendered.getRootState();

    for (String key : CONFIG_FIELD_KEYS) {
      assertFalse(
          hasErrors(rootState, key),
          "the first render of a new connected system must open clean, but field "
              + key
              + " reported "
              + rootState.getErrorsAtPath(new PropertyPath(key)));
    }
  }

  @Test
  void blankBrokerHostIsReportedOnEverySubsequentRender() {
    ConfigurationDescriptor firstRender = renderConnectedSystem(emptyDescriptor());
    assertFalse(
        hasErrors(firstRender.getRootState(), ConfigFields.BROKER_HOST),
        "the first render of a new connected system must open clean");

    // Feeding the first render straight back is a subsequent render: setProperties seeded
    // cleanSession to Boolean.TRUE, so the firstRender signal now evaluates false and validate runs.
    ConfigurationDescriptor secondRender = renderConnectedSystem(firstRender);

    List<String> errors =
        secondRender.getRootState().getErrorsAtPath(new PropertyPath(ConfigFields.BROKER_HOST));
    assertNotNull(errors, "broker host errors");
    assertFalse(
        errors.isEmpty(),
        "a blank required broker host must be reported on its field once the form re-renders");
  }

  @Test
  void enteringAValueClearsItsErrorWhileOtherFieldsStillReport() {
    ConfigurationDescriptor firstRender = renderConnectedSystem(emptyDescriptor());
    firstRender
        .getRootState()
        .setValueAtPath(new PropertyPath(ConfigFields.BROKER_HOST), "broker.example.com");

    ConfigurationDescriptor secondRender = renderConnectedSystem(firstRender);
    PropertyState rootState = secondRender.getRootState();

    assertFalse(
        hasErrors(rootState, ConfigFields.BROKER_HOST),
        "a supplied broker host must not be reported as an error");
    assertTrue(
        hasErrors(rootState, ConfigFields.BROKER_PORT),
        "a still-blank required broker port must be reported, proving ConfigValidator was not weakened");
  }

  /**
   * The rule that broke New &gt; Connected System: Appian's
   * {@code ConnectedSystemPropertiesValidator.checkPropertiesModifiers} rejects a connected system
   * descriptor carrying <em>any</em> expressionable property, so the configuration form never
   * renders. This fails if {@code isExpressionable(true)} is reintroduced on any of the 20 fields.
   *
   * <p>It reports every offending field rather than the first, because the modifier can also be set
   * by the SDK itself: {@code DocumentPropertyDescriptorBuilder}'s constructor seeds
   * {@code isExpressionable(true)} and its {@code checkValidState} refuses to build with it cleared
   * ({@code IllegalStateException: DOCUMENT property descriptors must be expressionable}), so a
   * document field is expressionable whether or not the source says so.
   */
  @Test
  void noConnectedSystemPropertyDescriptorIsExpressionable() {
    LocalTypeDescriptor rootType = connectedSystemRootType();

    List<String> offenders = new ArrayList<>();
    int inspected = 0;
    for (PropertyDescriptor<?> descriptor : rootType.getProperties()) {
      if (descriptor.isExpressionable()) {
        offenders.add(descriptor.getKey() + " (" + descriptor.getClass().getSimpleName() + ")");
      }
      inspected++;
    }

    assertEquals(20, inspected, "all 20 connected system fields must have been inspected");
    assertTrue(
        offenders.isEmpty(),
        "Appian rejects a connected system template declaring any expressionable property "
            + "descriptor (APNX-1-4198-000), but these fields read back expressionable: "
            + offenders);
  }

  /**
   * The other half of the same rule: integration inputs <em>may</em> be expressionable, and the
   * current code is deliberately asymmetric about it — all four publish inputs accept expressions,
   * while subscribe's {@code qos} does not. Asserting per key stops a future repo-wide
   * "expressionability cleanup" from stripping working integration behavior. Appian's 26.9
   * restrictions page additionally requires a {@code ListTypePropertyDescriptor} to be
   * expressionable, which is why subscribe's {@code topicFilters} must keep the flag.
   */
  @Test
  void integrationInputExpressionabilityIsPinnedPerInput() {
    LocalTypeDescriptor publishRootType =
        rootTypeOf(
            new MqttPublishIntegration()
                .getConfigurationDescriptor(
                    emptyDescriptor(), emptyDescriptor(), new PropertyPath(), null));
    for (String key : List.of("topic", "payload", "qos", "retained")) {
      assertTrue(
          expressionable(publishRootType, key),
          "publish input " + key + " must stay expressionable");
    }

    LocalTypeDescriptor subscribeRootType =
        rootTypeOf(
            new MqttSubscribeIntegration()
                .getConfigurationDescriptor(
                    emptyDescriptor(), emptyDescriptor(), new PropertyPath(), null));
    for (String key : List.of("topicFilters", "maxWaitSeconds", "maxMessageCount")) {
      assertTrue(
          expressionable(subscribeRootType, key),
          "subscribe input " + key + " must stay expressionable");
    }
    assertFalse(
        expressionable(subscribeRootType, "qos"),
        "subscribe qos is a choice list and is deliberately not expressionable");
  }

  /**
   * The remaining documented connected-system descriptor rules, pinned now that the descriptors are
   * in hand: every field refreshes {@link RefreshPolicy#NEVER}, no field is hidden, and no field is
   * list-typed. Nothing violates these today — this guards against the next contract surprise being
   * found only at deploy time.
   */
  @Test
  void connectedSystemDescriptorsSatisfyThePlatformModifierRules() {
    for (PropertyDescriptor<?> descriptor : connectedSystemRootType().getProperties()) {
      assertEquals(
          RefreshPolicy.NEVER,
          descriptor.getRefresh(),
          "connected system field " + descriptor.getKey() + " must use RefreshPolicy.NEVER");
      assertFalse(
          descriptor.isHidden(),
          "connected system field " + descriptor.getKey() + " must not be hidden");
      // These three descriptor types set isExpressionable(true) in their own builder constructors,
      // and Document/AppianValue additionally refuse to build with it cleared
      // (Guard.checkPropertyIsExpressionable). They are therefore unusable on a connected system
      // template, whatever the declaring code asks for.
      assertFalse(
          descriptor instanceof ListTypePropertyDescriptor,
          "connected system field " + descriptor.getKey() + " must not be LIST type");
      assertFalse(
          descriptor instanceof DocumentPropertyDescriptor,
          "connected system field "
              + descriptor.getKey()
              + " must not be a document field: the SDK forces document descriptors to be "
              + "expressionable, which Appian rejects on a connected system template");
      assertFalse(
          descriptor instanceof AppianValuePropertyDescriptor,
          "connected system field "
              + descriptor.getKey()
              + " must not be an Appian-value field: the SDK forces it to be expressionable");
    }
  }

  /**
   * The two certificate fields are multi-line text, not document uploads.
   *
   * <p>They must stay {@link ParagraphPropertyDescriptor}: PEM is inherently multi-line, and
   * {@code DisplayHint} in core 1.9.1 offers only {@code NORMAL} and {@code EXPRESSION}, so
   * paragraph is the SDK's only multi-line text input. A {@code DocumentPropertyDescriptor} cannot
   * be used here at all — see the modifier-rules test.
   */
  @Test
  void certificateFieldsAreMultiLinePastedTextNotDocuments() {
    LocalTypeDescriptor rootType = connectedSystemRootType();

    for (String key : List.of(ConfigFields.CA_CERTIFICATE, ConfigFields.CLIENT_CERTIFICATE)) {
      PropertyDescriptor<?> descriptor =
          rootType.getProperty(key).orElseThrow(() -> new AssertionError("no such field: " + key));
      assertTrue(
          descriptor instanceof ParagraphPropertyDescriptor,
          "certificate field "
              + key
              + " must be a multi-line paragraph field, but was "
              + descriptor.getClass().getSimpleName());
      assertFalse(descriptor.isExpressionable(), "certificate field " + key);
    }
  }

  /**
   * End-to-end proof of the handoff this release changes: a Designer pastes PEM into the descriptor,
   * and those exact bytes reach the X.509 parser.
   *
   * <p>The chain asserted is descriptor value → {@code resolveCertificateBytes} (String → UTF-8
   * bytes) → {@link MqttConnectionConfig} → {@link ConnectOptionsFactory}, whose
   * {@code buildTrustManagers} throws unless {@link java.security.cert.CertificateFactory} parses
   * the bytes into at least one certificate. A non-null socket factory therefore means the pasted
   * text really was parsed as a certificate and installed into an {@code SSLContext}.
   */
  @Test
  void pastedPemCertificateReachesTheTlsLayerAsBytes() throws Exception {
    ConfigurationDescriptor rendered = renderConnectedSystem(emptyDescriptor());
    PropertyState rootState = rendered.getRootState();
    rootState.setValueAtPath(new PropertyPath(ConfigFields.BROKER_HOST), "broker.example.com");
    rootState.setValueAtPath(new PropertyPath(ConfigFields.BROKER_PORT), 8883);
    rootState.setValueAtPath(new PropertyPath(ConfigFields.TLS_ENABLED), Boolean.TRUE);
    rootState.setValueAtPath(new PropertyPath(ConfigFields.CA_CERTIFICATE), TEST_CERTIFICATE_PEM);

    MqttConnectionConfig config = MqttConnectedSystemTemplate.toConnectionConfig(rootState);

    assertArrayEquals(
        TEST_CERTIFICATE_PEM.getBytes(StandardCharsets.UTF_8),
        config.caCertificate(),
        "the pasted PEM text must reach the config as its UTF-8 bytes");

    MqttConnectOptions options = ConnectOptionsFactory.build(config);

    assertNotNull(
        options.getSocketFactory(),
        "the pasted certificate must be parsed into a trust manager and an SSL socket factory");
  }

  /**
   * The migration consequence, made visible instead of opaque.
   *
   * <p>A connected system saved by 1.0.2 or earlier holds a document reference in these fields,
   * which this version reads as text — typically a bare numeric id. That cannot parse as a
   * certificate, and this SDK version could never resolve it to certificate bytes either, so the
   * remedy is for the Designer to paste the PEM. Without this check the only symptom would be a
   * handshake failure at run time.
   */
  @Test
  void aLegacyDocumentReferenceInACertificateFieldIsReportedAsNonPem() {
    ConfigurationDescriptor firstRender = renderConnectedSystem(emptyDescriptor());
    PropertyState staleState = firstRender.getRootState();
    staleState.setValueAtPath(new PropertyPath(ConfigFields.TLS_ENABLED), Boolean.TRUE);
    // What a document reference saved by an earlier version reads back as.
    staleState.setValueAtPath(new PropertyPath(ConfigFields.CA_CERTIFICATE), "216821");

    ConfigurationDescriptor afterUpgrade = renderConnectedSystem(firstRender);

    assertTrue(
        hasErrors(afterUpgrade.getRootState(), ConfigFields.CA_CERTIFICATE),
        "a stored document reference is not PEM and must be reported on its field");
  }

  /**
   * Valid pasted PEM passes the shape check, on a render where validation genuinely runs.
   *
   * <p>Deliberately asserted on a freshly supplied state rather than by fixing the field on the
   * state used by the test above, because those are two different claims. That "an error attached
   * by an earlier render disappears once the field is corrected" does <em>not</em> hold on a reused
   * state object: {@code validate} only attaches errors and never calls the SDK's
   * {@code clearErrorsAtPath}, so an error set on render N survives on render N+1 even after the
   * value is fixed. That is a pre-existing error-lifecycle issue, independent of certificates and
   * of this release's change, and it is recorded as an open item rather than fixed here.
   */
  @Test
  void validPastedPemIsNotReportedAsAnError() {
    ConfigurationDescriptor firstRender = renderConnectedSystem(emptyDescriptor());
    PropertyState state = firstRender.getRootState();
    state.setValueAtPath(new PropertyPath(ConfigFields.TLS_ENABLED), Boolean.TRUE);
    state.setValueAtPath(new PropertyPath(ConfigFields.CA_CERTIFICATE), TEST_CERTIFICATE_PEM);
    state.setValueAtPath(new PropertyPath(ConfigFields.CLIENT_CERTIFICATE), TEST_CERTIFICATE_PEM);

    ConfigurationDescriptor validated = renderConnectedSystem(firstRender);
    PropertyState validatedState = validated.getRootState();

    assertFalse(
        hasErrors(validatedState, ConfigFields.CA_CERTIFICATE),
        "valid pasted PEM must not be reported on the CA certificate field");
    // The client certificate field does report here, but for the pre-existing reason that a client
    // certificate without its private key is an incomplete mutual-TLS configuration — not because
    // the PEM was rejected. Asserting which error it is keeps the two causes from being confused.
    List<String> clientCertificateErrors =
        validatedState.getErrorsAtPath(new PropertyPath(ConfigFields.CLIENT_CERTIFICATE));
    assertNotNull(clientCertificateErrors, "client certificate errors");
    assertTrue(
        clientCertificateErrors.stream().anyMatch(message -> message.contains("private key")),
        "a client certificate supplied without a private key must be reported as a missing key, "
            + "but was: "
            + clientCertificateErrors);
    assertFalse(
        clientCertificateErrors.stream().anyMatch(message -> message.contains("-----BEGIN")),
        "valid pasted PEM must not be reported as a malformed value, but was: "
            + clientCertificateErrors);
    assertTrue(
        hasErrors(validatedState, ConfigFields.BROKER_HOST),
        "validation must actually have run on this render, or the assertions above prove nothing");
  }

  @Test
  void publishIntegrationPopulatesItsRootStateAndFourInputs() {
    ConfigurationDescriptor rendered =
        new MqttPublishIntegration()
            .getConfigurationDescriptor(emptyDescriptor(), emptyDescriptor(), new PropertyPath(), null);

    assertNotNull(rendered.getRootState(), "publish integration root state");
    LocalTypeDescriptor rootType =
        rendered.getTypes().get(rendered.getRootState().getType().getUnqualifiedTypeName());
    assertNotNull(rootType, "publish integration root type must be registered");
    assertEquals(
        List.of("topic", "payload", "qos", "retained"),
        rootType.getPropertyKeys(),
        "publish inputs, in display order");
  }

  @Test
  void subscribeIntegrationPopulatesItsRootStateAndFourInputs() {
    ConfigurationDescriptor rendered =
        new MqttSubscribeIntegration()
            .getConfigurationDescriptor(emptyDescriptor(), emptyDescriptor(), new PropertyPath(), null);

    assertNotNull(rendered.getRootState(), "subscribe integration root state");
    LocalTypeDescriptor rootType =
        rendered.getTypes().get(rendered.getRootState().getType().getUnqualifiedTypeName());
    assertNotNull(rootType, "subscribe integration root type must be registered");
    assertEquals(
        List.of("topicFilters", "qos", "maxWaitSeconds", "maxMessageCount"),
        rootType.getPropertyKeys(),
        "subscribe inputs, in display order");
  }
}
