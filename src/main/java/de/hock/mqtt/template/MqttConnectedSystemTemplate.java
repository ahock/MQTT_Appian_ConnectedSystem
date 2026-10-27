package de.hock.mqtt.template;

import com.appian.connectedsystems.simplified.sdk.SimpleConnectedSystemTemplate;
import com.appian.connectedsystems.simplified.sdk.configuration.SimpleConfiguration;
import com.appian.connectedsystems.templateframework.sdk.ExecutionContext;
import com.appian.connectedsystems.templateframework.sdk.TemplateId;
import com.appian.connectedsystems.templateframework.sdk.configuration.BooleanDisplayMode;
import com.appian.connectedsystems.templateframework.sdk.configuration.BooleanPropertyDescriptor;
import com.appian.connectedsystems.templateframework.sdk.configuration.Choice;
import com.appian.connectedsystems.templateframework.sdk.configuration.EncryptedTextPropertyDescriptor;
import com.appian.connectedsystems.templateframework.sdk.configuration.IntegerPropertyDescriptor;
import com.appian.connectedsystems.templateframework.sdk.configuration.ParagraphPropertyDescriptor;
import com.appian.connectedsystems.templateframework.sdk.configuration.PropertyDescriptor;
import com.appian.connectedsystems.templateframework.sdk.configuration.PropertyPath;
import com.appian.connectedsystems.templateframework.sdk.configuration.PropertyState;
import com.appian.connectedsystems.templateframework.sdk.configuration.TextPropertyDescriptor;
import de.hock.mqtt.MqttConnectionConfig;
import de.hock.mqtt.validation.CertificateLoader;
import de.hock.mqtt.validation.ClientIdentifierGenerator;
import de.hock.mqtt.validation.ConfigFields;
import de.hock.mqtt.validation.ConfigValidator;
import de.hock.mqtt.validation.LwtValidator;
import de.hock.mqtt.validation.PemValidator;
import de.hock.mqtt.validation.ReconnectionValidator;
import de.hock.mqtt.validation.ValidationResult;
import de.hock.mqtt.validation.ValidationResults;
import java.nio.charset.StandardCharsets;

