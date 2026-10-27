package de.hock.mqtt;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

/**
 * The single Eclipse Paho call site of the minimal smoke-test build: connect, publish, disconnect,
 * once per request.
 *
 * <p>Keeping the whole Paho surface in one class is the point of this build. Paho 1.2.5 ships as a
 * nested jar under {@code META-INF/lib/}, which is where Appian's plug-in classloader resolves
 * bundled libraries from — a layout that has never been confirmed on a live instance. If the
 * classloader does not find it, the {@code NoClassDefFoundError} names
 * {@code de.hock.mqtt.MqttPublisher} and nothing else.
 *
 * <p>There is deliberately no connection cache, no session manager, no reconnection, no background
 * thread and no Last Will. The synchronous {@link MqttClient} is used rather than
 * {@code MqttAsyncClient} because its blocking API matches Appian's request/response model without
 * token plumbing, and {@link MemoryPersistence} is used because Paho's default persistence writes
 * to the filesystem, which Appian's plug-in policies forbid.
 *
 * <p>Transport is plain {@code tcp://} only. This build contains no TLS: no {@code ssl://} scheme,
 * no {@code SSLContext}, no socket factory, and — as the policies require — no
 * {@code System.setProperty} call anywhere.
 */
public final class MqttPublisher {

  /**
   * Connect timeout in seconds. Short on purpose: an unreachable broker must fail fast rather than
   * hang an Appian request thread.
   */
  static final int CONNECT_TIMEOUT_SECONDS = 5;

  /** Upper bound in milliseconds on any single blocking Paho call. */
  static final long TIME_TO_WAIT_MILLIS = 10_000L;

  private MqttPublisher() {
    // Utility class: no instances.
  }

  /**
   * Publishes one message, then disconnects.
   *
   * <p>A QoS 1 or 2 publish returns only once the broker acknowledges it; a QoS 0 publish returns
   * as soon as the message is handed off. The client is disconnected and closed in a
   * {@code finally} block, so a failed publish never leaks a connection. Explicit {@code finally}
   * is used rather than try-with-resources because closing the client would otherwise race the
   * disconnect.
   *
   * @param brokerHost broker host name or address
   * @param brokerPort broker TCP port
   * @param clientIdentifier MQTT client identifier; a blank value falls back to Paho's own
   *     {@link MqttClient#generateClientId()}
   * @param username broker user name, or {@code null}/blank for an anonymous connection
   * @param password broker password; applied only alongside a non-blank username
   * @param topic the topic to publish to
   * @param payload the message bytes; {@code null} is sent as an empty payload
   * @param qos requested quality of service, 0, 1 or 2
   * @throws MqttException if the connection or the publish fails
   */
  public static void publish(
      String brokerHost,
      int brokerPort,
      String clientIdentifier,
      String username,
      char[] password,
      String topic,
      byte[] payload,
      int qos)
      throws MqttException {

    String clientId =
        (clientIdentifier == null || clientIdentifier.isBlank())
            ? MqttClient.generateClientId()
            : clientIdentifier.trim();

    MqttConnectOptions options = new MqttConnectOptions();
    options.setConnectionTimeout(CONNECT_TIMEOUT_SECONDS);
    options.setCleanSession(true);
    options.setAutomaticReconnect(false);
    if (username != null && !username.isBlank() && password != null && password.length > 0) {
      options.setUserName(username);
      options.setPassword(password);
    }

    MqttClient client =
        new MqttClient("tcp://" + brokerHost + ":" + brokerPort, clientId, new MemoryPersistence());
    client.setTimeToWait(TIME_TO_WAIT_MILLIS);
    try {
      client.connect(options);
      client.publish(topic, payload == null ? new byte[0] : payload, qos, false);
    } finally {
      try {
        if (client.isConnected()) {
          client.disconnect();
        }
      } catch (MqttException ignored) {
        // Already failing or already gone: the close below is what matters.
      }
      try {
        client.close();
      } catch (MqttException ignored) {
        // Nothing useful can be done about a failed close of a client being discarded.
      }
    }
  }
}
