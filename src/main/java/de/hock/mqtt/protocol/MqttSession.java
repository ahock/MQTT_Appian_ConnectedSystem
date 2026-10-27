package de.hock.mqtt.protocol;

import de.hock.mqtt.ReceivedMessage;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

/**
 * A live MQTT session that performs publish and subscribe operations against a connected broker.
 *
 * <p>The session wraps a {@link MqttClientAdapter} — in production a Paho-backed adapter, in tests a
 * fake — so the publish/subscribe logic (including inbound message transformation) can be exercised
 * without a live broker. The {@link #subscribe} sink receives a {@link ReceivedMessage} for every
 * inbound delivery, with the originating topic, QoS, and retained flag preserved verbatim.
 *
 * <p><b>Payload handling.</b> Inbound payload bytes are classified as either valid UTF-8 text or
 * binary. Text payloads are passed through as a decoded string with {@code binary = false}; binary
 * payloads are Base64-encoded with {@code binary = true}, so arbitrary bytes survive the round-trip
 * through Appian's string-typed response surface (Req 6.10).
 *
 * <p>Requirements: 4.1, 5.2, 5.5, 6.2, 6.3, 6.10.
 */
public final class MqttSession {

  private final MqttClientAdapter client;

  /**
   * Creates a session over the given client adapter.
   *
   * @param client the client adapter to delegate protocol operations to (must not be {@code null})
   */
  public MqttSession(MqttClientAdapter client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  /**
   * Publishes a payload to a topic at the requested QoS, blocking until the broker acknowledges
   * delivery (QoS 1/2) or the message is dispatched (QoS 0).
   *
   * @param topic the destination topic
   * @param payload the raw payload bytes ({@code null} is treated as an empty payload)
   * @param qos the quality of service (0, 1, or 2)
   * @param retained whether the broker should retain the message
   * @throws MqttException if delivery fails or times out
   */
  public void publish(String topic, byte[] payload, int qos, boolean retained)
      throws MqttException {
    Objects.requireNonNull(topic, "topic");
    client.publish(topic, payload, qos, retained);
  }

  /**
   * Subscribes to the given topic filters at the requested QoS and routes every inbound message to
   * {@code sink} as a {@link ReceivedMessage}.
   *
   * <p>The message listener is registered before the subscription is sent so no delivery between
   * subscription and the first drain is lost.
   *
   * @param filters the topic filters to subscribe to
   * @param qos the quality of service to request
   * @param sink receiver of each transformed inbound message
   * @throws MqttException if the subscription fails
   */
  public void subscribe(List<String> filters, int qos, Consumer<ReceivedMessage> sink)
      throws MqttException {
    Objects.requireNonNull(filters, "filters");
    Objects.requireNonNull(sink, "sink");
    client.setMessageListener((topic, message) -> sink.accept(toReceivedMessage(topic, message)));
    client.subscribe(filters.toArray(new String[0]), qos);
  }

  /**
   * Unsubscribes from the given topic filters, blocking until the broker acknowledges.
   *
   * @param filters the topic filters to unsubscribe from
   * @throws MqttException if the unsubscription fails
   */
  public void unsubscribe(List<String> filters) throws MqttException {
    Objects.requireNonNull(filters, "filters");
    client.unsubscribe(filters.toArray(new String[0]));
  }

  /**
   * Reports whether the underlying client holds an active connection.
   *
   * @return {@code true} if connected, {@code false} otherwise
   */
  public boolean isConnected() {
    return client.isConnected();
  }

  /**
   * Builds a {@link ReceivedMessage} from an inbound Paho delivery, preserving the topic, QoS, and
   * retained flag and classifying the payload as text or binary.
   *
   * @param topic the originating topic
   * @param message the inbound Paho message
   * @return the transformed received message
   */
  static ReceivedMessage toReceivedMessage(String topic, MqttMessage message) {
    byte[] bytes = message.getPayload();
    if (bytes == null) {
      bytes = new byte[0];
    }

    String decoded = tryDecodeUtf8(bytes);
    if (decoded != null) {
      return new ReceivedMessage(topic, decoded, false, message.getQos(), message.isRetained());
    }

    String encoded = Base64.getEncoder().encodeToString(bytes);
    return new ReceivedMessage(topic, encoded, true, message.getQos(), message.isRetained());
  }

  /**
   * Attempts to decode the given bytes as strict UTF-8.
   *
   * @param bytes the payload bytes
   * @return the decoded string when the bytes form valid UTF-8, or {@code null} when they do not
   *     (indicating a binary payload)
   */
  private static String tryDecodeUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException e) {
      return null;
    }
  }
}
