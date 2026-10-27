package de.hock.mqtt;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

/**
 * One of the plug-in's two Eclipse Paho call sites: connect, publish, disconnect, once per request.
 *
 * <p>Paho 1.2.5 ships as a nested jar under {@code META-INF/lib/}, which is where Appian's plug-in
 * classloader resolves bundled libraries from — confirmed by the 1.0.4 deploy on a live 26.9
 * instance. The Paho surface stays confined to this class and {@link MqttSubscriber}, so a
 * classloading failure names one of those two and nothing else. {@link #buildConnectOptions} is
 * shared with the subscriber, which is why it is package-private rather than private: the
 * connected system's session settings are mapped to Paho in exactly one place.
 *
 * <p>Session settings — connect timeout, keep-alive interval, clean session and automatic reconnect
 * — come from the connected system and are applied in {@link #buildConnectOptions}; a {@code null}
 * value means "not configured" and takes the default documented there. There is deliberately no
 * connection cache, no session manager, no background thread and no Last Will. The synchronous
 * {@link MqttClient} is used rather than
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

  /** Lowest configured connect timeout accepted at run time; matches the design-time rule. */
  static final int CONNECT_TIMEOUT_MIN_SECONDS = 1;

  /** Highest configured connect timeout accepted at run time; matches the design-time rule. */
  static final int CONNECT_TIMEOUT_MAX_SECONDS = 300;

  /** Lowest configured keep-alive interval accepted at run time; {@code 0} disables keep-alive. */
  static final int KEEP_ALIVE_MIN_SECONDS = 0;

  /** Highest configured keep-alive interval accepted at run time, the MQTT 16-bit ceiling. */
  static final int KEEP_ALIVE_MAX_SECONDS = 65535;

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
   * @param connectionTimeoutSeconds connect timeout; {@code null} or out of range falls back to
   *     {@value #CONNECT_TIMEOUT_SECONDS} seconds
   * @param keepAliveSeconds keep-alive interval; {@code null} or out of range leaves Paho's own
   *     default of 60 seconds in place, and {@code 0} disables keep-alive
   * @param cleanSession clean-session flag; {@code null} means {@code true}
   * @param autoReconnectEnabled automatic-reconnect flag; {@code null} means {@code false}
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
      int qos,
      Integer connectionTimeoutSeconds,
      Integer keepAliveSeconds,
      Boolean cleanSession,
      Boolean autoReconnectEnabled)
      throws MqttException {

    String clientId =
        (clientIdentifier == null || clientIdentifier.isBlank())
            ? MqttClient.generateClientId()
            : clientIdentifier.trim();

    MqttConnectOptions options =
        buildConnectOptions(
            username,
            password,
            connectionTimeoutSeconds,
            keepAliveSeconds,
            cleanSession,
            autoReconnectEnabled);

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

  /**
   * Builds the connect options from the connected system's session settings.
   *
   * <p>Separated out so the mapping from configuration to Paho is observable without a broker, and
   * shared with {@link MqttSubscriber} so neither call site duplicates the default-from-{@code null}
   * logic: every setter used here has a matching getter on {@link MqttConnectOptions}. The method is
   * total — it never throws. A {@code null} value means "not configured" and takes the documented
   * default; a supplied value outside its bounds also falls back to the default rather than throwing
   * here.
   *
   * <p>Since 1.0.6 those out-of-range fallbacks are unreachable on the integration path:
   * {@link ConnectionConfigValidator#firstError} rejects an out-of-range
   * {@code connectionTimeoutSeconds} or {@code keepAliveSeconds} before either integration reaches
   * this method. They are kept as defence in depth, so this method stays a total function for any
   * caller.
   *
   * <p>Effective defaults: connect timeout {@value #CONNECT_TIMEOUT_SECONDS} seconds; keep-alive
   * Paho's own {@link MqttConnectOptions#KEEP_ALIVE_INTERVAL_DEFAULT} seconds, left in place by not
   * calling the setter at all; clean session {@code true}; automatic reconnect {@code false}.
   *
   * @param username broker user name, or {@code null}/blank for an anonymous connection
   * @param password broker password; applied only alongside a non-blank username
   * @param connectionTimeoutSeconds connect timeout in seconds, 1–300, or {@code null}
   * @param keepAliveSeconds keep-alive interval in seconds, 0–65535, or {@code null}; {@code 0}
   *     disables MQTT keep-alive
   * @param cleanSession clean-session flag, or {@code null} for {@code true}
   * @param autoReconnectEnabled automatic-reconnect flag, or {@code null} for {@code false}. One
   *     request connects, publishes and disconnects, so this has almost no room to act; it is
   *     honoured because it is a legitimate Paho setting, and it starts no thread that outlives the
   *     request — the {@code finally} block in {@link #publish} disconnects and closes the client
   *     either way.
   * @return the connect options to hand to Paho
   */
  static MqttConnectOptions buildConnectOptions(
      String username,
      char[] password,
      Integer connectionTimeoutSeconds,
      Integer keepAliveSeconds,
      Boolean cleanSession,
      Boolean autoReconnectEnabled) {

    MqttConnectOptions options = new MqttConnectOptions();

    options.setConnectionTimeout(
        inRange(connectionTimeoutSeconds, CONNECT_TIMEOUT_MIN_SECONDS, CONNECT_TIMEOUT_MAX_SECONDS)
            ? connectionTimeoutSeconds
            : CONNECT_TIMEOUT_SECONDS);

    // Not calling the setter is what leaves Paho's own KEEP_ALIVE_INTERVAL_DEFAULT (60) in place.
    if (inRange(keepAliveSeconds, KEEP_ALIVE_MIN_SECONDS, KEEP_ALIVE_MAX_SECONDS)) {
      options.setKeepAliveInterval(keepAliveSeconds);
    }

    options.setCleanSession(cleanSession == null ? true : cleanSession);
    options.setAutomaticReconnect(autoReconnectEnabled != null && autoReconnectEnabled);

    if (username != null && !username.isBlank() && password != null && password.length > 0) {
      options.setUserName(username);
      options.setPassword(password);
    }

    return options;
  }

  /** True when {@code value} is supplied and within the inclusive bounds. */
  private static boolean inRange(Integer value, int min, int max) {
    return value != null && value >= min && value <= max;
  }
}
