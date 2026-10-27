package de.hock.mqtt.diagnostics;

import java.util.Collections;
import java.util.Map;
import com.appian.connectedsystems.templateframework.sdk.IntegrationError;
import com.appian.connectedsystems.templateframework.sdk.IntegrationResponse;
import de.hock.mqtt.MqttErrorCategory;
import de.hock.mqtt.OperationResult;

/**
 * Maps the uniform {@link OperationResult} produced by the protocol/operation layer into the Appian
 * SDK {@link IntegrationResponse} returned to the calling expression.
 *
 * <p>This is the single shaping point for every operation outcome, guaranteeing a stable response
 * contract (Requirement 9.1):
 *
 * <ul>
 *   <li>On success -> {@link IntegrationResponse#forSuccess(Map)} carrying the result data map, so a
 *       calling expression reads {@code isSuccess() == true} and the operation-specific result.</li>
 *   <li>On failure -> {@link IntegrationResponse#forError(IntegrationError)} where the error title is
 *       the fixed {@link MqttErrorCategory} name (never free-form text), so expressions can branch on
 *       the bounded category set (Requirement 9.2, 9.6), and the error message is truncated to at
 *       most {@value #MAX_ERROR_MESSAGE_LENGTH} characters (Requirement 9.2).</li>
 * </ul>
 *
 * <p>The mapping logic is intentionally isolated here so the exact SDK builder calls can be adjusted
 * in one place if the SDK surface changes. The exception-to-category translation it relies on lives
 * in {@link ExceptionCategoryTranslator}.
 *
 * <p>Requirements: 9.1, 9.2, 9.6.
 */
public final class ResponseMapper {

  /** Hard upper bound on the length of an error message carried in a response (Requirement 9.2). */
  public static final int MAX_ERROR_MESSAGE_LENGTH = 500;

  private ResponseMapper() {
    // Utility class: no instances.
  }

  /**
   * Maps an {@link OperationResult} to an {@link IntegrationResponse}.
   *
   * @param result the operation outcome to shape (must not be {@code null})
   * @return a success response with the result data, or an error response carrying the fixed error
   *     category and a message truncated to {@value #MAX_ERROR_MESSAGE_LENGTH} characters
   * @throws NullPointerException if {@code result} is {@code null}
   */
  public static IntegrationResponse toIntegrationResponse(OperationResult result) {
    if (result == null) {
      throw new NullPointerException("result");
    }

    if (result.success()) {
      Map<String, Object> data = result.data();
      Map<String, Object> safeData = data == null ? Collections.emptyMap() : data;
      return IntegrationResponse.forSuccess(safeData).build();
    }

    MqttErrorCategory category = result.category() == null ? MqttErrorCategory.UNKNOWN : result.category();
    String message = truncate(result.message());

    IntegrationError error =
        IntegrationError.builder()
            // The title is the fixed category name so expressions branch on a bounded set,
            // never on parsed free-form text (Req 9.2, 9.6).
            .title(category.name())
            .message(message)
            .build();

    return IntegrationResponse.forError(error).build();
  }

  /**
   * Truncates an error message to at most {@value #MAX_ERROR_MESSAGE_LENGTH} characters, treating a
   * {@code null} message as empty (Requirement 9.2).
   *
   * @param message the raw error message (may be {@code null})
   * @return a non-{@code null} message no longer than {@value #MAX_ERROR_MESSAGE_LENGTH} characters
   */
  private static String truncate(String message) {
    if (message == null) {
      return "";
    }
    if (message.length() <= MAX_ERROR_MESSAGE_LENGTH) {
      return message;
    }
    return message.substring(0, MAX_ERROR_MESSAGE_LENGTH);
  }
}