/**
 * Connected System template that declares the reusable MQTT broker configuration surface.
 *
 * <p>This class is the design-time configuration descriptor for the plug-in. It declares six field
 * groups — Connection, Authentication, TLS, Reconnection, Last Will and Testament (LWT), and
 * Session — matching the design's "Components and Interfaces &gt; MqttConnectedSystemTemplate"
 * section. A Designer fills these in once on the Connected System object; the Publish and Subscribe
 * integrations then reuse the same configuration.
 *
 * <p><strong>SDK shape.</strong> The class extends {@link SimpleConnectedSystemTemplate} from
 * {@code com.appian:connected-systems-client} and declares its fields in
 * {@link #getConfiguration(SimpleConfiguration, ExecutionContext)}, which is Appian's documented
 * contract for a connected system. The facade owns the two things the low-level
 * {@code ConnectedSystemTemplate} interface leaves to the implementer: it generates the root
 * {@link PropertyState} from the declared properties (tolerating the {@code null} state Appian
 * supplies for a brand-new connected system) and registers the generated root
 * {@code LocalTypeDescriptor} in the descriptor's type map. Implementing the low-level interface
 * instead is what caused {@code ConfigurationDescriptor.getRootState()} to be {@code null} and the
 * New &gt; Connected System form to fail with an expression-evaluation error.
 *
 * <p><strong>No field here may be expressionable.</strong> Appian forbids expressionable property
 * descriptors on a connected system template:
 * {@code ConnectedSystemPropertiesValidator.checkPropertiesModifiers} rejects the whole descriptor
 * at conversion time with "Property Descriptors in Connected System Template must not be
 * Expressionable (APNX-1-4198-000)", so New &gt; Connected System fails to render at all. No
 * descriptor in {@link #allProperties()} may be marked expressionable.
 *
 * <p>Two traps follow from that. First, the restriction applies to connected system templates only
 * — integration input descriptors may be expressionable, and a {@code ListTypePropertyDescriptor}
 * input must be, which is why the Publish and Subscribe integrations keep theirs. Second, some SDK
 * descriptor builders set the flag themselves and cannot be talked out of it:
 * {@code DocumentPropertyDescriptor} and {@code AppianValuePropertyDescriptor} both seed it in
 * their constructor and reject a cleared flag in {@code checkValidState}, which makes those two
 * types unusable on a connected system template entirely. That is why the certificate fields below
 * take pasted text rather than a document upload.
 *
 * <p><strong>Sensitive fields (Req 1.6).</strong> {@code password}, {@code clientPrivateKey}, and
 * {@code keyPassphrase} are declared with {@link EncryptedTextPropertyDescriptor} so the platform
 * stores them encrypted, masks them in the UI, and does not render their values back after save.
 *
 * <p><strong>TLS (Req 3.1, 3.5).</strong> A boolean {@code tlsEnabled} toggle and two
 * {@link ParagraphPropertyDescriptor} fields — {@code caCertificate} and
 * {@code clientCertificate} — are present so a Designer can enable TLS, validate the broker, and
 * present a client certificate for mutual authentication. Both take <strong>pasted PEM text</strong>
 * rather than a document upload, which makes the three TLS material fields consistent:
 * {@code clientPrivateKey} was already text. Certificates are public key material, so holding them
 * as plain text is not a secret-handling downgrade; the private key and its passphrase remain
 * encrypted and masked.
 *
 * <p>Field keys come from {@link ConfigFields} so the descriptor, the validators, and the config
 * mapping all agree on a single source of truth.
 *
 * <p><strong>Design-time validation (task 9.2).</strong> Because the connected system contract
 * declares no separate {@code validate(...)} method, validation runs inside
 * {@link #getConfiguration(SimpleConfiguration, ExecutionContext)}: the
 * raw entered values are read from the root {@link PropertyState}, delegated to
 * {@link ConfigValidator}, {@link LwtValidator}, {@link ReconnectionValidator}, and
 * {@link CertificateLoader}, and every resulting error is attached to the offending field via
 * {@link PropertyState#setErrorAtPath(PropertyPath, String)}. Because the state is returned
 * unchanged except for the attached errors, the entered values are retained and re-displayed
 * (Req 1.4, 1.5, 1.7, 2.4, 3.6, 7.4, 7.5, 8.5, 8.6).
 *
 * <p>Validation runs on every render <em>except</em> the very first render of a brand-new connected
 * system, so the form opens clean rather than reporting required-field and bounds errors before the
 * Designer has entered anything. From the second render onward — the moment the form is interacted
 * with or saved — every error is reported as described above. The freshness signal is the same one
 * that seeds the Clean Session default, so there is exactly one definition of "first render" in this
 * class.
 *
 * <p><strong>Round-trip assumption.</strong> That signal reads {@code cleanSession} from the
 * incoming state, so it is only reliable if Appian hands the template back a descriptor derived from
 * the one the template last returned. If the platform instead supplies only persisted object state,
 * {@code cleanSession} reads {@code null} on every render, the signal stays permanently {@code true},
 * and two behaviors break together: Clean Session can never be turned off, and design-time
 * validation never runs. The check that distinguishes the two platform behaviors is clearing the
 * Clean Session checkbox, saving, and reopening the object — it must still be cleared. This cannot be
 * settled offline.
 *
 * <p><strong>Config mapping (task 9.2).</strong> {@link #toConnectionConfig(PropertyState)} maps a
 * saved, valid state to an immutable {@link MqttConnectionConfig}, applying the documented defaults
 * for blank numeric fields and copying sensitive values into {@code char[]}.
 *
 * <p>Requirements: 1.1, 1.4, 1.5, 1.6, 1.7, 2.1, 2.4, 3.1, 3.5, 3.6, 4.5, 7.1, 7.4, 7.5, 8.1, 8.4,
 * 8.5, 8.6.
 */
@TemplateId(name = "MqttConnectedSystemTemplate")
public class MqttConnectedSystemTemplate extends SimpleConnectedSystemTemplate {

