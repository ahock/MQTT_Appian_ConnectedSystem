package de.hock.mqtt.integration;

import com.appian.connectedsystems.simplified.sdk.SimpleIntegrationTemplate;
import com.appian.connectedsystems.simplified.sdk.configuration.SimpleConfiguration;
import com.appian.connectedsystems.templateframework.sdk.ExecutionContext;
import com.appian.connectedsystems.templateframework.sdk.IntegrationError;
import com.appian.connectedsystems.templateframework.sdk.IntegrationResponse;
import com.appian.connectedsystems.templateframework.sdk.TemplateId;
import com.appian.connectedsystems.templateframework.sdk.configuration.IntegerPropertyDescriptor;
import com.appian.connectedsystems.templateframework.sdk.configuration.PropertyPath;
import com.appian.connectedsystems.templateframework.sdk.configuration.PropertyState;
import com.appian.connectedsystems.templateframework.sdk.configuration.TextPropertyDescriptor;
import com.appian.connectedsystems.templateframework.sdk.metadata.IntegrationTemplateRequestPolicy;
import com.appian.connectedsystems.templateframework.sdk.metadata.IntegrationTemplateType;
import de.hock.mqtt.ConnectionConfigValidator;
import de.hock.mqtt.MqttSubscriber;
import de.hock.mqtt.template.MqttConnectedSystemTemplate;
import java.security.GeneralSecurityException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.paho.client.mqttv3.MqttException;

/**
 * Collects messages from one MQTT topic filter on the broker configured by the
 * {@link MqttConnectedSystemTemplate} Connected System, and returns them to the calling expression.
 *
 * <p>Marked {@link IntegrationTemplateRequestPolicy#READ} because collecting messages has no effect
 * on the broker beyond a subscription that is removed again before the call returns.
 *
 * <p><strong>Inputs.</strong> {@code topicFilter} (text, required), {@code qos} (integer, optional,
 * 0 when blank, 0–2), {@code maxWaitSeconds} (integer, optional, 10 when blank, 1–30) and
 * {@code maxMessageCount} (integer, optional, 10 when blank, 1–100). All four are
 * <em>expressionable</em>, consistent with the publish integration's three inputs and the deliberate
 * opposite of the connected system, where Appian rejects any expressionable descriptor.
 *
 * <p><strong>Out-of-range values are rejected, not clamped.</strong> A {@code maxWaitSeconds} of 300
 * fails with {@code Invalid input} rather than quietly becoming 30, because a designer who asked for
 * five minutes and got thirty seconds would have no way to notice. Blank is different from
 * out-of-range: blank takes the documented default.
 *
 * <p><strong>One topic filter, not a list — deliberately.</strong> {@code topicFilter} is a plain
 * {@link TextPropertyDescriptor}, and MQTT wildcards cover most real subscriptions:
 * {@code sensors/+/temp} matches one level, {@code home/#} matches a whole subtree. A list input
 * would need {@code ListTypePropertyDescriptor}, whose builder force-sets the expressionable
 * modifier <em>and</em> an {@code EXPRESSION} display hint in its constructor and then couples the
 * two in its own {@code build()} guard. That is the same family of forced-flag builders whose
 * connected-system equivalent, {@code DocumentPropertyDescriptor}, caused a live deploy failure.
 * Such a descriptor is very probably legal on an <em>integration</em> input — the validator that
 * rejected the forced flags is connected-system-specific — but that has never been observed on a
 * real instance, cannot be proven offline (no platform-side integration-input validator ships in
 * either SDK jar), and this project has already lost three deploy cycles to descriptor-contract
 * surprises. Multi-topic support is a deliberate follow-up for a later version.
 *
 * <p><strong>This blocks an Appian request thread</strong> for up to {@code maxWaitSeconds}, which is
 * why the cap is {@value #MAX_WAIT_SECONDS_MAX} and the default
 * {@value #DEFAULT_MAX_WAIT_SECONDS}. Appian applies its own integration request timeout
 * independently; if that fires first, the designer sees an Appian error instead of an empty result.
 * See {@link MqttSubscriber} for the teardown guarantee.
 *
 * <p><strong>Output.</strong> Success returns {@code messages} — a list of maps carrying
 * {@code topic}, {@code payload}, {@code qos}, {@code retained} and {@code binary} — and
 * {@code messageCount}. A payload that is not valid UTF-8 is Base64-encoded with {@code binary} set
 * to {@code true}. <em>An empty collection is a success with {@code messageCount} 0</em>, not an
 * error: a subscription that saw no traffic in its window is a normal outcome. Failure returns an
 * {@link IntegrationError} titled {@code Invalid input}, {@code Invalid configuration} or
 * {@code MQTT subscribe failed}. No secret reaches a response: the password is never read into a
 * message.
 */
