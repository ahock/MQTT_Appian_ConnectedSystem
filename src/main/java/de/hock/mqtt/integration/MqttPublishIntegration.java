package de.hock.mqtt.integration;

import com.appian.connectedsystems.simplified.sdk.SimpleIntegrationTemplate;
import com.appian.connectedsystems.simplified.sdk.configuration.SimpleConfiguration;
import com.appian.connectedsystems.templateframework.sdk.ExecutionContext;
import com.appian.connectedsystems.templateframework.sdk.IntegrationResponse;
import com.appian.connectedsystems.templateframework.sdk.TemplateId;
import com.appian.connectedsystems.templateframework.sdk.configuration.BooleanDisplayMode;
import com.appian.connectedsystems.templateframework.sdk.configuration.BooleanPropertyDescriptor;
import com.appian.connectedsystems.templateframework.sdk.configuration.Choice;
import com.appian.connectedsystems.templateframework.sdk.configuration.PropertyDescriptor;
import com.appian.connectedsystems.templateframework.sdk.configuration.PropertyPath;
import com.appian.connectedsystems.templateframework.sdk.configuration.PropertyState;
import com.appian.connectedsystems.templateframework.sdk.configuration.TextPropertyDescriptor;
import com.appian.connectedsystems.templateframework.sdk.metadata.IntegrationTemplateRequestPolicy;
import com.appian.connectedsystems.templateframework.sdk.metadata.IntegrationTemplateType;
import de.hock.mqtt.MqttConnectionConfig;
import de.hock.mqtt.MqttErrorCategory;
import de.hock.mqtt.OperationResult;
import de.hock.mqtt.PublishRequest;
import de.hock.mqtt.diagnostics.DiagnosticsBuilder;
import de.hock.mqtt.diagnostics.ExceptionCategoryTranslator;
import de.hock.mqtt.diagnostics.ResponseMapper;
import de.hock.mqtt.protocol.MqttConnectionManager;
import de.hock.mqtt.protocol.MqttOperationException;
import de.hock.mqtt.protocol.MqttSession;
import de.hock.mqtt.template.MqttConnectedSystemTemplate;
import de.hock.mqtt.validation.InputValidator;
import de.hock.mqtt.validation.ValidationResult;
import de.hock.mqtt.validation.ValidationResults;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.paho.client.mqttv3.MqttException;

/**
 * Publish integration: publishes a single message to a topic on the broker configured by the
 * reusable {@link MqttConnectedSystemTemplate} Connected System.
 *
 * <p><strong>SDK shape.</strong> This class extends {@link SimpleIntegrationTemplate} from
 * {@code com.appian:connected-systems-client}, the same simplified surface the Connected System
 * template uses. {@link #getConfiguration(SimpleConfiguration, SimpleConfiguration, PropertyPath,
 * ExecutionContext)} declares the operation inputs and lets the facade generate and register the
 * root state, and
 * {@link #execute(SimpleConfiguration, SimpleConfiguration, ExecutionContext)} performs the
 * publish. The {@link IntegrationTemplateType} annotation marks the operation {@code WRITE} because
 * publishing is a side-effecting operation (Req 5.1).
 *
 * <p><strong>Inputs.</strong> {@code topic} (text), {@code payload} (text, encoded as UTF-8 bytes on
 * the wire), {@code qos} (choice 0/1/2), and {@code retained} (boolean).
 *
 * <p><strong>Execution flow.</strong>
 *
 * <ol>
 *   <li>Build a {@link PublishRequest} from the inputs and validate it with
 *       {@link InputValidator#validatePublish(PublishRequest)}. On any validation error the
 *       operation returns a {@link MqttErrorCategory#VALIDATION} failure <em>without contacting the
 *       broker</em> (Req 5.1, 5.3, 5.4).</li>
 *   <li>Build the {@link MqttConnectionConfig} from the Connected System configuration (reusing
 *       {@link MqttConnectedSystemTemplate#toConnectionConfig(PropertyState)}), acquire a session
 *       from the {@link MqttConnectionManager}, and publish the payload at the requested QoS with the
 *       retained flag (Req 5.2, 5.5). The session is always released in a {@code finally} block so a
 *       failed publish never leaks a connection (Req 5.5).</li>
 *   <li>On success, return {@code "delivered"} for QoS 1/2 (the broker acknowledged the message,
 *       Req 5.6) or {@code "dispatched"} for QoS 0 (handed off without acknowledgment, Req 5.7).</li>
 *   <li>A Paho failure is classified through {@link ExceptionCategoryTranslator}; a missing
 *       acknowledgment / write timeout for a QoS 1/2 message surfaces as
 *       {@link MqttErrorCategory#DELIVERY_TIMEOUT} (Req 5.8).</li>
 * </ol>
 *
 * <p>Every outcome is recorded into {@link DiagnosticsBuilder} (broker host, operation name
 * {@code "Publish"}, outcome, and error category on failure, with sensitive values redacted) and
 * mapped to the final {@link IntegrationResponse} through {@link ResponseMapper}.
 *
 * <p>Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8.
 */