  // --- Default values (mirror the validator defaults so the UI pre-populates them) ---

  /** Default connection timeout in seconds (Req 1.3, 1.7). */
  static final int DEFAULT_CONNECTION_TIMEOUT_SECONDS = 30;

  /** Default maximum reconnection attempts (Req 4.5). */
  static final int DEFAULT_MAX_RECONNECT_ATTEMPTS = 3;

  /** Default reconnection interval in seconds (Req 4.5). */
  static final int DEFAULT_RECONNECT_INTERVAL_SECONDS = 5;

  /** Default keep-alive interval in seconds (Req 8.4, 8.6). */
  static final int DEFAULT_KEEP_ALIVE_SECONDS = 60;

  /**
   * Declares the configuration fields and validates the entered values.
   *
   * <p>{@link SimpleConfiguration#setProperties(PropertyDescriptor...)} builds the root local type
   * from {@link #allProperties()}, registers it in the descriptor's type map, and generates the
   * root {@link PropertyState} from it — including on the very first render, where Appian hands in a
   * descriptor whose root state is {@code null}. Everything after that reads and annotates the
   * generated state, so no caller ever sees a {@code null} root state.
   *
   * @param configuration the configuration wrapper for the (possibly empty) incoming descriptor
   * @param executionContext the design-time execution context
   * @return the same configuration, with properties declared, defaults seeded, and — from the
   *     second render onward — validation errors attached
   */
  @Override
  protected SimpleConfiguration getConfiguration(
      SimpleConfiguration configuration, ExecutionContext executionContext) {

    // Captured BEFORE setProperties: state generation pre-populates booleans with Boolean.FALSE, so
    // afterwards a generated false is indistinguishable from a Designer's deliberate false.
    PropertyState priorState = configuration.toConfiguration().getRootState();
    boolean firstRender =
        priorState == null
            || priorState.getValueAtPath(new PropertyPath(ConfigFields.CLEAN_SESSION)) == null;

    configuration.setProperties(allProperties());

    // Clean Session defaults to true (Req 8.1). Seeded only on the first render so a Designer who
    // deliberately cleared the checkbox is never overridden.
    if (firstRender) {
      configuration.setValue(ConfigFields.CLEAN_SESSION, Boolean.TRUE);
    }

    // Design-time validation: read the raw entered values, delegate to the validators, and attach
    // each error to its field. Attaching errors leaves the entered values in place, so they are
    // retained and re-displayed on a rejected save (Req 1.4, 1.5, 1.7, 2.4, 3.6, 7.4, 7.5, 8.5,
    // 8.6).
    //
    // Skipped on the very first render so a brand-new connected system opens clean instead of
    // showing "Broker host is required" and the broker-port bounds error before the Designer has
    // typed anything. The generated state hands validate() "" for brokerHost and null for
    // brokerPort, which ConfigValidator correctly rejects — there is just nothing to report yet.
    // Every subsequent render validates, so the moment the Designer touches the form (or saves)
    // the errors appear and the entered values are retained. This reuses the same `firstRender`
    // signal as the Clean Session seed above: one definition of freshness in this class,
    // deliberately.
    if (!firstRender) {
      validate(configuration.toConfiguration().getRootState());
    }

    return configuration;
  }