@TemplateId(name = "MqttSubscribeIntegration")
@IntegrationTemplateType(IntegrationTemplateRequestPolicy.READ)
public class MqttSubscribeIntegration extends SimpleIntegrationTemplate {

  /** Input key: the single topic filter to subscribe to; MQTT wildcards are supported. */
  public static final String INPUT_TOPIC_FILTER = "topicFilter";

  /** Input key: requested quality of service, 0, 1 or 2. */
  public static final String INPUT_QOS = "qos";

  /** Input key: how long to collect messages for, in seconds. */
  public static final String INPUT_MAX_WAIT_SECONDS = "maxWaitSeconds";

  /** Input key: how many messages to collect before returning early. */
  public static final String INPUT_MAX_MESSAGE_COUNT = "maxMessageCount";

  /** QoS applied when the input is left blank. */
  static final int DEFAULT_QOS = 0;

  /** Collection window applied when the input is left blank, in seconds. */
  static final int DEFAULT_MAX_WAIT_SECONDS = 10;

  /** Message cap applied when the input is left blank. */
  static final int DEFAULT_MAX_MESSAGE_COUNT = 10;

  /** Shortest accepted collection window, in seconds. */
  static final int MAX_WAIT_SECONDS_MIN = 1;

  /** Longest accepted collection window, in seconds — a hard cap on how long a request may block. */
  static final int MAX_WAIT_SECONDS_MAX = 30;

  /** Smallest accepted message cap; it is also the inbound queue capacity, so it cannot be 0. */
  static final int MAX_MESSAGE_COUNT_MIN = 1;

  /** Largest accepted message cap. */
  static final int MAX_MESSAGE_COUNT_MAX = 100;

  /** Hard upper bound on the length of an error message carried in a response. */
  static final int MAX_ERROR_MESSAGE_LENGTH = 500;

  @Override
  protected SimpleConfiguration getConfiguration(
      SimpleConfiguration integrationConfiguration,
      SimpleConfiguration connectedSystemConfiguration,
      PropertyPath propertyPath,
      ExecutionContext executionContext) {

    integrationConfiguration.setProperties(
        TextPropertyDescriptor.builder()
            .key(INPUT_TOPIC_FILTER)
            .label("Topic Filter")
            .instructionText(
                "A single MQTT topic filter. Wildcards are supported: + matches one level"
                    + " (sensors/+/temp) and # matches the rest of the tree (home/#).")
            .isRequired(true)
            .isExpressionable(true)
            .build(),
        IntegerPropertyDescriptor.builder()
            .key(INPUT_QOS)
            .label("QoS")
            .instructionText("Quality of Service 0, 1 or 2. Defaults to 0 when blank.")
            .placeholder("0")
            .isRequired(false)
            .isExpressionable(true)
            .build(),
        IntegerPropertyDescriptor.builder()
            .key(INPUT_MAX_WAIT_SECONDS)
            .label("Max Wait (seconds)")
            .instructionText(
                "How long to collect messages, 1 to 30 seconds. Defaults to 10 when blank. This"
                    + " blocks an Appian request thread for the whole wait.")
            .placeholder("10")
            .isRequired(false)
            .isExpressionable(true)
            .build(),
        IntegerPropertyDescriptor.builder()
            .key(INPUT_MAX_MESSAGE_COUNT)
            .label("Max Message Count")
            .instructionText(
                "Stop as soon as this many messages have been collected, 1 to 100. Defaults to 10"
                    + " when blank.")
            .placeholder("10")
            .isRequired(false)
            .isExpressionable(true)
            .build());

    return integrationConfiguration;
  }

