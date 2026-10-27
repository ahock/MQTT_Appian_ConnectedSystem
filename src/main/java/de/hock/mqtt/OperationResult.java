package de.hock.mqtt;

import java.util.Map;

/**
 * Uniform result of an operation, mapped into an Appian {@code IntegrationResponse}.
 *
 * <p>Carries a boolean success indicator, an {@link MqttErrorCategory} drawn from the fixed enum on
 * failure, a human-readable message (capped at 500 characters at the mapping point), and a data map
 * holding operation-specific results.
 *
 * <p>Requirements: 9.1.
 *
 * @param success whether the operation succeeded
 * @param category the error category on failure (from the fixed enum)
 * @param message a human-readable outcome message
 * @param data operation-specific result data
 */
public record OperationResult(boolean success, MqttErrorCategory category, String message, Map<String, Object> data) {
}
