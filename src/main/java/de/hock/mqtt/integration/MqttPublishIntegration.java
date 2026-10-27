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
import de.hock.mqtt.MqttPublisher;
import de.hock.mqtt.template.MqttConnectedSystemTemplate;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.paho.client.mqttv3.MqttException;

/**
 * The one integration of the minimal smoke-test build: publishes a single message to a topic on the
 * broker configured by the {@link MqttConnectedSystemTemplate} Connected System.
 *
 * <p>This is requirement (4) of the reduction — proof that a real message reaches a real broker
 * through the bundled Paho library. The subscribe integration, retained publishing, the error
 * category taxonomy and the diagnostics/redaction layer are all gone; the full 1.0.3 surface is
 * archived at {@code .backup/mqtt-plugin-FULL-1.0.3-src.zip}.
 *
 * <p><strong>SDK shape.</strong> Extends {@link SimpleIntegrationTemplate} from
 * {@code com.appian:connected-systems-client}, the same simplified facade the Connected System
 * template uses, so the facade generates and registers the root state rather than leaving it to
 * this class. {@link IntegrationTemplateType} marks the operation {@code WRITE} because publishing
 * has side effects.
 *
 * <p><strong>Inputs.</strong> {@code topic} (text, required), {@code payload} (text, required, sent
 * as UTF-8 bytes) and {@code qos} (integer, optional, 0 when blank). All three are
 * <em>expressionable</em>, which is legal and useful on an integration — and the deliberate
 * opposite of the connected system, where Appian rejects any expressionable descriptor.
 *
 * <p><strong>Execution.</strong> {@code execute} reads the three inputs and the nine connected
 * system values — including the four session settings, which are passed straight through to
 * {@link MqttPublisher#buildConnectOptions} where a {@code null} takes its documented default —
 * applies four cheap run-time guards that fail before any broker contact, and then calls
 * {@link MqttPublisher#publish}. These guards are separate from the connected system's design-time
 * validation: they run at invocation, return an error response, and never touch
 * {@code PropertyState.setErrorAtPath}. They exist because Paho rejects a QoS outside 0–2 with an
 * {@code IllegalArgumentException} and because a blank host yields an opaque URI failure.
 *
 * <p>Success returns a flat map — {@code success}, {@code status}, {@code topic}, {@code qos} —
 * where {@code status} is {@code delivered} for QoS 1 or 2 (the broker acknowledged) and
 * {@code dispatched} for QoS 0 (handed off without acknowledgment). Failure returns an
 * {@link IntegrationError} titled {@code Invalid input} or {@code MQTT publish failed} with the
 * underlying message, truncated. Nothing secret is placed in a response: the password is never read
 * into a message.
 */
@TemplateId(name = "MqttPublishIntegration")
@IntegrationTemplateType(IntegrationTemplateRequestPolicy.WRITE)
public class MqttPublishIntegration extends SimpleIntegrationTemplate {

  /** Input key: topic to publish to. */
  public static final String INPUT_TOPIC = "topic";

  /** Input key: payload text, sent as UTF-8 bytes. */
  public static final String INPUT_PAYLOAD = "payload";

  /** Input key: requested quality of service, 0, 1 or 2. */
  public static final String INPUT_QOS = "qos";

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
            .key(INPUT_TOPIC)
            .label("Topic")
            .instructionText("Topic to publish to.")
            .isRequired(true)
            .isExpressionable(true)
            .build(),
        TextPropertyDescriptor.builder()
            .key(INPUT_PAYLOAD)
            .label("Payload")
            .instructionText("Message payload. Sent as UTF-8 bytes.")
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

    String topic = MqttConnectedSystemTemplate.textValue(inputs, INPUT_TOPIC);
    String payloadText = MqttConnectedSystemTemplate.textValue(inputs, INPUT_PAYLOAD);
    int qos = MqttConnectedSystemTemplate.intValue(inputs, INPUT_QOS, 0);

    String brokerHost =
        MqttConnectedSystemTemplate.textValue(connection, MqttConnectedSystemTemplate.BROKER_HOST);
    int brokerPort =
        MqttConnectedSystemTemplate.intValue(connection, MqttConnectedSystemTemplate.BROKER_PORT, 0);
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

    // Run-time guards, before any broker contact.
    if (topic == null || topic.isBlank()) {
      return error("Invalid input", "Topic is required.");
    }
    if (brokerHost == null || brokerHost.isBlank()) {
      return error(
          "Invalid input", "Broker host is required on the MQTT Broker connected system.");
    }
    if (brokerPort < 1 || brokerPort > 65535) {
      return error(
          "Invalid input",
          "Broker port must be between 1 and 65535 on the MQTT Broker connected system, but was "
              + brokerPort
              + ".");
    }
    if (qos < 0 || qos > 2) {
      return error("Invalid input", "QoS must be 0, 1 or 2, but was " + qos + ".");
    }

    byte[] payload =
        payloadText == null ? new byte[0] : payloadText.getBytes(StandardCharsets.UTF_8);
    char[] password = passwordText == null ? null : passwordText.toCharArray();

    try {
      MqttPublisher.publish(
          brokerHost,
          brokerPort,
          clientIdentifier,
          username,
          password,
          topic,
          payload,
          qos,
          connectionTimeoutSeconds,
          keepAliveSeconds,
          cleanSession,
          autoReconnectEnabled);
    } catch (MqttException | RuntimeException e) {
      return error(
          "MQTT publish failed",
          "Failed to publish to topic '"
              + topic
              + "' on MQTT broker "
              + brokerHost
              + ":"
              + brokerPort
              + " at QoS "
              + qos
              + ": "
              + e.getMessage());
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("success", Boolean.TRUE);
    result.put("status", qos == 0 ? "dispatched" : "delivered");
    result.put("topic", topic);
    result.put("qos", qos);
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