  @Override
  protected IntegrationResponse execute(
      SimpleConfiguration integrationConfiguration,
      SimpleConfiguration connectedSystemConfiguration,
      ExecutionContext executionContext) {

    PropertyState inputs = integrationConfiguration.toConfiguration().getRootState();
    PropertyState connection = connectedSystemConfiguration.toConfiguration().getRootState();

    String topicFilter = MqttConnectedSystemTemplate.textValue(inputs, INPUT_TOPIC_FILTER);
    int qos = qosOf(inputs);
    int maxWaitSeconds = maxWaitSecondsOf(inputs);
    int maxMessageCount = maxMessageCountOf(inputs);

    // Run-time guards, before any broker contact.
    if (topicFilter == null || topicFilter.isBlank()) {
      return error("Invalid input", "Topic filter is required.");
    }
    String configError = ConnectionConfigValidator.firstError(connection);
    if (configError != null) {
      return error("Invalid configuration", configError);
    }
    String inputError = inputError(qos, maxWaitSeconds, maxMessageCount);
    if (inputError != null) {
      return error("Invalid input", inputError);
    }

    String brokerHost =
        MqttConnectedSystemTemplate.textValue(connection, MqttConnectedSystemTemplate.BROKER_HOST);
    int brokerPort =
        MqttConnectedSystemTemplate.intValue(connection, MqttConnectedSystemTemplate.BROKER_PORT, 0);
    Boolean tlsEnabled =
        MqttConnectedSystemTemplate.booleanValue(
            connection, MqttConnectedSystemTemplate.TLS_ENABLED);
    String caCertificate =
        MqttConnectedSystemTemplate.textValue(
            connection, MqttConnectedSystemTemplate.CA_CERTIFICATE);
    String clientIdentifier =
        MqttConnectedSystemTemplate.textValue(
            connection, MqttConnectedSystemTemplate.CLIENT_IDENTIFIER);
    String username =
        MqttConnectedSystemTemplate.textValue(connection, MqttConnectedSystemTemplate.USERNAME);
    String passwordText =
        MqttConnectedSystemTemplate.textValue(connection, MqttConnectedSystemTemplate.PASSWORD);
    Integer connectionTimeoutSeconds =
        MqttConnectedSystemTemplate.integerValue(
            connection, MqttConnectedSystemTemplate.CONNECTION_TIMEOUT_SECONDS);
    Integer keepAliveSeconds =
        MqttConnectedSystemTemplate.integerValue(
            connection, MqttConnectedSystemTemplate.KEEP_ALIVE_SECONDS);
    Boolean cleanSession =
        MqttConnectedSystemTemplate.booleanValue(
            connection, MqttConnectedSystemTemplate.CLEAN_SESSION);
    Boolean autoReconnectEnabled =
        MqttConnectedSystemTemplate.booleanValue(
            connection, MqttConnectedSystemTemplate.AUTO_RECONNECT_ENABLED);

    char[] password = passwordText == null ? null : passwordText.toCharArray();

    List<Map<String, Object>> messages;
    try {
      messages =
          MqttSubscriber.subscribe(
              brokerHost,
              brokerPort,
              tlsEnabled,
              caCertificate,
              clientIdentifier,
              username,
              password,
              topicFilter,
              qos,
              maxWaitSeconds,
              maxMessageCount,
              connectionTimeoutSeconds,
              keepAliveSeconds,
              cleanSession,
              autoReconnectEnabled);
    } catch (GeneralSecurityException e) {
      // Fixed message, deliberately: never let a crypto exception's text reach a response, in case a
      // future JDK starts quoting its input. Unparseable PEM was already rejected by the validator.
      return error(
          "Invalid configuration",
          "The TLS configuration of the MQTT Broker connected system could not be applied."
              + " Check the CA Certificate field.");
    } catch (MqttException | RuntimeException e) {
      return error(
          "MQTT subscribe failed",
          "Failed to subscribe to filter '"
              + topicFilter
              + "' on MQTT broker "
              + brokerHost
              + ":"
              + brokerPort
              + " at QoS "
              + qos
              + ": "
              + e.getMessage());
    }

    return success(messages);
  }