  /**
   * Runs every configuration validator against the entered values and attaches a field-scoped error
   * for each rejected field, retaining the entered values.
   *
   * <p>The three field validators ({@link ConfigValidator}, {@link LwtValidator},
   * {@link ReconnectionValidator}) work on the raw, possibly-blank or non-numeric strings a Designer
   * typed, so blank or non-integer entries are themselves reported as errors with the typed value
   * retained. When TLS is enabled, {@link PemValidator} additionally checks that each supplied
   * certificate field looks like PEM, and the {@link CertificateLoader} runs when both a client
   * certificate and private key are present, rejecting a configuration whose certificate/key cannot
   * be parsed before the TLS handshake would fail later (Req 3.6).
   *
   * @param rootState the root configuration state to read values from and attach errors to
   */
  void validate(PropertyState rootState) {
    String brokerHost = asString(rootState, ConfigFields.BROKER_HOST);
    String brokerPort = asString(rootState, ConfigFields.BROKER_PORT);
    String clientIdentifier = asString(rootState, ConfigFields.CLIENT_IDENTIFIER);
    String connectionTimeout = asString(rootState, ConfigFields.CONNECTION_TIMEOUT_SECONDS);
    String username = asString(rootState, ConfigFields.USERNAME);
    String password = asString(rootState, ConfigFields.PASSWORD);
    String keepAlive = asString(rootState, ConfigFields.KEEP_ALIVE_SECONDS);
    boolean cleanSession = asBoolean(rootState, ConfigFields.CLEAN_SESSION, true);

    ValidationResults results = new ValidationResults();

    // Connection, credential, and session field groups (Req 1.4, 1.5, 1.7, 2.4, 8.5, 8.6).
    ConfigValidator configValidator = new ConfigValidator();
    results.addAll(
        configValidator.validateAll(
            brokerHost,
            brokerPort,
            clientIdentifier,
            connectionTimeout,
            username,
            password,
            keepAlive,
            cleanSession));

    // LWT field group (Req 7.4, 7.5).
    String lwtTopic = asString(rootState, ConfigFields.LWT_TOPIC);
    String lwtPayloadText = asString(rootState, ConfigFields.LWT_PAYLOAD);
    byte[] lwtPayload =
        isBlank(lwtPayloadText) ? null : lwtPayloadText.getBytes(StandardCharsets.UTF_8);
    int lwtQos = parseIntOrDefault(asString(rootState, ConfigFields.LWT_QOS), 0);
    results.addAll(new LwtValidator().validate(lwtTopic, lwtPayload, lwtQos));

    // Reconnection field group (Req 4.5). A blank value is a valid "use the default", so only a
    // present-but-non-integer or out-of-range value is reported here.
    Integer maxReconnectAttempts = parseIntegerOrNull(asString(rootState, ConfigFields.MAX_RECONNECT_ATTEMPTS));
    Integer reconnectInterval = parseIntegerOrNull(asString(rootState, ConfigFields.RECONNECT_INTERVAL_SECONDS));
    results.addAll(new ReconnectionValidator().validate(maxReconnectAttempts, reconnectInterval));

    // Certificate load (Req 3.6): only meaningful when TLS is on and a cert/key pair was supplied.
    boolean tlsEnabled = asBoolean(rootState, ConfigFields.TLS_ENABLED, false);

    // PEM shape of the two pasted certificate fields, checked before any parse so a non-PEM value
    // — most likely a document id left behind by a pre-1.0.3 object, when these were upload fields
    // — produces a clear instruction rather than an opaque parse failure. Gated on TLS to match
    // the certificate-load check below: with TLS off the certificates are not used.
    if (tlsEnabled) {
      results.addAll(
          new PemValidator()
              .validate(
                  asString(rootState, ConfigFields.CA_CERTIFICATE),
                  asString(rootState, ConfigFields.CLIENT_CERTIFICATE)));
    }

    byte[] clientCertificate = resolveCertificateBytes(rootState, ConfigFields.CLIENT_CERTIFICATE);
    String privateKeyText = asString(rootState, ConfigFields.CLIENT_PRIVATE_KEY);
    byte[] privateKeyBytes =
        isBlank(privateKeyText) ? null : privateKeyText.getBytes(StandardCharsets.UTF_8);
    if (tlsEnabled && (clientCertificate != null || privateKeyBytes != null)) {
      CertificateLoader.LoadOutcome outcome =
          CertificateLoader.load(clientCertificate, privateKeyBytes);
      outcome.error().ifPresent(results::add);
    }

    // Attach each error to its field path so the message shows against the field and the entered
    // value is retained.
    for (ValidationResult error : results.errors()) {
      rootState.setErrorAtPath(new PropertyPath(error.fieldId()), error.message());
    }
  }

