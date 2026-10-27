package de.hock.mqtt.validation;

/**
 * Design-time validator for the Last Will and Testament (LWT) configuration group.
 *
 * <p>Produces field-scoped {@link ValidationResult} entries so the Connected System configuration
 * surface can display a message against the offending field and retain the designer-entered values
 * on a rejected save. The rules enforced here mirror the design's LWT field bounds:
 *
 * <ul>
 *   <li>An LWT payload set without an LWT topic is rejected with a missing-topic error
 *       (Requirement 7.4).</li>
 *   <li>The LWT topic, when provided, must be 1–65535 characters; a longer topic is rejected with
 *       an invalid-field error naming the permitted range (Requirement 7.1, 7.5).</li>
 *   <li>The LWT payload must be 0–268435455 bytes (Requirement 7.1).</li>
 *   <li>The LWT QoS level must be 0, 1, or 2; any other value is rejected with the permitted range
 *       (Requirement 7.1, 7.5).</li>
 * </ul>
 *
 * <p>This validator is standalone and does not depend on {@code ConfigValidator}; it reuses the
 * shared {@link ValidationResult}/{@link ValidationResults} shape so results compose with the other
 * configuration validators.
 */
public final class LwtValidator {

  /** Field identifier for the LWT topic. */
  public static final String FIELD_LWT_TOPIC = "lwtTopic";

  /** Field identifier for the LWT payload. */
  public static final String FIELD_LWT_PAYLOAD = "lwtPayload";

  /** Field identifier for the LWT QoS level. */
  public static final String FIELD_LWT_QOS = "lwtQos";

  /** Minimum LWT topic length in characters (inclusive). */
  public static final int TOPIC_MIN_CHARS = 1;

  /** Maximum LWT topic length in characters (inclusive). */
  public static final int TOPIC_MAX_CHARS = 65535;

  /** Minimum LWT payload size in bytes (inclusive). */
  public static final int PAYLOAD_MIN_BYTES = 0;

  /** Maximum LWT payload size in bytes (inclusive). */
  public static final int PAYLOAD_MAX_BYTES = 268435455;

  /**
   * Validates the LWT configuration fields.
   *
   * <p>Only fields that are present are range-checked; the cross-field rule that a payload requires
   * a topic is always evaluated. A blank or {@code null} topic combined with a {@code null} payload
   * means LWT is simply not configured and produces no errors.
   *
   * @param lwtTopic the configured LWT topic, or {@code null}/blank when not configured
   * @param lwtPayload the configured LWT payload bytes, or {@code null} when not configured
   * @param lwtQos the configured LWT QoS level
   * @return an accumulator that is {@link ValidationResults#isValid() valid} when the LWT
   *     configuration is acceptable, or carries one field-scoped error per rejected field otherwise
   */
  public ValidationResults validate(String lwtTopic, byte[] lwtPayload, int lwtQos) {
    ValidationResults results = new ValidationResults();

    boolean topicPresent = lwtTopic != null && !lwtTopic.isBlank();
    boolean payloadPresent = lwtPayload != null;

    // Cross-field rule: payload set without a topic -> missing-topic error (Req 7.4).
    if (payloadPresent && !topicPresent) {
      results.add(
          ValidationResult.of(
              FIELD_LWT_TOPIC,
              "An LWT topic is required when an LWT payload is configured.",
              lwtTopic));
    }

    // Topic length bound, only when a topic was actually provided (Req 7.1, 7.5).
    if (topicPresent) {
      int length = lwtTopic.length();
      if (length < TOPIC_MIN_CHARS || length > TOPIC_MAX_CHARS) {
        results.add(
            ValidationResult.of(
                FIELD_LWT_TOPIC,
                "LWT topic must be "
                    + TOPIC_MIN_CHARS
                    + " to "
                    + TOPIC_MAX_CHARS
                    + " characters, but was "
                    + length
                    + ".",
                lwtTopic));
      }
    }

    // Payload size bound, only when a payload was provided (Req 7.1).
    if (payloadPresent) {
      int size = lwtPayload.length;
      if (size < PAYLOAD_MIN_BYTES || size > PAYLOAD_MAX_BYTES) {
        results.add(
            ValidationResult.of(
                FIELD_LWT_PAYLOAD,
                "LWT payload must be "
                    + PAYLOAD_MIN_BYTES
                    + " to "
                    + PAYLOAD_MAX_BYTES
                    + " bytes, but was "
                    + size
                    + ".",
                String.valueOf(size)));
      }
    }

    // QoS bound, evaluated whenever LWT is configured at all (Req 7.1, 7.5).
    if ((topicPresent || payloadPresent) && (lwtQos < 0 || lwtQos > 2)) {
      results.add(
          ValidationResult.of(
              FIELD_LWT_QOS,
              "LWT QoS must be 0, 1, or 2, but was " + lwtQos + ".",
              String.valueOf(lwtQos)));
    }

    return results;
  }
}
