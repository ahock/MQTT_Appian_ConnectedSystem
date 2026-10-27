package de.hock.mqtt;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.Test;

/**
 * The collection runtime, proven without a broker.
 *
 * <p>{@code MqttSubscriber.drain} takes an absolute {@link System#nanoTime()} deadline and a queue,
 * which is the whole testability seam: a pre-filled queue proves the count condition ends the wait
 * early, and a past or very near deadline proves the time condition ends it at all. No adapter
 * interface, no injected client, no socket. {@code toMessageMap} takes a plain {@link MqttMessage},
 * which is constructible offline because Paho is a compile-scope dependency.
 */
class MqttSubscriberTest {

  /** A distinguishable stand-in for a collected message. */
  private static Map<String, Object> message(String topic) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("topic", topic);
    return map;
  }

  /**
   * The count condition: with five messages already queued and a cap of three, {@code drain} must
   * return three immediately rather than waiting out the 30-second window.
   */
  @Test
  void drainStopsAsSoonAsTheMessageCountIsReached() {
    BlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>(10);
    for (int i = 0; i < 5; i++) {
      queue.offer(message("t/" + i));
    }

    long startedAt = System.nanoTime();
    List<Map<String, Object>> collected =
        MqttSubscriber.drain(queue, 3, System.nanoTime() + TimeUnit.SECONDS.toNanos(30));
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

    assertEquals(3, collected.size(), "the cap must stop collection");
    assertEquals(
        List.of("t/0", "t/1", "t/2"),
        collected.stream().map(m -> m.get("topic")).toList(),
        "messages must be returned in arrival order");
    assertTrue(
        elapsedMillis < 5_000L,
        "reaching the cap must end the wait at once, but it took " + elapsedMillis + " ms");
    assertEquals(2, queue.size(), "the untaken messages must stay in the queue");
  }

  /** The time condition: an empty queue returns empty, both for a past and for a near deadline. */
  @Test
  void drainStopsWhenTheDeadlineHasPassed() {
    BlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>(10);

    long startedAt = System.nanoTime();
    List<Map<String, Object>> past =
        MqttSubscriber.drain(queue, 10, System.nanoTime() - TimeUnit.SECONDS.toNanos(1));
    long pastElapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

    assertTrue(past.isEmpty(), "a deadline already passed must collect nothing");
    assertTrue(pastElapsed < 1_000L, "a past deadline must not block, but took " + pastElapsed);

    startedAt = System.nanoTime();
    List<Map<String, Object>> soon =
        MqttSubscriber.drain(queue, 10, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300));
    long soonElapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

    assertTrue(soon.isEmpty(), "a quiet window must collect nothing — and that is a success");
    assertTrue(
        soonElapsed < 5_000L,
        "the wait must end at the deadline, but it took " + soonElapsed + " ms");
  }

  /** Payload handling: UTF-8 text passes through, anything else is Base64 with {@code binary}. */
  @Test
  void aUtf8PayloadDecodesToTextAndANonUtf8PayloadIsBase64Flagged() {
    String text = "Gr\u00fc\u00dfe/\u65e5\u672c";
    MqttMessage utf8 = new MqttMessage(text.getBytes(UTF_8));
    utf8.setQos(1);
    utf8.setRetained(true);

    Map<String, Object> decoded = MqttSubscriber.toMessageMap("sensors/1/temp", utf8);

    assertEquals(
        List.of("topic", "payload", "qos", "retained", "binary"),
        List.copyOf(decoded.keySet()),
        "the message map keys, in order");
    assertEquals("sensors/1/temp", decoded.get("topic"));
    assertEquals(text, decoded.get("payload"), "valid UTF-8 must come back as text");
    assertEquals(1, decoded.get("qos"));
    assertEquals(Boolean.TRUE, decoded.get("retained"));
    assertEquals(Boolean.FALSE, decoded.get("binary"));

    // 0xC3 0x28 is a truncated two-byte sequence and 0xFF is never legal UTF-8.
    byte[] binaryPayload = {(byte) 0xC3, (byte) 0x28, (byte) 0xFF};
    Map<String, Object> binary =
        MqttSubscriber.toMessageMap("sensors/raw", new MqttMessage(binaryPayload));

    assertEquals(Boolean.TRUE, binary.get("binary"), "invalid UTF-8 must be flagged binary");
    assertEquals(
        Base64.getEncoder().encodeToString(binaryPayload),
        binary.get("payload"),
        "a binary payload is Base64-encoded, losing nothing");
    assertEquals(Boolean.FALSE, binary.get("retained"), "an unretained message reads false");

    Map<String, Object> empty = MqttSubscriber.toMessageMap("sensors/empty", new MqttMessage());
    assertEquals("", empty.get("payload"), "an empty payload is an empty string");
    assertEquals(Boolean.FALSE, empty.get("binary"), "an empty payload is not binary");
    assertFalse(
        MqttSubscriber.SUBSCRIBE_CLIENT_ID_SUFFIX.isBlank(),
        "a configured client id must get a distinguishing suffix");
  }
}