  /**
   * Every configuration property across all six field groups, in display order (Connection,
   * Authentication, TLS, Reconnection, LWT, Session).
   *
   * @return the field descriptors making up the Connected System configuration
   */
  private PropertyDescriptor[] allProperties() {
    return new PropertyDescriptor[] {
        // --- Connection group (Req 1.1) ---
        TextPropertyDescriptor.builder()
            .key(ConfigFields.BROKER_HOST)
            .label("Broker Host")
            .instructionText("MQTT broker host name or address (1\u2013253 characters).")
            .isRequired(true)
            .build(),
        IntegerPropertyDescriptor.builder()
            .key(ConfigFields.BROKER_PORT)
            .label("Broker Port")
            .instructionText("Broker TCP port (1\u201365535). Typically 1883 (plain) or 8883 (TLS).")
            .isRequired(true)
            .build(),
        TextPropertyDescriptor.builder()
            .key(ConfigFields.CLIENT_IDENTIFIER)
            .label("Client Identifier")
            .instructionText(
                "MQTT client identifier (1\u201365535 characters). Auto-generated when left blank.")
            .isRequired(false)
            .build(),
        IntegerPropertyDescriptor.builder()
            .key(ConfigFields.CONNECTION_TIMEOUT_SECONDS)
            .label("Connection Timeout (seconds)")
            .instructionText("Connect timeout in seconds (1\u2013300).")
            .isRequired(false)
            .build(),

        // --- Authentication group (Req 2.1) ---
        TextPropertyDescriptor.builder()
            .key(ConfigFields.USERNAME)
            .label("Username")
            .instructionText(
                "Broker user name (1\u2013256 characters). Leave username and password blank for "
                    + "an anonymous connection.")
            .isRequired(false)
            .build(),
        // Sensitive: encrypted + masked so it is never rendered after save (Req 1.6).
        EncryptedTextPropertyDescriptor.builder()
            .key(ConfigFields.PASSWORD)
            .label("Password")
            .instructionText("Broker password (1\u2013256 characters). Stored encrypted and masked.")
            .masked(true)
            .isRequired(false)
            .build(),

        // --- TLS group (Req 3.1, 3.5) ---
        BooleanPropertyDescriptor.builder()
            .key(ConfigFields.TLS_ENABLED)
            .label("Enable TLS")
            .instructionText("Use TLS (\u2265 1.2) for the broker connection.")
            .displayMode(BooleanDisplayMode.CHECKBOX)
            .build(),
        // Pasted PEM rather than a document upload: a DocumentPropertyDescriptor cannot be
        // declared on a connected system template at all, because its builder forces the
        // expressionable flag on and Appian rejects any expressionable connected system property.
        // See the class Javadoc. ParagraphPropertyDescriptor is the SDK's multi-line text input.
        ParagraphPropertyDescriptor.builder()
            .key(ConfigFields.CA_CERTIFICATE)
            .label("CA Certificate (PEM)")
            .instructionText(
                "CA certificate used to validate the broker (optional). Paste the PEM text, "
                    + "including the -----BEGIN CERTIFICATE----- and -----END CERTIFICATE----- "
                    + "lines.")
            .isRequired(false)
            .build(),
        // Client certificate for mutual TLS, pasted as PEM (Req 3.5).
        ParagraphPropertyDescriptor.builder()
            .key(ConfigFields.CLIENT_CERTIFICATE)
            .label("Client Certificate (PEM)")
            .instructionText(
                "Client certificate for mutual TLS (optional). Paste the PEM text, including the "
                    + "-----BEGIN CERTIFICATE----- and -----END CERTIFICATE----- lines.")
            .isRequired(false)
            .build(),
        // Sensitive: encrypted + masked (Req 1.6).
        EncryptedTextPropertyDescriptor.builder()
            .key(ConfigFields.CLIENT_PRIVATE_KEY)
            .label("Client Private Key")
            .instructionText("Private key for the client certificate. Stored encrypted and masked.")
            .masked(true)
            .isRequired(false)
            .build(),
        // Sensitive: encrypted + masked (Req 1.6).
        EncryptedTextPropertyDescriptor.builder()
            .key(ConfigFields.KEY_PASSPHRASE)
            .label("Key Passphrase")
            .instructionText("Passphrase protecting the client private key. Stored encrypted and masked.")
            .masked(true)
            .isRequired(false)
            .build(),

        // --- Reconnection group (Req 4.5) ---
        BooleanPropertyDescriptor.builder()
            .key(ConfigFields.AUTO_RECONNECT_ENABLED)
            .label("Enable Automatic Reconnect")
            .instructionText("Automatically reconnect to the broker after a lost connection.")
            .displayMode(BooleanDisplayMode.CHECKBOX)
            .build(),
        IntegerPropertyDescriptor.builder()
            .key(ConfigFields.MAX_RECONNECT_ATTEMPTS)
            .label("Max Reconnect Attempts")
            .instructionText("Maximum reconnection attempts (0\u201310).")
            .isRequired(false)
            .build(),
        IntegerPropertyDescriptor.builder()
            .key(ConfigFields.RECONNECT_INTERVAL_SECONDS)
            .label("Reconnect Interval (seconds)")
            .instructionText("Interval between reconnection attempts in seconds (1\u2013120).")
            .isRequired(false)
            .build(),

        // --- Last Will and Testament group (Req 7.1) ---
        TextPropertyDescriptor.builder()
            .key(ConfigFields.LWT_TOPIC)
            .label("LWT Topic")
            .instructionText("Last Will and Testament topic (1\u201365535 characters, optional).")
            .isRequired(false)
            .build(),
        TextPropertyDescriptor.builder()
            .key(ConfigFields.LWT_PAYLOAD)
            .label("LWT Payload")
            .instructionText("Last Will and Testament payload (optional).")
            .isRequired(false)
            .build(),
        TextPropertyDescriptor.builder()
            .key(ConfigFields.LWT_QOS)
            .label("LWT QoS")
            .instructionText("Quality of Service for the Last Will message.")
            .choices(
                Choice.builder().name("At most once (0)").value("0").build(),
                Choice.builder().name("At least once (1)").value("1").build(),
                Choice.builder().name("Exactly once (2)").value("2").build())
            .isRequired(false)
            .build(),
        BooleanPropertyDescriptor.builder()
            .key(ConfigFields.LWT_RETAINED)
            .label("LWT Retained")
            .instructionText("Whether the broker retains the Last Will message (default false).")
            .displayMode(BooleanDisplayMode.CHECKBOX)
            .build(),

        // --- Session group (Req 8.1, 8.4) ---
        BooleanPropertyDescriptor.builder()
            .key(ConfigFields.CLEAN_SESSION)
            .label("Clean Session")
            .instructionText(
                "Start a clean session (default true). Disable for a persistent session; a client "
                    + "identifier is then required.")
            .displayMode(BooleanDisplayMode.CHECKBOX)
            .build(),
        IntegerPropertyDescriptor.builder()
            .key(ConfigFields.KEEP_ALIVE_SECONDS)
            .label("Keep Alive (seconds)")
            .instructionText("Keep-alive interval in seconds (0\u201365535).")
            .isRequired(false)
            .build(),
    };
  }