@TemplateId(name = "MqttPublishIntegration")
@IntegrationTemplateType(IntegrationTemplateRequestPolicy.WRITE)
public class MqttPublishIntegration extends SimpleIntegrationTemplate {

  /** Operation name recorded in the diagnostics (Req 9.3). */
  static final String OPERATION_NAME = "Publish";

  /** Input key: topic to publish to. */
  static final String INPUT_TOPIC = "topic";

  /** Input key: payload text (encoded as UTF-8 bytes on the wire). */
  static final String INPUT_PAYLOAD = "payload";

  /** Input key: requested quality of service (0, 1, or 2). */
  static final String INPUT_QOS = "qos";

  /** Input key: whether the broker retains the message. */
  static final String INPUT_RETAINED = "retained";

  /** Result key carrying the success delivery status ("delivered" or "dispatched"). */
  static final String RESULT_STATUS = "status";

  /** Result key echoing the topic the message was published to. */
  static final String RESULT_TOPIC = "topic";

  /** Result key echoing the QoS the message was published at. */
  static final String RESULT_QOS = "qos";

  /** Status value for a QoS 1/2 publish the broker acknowledged (Req 5.6). */
  static final String STATUS_DELIVERED = "delivered";

  /** Status value for a QoS 0 publish handed off without acknowledgment (Req 5.7). */
  static final String STATUS_DISPATCHED = "dispatched";

  private final MqttConnectionManager connectionManager;

  /** Creates a publish integration backed by a real Paho connection manager. */
  public MqttPublishIntegration() {
    this(new MqttConnectionManager());
  }

  /**
   * Creates a publish integration backed by the supplied connection manager.
   *
   * <p>This constructor is the testability seam: a manager built with a fake client factory lets the
   * publish flow be exercised without a live broker.
   *
   * @param connectionManager the connection manager to acquire/release sessions through
   */
  MqttPublishIntegration(MqttConnectionManager connectionManager) {
    this.connectionManager = connectionManager;
  }

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

    // Build the connection config up front so diagnostics can redact its secrets and name the host,
    // even if the operation fails before (or during) broker contact.
    MqttConnectionConfig config = MqttConnectedSystemTemplate.toConnectionConfig(connectionState);
    DiagnosticsBuilder diagnostics =
        DiagnosticsBuilder.forConfig(config)
            .operation(OPERATION_NAME)
            .brokerHost(config.brokerHost());

    PublishRequest request = readRequest(inputs);

    // Runtime input validation runs before any broker contact; a rejected input returns a VALIDATION
    // failure without opening a connection (Req 5.1, 5.3, 5.4).
    ValidationResults validation = new InputValidator().validatePublish(request);
    if (validation.hasErrors()) {
      diagnostics.failure(MqttErrorCategory.VALIDATION);
      return toResponse(validationFailure(validation, diagnostics), diagnostics);
    }

