package de.hock.mqtt.integration;

import com.appian.connectedsystems.simplified.sdk.SimpleIntegrationTemplate;
import com.appian.connectedsystems.simplified.sdk.configuration.SimpleConfiguration;
import com.appian.connectedsystems.templateframework.sdk.ExecutionContext;
import com.appian.connectedsystems.templateframework.sdk.IntegrationResponse;
import com.appian.connectedsystems.templateframework.sdk.TemplateId;
import com.appian.connectedsystems.templateframework.sdk.configuration.IntegerPropertyDescriptor;
import com.appian.connectedsystems.templateframework.sdk.configuration.ListTypePropertyDescriptor;
import com.appian.connectedsystems.templateframework.sdk.configuration.PropertyDescriptor;
import com.appian.connectedsystems.templateframework.sdk.configuration.PropertyPath;
import com.appian.connectedsystems.templateframework.sdk.configuration.PropertyState;
import com.appian.connectedsystems.templateframework.sdk.configuration.SystemType;
import com.appian.connectedsystems.templateframework.sdk.configuration.TextPropertyDescriptor;
import com.appian.connectedsystems.templateframework.sdk.metadata.IntegrationTemplateRequestPolicy;
import com.appian.connectedsystems.templateframework.sdk.metadata.IntegrationTemplateType;
import de.hock.mqtt.MqttConnectionConfig;
import de.hock.mqtt.MqttErrorCategory;
import de.hock.mqtt.OperationResult;
import de.hock.mqtt.ReceivedMessage;
import de.hock.mqtt.SubscribeRequest;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Integration template that performs a bounded MQTT <em>subscribe</em> operation and returns the
 * collected messages to the calling Appian expression.
 *
 * <p>Appian integrations are synchronous (invoke &rarr; return a result) but MQTT delivery is
 * event-driven, so the operation uses the design's <strong>bounded collection pattern</strong>: it
 * acquires a session, registers a message sink that offers each inbound {@link ReceivedMessage} into
 * a bounded {@link LinkedBlockingQueue}, subscribes at the requested QoS (Req 6.2), then drains the
 * queue until {@code maxMessageCount} messages have been collected or {@code maxWaitSeconds} elapses
 * (Req 6.5). The session is always unsubscribed and released in a {@code finally} block so a failed
 * or timed-out collection never leaks a connection.
 *
 * <p>Each returned message preserves its originating topic, payload, QoS, and retained flag; binary
 * payloads are already Base64-encoded with a binary indicator by {@link MqttSession} (Req 6.3,
 * 6.10). Returning <em>no</em> messages before the wait elapses is a SUCCESS with an empty
 * collection, not an error (Req 6.6).
 *
 * <p>This class extends {@link SimpleIntegrationTemplate} from
 * {@code com.appian:connected-systems-client}, the simplified surface that owns root-state
 * generation and local-type registration:
 * {@link #getConfiguration(SimpleConfiguration, SimpleConfiguration, PropertyPath,
 * ExecutionContext)} declares the four operation inputs, and
 * {@link #execute(SimpleConfiguration, SimpleConfiguration, ExecutionContext)} runs the
 * operation. It is a read/query operation, so it is annotated
 * {@link IntegrationTemplateRequestPolicy#READ} (no side effects on the broker).
 *
 * <p>Requirements: 6.2, 6.3, 6.5, 6.6, 6.10.
 */
@TemplateId(name = "MqttSubscribeIntegration")
@IntegrationTemplateType(IntegrationTemplateRequestPolicy.READ)
public class MqttSubscribeIntegration extends SimpleIntegrationTemplate {

  /** Operation name recorded in diagnostics (Req 9.3). */
  static final String OPERATION = "Subscribe";

  /** How long a single queue poll waits before re-checking the overall time budget. */
  private static final long POLL_SLICE_MILLIS = 200L;

  private final MqttConnectionManager connectionManager;
  private final InputValidator inputValidator;

  /** Creates an integration backed by a production connection manager. */
  public MqttSubscribeIntegration() {
    this(new MqttConnectionManager(), new InputValidator());
  }

  /**
   * Creates an integration with injected collaborators (testability seam).
   *
   * @param connectionManager the connection manager used to acquire and release sessions
   * @param inputValidator the runtime input validator
   */
  public MqttSubscribeIntegration(
      MqttConnectionManager connectionManager, InputValidator inputValidator) {
    this.connectionManager = connectionManager;
    this.inputValidator = inputValidator;
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

  /**
   * The four subscribe inputs: {@code topicFilters} (a list of text, 1&ndash;10 filters),
   * {@code qos} (0/1/2 choice), {@code maxWaitSeconds} (1&ndash;300), and
   * {@code maxMessageCount} (1&ndash;1000). Range enforcement is performed by {@link InputValidator}
   * at execution time; the descriptor labels document the permitted ranges to the Designer.
   */
  private PropertyDescriptor[] inputProperties() {
    return new PropertyDescriptor[] {
        ListTypePropertyDescriptor.builder()
            .key(InputValidator.FIELD_TOPIC_FILTERS)
            .label("Topic Filters")
            .instructionText("Topic filters to subscribe to (1\u201310 filters, each non-empty).")
            .itemType(SystemType.STRING)
            .isRequired(true)
            .isExpressionable(true)
            .build(),
        TextPropertyDescriptor.builder()
            .key(InputValidator.FIELD_QOS)
            .label("Quality of Service")
            .instructionText("Requested QoS for the subscription.")
            .choices(
                com.appian.connectedsystems.templateframework.sdk.configuration.Choice.builder()
                    .name("At most once (0)").value("0").build(),
                com.appian.connectedsystems.templateframework.sdk.configuration.Choice.builder()
                    .name("At least once (1)").value("1").build(),
                com.appian.connectedsystems.templateframework.sdk.configuration.Choice.builder()
                    .name("Exactly once (2)").value("2").build())
            .isRequired(true)
            .build(),
        IntegerPropertyDescriptor.builder()
            .key(InputValidator.FIELD_MAX_WAIT_SECONDS)
            .label("Max Wait (seconds)")
            .instructionText("Maximum time to collect messages, in seconds (1\u2013300).")
            .isRequired(true)
            .isExpressionable(true)
            .build(),
        IntegerPropertyDescriptor.builder()
            .key(InputValidator.FIELD_MAX_MESSAGE_COUNT)
            .label("Max Message Count")
            .instructionText("Maximum number of messages to collect (1\u20131000).")
            .isRequired(true)
            .isExpressionable(true)
            .build(),
    };
  }

  @Override
  protected IntegrationResponse execute(
      SimpleConfiguration integrationConfiguration,
      SimpleConfiguration connectedSystemConfiguration,
      ExecutionContext executionContext) {

    PropertyState inputState = integrationConfiguration.toConfiguration().getRootState();
    SubscribeRequest request = readRequest(inputState);

    // Build the config up front so diagnostics (broker host, redaction) are available on every path,
    // including validation failures.
    MqttConnectionConfig config =
        MqttConnectedSystemTemplate.toConnectionConfig(
            connectedSystemConfiguration.toConfiguration().getRootState());

    DiagnosticsBuilder diagnostics =
        DiagnosticsBuilder.forConfig(config)
            .operation(OPERATION)
            .brokerHost(config == null ? null : config.brokerHost());

    // Runtime input validation: reject before contacting the broker (Req 6 validation, 9.x).
    ValidationResults validation = inputValidator.validateSubscribe(request);
    if (validation.hasErrors()) {
      return toResponse(validationFailure(validation, diagnostics), diagnostics);
    }

    MqttSession session = null;
    try {
      session = connectionManager.acquire(config);
      List<ReceivedMessage> collected =
          collect(session, request.topicFilters(), request.qos(),
              request.maxWaitSeconds(), request.maxMessageCount());

      diagnostics.success();
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("messages", toSerializableMessages(collected));
      data.put("messageCount", collected.size());
      return toResponse(new OperationResult(true, null, null, data), diagnostics);

    } catch (MqttOperationException e) {
      diagnostics.failure(e.category());
      return toResponse(
          new OperationResult(false, e.category(), diagnostics.redact(e.getMessage()), null),
          diagnostics);
    } catch (RuntimeException e) {
      MqttErrorCategory category = ExceptionCategoryTranslator.categorize(e);
      diagnostics.failure(category);
      return toResponse(
          new OperationResult(false, category, diagnostics.redact(e.getMessage()), null),
          diagnostics);
    } finally {
      // Unsubscribe and release in finally so a failed or timed-out collection never leaks the
      // session; both are best-effort because the operation outcome is already decided.
      if (session != null) {
        try {
          session.unsubscribe(request.topicFilters());
        } catch (Exception ignored) {
          // Unsubscribe failure must not override the operation result.
        }
        connectionManager.release(session);
      }
    }
  }

  /**
   * Subscribes and drains the inbound queue until {@code maxMessageCount} messages are collected or
   * the {@code maxWaitSeconds} budget elapses (Req 6.5), whichever happens first. Returns the
   * collected messages in arrival order; an empty list is a valid (timed-out, no-message) result
   * (Req 6.6).
   */
  private List<ReceivedMessage> collect(
      MqttSession session, List<String> filters, int qos, int maxWaitSeconds, int maxMessageCount)
      throws MqttOperationException {

    // Bounded queue capped at the requested message count so a flood of inbound messages cannot grow
    // memory without bound; the sink drops overflow with offer() rather than blocking the callback.
    LinkedBlockingQueue<ReceivedMessage> queue = new LinkedBlockingQueue<>(maxMessageCount);
    Consumer<ReceivedMessage> sink = queue::offer;

    try {
      session.subscribe(filters, qos, sink);
    } catch (org.eclipse.paho.client.mqttv3.MqttException e) {
      throw new MqttOperationException(
          MqttErrorCategory.UNKNOWN, "Failed to subscribe to the requested topic filters.", e);
    }

    List<ReceivedMessage> collected = new ArrayList<>(Math.min(maxMessageCount, 64));
    long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(maxWaitSeconds);

    while (collected.size() < maxMessageCount) {
      long remainingNanos = deadlineNanos - System.nanoTime();
      if (remainingNanos <= 0L) {
        break;
      }
      long waitMillis = Math.min(POLL_SLICE_MILLIS, TimeUnit.NANOSECONDS.toMillis(remainingNanos) + 1);
      ReceivedMessage message;
      try {
        message = queue.poll(waitMillis, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
      if (message != null) {
        collected.add(message);
        // Drain anything already buffered without re-blocking, up to the cap.
        queue.drainTo(collected, maxMessageCount - collected.size());
      }
    }

    return collected;
  }

  /**
   * Reads the four input values from the integration configuration state into a
   * {@link SubscribeRequest}. Missing or non-integer numeric inputs become out-of-range sentinels so
   * the validator reports them rather than this method throwing.
   */
  private SubscribeRequest readRequest(PropertyState inputState) {
    List<String> topicFilters = readStringList(inputState, InputValidator.FIELD_TOPIC_FILTERS);
    int qos = readInt(inputState, InputValidator.FIELD_QOS, -1);
    int maxWaitSeconds = readInt(inputState, InputValidator.FIELD_MAX_WAIT_SECONDS, -1);
    int maxMessageCount = readInt(inputState, InputValidator.FIELD_MAX_MESSAGE_COUNT, -1);
    return new SubscribeRequest(topicFilters, qos, maxWaitSeconds, maxMessageCount);
  }

  @SuppressWarnings("unchecked")
  private static List<String> readStringList(PropertyState state, String key) {
    Object value = state == null ? null : state.getValueAtPath(new PropertyPath(key));
    if (value == null) {
      return null;
    }
    List<String> result = new ArrayList<>();
    if (value instanceof List<?> list) {
      for (Object item : list) {
        result.add(item == null ? null : String.valueOf(item));
      }
      return result;
    }
    if (value instanceof Object[] array) {
      for (Object item : array) {
        result.add(item == null ? null : String.valueOf(item));
      }
      return result;
    }
    // A single scalar value is treated as a one-element list.
    result.add(String.valueOf(value));
    return result;
  }

  private static int readInt(PropertyState state, String key, int defaultValue) {
    Object value = state == null ? null : state.getValueAtPath(new PropertyPath(key));
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof Number n) {
      return n.intValue();
    }
    try {
      return Integer.parseInt(String.valueOf(value).trim());
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  /**
   * Converts the collected messages into a serializable, SDK-friendly form: a list of maps carrying
   * {@code topic}, {@code payload}, {@code qos}, {@code retained}, and {@code binary} for each
   * message (Req 6.3, 6.10).
   */
  private static List<Map<String, Object>> toSerializableMessages(List<ReceivedMessage> messages) {
    List<Map<String, Object>> out = new ArrayList<>(messages.size());
    for (ReceivedMessage message : messages) {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("topic", message.topic());
      map.put("payload", message.payload());
      map.put("qos", message.qos());
      map.put("retained", message.retained());
      map.put("binary", message.binary());
      out.add(map);
    }
    return out;
  }

  /**
   * Builds a VALIDATION {@link OperationResult} from the collected validation errors, carrying the
   * per-field messages so a caller can see every rejected input, with no broker contact.
   */
  private OperationResult validationFailure(
      ValidationResults validation, DiagnosticsBuilder diagnostics) {
    diagnostics.failure(MqttErrorCategory.VALIDATION);
    StringBuilder message = new StringBuilder("Invalid subscribe inputs: ");
    List<Map<String, Object>> fieldErrors = new ArrayList<>();
    boolean first = true;
    for (ValidationResult error : validation.errors()) {
      if (!first) {
        message.append("; ");
      }
      message.append(error.fieldId()).append(": ").append(error.message());
      first = false;

      Map<String, Object> fieldError = new LinkedHashMap<>();
      fieldError.put("field", error.fieldId());
      fieldError.put("message", error.message());
      fieldErrors.add(fieldError);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("validationErrors", fieldErrors);
    return new OperationResult(
        false, MqttErrorCategory.VALIDATION, diagnostics.redact(message.toString()), data);
  }

  /**
   * Maps an {@link OperationResult} to an {@link IntegrationResponse} via {@link ResponseMapper} and
   * attaches the redacted diagnostics (broker host, operation, outcome, error category).
   */
  private static IntegrationResponse toResponse(
      OperationResult result, DiagnosticsBuilder diagnostics) {
    return ResponseMapper.toIntegrationResponse(result).toBuilder()
        .withDiagnostic(diagnostics.buildDesignerDiagnostic())
        .build();
  }
}
