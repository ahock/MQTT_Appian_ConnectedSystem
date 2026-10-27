/**
 * Diagnostics and sensitive-value redaction for the MQTT Connected System plug-in.
 *
 * <p>Holds {@link de.hock.mqtt.diagnostics.DiagnosticsBuilder}, which records the broker host,
 * operation name, operation outcome, and (on failure) the fixed error category into the Appian
 * integration diagnostics (Requirement 9.3, 9.5), and {@link de.hock.mqtt.diagnostics.Redactor},
 * which scrubs configured sensitive values (usernames, passwords, private keys) from every
 * diagnostic entry and from any returned error-message string (Requirement 9.4). The operation
 * layer uses this package to assemble the diagnostics attached to each {@code IntegrationResponse}
 * so sensitive values never leak into diagnostics, error messages, or error categories.
 */
package de.hock.mqtt.diagnostics;
