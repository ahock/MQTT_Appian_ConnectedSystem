package de.hock.mqtt.validation;

import de.hock.mqtt.PublishRequest;
import de.hock.mqtt.SubscribeRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runtime input validator for the Publish and Subscribe operations.
 *
 * <p>Unlike the design-time {@code ConfigValidator} (which checks reusable Connected System
 * settings), this validator inspects the per-invocation inputs supplied to an integration before
 * any broker interaction occurs. When an input is rejected the operation returns a VALIDATION
 * outcome without contacting the broker; each returned {@link ValidationResult} identifies the
 * offending input by field id (and, for list members, the offending index) together with a
 * human-readable message describing the permitted range.
 *
 * <p>Topic and payload size bounds are measured in UTF-8 bytes / raw bytes to match the MQTT wire
 * limits, not in Java {@code char} counts.
 *
 * <p>Requirements: 5.1, 5.3, 5.4, 6.1, 6.4, 6.7, 6.8, 6.9.
 */
public final class InputValidator {

  /** Field id for the publish topic input. */
  public static final String FIELD_TOPIC = "topic";

  /** Field id for the publish payload input. */
  public static final String FIELD_PAYLOAD = "payload";

  /** Field id for a QoS input (publish or subscribe). */
  public static final String FIELD_QOS = "qos";

  /** Field id for the subscribe topic-filter collection. */
  public static final String FIELD_TOPIC_FILTERS = "topicFilters";

  /** Field id for the subscribe maximum wait duration input. */
  public static final String FIELD_MAX_WAIT_SECONDS = "maxWaitSeconds";

  /** Field id for the subscribe maximum message count input. */
  public static final String FIELD_MAX_MESSAGE_COUNT = "maxMessageCount";

  /** Minimum topic length in UTF-8 bytes (inclusive). */
  public static final int TOPIC_MIN_BYTES = 1;

  /** Maximum topic length in UTF-8 bytes (inclusive). */
  public static final int TOPIC_MAX_BYTES = 65535;

  /** Minimum payload length in bytes (inclusive). */
  public static final int PAYLOAD_MIN_BYTES = 0;

  /** Maximum payload length in bytes (inclusive). */
  public static final int PAYLOAD_MAX_BYTES = 268435455;

  /** Minimum number of subscribe topic filters (inclusive). */
  public static final int FILTERS_MIN = 1;

  /** Maximum number of subscribe topic filters (inclusive). */
  public static final int FILTERS_MAX = 10;

  /** Minimum subscribe wait duration in seconds (inclusive). */
  public static final int MAX_WAIT_SECONDS_MIN = 1;

  /** Maximum subscribe wait duration in seconds (inclusive). */
  public static final int MAX_WAIT_SECONDS_MAX = 300;

  /** Minimum subscribe message count (inclusive). */
  public static final int MAX_MESSAGE_COUNT_MIN = 1;

  /** Maximum subscribe message count (inclusive). */
  public static final int MAX_MESSAGE_COUNT_MAX = 1000;

  /** The MQTT single-level wildcard character, forbidden in a publish topic. */
  private static final char WILDCARD_PLUS = '+';

  /** The MQTT multi-level wildcard character, forbidden in a publish topic. */
  private static final char WILDCARD_HASH = '#';

  /**
   * Validates the inputs of a publish operation.
   *
   * <p>Checks, independently so every offending input is reported:
   *
   * <ul>
   *   <li>topic present and 1–65535 UTF-8 bytes (Req 5.1, 5.4);
   *   <li>topic free of the {@code +} and {@code #} wildcard characters (Req 5.4);
   *   <li>payload at most 268435455 bytes (Req 5.1);
   *   <li>QoS one of {@code 0}, {@code 1}, {@code 2} (Req 5.3).
   * </ul>
   *
   * @param request the publish inputs (must not be {@code null})
   * @return a results collection that {@link ValidationResults#hasErrors() has no errors} when the
   *     request is acceptable, or carries one error per rejected input identifying the offending
   *     field
   */
  public ValidationResults validatePublish(PublishRequest request) {
    Objects.requireNonNull(request, "request");
    List<ValidationResult> results = new ArrayList<>();

    validatePublishTopic(request.topic(), results);
    validatePayload(request.payload(), results);
    validateQos(request.qos(), results);

    return ValidationResults.of(results);
  }