  /**
   * Produces an immutable {@link MqttConnectionConfig} from the saved configuration state.
   *
   * <p>Each field is read from the saved {@link PropertyState}. Blank numeric fields fall back to
   * the documented defaults — connection timeout {@value #DEFAULT_CONNECTION_TIMEOUT_SECONDS} (Req
   * 1.7), keep-alive {@value #DEFAULT_KEEP_ALIVE_SECONDS} (Req 8.6), max reconnect attempts
   * {@value #DEFAULT_MAX_RECONNECT_ATTEMPTS} and reconnect interval
   * {@value #DEFAULT_RECONNECT_INTERVAL_SECONDS} (Req 4.5), LWT QoS 0, and clean session {@code
   * true} (Req 8.1). When the Designer left the client identifier blank, a stable identifier is
   * auto-generated from the host and port (Req 1.2). Sensitive values (password, client private
   * key, key passphrase) are copied into {@code char[]} rather than held as {@link String} so they
   * can be cleared after use (Req 1.6).
   *
   * <p><strong>Certificate resolution.</strong> The CA and client certificate fields hold pasted
   * PEM text, so their bytes are the UTF-8 encoding of the entered value and are always available
   * here; see {@link #resolveCertificateBytes(PropertyState, String)}. The legacy document-valued
   * shape is still tolerated by that helper so an upgraded object does not throw, but a saved
   * document reference cannot be turned into bytes by this SDK version and never could be — see
   * the migration note on {@link #resolveCertificateBytes(PropertyState, String)}.
   *
   * @param rootState the saved root configuration state
   * @return the fully-populated immutable connection config
   */
  public static MqttConnectionConfig toConnectionConfig(PropertyState rootState) {
    String brokerHost = trimToNull(asString(rootState, ConfigFields.BROKER_HOST));
    int brokerPort = parseIntOrDefault(asString(rootState, ConfigFields.BROKER_PORT), 0);

    String clientIdentifier = trimToNull(asString(rootState, ConfigFields.CLIENT_IDENTIFIER));
    if (clientIdentifier == null && brokerHost != null) {
      clientIdentifier = ClientIdentifierGenerator.generate(brokerHost, brokerPort);
    }

    int connectionTimeoutSeconds =
        parseIntOrDefault(
            asString(rootState, ConfigFields.CONNECTION_TIMEOUT_SECONDS),
            DEFAULT_CONNECTION_TIMEOUT_SECONDS);

    String username = trimToNull(asString(rootState, ConfigFields.USERNAME));
    char[] password = toCharArray(asString(rootState, ConfigFields.PASSWORD));

    boolean tlsEnabled = asBoolean(rootState, ConfigFields.TLS_ENABLED, false);
    byte[] caCertificate = resolveCertificateBytes(rootState, ConfigFields.CA_CERTIFICATE);
    byte[] clientCertificate = resolveCertificateBytes(rootState, ConfigFields.CLIENT_CERTIFICATE);
    char[] clientPrivateKey = toCharArray(asString(rootState, ConfigFields.CLIENT_PRIVATE_KEY));
    char[] keyPassphrase = toCharArray(asString(rootState, ConfigFields.KEY_PASSPHRASE));

    boolean autoReconnectEnabled =
        asBoolean(rootState, ConfigFields.AUTO_RECONNECT_ENABLED, false);
    int maxReconnectAttempts =
        parseIntOrDefault(
            asString(rootState, ConfigFields.MAX_RECONNECT_ATTEMPTS),
            DEFAULT_MAX_RECONNECT_ATTEMPTS);
    int reconnectIntervalSeconds =
        parseIntOrDefault(
            asString(rootState, ConfigFields.RECONNECT_INTERVAL_SECONDS),
            DEFAULT_RECONNECT_INTERVAL_SECONDS);

    String lwtTopic = trimToNull(asString(rootState, ConfigFields.LWT_TOPIC));
    String lwtPayloadText = asString(rootState, ConfigFields.LWT_PAYLOAD);
    byte[] lwtPayload =
        isBlank(lwtPayloadText) ? null : lwtPayloadText.getBytes(StandardCharsets.UTF_8);
    int lwtQos = parseIntOrDefault(asString(rootState, ConfigFields.LWT_QOS), 0);
    boolean lwtRetained = asBoolean(rootState, ConfigFields.LWT_RETAINED, false);

    boolean cleanSession = asBoolean(rootState, ConfigFields.CLEAN_SESSION, true);
    int keepAliveSeconds =
        parseIntOrDefault(
            asString(rootState, ConfigFields.KEEP_ALIVE_SECONDS), DEFAULT_KEEP_ALIVE_SECONDS);

    return new MqttConnectionConfig(
        brokerHost,
        brokerPort,
        clientIdentifier,
        connectionTimeoutSeconds,
        username,
        password,
        tlsEnabled,
        caCertificate,
        clientCertificate,
        clientPrivateKey,
        keyPassphrase,
        autoReconnectEnabled,
        maxReconnectAttempts,
        reconnectIntervalSeconds,
        lwtTopic,
        lwtPayload,
        lwtQos,
        lwtRetained,
        cleanSession,
        keepAliveSeconds);
  }

