package de.hock.mqtt;

import com.appian.connectedsystems.templateframework.sdk.configuration.PropertyState;
import de.hock.mqtt.template.MqttConnectedSystemTemplate;
import java.security.GeneralSecurityException;

/**
 * Run-time validation of the MQTT Broker connected system configuration, shared by both
 * integrations.
 *
 * <p><strong>Why this class lives here and not in a {@code validation} package.</strong> Until
 * 1.0.5 these same rules ran at <em>design</em> time, attaching messages to field paths through
 * {@code PropertyState.setErrorAtPath}. Release 1.0.5 deployed that deliberately ungated as a probe,
 * and the answer observed on a live Appian 26.9 instance was unambiguous: <em>no message is
 * displayed</em>. Appian discards errors attached that way on a connected system template, so the
 * documented {@code setErrors} restriction covers {@code setErrorAtPath} too. The rules were correct
 * but their results were thrown away, which made the old {@code de.hock.mqtt.validation
 * .ConfigValidator} dead code that looked like working validation.
 *
 * <p>1.0.6 therefore demotes them to an <em>invocation-time</em> check. The class sits in the root
 * package beside the two Paho call sites that consume its verdict, and its name says what it
 * validates rather than when. {@code isRequired} on the descriptors remains the only design-time
 * guard available, and Appian does honour that one.
 *
 * <p><strong>Contract.</strong> {@link #firstError(PropertyState)} returns {@code null} when the
 * configuration is usable, or the first problem as a sentence ready to hand to a designer. Each
 * integration wraps it in an {@code Invalid configuration} error response, which is how a
 * misconfigured connected system surfaces as a clear message instead of an obscure Paho failure.
 * Nothing here touches, clears or attaches descriptor state, and nothing here reads the password.
 *
 * <p><strong>Behaviour change from 1.0.5.</strong> An out-of-range {@code connectionTimeoutSeconds}
 * or {@code keepAliveSeconds} now fails the integration. In 1.0.5 such a value silently fell back to
 * its default inside {@link MqttPublisher#buildConnectOptions}. Those fallbacks remain as defence in
 * depth — that method is total and must never throw — but on the integration path this check now
 * gates them.
 */
public final class ConnectionConfigValidator {

  /** Lowest valid TCP port. */
  static final int PORT_MIN = 1;

  /** Highest valid TCP port. */
  static final int PORT_MAX = 65535;

  /** Lowest accepted connect timeout, in seconds. */
  static final int TIMEOUT_MIN = 1;

  /**
   * Highest accepted connect timeout, in seconds. Five minutes is already far longer than an Appian
   * request should wait on an unreachable broker; the bound exists to catch a mistyped value.
   */
  static final int TIMEOUT_MAX = 300;

  /** Lowest accepted keep-alive interval. {@code 0} is legal MQTT and disables keep-alive. */
  static final int KEEP_ALIVE_MIN = 0;

  /** Highest accepted keep-alive interval, in seconds — the MQTT protocol's own 16-bit ceiling. */
  static final int KEEP_ALIVE_MAX = 65535;

  /**
   * The PEM header a pasted certificate must contain, shared with {@link TlsSupport} so the
   * validator's idea of "is this even PEM" cannot drift from the parser's.
   */
  static final String PEM_HEADER = TlsSupport.PEM_CERTIFICATE_HEADER;

  private ConnectionConfigValidator() {
    // Utility class: no instances.
  }

