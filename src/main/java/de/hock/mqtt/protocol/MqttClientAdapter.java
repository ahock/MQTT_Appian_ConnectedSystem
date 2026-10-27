package de.hock.mqtt.protocol;

import java.util.function.BiConsumer;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;

/**
 * Narrow abstraction over the subset of the Eclipse Paho {@code MqttAsyncClient} surface that
 * {@link MqttSession} depends on.
 *
 * <p>Introducing this seam lets {@link MqttSession}'s logic be exercised against an in-memory fake
 * client in property tests without a live broker, while the production {@link PahoMqttClientAdapter}
 * delegates to a real {@code org.eclipse.paho.client.mqttv3.MqttAsyncClient}. The method set
 * deliberately mirrors the Paho client (connect/publish/subscribe/unsubscribe/isConnected/disconnect)
 * plus a single message-callback registration so inbound deliveries can be routed to a sink.
 *
 * <p>Publish and subscribe calls block until the broker acknowledges the operation (or the enclosing
 * session's timeout elapses), presenting the asynchronous Paho client as a synchronous surface that
 * matches Appian's request/response integration model.
 *
 * <p>Requirements: 4.1, 5.2, 5.5, 6.2.
 */
public interface MqttClientAdapter {

  /**
   * Registers the callback invoked for every inbound message once a subscription is active.
   *
   * <p>The callback receives the originating topic and the raw Paho message so the session can build
   * a {@link de.hock.mqtt.ReceivedMessage}. It must be registered before {@link #connect} so no
   * delivery is missed.
   *
   * @param onMessage receiver of {@code (topic, message)} pairs for each inbound message
   */
  void setMessageListener(BiConsumer<String, org.eclipse.paho.client.mqttv3.MqttMessage> onMessage);

  /**
   * Establishes the connection to the broker using the supplied options, blocking until the CONNECT
   * exchange completes.
   *
   * @param options the Paho connect options assembled from the connection configuration
   * @throws MqttException if the connection cannot be established
   */
  void connect(MqttConnectOptions options) throws MqttException;

  /**
   * Publishes a message to a topic, blocking until the broker acknowledges delivery for QoS 1/2 (or
   * the message is handed off for QoS 0).
   *
   * @param topic the destination topic
   * @param payload the raw message payload bytes
   * @param qos the quality of service (0, 1, or 2)
   * @param retained whether the broker should retain the message
   * @throws MqttException if delivery fails or times out
   */
  void publish(String topic, byte[] payload, int qos, boolean retained) throws MqttException;

  /**
   * Subscribes to the given topic filters at the requested QoS, blocking until the subscription is
   * acknowledged.
   *
   * @param filters the topic filters to subscribe to
   * @param qos the quality of service to request for each filter
   * @throws MqttException if the subscription fails
   */
  void subscribe(String[] filters, int qos) throws MqttException;

  /**
   * Unsubscribes from the given topic filters, blocking until the unsubscription is acknowledged.
   *
   * @param filters the topic filters to unsubscribe from
   * @throws MqttException if the unsubscription fails
   */
  void unsubscribe(String[] filters) throws MqttException;

  /**
   * Reports whether the client currently holds an active connection to the broker.
   *
   * @return {@code true} if connected, {@code false} otherwise
   */
  boolean isConnected();

  /**
   * Disconnects from the broker and releases client resources.
   *
   * @throws MqttException if the disconnect fails
   */
  void disconnect() throws MqttException;
}