    OperationResult result = publish(request, config, diagnostics);
    return toResponse(result, diagnostics);
  }

  /**
   * Acquires a session, publishes the message, and releases the session in a {@code finally} block.
   *
   * @param request the validated publish inputs
   * @param config the connection configuration derived from the Connected System
   * @param diagnostics the diagnostics accumulator for this operation
   * @return the operation result (success with "delivered"/"dispatched", or a categorized failure)
   */
  private OperationResult publish(
      PublishRequest request, MqttConnectionConfig config, DiagnosticsBuilder diagnostics) {

    MqttSession session = null;
    try {
      session = connectionManager.acquire(config);
      session.publish(request.topic(), request.payload(), request.qos(), request.retained());

      // QoS 1/2 publish returns only after the broker acknowledges (PUBACK/PUBCOMP), so reaching
      // this point means the message was delivered (Req 5.6). QoS 0 is fire-and-forget, so it is
      // merely dispatched (Req 5.7).
      String status = request.qos() == 0 ? STATUS_DISPATCHED : STATUS_DELIVERED;
      diagnostics.success();
      return success(status, request);
    } catch (MqttOperationException e) {
      // Session acquisition / connect failures already carry a fixed category (e.g. DISCONNECTION
      // naming host:port, AUTHENTICATION, CONNECTION_TIMEOUT, TLS_VALIDATION).
      diagnostics.failure(e.category());
      return failure(e.category(), diagnostics.redact(e.getMessage()));
    } catch (MqttException e) {
      // Publish-time Paho failures are classified centrally; a missing ack / write timeout for a
      // QoS 1/2 message maps to DELIVERY_TIMEOUT (Req 5.8).
      MqttErrorCategory category = ExceptionCategoryTranslator.categorize(e);
      diagnostics.failure(category);
      return failure(category, diagnostics.redact(publishFailureMessage(request, config, e)));
    } finally {
      // Always release so a failed publish cannot leak the connection (Req 5.5).
      connectionManager.release(session);
    }
  }

  /** Reads the four publish inputs from the integration configuration state into a request. */
  private PublishRequest readRequest(PropertyState inputs) {
    String topic = asString(inputs, INPUT_TOPIC);
    String payloadText = asString(inputs, INPUT_PAYLOAD);
    byte[] payload = payloadText == null ? new byte[0] : payloadText.getBytes(StandardCharsets.UTF_8);
    int qos = parseIntOrDefault(asString(inputs, INPUT_QOS), 0);
    boolean retained = asBoolean(inputs, INPUT_RETAINED, false);
    return new PublishRequest(topic, payload, qos, retained);
  }

  /** The four publish inputs, in display order. */
  private PropertyDescriptor[] inputProperties() {
    return new PropertyDescriptor[] {
        TextPropertyDescriptor.builder()
            .key(INPUT_TOPIC)
            .label("Topic")
            .instructionText(
                "Topic to publish to (1\u201365535 UTF-8 bytes, no '+' or '#' wildcards).")
            .isRequired(true)
            .isExpressionable(true)
            .build(),
        TextPropertyDescriptor.builder()
            .key(INPUT_PAYLOAD)
            .label("Payload")
            .instructionText("Message payload. Sent as UTF-8 bytes (0\u2013268435455 bytes).")
            .isRequired(false)
            .isExpressionable(true)
            .build(),
        TextPropertyDescriptor.builder()
            .key(INPUT_QOS)
            .label("QoS")
            .instructionText("Quality of Service for delivery.")
            .choices(
                Choice.builder().name("At most once (0)").value("0").build(),
                Choice.builder().name("At least once (1)").value("1").build(),
                Choice.builder().name("Exactly once (2)").value("2").build())
            .isRequired(true)
            .isExpressionable(true)
            .build(),
        BooleanPropertyDescriptor.builder()
            .key(INPUT_RETAINED)
            .label("Retained")
            .instructionText("Whether the broker retains the message as the topic's last value.")
            .displayMode(BooleanDisplayMode.CHECKBOX)
            .isExpressionable(true)
            .build(),
    };
  }

  // --- Result/response helpers -----------------------------------------------------------------

  /** Builds a VALIDATION failure whose message lists every rejected input (Req 5.3, 5.4). */
  private static OperationResult validationFailure(
      ValidationResults validation, DiagnosticsBuilder diagnostics) {
    StringBuilder message = new StringBuilder("Invalid publish input: ");
    boolean first = true;
    for (ValidationResult error : validation.errors()) {
      if (!first) {
        message.append("; ");
      }
      message.append(error.message());
      first = false;
    }
    return failure(MqttErrorCategory.VALIDATION, diagnostics.redact(message.toString()));
  }

  private static OperationResult success(String status, PublishRequest request) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put(RESULT_STATUS, status);
    data.put(RESULT_TOPIC, request.topic());
    data.put(RESULT_QOS, request.qos());
    return new OperationResult(true, null, status, data);
  }

  private static OperationResult failure(MqttErrorCategory category, String message) {
    return new OperationResult(false, category, message, Map.of());
  }

  private static String publishFailureMessage(
      PublishRequest request, MqttConnectionConfig config, MqttException e) {
    return "Failed to publish to topic '"
        + request.topic()
        + "' on MQTT broker "
        + config.brokerHost()
        + ":"
        + config.brokerPort()
        + " at QoS "
        + request.qos()
        + ": "
        + e.getMessage();
  }

  /** Maps the operation result to a response and attaches the redacted diagnostics (Req 9.1–9.5). */
  private static IntegrationResponse toResponse(
      OperationResult result, DiagnosticsBuilder diagnostics) {
    return ResponseMapper.toIntegrationResponse(result)
        .toBuilder()
        .withDiagnostic(diagnostics.buildDesignerDiagnostic())
        .build();
  }

  // --- Value-reading helpers -------------------------------------------------------------------

  private static String asString(PropertyState state, String key) {
    Object value = state.getValueAtPath(new PropertyPath(key));
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

  private static boolean asBoolean(PropertyState state, String key, boolean defaultValue) {
    Object value = state.getValueAtPath(new PropertyPath(key));
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof Boolean b) {
      return b;
    }
    return Boolean.parseBoolean(String.valueOf(value));
  }

  private static int parseIntOrDefault(String value, int defaultValue) {
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      // Return a deliberately out-of-range sentinel so QoS validation reports the bad value rather
      // than silently defaulting a non-integer entry.
      return -1;
    }
  }
}
