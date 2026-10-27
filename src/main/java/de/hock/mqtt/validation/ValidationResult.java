package de.hock.mqtt.validation;

/**
 * A single field-scoped validation outcome.
 *
 * <p>Each result identifies the configuration or input field it applies to ({@code fieldId}),
 * carries a human-readable {@code message} describing why the value was rejected (or {@code null}
 * for a valid field), and retains the {@code value} the designer entered so the configuration
 * surface can echo it back rather than discarding it on a rejected save (Requirements 1.4, 1.5,
 * 1.7, 2.4, 7.4, 7.5, 8.5, 8.6).
 *
 * <p>This is the shared validation shape reused by every validator in this package (for example
 * {@code ConfigValidator}, {@code InputValidator}, {@code LwtValidator}, and
 * {@code ReconnectionValidator}). A result is an error when {@link #isError()} is {@code true}.
 *
 * @param fieldId identifier of the field this result applies to
 * @param message validation error message, or {@code null} when the field is valid
 * @param value the designer-entered value, retained so it can be re-displayed
 */
public record ValidationResult(String fieldId, String message, Object value) {

  /**
   * Creates a field-scoped validation error, retaining the entered value.
   *
   * @param fieldId identifier of the field
   * @param message validation error message describing the failure
   * @param value the entered value to retain
   * @return a validation error result
   */
  public static ValidationResult error(String fieldId, String message, Object value) {
    return new ValidationResult(fieldId, message, value);
  }

  /**
   * Alias of {@link #error(String, String, Object)} for callers that read a bare {@code of}
   * factory as "a result for this field".
   *
   * @param fieldId identifier of the field
   * @param message validation error message describing the failure
   * @param value the entered value to retain
   * @return a validation error result
   */
  public static ValidationResult of(String fieldId, String message, Object value) {
    return new ValidationResult(fieldId, message, value);
  }

  /**
   * Creates a successful (non-error) result for a field, retaining the entered value.
   *
   * @param fieldId identifier of the field
   * @param value the entered value to retain
   * @return a valid result with no message
   */
  public static ValidationResult valid(String fieldId, Object value) {
    return new ValidationResult(fieldId, null, value);
  }

  /**
   * Indicates whether this result represents a validation error.
   *
   * @return {@code true} when a non-blank error message is present
   */
  public boolean isError() {
    return message != null && !message.isBlank();
  }
}
