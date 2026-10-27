package de.hock.mqtt;

/**
 * Immutable inputs for a publish operation.
 *
 * <p>Requirements: 5.1.
 *
 * @param topic the topic to publish to (1–65535 bytes, no wildcards)
 * @param payload the message payload bytes (0–268435455 bytes)
 * @param qos the requested quality of service (0, 1, or 2)
 * @param retained whether the broker should retain the message
 */
public record PublishRequest(String topic, byte[] payload, int qos, boolean retained) {
}