  /** Reads {@code qos}, falling back to {@value #DEFAULT_QOS} when blank. */
  static int qosOf(PropertyState inputs) {
    return MqttConnectedSystemTemplate.intValue(inputs, INPUT_QOS, DEFAULT_QOS);
  }

  /** Reads {@code maxWaitSeconds}, falling back to {@value #DEFAULT_MAX_WAIT_SECONDS} when blank. */
  static int maxWaitSecondsOf(PropertyState inputs) {
    return MqttConnectedSystemTemplate.intValue(
        inputs, INPUT_MAX_WAIT_SECONDS, DEFAULT_MAX_WAIT_SECONDS);
  }

  /**
   * Reads {@code maxMessageCount}, falling back to {@value #DEFAULT_MAX_MESSAGE_COUNT} when blank.
   */
  static int maxMessageCountOf(PropertyState inputs) {
    return MqttConnectedSystemTemplate.intValue(
        inputs, INPUT_MAX_MESSAGE_COUNT, DEFAULT_MAX_MESSAGE_COUNT);
  }

  /**
   * Reports the first out-of-range input, or {@code null} when all three are acceptable.
   *
   * <p>Rejection rather than clamping, and before any broker contact: Paho rejects a QoS outside 0–2
   * with an {@code IllegalArgumentException}, and a {@code maxMessageCount} of 0 would be an illegal
   * queue capacity.
   *
   * @param qos the requested quality of service
   * @param maxWaitSeconds the requested collection window
   * @param maxMessageCount the requested message cap
   * @return the problem found, or {@code null}
   */
  static String inputError(int qos, int maxWaitSeconds, int maxMessageCount) {
    if (qos < 0 || qos > 2) {
      return "QoS must be 0, 1 or 2, but was " + qos + ".";
    }
    if (maxWaitSeconds < MAX_WAIT_SECONDS_MIN || maxWaitSeconds > MAX_WAIT_SECONDS_MAX) {
      return "Max wait must be between "
          + MAX_WAIT_SECONDS_MIN
          + " and "
          + MAX_WAIT_SECONDS_MAX
          + " seconds, but was "
          + maxWaitSeconds
          + ".";
    }
    if (maxMessageCount < MAX_MESSAGE_COUNT_MIN || maxMessageCount > MAX_MESSAGE_COUNT_MAX) {
      return "Max message count must be between "
          + MAX_MESSAGE_COUNT_MIN
          + " and "
          + MAX_MESSAGE_COUNT_MAX
          + ", but was "
          + maxMessageCount
          + ".";
    }
    return null;
  }

  /**
   * Builds the success response, including for an empty collection.
   *
   * <p>An empty list is a success with {@code messageCount} 0 — a quiet window is a normal MQTT
   * outcome and must not look like a failure.
   *
   * @param messages the collected messages
   * @return a success response carrying {@code messages} and {@code messageCount}
   */
  static IntegrationResponse success(List<Map<String, Object>> messages) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("messages", messages);
    result.put("messageCount", messages.size());
    return IntegrationResponse.forSuccess(result).build();
  }

  /** Builds an error response, truncating the message to {@value #MAX_ERROR_MESSAGE_LENGTH}. */
  private static IntegrationResponse error(String title, String message) {
    String safe = message == null ? "" : message;
    if (safe.length() > MAX_ERROR_MESSAGE_LENGTH) {
      safe = safe.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }
    return IntegrationResponse.forError(
            IntegrationError.builder().title(title).message(safe).build())
        .build();
  }
}
