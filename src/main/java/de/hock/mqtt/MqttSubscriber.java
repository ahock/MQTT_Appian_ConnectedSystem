package de.hock.mqtt;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

/**
 * The plug-in's second Eclipse Paho call site: connect, subscribe, collect for a bounded window,
 * unsubscribe, disconnect, once per request.
 *
 * <p>Appian integrations are synchronous — invoke, then return a result — while MQTT delivery is
 * event-driven, so a subscribe has to be bounded in both dimensions. This class collects until
 * {@code maxMessageCount} messages have arrived or {@code maxWaitSeconds} elapses, whichever comes
 * first, and an empty result is a perfectly normal success.
 *
 * <p><strong>It blocks an Appian request thread for the whole wait.</strong> That is the reason
 * {@code maxWaitSeconds} is capped at 30 by {@code MqttSubscribeIntegration} and defaults to 10.
 * Appian also applies its own integration request timeout, independently of this plug-in and unknown
 * to it; if Appian's timeout fires first, the designer sees an Appian error rather than an empty
 * result. The cap is not going up.
 *
 * <p><strong>Nothing outlives the request.</strong> No executor is created, no thread is started and
 * no {@code MqttCallback} is registered. Paho's own receiver thread hands each message to the
 * listener passed to {@code subscribe}, which does nothing but {@code offer} a finished map into a
 * bounded {@link LinkedBlockingQueue} sized to {@code maxMessageCount} — so the callback never
 * blocks and a flood of traffic cannot grow memory without bound; the overflow is simply dropped.
 * The {@code finally} block in {@link #subscribe} unsubscribes, disconnects and closes the client on
 * every path, including a timeout or a thrown exception, so no subscription and no connection can
 * survive the call.
 *
 * <p><strong>Client identifier.</strong> A blank configured {@code clientIdentifier} falls back to
 * Paho's {@link MqttClient#generateClientId()}, which is already unique per call. A configured one
 * gets {@value #SUBSCRIBE_CLIENT_ID_SUFFIX} appended, so a subscribe can never take over the publish
 * session's identifier on the same broker — a broker that sees a second connection with an existing
 * client id disconnects the first. Two consequences worth knowing: two <em>concurrent</em> subscribe
 * calls against the same connected system still share one derived id (leave
 * {@code clientIdentifier} blank to avoid that), and a broker enforcing the MQTT 3.1 23-character
 * client-id limit may reject the suffixed form if the configured id is already long.
 *
 * <p>Session settings come from the connected system and are applied by
 * {@link MqttPublisher#buildConnectOptions}, shared so the default-from-{@code null} logic exists
 * once. {@link MemoryPersistence} is used because Paho's default persistence writes to the
 * filesystem, which Appian's plug-in policies forbid. Transport is plain {@code tcp://} only: no
 * TLS, no socket factory, and no JVM-global property is ever set.
 */
public final class MqttSubscriber {

  /** How long a single queue poll waits before re-checking the overall time budget. */
  static final long POLL_SLICE_MILLIS = 200L;

  /** Appended to a configured client identifier so a subscribe cannot collide with a publish. */
  static final String SUBSCRIBE_CLIENT_ID_SUFFIX = "-sub";

  private MqttSubscriber() {
    // Utility class: no instances.
  }