  /**
   * Reports the first problem with the connected system configuration, or {@code null}.
   *
   * <p>Rules, evaluated in this order:
   *
   * <ul>
   *   <li>{@code brokerHost} — required, non-blank after trimming.
   *   <li>{@code brokerPort} — required, {@value #PORT_MIN}–{@value #PORT_MAX}.
   *   <li>{@code connectionTimeoutSeconds} — optional; when supplied, {@value #TIMEOUT_MIN}–{@value
   *       #TIMEOUT_MAX} seconds.
   *   <li>{@code keepAliveSeconds} — optional; when supplied, {@value #KEEP_ALIVE_MIN}–{@value
   *       #KEEP_ALIVE_MAX} seconds, where {@code 0} disables MQTT keep-alive.
   *   <li>{@code caCertificate} supplied while {@code tlsEnabled} is off — reported, see below.
   *   <li>{@code caCertificate} that is not PEM at all — reported, naming the expected header, and
   *       naming an Appian document id as the likely mistake when the value is a bare number.
   *   <li>{@code caCertificate} that has the header but will not parse — reported.
   * </ul>
   *
   * <p><strong>Rule 5 is a hard error, deliberately.</strong> A CA certificate pasted while TLS is
   * off is configured but inert, and this project has already condemned exactly that shape once: the
   * 1.0.3 {@code maxReconnectAttempts} field that nothing enforced. Appian offers no design-time
   * warning channel on a connected system (its field errors are discarded, measured on a live
   * instance) and a success response has no warning slot, so failing the call is the only way to
   * tell a designer. The cost is real and worth stating: unchecking <em>Enable TLS</em> to test a
   * plain connection also means clearing the certificate field, and until it is cleared both
   * integrations fail. That is a visible, self-explaining, one-field fix rather than a silent
   * misconfiguration.
   *
   * <p>{@code clientIdentifier}, {@code username}, {@code password}, {@code cleanSession} and
   * {@code autoReconnectEnabled} are optional and unvalidated. A {@code null} state is reported as a
   * missing broker host, which is the honest description of a connected system that carries no
   * configuration at all.
   *
   * <p>No message returned here contains any part of the certificate field's contents.
   *
   * @param connectionState the root state of the connected system configuration; {@code null} is
   *     tolerated
   * @return the first problem found, or {@code null} when the configuration is usable
   */
  public static String firstError(PropertyState connectionState) {
    String brokerHost =
        MqttConnectedSystemTemplate.textValue(
            connectionState, MqttConnectedSystemTemplate.BROKER_HOST);
    if (brokerHost == null || brokerHost.isBlank()) {
      return "Broker host is required on the MQTT Broker connected system.";
    }

    Integer brokerPort =
        MqttConnectedSystemTemplate.integerValue(
            connectionState, MqttConnectedSystemTemplate.BROKER_PORT);
    if (brokerPort == null) {
      return "Broker port is required on the MQTT Broker connected system.";
    }
    if (brokerPort < PORT_MIN || brokerPort > PORT_MAX) {
      return "Broker port must be between "
          + PORT_MIN
          + " and "
          + PORT_MAX
          + " on the MQTT Broker connected system, but was "
          + brokerPort
          + ".";
    }

    Integer connectionTimeoutSeconds =
        MqttConnectedSystemTemplate.integerValue(
            connectionState, MqttConnectedSystemTemplate.CONNECTION_TIMEOUT_SECONDS);
    if (connectionTimeoutSeconds != null
        && (connectionTimeoutSeconds < TIMEOUT_MIN || connectionTimeoutSeconds > TIMEOUT_MAX)) {
      return "Connection timeout must be between "
          + TIMEOUT_MIN
          + " and "
          + TIMEOUT_MAX
          + " seconds on the MQTT Broker connected system, but was "
          + connectionTimeoutSeconds
          + ".";
    }

    Integer keepAliveSeconds =
        MqttConnectedSystemTemplate.integerValue(
            connectionState, MqttConnectedSystemTemplate.KEEP_ALIVE_SECONDS);
    if (keepAliveSeconds != null
        && (keepAliveSeconds < KEEP_ALIVE_MIN || keepAliveSeconds > KEEP_ALIVE_MAX)) {
      return "Keep alive interval must be between "
          + KEEP_ALIVE_MIN
          + " and "
          + KEEP_ALIVE_MAX
          + " seconds on the MQTT Broker connected system, but was "
          + keepAliveSeconds
          + ".";
    }

    Boolean tlsEnabled =
        MqttConnectedSystemTemplate.booleanValue(
            connectionState, MqttConnectedSystemTemplate.TLS_ENABLED);
    String caCertificate =
        MqttConnectedSystemTemplate.textValue(
            connectionState, MqttConnectedSystemTemplate.CA_CERTIFICATE);
    boolean hasCa = caCertificate != null && !caCertificate.isBlank();

    if (!hasCa) {
      return null;
    }

    // Rule 5: configured but inert. See the judgement recorded in this method's documentation.
    if (!TlsSupport.isTlsEnabled(tlsEnabled)) {
      return "A CA certificate is configured but TLS is disabled on the MQTT Broker connected"
          + " system. Enable TLS, or clear the CA Certificate field.";
    }

    // Rule 6: not PEM at all. A bare number is the stale-Appian-document-id mistake the 1.0.3
    // design produced, and is worth naming explicitly — without repeating the digits.
    if (!caCertificate.contains(PEM_HEADER)) {
      String message =
          "The CA Certificate on the MQTT Broker connected system is not a PEM certificate. It must"
              + " start with "
              + PEM_HEADER
              + " and end with -----END CERTIFICATE-----. Paste the certificate text itself, not a"
              + " document id, a file name or a path.";
      if (caCertificate.trim().matches("\\d{1,20}")) {
        message += " The configured value looks like an Appian document id.";
      }
      return message;
    }

    // Rule 7: header present, but the body will not parse. The caught message is discarded rather
    // than appended, so nothing from the field can reach the designer's screen.
    try {
      TlsSupport.parseCertificates(caCertificate);
    } catch (GeneralSecurityException e) {
      return "The CA Certificate on the MQTT Broker connected system could not be parsed as an"
          + " X.509 certificate. Check that the whole PEM block was pasted, including the BEGIN and"
          + " END lines, and that no characters were lost.";
    }

    return null;
  }
}
