/**
 * Configuration and input validation for the MQTT Connected System plug-in.
 *
 * <p>Holds the shared, field-scoped result types ({@link de.hock.mqtt.validation.ValidationResult}
 * and the {@link de.hock.mqtt.validation.ValidationResults} accumulator) and the validators that
 * produce them. {@link de.hock.mqtt.validation.ConfigValidator} owns the connection, credential,
 * and session design-time concerns; sibling validators (LWT, reconnection, certificate load) and the
 * runtime input validator live alongside it in this package and reuse the same result types and
 * {@link de.hock.mqtt.validation.ConfigFields field identifiers}, so new validation concerns are
 * added by introducing new classes rather than editing existing ones.
 */
package de.hock.mqtt.validation;