  /**
   * Subscribes to one topic filter, collects messages within the bounds, then tears everything down.
   *
   * @param brokerHost broker host name or address
   * @param brokerPort broker TCP port
   * @param clientIdentifier MQTT client identifier; a blank value falls back to Paho's own
   *     {@link MqttClient#generateClientId()}, and a configured one is suffixed with
   *     {@value #SUBSCRIBE_CLIENT_ID_SUFFIX}
   * @param username broker user name, or {@code null}/blank for an anonymous connection
   * @param password broker password; applied only alongside a non-blank username
   * @param topicFilter the topic filter to subscribe to; MQTT wildcards {@code +} and {@code #} are
   *     supported by the broker
   * @param qos requested quality of service, 0, 1 or 2
   * @param maxWaitSeconds how long to collect for; the caller is responsible for keeping this within
   *     its documented range
   * @param maxMessageCount stop as soon as this many messages are collected; must be at least 1,
   *     because it is also the queue capacity
   * @param connectionTimeoutSeconds connect timeout; {@code null} falls back to the default
   *     documented on {@link MqttPublisher#buildConnectOptions}
   * @param keepAliveSeconds keep-alive interval; {@code null} leaves Paho's own default in place
   * @param cleanSession clean-session flag; {@code null} means {@code true}
   * @param autoReconnectEnabled automatic-reconnect flag; {@code null} means {@code false}
   * @return the collected messages in arrival order, each a map of {@code topic}, {@code payload},
   *     {@code qos}, {@code retained} and {@code binary}; empty when nothing arrived in the window
   * @throws MqttException if the connection or the subscription fails
   */
  public static List<Map<String, Object>> subscribe(
      String brokerHost,
      int brokerPort,
      String clientIdentifier,
      String username,
      char[] password,
      String topicFilter,
      int qos,
      int maxWaitSeconds,
      int maxMessageCount,
      Integer connectionTimeoutSeconds,
      Integer keepAliveSeconds,
      Boolean cleanSession,
      Boolean autoReconnectEnabled)
      throws MqttException {

    String clientId =
        (clientIdentifier == null || clientIdentifier.isBlank())
            ? MqttClient.generateClientId()
            : clientIdentifier.trim() + SUBSCRIBE_CLIENT_ID_SUFFIX;

    MqttConnectOptions options =
        MqttPublisher.buildConnectOptions(
            username,
            password,
            connectionTimeoutSeconds,
            keepAliveSeconds,
            cleanSession,
            autoReconnectEnabled);

    BlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>(maxMessageCount);

    MqttClient client =
        new MqttClient("tcp://" + brokerHost + ":" + brokerPort, clientId, new MemoryPersistence());
    client.setTimeToWait(MqttPublisher.TIME_TO_WAIT_MILLIS);

    boolean subscribed = false;
    try {
      client.connect(options);
      // The three-argument overload registers the listener as part of the subscription itself, so
      // nothing delivered between SUBACK and the first poll is lost. offer() drops overflow rather
      // than blocking Paho's receiver thread.
      client.subscribe(
          topicFilter, qos, (topic, message) -> queue.offer(toMessageMap(topic, message)));
      subscribed = true;
      return drain(
          queue, maxMessageCount, System.nanoTime() + TimeUnit.SECONDS.toNanos(maxWaitSeconds));
    } finally {
      if (subscribed) {
        try {
          client.unsubscribe(topicFilter);
        } catch (MqttException ignored) {
          // Best effort: the outcome is already decided, and the disconnect below ends delivery.
        }
      }
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
   * Drains the queue until the count is reached or the deadline passes, whichever comes first.
   *
   * <p>The deadline is an absolute {@link System#nanoTime()} reading rather than a duration, which
   * keeps the loop immune to clock adjustments and makes it drivable in a test without a broker: a
   * deadline in the past must return immediately, and a pre-filled queue must return exactly
   * {@code maxMessageCount} without waiting out the window. Polling in {@value #POLL_SLICE_MILLIS}
   * ms slices is what lets the count condition end the wait promptly instead of blocking for the
   * whole remaining budget.
   *
   * @param queue the queue the subscription listener offers into
   * @param maxMessageCount the number of messages after which to stop
   * @param deadlineNanos the absolute {@link System#nanoTime()} value at which to stop waiting
   * @return the collected messages in arrival order; possibly empty
   */
  static List<Map<String, Object>> drain(
      BlockingQueue<Map<String, Object>> queue, int maxMessageCount, long deadlineNanos) {

    List<Map<String, Object>> collected = new ArrayList<>(Math.min(maxMessageCount, 64));
    while (collected.size() < maxMessageCount) {
      long remainingNanos = deadlineNanos - System.nanoTime();
      if (remainingNanos <= 0L) {
        break;
      }
      long waitMillis =
          Math.min(POLL_SLICE_MILLIS, TimeUnit.NANOSECONDS.toMillis(remainingNanos) + 1);
      Map<String, Object> message;
      try {
        message = queue.poll(waitMillis, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
      if (message != null) {
        collected.add(message);
        // Take anything already buffered without re-blocking, up to the cap.
        queue.drainTo(collected, maxMessageCount - collected.size());
      }
    }
    return collected;
  }

  /**
   * Converts one inbound message into the map shape the integration returns.
   *
   * <p>Total by design: this runs on Paho's receiver thread, where a thrown exception would be
   * swallowed or would tear down delivery, so a {@code null} payload is treated as empty and nothing
   * here can fail. A payload that is valid UTF-8 becomes the decoded text with {@code binary} false;
   * anything else is Base64-encoded (RFC 4648 standard alphabet, with padding) with {@code binary}
   * true, so a byte is never lost or silently replaced. An empty payload is an empty string.
   *
   * @param topic the topic the message arrived on
   * @param message the inbound message
   * @return a map of {@code topic}, {@code payload}, {@code qos}, {@code retained}, {@code binary}
   */
  static Map<String, Object> toMessageMap(String topic, MqttMessage message) {
    byte[] payload =
        message == null || message.getPayload() == null ? new byte[0] : message.getPayload();
    String text = tryDecodeUtf8(payload);

    Map<String, Object> map = new LinkedHashMap<>();
    map.put("topic", topic);
    map.put("payload", text != null ? text : Base64.getEncoder().encodeToString(payload));
    map.put("qos", message == null ? 0 : message.getQos());
    map.put("retained", message != null && message.isRetained());
    map.put("binary", text == null);
    return map;
  }

  /** Strictly decodes UTF-8, returning {@code null} when the bytes are not valid UTF-8. */
  private static String tryDecodeUtf8(byte[] payload) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(payload))
          .toString();
    } catch (CharacterCodingException e) {
      return null;
    }
  }
}