  /**
   * Validates the inputs of a subscribe operation.
   *
   * <p>Checks, independently so every offending input is reported:
   *
   * <ul>
   *   <li>filter count 1–10 (Req 6.1, 6.4, 6.8);
   *   <li>each filter non-blank, reporting the offending index (Req 6.7);
   *   <li>QoS one of {@code 0}, {@code 1}, {@code 2} (Req 6.9);
   *   <li>max wait duration 1–300 seconds (Req 6.4);
   *   <li>max message count 1–1000 (Req 6.4).
   * </ul>
   *
   * @param request the subscribe inputs (must not be {@code null})
   * @return a results collection that {@link ValidationResults#hasErrors() has no errors} when the
   *     request is acceptable, or carries one error per rejected input identifying the offending
   *     field
   */
  public ValidationResults validateSubscribe(SubscribeRequest request) {
    Objects.requireNonNull(request, "request");
    List<ValidationResult> results = new ArrayList<>();

    validateTopicFilters(request.topicFilters(), results);
    validateQos(request.qos(), results);
    validateMaxWaitSeconds(request.maxWaitSeconds(), results);
    validateMaxMessageCount(request.maxMessageCount(), results);

    return ValidationResults.of(results);
  }

  private void validatePublishTopic(String topic, List<ValidationResult> results) {
    if (topic == null || topic.isEmpty()) {
      results.add(
          ValidationResult.error(
              FIELD_TOPIC,
              "Topic is required and must be 1 to " + TOPIC_MAX_BYTES + " UTF-8 bytes.",
              topic));
      return;
    }

    int byteLength = topic.getBytes(StandardCharsets.UTF_8).length;
    if (byteLength < TOPIC_MIN_BYTES || byteLength > TOPIC_MAX_BYTES) {
      results.add(
          ValidationResult.error(
              FIELD_TOPIC,
              "Topic must be "
                  + TOPIC_MIN_BYTES
                  + " to "
                  + TOPIC_MAX_BYTES
                  + " UTF-8 bytes, but was "
                  + byteLength
                  + " bytes.",
              topic));
      return;
    }

    if (topic.indexOf(WILDCARD_PLUS) >= 0 || topic.indexOf(WILDCARD_HASH) >= 0) {
      results.add(
          ValidationResult.error(
              FIELD_TOPIC,
              "Publish topic must not contain the wildcard characters '"
                  + WILDCARD_PLUS
                  + "' or '"
                  + WILDCARD_HASH
                  + "'.",
              topic));
    }
  }

  private void validatePayload(byte[] payload, List<ValidationResult> results) {
    int length = payload == null ? 0 : payload.length;
    if (length < PAYLOAD_MIN_BYTES || length > PAYLOAD_MAX_BYTES) {
      results.add(
          ValidationResult.error(
              FIELD_PAYLOAD,
              "Payload must be "
                  + PAYLOAD_MIN_BYTES
                  + " to "
                  + PAYLOAD_MAX_BYTES
                  + " bytes, but was "
                  + length
                  + " bytes.",
              length));
    }
  }

  private void validateQos(int qos, List<ValidationResult> results) {
    if (qos < 0 || qos > 2) {
      results.add(
          ValidationResult.error(
              FIELD_QOS, "QoS must be 0, 1, or 2, but was " + qos + ".", qos));
    }
  }

  private void validateTopicFilters(List<String> filters, List<ValidationResult> results) {
    int count = filters == null ? 0 : filters.size();
    if (count < FILTERS_MIN || count > FILTERS_MAX) {
      results.add(
          ValidationResult.error(
              FIELD_TOPIC_FILTERS,
              "Topic filter count must be "
                  + FILTERS_MIN
                  + " to "
                  + FILTERS_MAX
                  + ", but was "
                  + count
                  + ".",
              count));
    }

    if (filters == null) {
      return;
    }

    for (int i = 0; i < filters.size(); i++) {
      String filter = filters.get(i);
      if (filter == null || filter.isBlank()) {
        results.add(
            ValidationResult.error(
                FIELD_TOPIC_FILTERS + "[" + i + "]",
                "Topic filter at index " + i + " must not be empty.",
                filter));
      }
    }
  }

  private void validateMaxWaitSeconds(int maxWaitSeconds, List<ValidationResult> results) {
    if (maxWaitSeconds < MAX_WAIT_SECONDS_MIN || maxWaitSeconds > MAX_WAIT_SECONDS_MAX) {
      results.add(
          ValidationResult.error(
              FIELD_MAX_WAIT_SECONDS,
              "Maximum wait duration must be "
                  + MAX_WAIT_SECONDS_MIN
                  + " to "
                  + MAX_WAIT_SECONDS_MAX
                  + " seconds, but was "
                  + maxWaitSeconds
                  + ".",
              maxWaitSeconds));
    }
  }

  private void validateMaxMessageCount(int maxMessageCount, List<ValidationResult> results) {
    if (maxMessageCount < MAX_MESSAGE_COUNT_MIN || maxMessageCount > MAX_MESSAGE_COUNT_MAX) {
      results.add(
          ValidationResult.error(
              FIELD_MAX_MESSAGE_COUNT,
              "Maximum message count must be "
                  + MAX_MESSAGE_COUNT_MIN
                  + " to "
                  + MAX_MESSAGE_COUNT_MAX
                  + ", but was "
                  + maxMessageCount
                  + ".",
              maxMessageCount));
    }
  }
}
