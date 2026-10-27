package de.hock.mqtt.validation;

/**
 * Design-time validator for the automatic-reconnection configuration group.
 *
 * <p>Produces field-scoped {@link ValidationResult} entries so the Connected System configuration
 * surface can display a message against the offending field and retain the designer-entered values
 * on a rejected save. The rules enforced here mirror the design's reconnection field bounds
 * (Requirement 4.5):
 *
 * <ul>
 *   <li>Maximum reconnection attempts must be an integer 0–10; an absent value defaults to 3.</li>
 *   <li>The reconnection interval must be an integer 1–120 seconds; an absent value defaults to
 *       5.</li>
 * </ul>
 *
 * <p>An absent value is represented by {@code null} and is always valid because the default is
 * applied. A present value is accepted only when it falls within its bound.
 *
 * <p>This validator is standalone and does not depend on {@code ConfigValidator}; it reuses the
 * shared {@link ValidationResult}/{@link ValidationResults} shape so results compose with the other
 * configuration validators.
 */
public final class ReconnectionValidator {

  /** Field identifier for the maximum reconnection attempts. */
  public static final String FIELD_MAX_RECONNECT_ATTEMPTS = "maxReconnectAttempts";

  /** Field identifier for the reconnection interval. */
  public static final String FIELD_RECONNECT_INTERVAL = "reconnectIntervalSeconds";

  /** Minimum allowed maximum-reconnect-attempts value (inclusive). */
  public static final int MAX_ATTEMPTS_MIN = 0;

  /** Maximum allowed maximum-reconnect-attempts value (inclusive). */
  public static final int MAX_ATTEMPTS_MAX = 10;

  /** Default maximum reconnection attempts applied when the value is absent. */
  public static final int MAX_ATTEMPTS_DEFAULT = 3;

  /** Minimum allowed reconnection interval in seconds (inclusive). */
  public static final int INTERVAL_MIN_SECONDS = 1;

  /** Maximum allowed reconnection interval in seconds (inclusive). */
  public static final int INTERVAL_MAX_SECONDS = 120;

  /** Default reconnection interval in seconds applied when the value is absent. */
  public static final int INTERVAL_DEFAULT_SECONDS = 5;

  /**
   * Validates the reconnection configuration fields.
   *
   * @param maxReconnectAttempts the configured maximum reconnection attempts, or {@code null} to
   *     apply the default of {@value #MAX_ATTEMPTS_DEFAULT}
   * @param reconnectIntervalSeconds the configured reconnection interval in seconds, or
   *     {@code null} to apply the default of {@value #INTERVAL_DEFAULT_SECONDS}
   * @return an accumulator that is {@link ValidationResults#isValid() valid} when the reconnection
   *     configuration is acceptable, or carries one field-scoped error per rejected field otherwise
   */
  public ValidationResults validate(
      Integer maxReconnectAttempts, Integer reconnectIntervalSeconds) {
    ValidationResults results = new ValidationResults();

    // Maximum attempts: integer 0-10, default 3 when absent (Req 4.5).
    if (maxReconnectAttempts != null
        && (maxReconnectAttempts < MAX_ATTEMPTS_MIN || maxReconnectAttempts > MAX_ATTEMPTS_MAX)) {
      results.add(
          ValidationResult.of(
              FIELD_MAX_RECONNECT_ATTEMPTS,
              "Maximum reconnection attempts must be an integer "
                  + MAX_ATTEMPTS_MIN
                  + " to "
                  + MAX_ATTEMPTS_MAX
                  + ", but was "
                  + maxReconnectAttempts
                  + ".",
              String.valueOf(maxReconnectAttempts)));
    }

    // Reconnection interval: integer 1-120, default 5 when absent (Req 4.5).
    if (reconnectIntervalSeconds != null
        && (reconnectIntervalSeconds < INTERVAL_MIN_SECONDS
            || reconnectIntervalSeconds > INTERVAL_MAX_SECONDS)) {
      results.add(
          ValidationResult.of(
              FIELD_RECONNECT_INTERVAL,
              "Reconnection interval must be an integer "
                  + INTERVAL_MIN_SECONDS
                  + " to "
                  + INTERVAL_MAX_SECONDS
                  + " seconds, but was "
                  + reconnectIntervalSeconds
                  + ".",
              String.valueOf(reconnectIntervalSeconds)));
    }

    return results;
  }

  /**
   * Resolves the effective maximum reconnection attempts, applying the default when absent.
   *
   * @param maxReconnectAttempts the configured value, or {@code null}
   * @return the configured value, or {@value #MAX_ATTEMPTS_DEFAULT} when absent
   */
  public int resolveMaxAttempts(Integer maxReconnectAttempts) {
    return maxReconnectAttempts == null ? MAX_ATTEMPTS_DEFAULT : maxReconnectAttempts;
  }

  /**
   * Resolves the effective reconnection interval, applying the default when absent.
   *
   * @param reconnectIntervalSeconds the configured value, or {@code null}
   * @return the configured value, or {@value #INTERVAL_DEFAULT_SECONDS} when absent
   */
  public int resolveIntervalSeconds(Integer reconnectIntervalSeconds) {
    return reconnectIntervalSeconds == null ? INTERVAL_DEFAULT_SECONDS : reconnectIntervalSeconds;
  }
}
