package de.hock.mqtt;

/**
 * A message delivered to a subscriber during a bounded collection.
 *
 * <p>Binary payloads are returned Base64-encoded with {@code binary} set to {@code true}; textual
 * payloads are returned as-is with {@code binary} set to {@code false}.
 *
 * <p>Requirements: 6.3, 6.10.
 *
 * @param topic the originating topic the message was published on
 * @param payload the message payload (Base64-encoded when {@code binary} is true)
 * @param binary whether the payload is binary and therefore Base64-encoded
 * @param qos the quality of service the message was delivered at (0, 1, or 2)
 * @param retained whether the message was flagged as retained
 */
public record ReceivedMessage(String topic, String payload, boolean binary, int qos, boolean retained) {
}
