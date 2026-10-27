package de.hock.mqtt;

/**
 * Fixed, closed set of error categories for all MQTT operation failures.
 *
 * <p>Every failure produced by the connected system or its integrations maps to exactly one value
 * of this enum, so calling Appian expressions can branch on the category without parsing free-form
 * error message text (Requirement 9.2, 9.6). The set is intentionally closed: unrecognized or
 * unclassified failures map to {@link #UNKNOWN} rather than leaking an uncategorized error.
 *
 * <ul>
 *   <li>{@link #VALIDATION} — design-time or runtime input failed validation.</li>
 *   <li>{@link #AUTHENTICATION} — the broker rejected the supplied credentials.</li>
 *   <li>{@link #CONNECTION_TIMEOUT} — the connection attempt did not complete within the timeout.</li>
 *   <li>{@link #TLS_VALIDATION} — the TLS handshake or certificate validation failed.</li>
 *   <li>{@link #DELIVERY_TIMEOUT} — a QoS 1/2 publish was not acknowledged within the timeout.</li>
 *   <li>{@link #DISCONNECTION} — an established connection to the broker was lost.</li>
 *   <li>{@link #CONFIGURATION} — the connection configuration is invalid or unusable.</li>
 *   <li>{@link #UNKNOWN} — an unrecognized failure that could not be classified.</li>
 * </ul>
 */
public enum MqttErrorCategory {
  VALIDATION,
  AUTHENTICATION,
  CONNECTION_TIMEOUT,
  TLS_VALIDATION,
  DELIVERY_TIMEOUT,
  DISCONNECTION,
  CONFIGURATION,
  UNKNOWN
}