  // --- Value-reading helpers -------------------------------------------------------------------

  /**
   * Reads a field value from the state and renders it as the raw string the validators expect.
   *
   * <p>Text and encrypted fields come back as {@link String}; integer fields come back as a boxed
   * number; everything else is rendered with {@link String#valueOf(Object)}. A {@code null} value
   * (field never set) is returned as {@code null} so blank-vs-set checks behave correctly.
   *
   * @param rootState the state to read from
   * @param fieldKey the field key (from {@link ConfigFields})
   * @return the string form of the value, or {@code null} when unset
   */
  private static String asString(PropertyState rootState, String fieldKey) {
    Object value = rootState.getValueAtPath(new PropertyPath(fieldKey));
    if (value == null) {
      return null;
    }
    if (value instanceof String s) {
      return s;
    }
    if (value instanceof char[] chars) {
      return new String(chars);
    }
    return String.valueOf(value);
  }

  /**
   * Reads a boolean field, applying {@code defaultValue} when the field is unset. A stored string
   * ("true"/"false") or boxed {@link Boolean} are both accepted.
   */
  private static boolean asBoolean(PropertyState rootState, String fieldKey, boolean defaultValue) {
    Object value = rootState.getValueAtPath(new PropertyPath(fieldKey));
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof Boolean b) {
      return b;
    }
    return Boolean.parseBoolean(String.valueOf(value));
  }

  /**
   * Resolves a certificate field's saved value to bytes.
   *
   * <p>The CA and client certificate fields hold pasted PEM text, so the normal path is the final
   * branch: the entered {@link String} is returned as its UTF-8 bytes, which is exactly what
   * {@link CertificateLoader} and the runtime
   * {@code ConnectOptionsFactory} parse.
   *
   * <p>The {@code byte[]} and {@code Document} branches are retained unchanged so an object saved
   * by an earlier version — when these fields were {@code DocumentPropertyDescriptor}s — does not
   * throw. They cannot recover a certificate in practice: a saved document reference is an opaque
   * id this SDK version offers no accessor to resolve, which is why TLS with a CA or client
   * certificate never actually worked before the fields became text. Such a value yields
   * {@code null} here, and the PEM-shape check in {@link #validate(PropertyState)} reports it as a
   * field error telling the Designer to paste the PEM instead.
   */
  private static byte[] resolveCertificateBytes(PropertyState rootState, String fieldKey) {
    Object value = rootState.getValueAtPath(new PropertyPath(fieldKey));
    if (value == null) {
      return null;
    }
    if (value instanceof byte[] bytes) {
      return bytes.length == 0 ? null : bytes;
    }
    if (value instanceof com.appian.connectedsystems.templateframework.sdk.configuration.Document doc) {
      java.io.InputStream documentStream = doc.getInputStream();
      if (documentStream == null) {
        return null;
      }
      // try-with-resources: the document stream is a Closeable and must be closed explicitly.
      try (java.io.InputStream in = documentStream) {
        byte[] bytes = in.readAllBytes();
        return bytes.length == 0 ? null : bytes;
      } catch (java.io.IOException e) {
        // Legacy document value that could not be read; reported by the PEM-shape check instead.
        return null;
      }
    }
    if (value instanceof String s && !s.isBlank()) {
      return s.getBytes(StandardCharsets.UTF_8);
    }
    return null;
  }

  // --- Parsing/formatting helpers --------------------------------------------------------------

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /** Copies a value into a {@code char[]}, returning {@code null} for a blank/absent value. */
  private static char[] toCharArray(String value) {
    return isBlank(value) ? null : value.toCharArray();
  }

  /** Parses a strict integer, or returns {@code defaultValue} when blank or non-integer. */
  private static int parseIntOrDefault(String value, int defaultValue) {
    Integer parsed = parseIntegerOrNull(value);
    return parsed == null ? defaultValue : parsed;
  }

  /** Parses a strict integer, or returns {@code null} when blank or non-integer. */
  private static Integer parseIntegerOrNull(String value) {
    if (isBlank(value)) {
      return null;
    }
    try {
      return Integer.valueOf(value.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
