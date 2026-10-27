package de.hock.mqtt.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.appian.connectedsystems.templateframework.sdk.IntegrationResponse;
import com.appian.connectedsystems.templateframework.sdk.configuration.ConfigurationDescriptor;
import com.appian.connectedsystems.templateframework.sdk.configuration.PropertyPath;
import com.appian.connectedsystems.templateframework.sdk.configuration.PropertyState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The subscribe integration's input contract and response shape, all without a broker.
 *
 * <p>Input reading, default fallback, range rejection and the success-response builder are reachable
 * as package-private statics, so each behaviour is asserted directly instead of being inferred from
 * a live call. The one test that drives {@code execute} uses the public
 * {@code execute(ConfigurationDescriptor, ConfigurationDescriptor, ExecutionContext)} overload
 * inherited from {@code SimpleIntegrationTemplate}, which is the only usable seam —
 * {@code SimpleConfiguration}'s constructor is private and its factory needs a
 * {@code TypePropertyFactory}. It also proves the first guard returns before any socket is opened.
 */
class MqttSubscribeIntegrationInputsTest {

  private static ConfigurationDescriptor emptyDescriptor() {
    return ConfigurationDescriptor.builder().build();
  }

  /** A freshly rendered, untouched subscribe input state. */
  private static PropertyState freshInputs() {
    ConfigurationDescriptor rendered =
        new MqttSubscribeIntegration()
            .getConfigurationDescriptor(
                emptyDescriptor(), emptyDescriptor(), new PropertyPath(), null);
    PropertyState rootState = rendered.getRootState();
    assertNotNull(rootState, "subscribe inputs must render a root state");
    return rootState;
  }

  private static void setValue(PropertyState state, String key, Object value) {
    state.setValueAtPath(new PropertyPath(key), value);
  }

  /** Blank inputs take the documented defaults; supplied values are read back unchanged. */
  @Test
  void absentInputsFallBackToTheDocumentedDefaults() {
    PropertyState inputs = freshInputs();

    assertEquals(
        MqttSubscribeIntegration.DEFAULT_QOS,
        MqttSubscribeIntegration.qosOf(inputs),
        "a blank QoS defaults to 0");
    assertEquals(
        MqttSubscribeIntegration.DEFAULT_MAX_WAIT_SECONDS,
        MqttSubscribeIntegration.maxWaitSecondsOf(inputs),
        "a blank max wait defaults to 10 seconds");
    assertEquals(
        MqttSubscribeIntegration.DEFAULT_MAX_MESSAGE_COUNT,
        MqttSubscribeIntegration.maxMessageCountOf(inputs),
        "a blank max message count defaults to 10");

    setValue(inputs, MqttSubscribeIntegration.INPUT_QOS, 2);
    setValue(inputs, MqttSubscribeIntegration.INPUT_MAX_WAIT_SECONDS, 25);
    setValue(inputs, MqttSubscribeIntegration.INPUT_MAX_MESSAGE_COUNT, 50);

    assertEquals(2, MqttSubscribeIntegration.qosOf(inputs));
    assertEquals(25, MqttSubscribeIntegration.maxWaitSecondsOf(inputs));
    assertEquals(50, MqttSubscribeIntegration.maxMessageCountOf(inputs));
  }

  /**
   * Out-of-range values are <strong>rejected</strong>, not clamped — a designer who asks for a
   * five-minute wait must be told it is not allowed rather than silently given thirty seconds.
   */
  @Test
  void outOfRangeQosMaxWaitAndMaxMessageCountAreRejected() {
    assertTrue(
        MqttSubscribeIntegration.inputError(-1, 10, 10).contains("-1"), "QoS -1 must be rejected");
    assertTrue(
        MqttSubscribeIntegration.inputError(3, 10, 10).contains("3"), "QoS 3 must be rejected");

    assertTrue(
        MqttSubscribeIntegration.inputError(0, 0, 10).contains("0"),
        "a zero-second wait must be rejected");
    assertTrue(
        MqttSubscribeIntegration.inputError(0, 31, 10).contains("31"),
        "31 seconds exceeds the 30-second cap");

    assertTrue(
        MqttSubscribeIntegration.inputError(0, 10, 0).contains("0"),
        "a zero message count must be rejected: it is also the queue capacity");
    assertTrue(
        MqttSubscribeIntegration.inputError(0, 10, 101).contains("101"),
        "101 messages exceeds the cap");

    assertNull(MqttSubscribeIntegration.inputError(0, 10, 10), "the defaults must be acceptable");
    assertNull(MqttSubscribeIntegration.inputError(2, 30, 100), "the upper bounds are inclusive");
    assertNull(MqttSubscribeIntegration.inputError(1, 1, 1), "the lower bounds are inclusive");
  }

  /** A quiet window is a success with {@code messageCount} 0, never an error. */
  @Test
  void anEmptyCollectionIsReportedAsSuccessWithMessageCountZero() {
    IntegrationResponse empty = MqttSubscribeIntegration.success(List.of());

    assertTrue(empty.isSuccess(), "no traffic in the window is a normal outcome, not a failure");
    assertEquals(0, empty.getResult().get("messageCount"), "messageCount must be 0");
    assertEquals(List.of(), empty.getResult().get("messages"), "messages must be an empty list");

    Map<String, Object> first = new LinkedHashMap<>();
    first.put("topic", "home/kitchen");
    Map<String, Object> second = new LinkedHashMap<>();
    second.put("topic", "home/hall");
    IntegrationResponse two = MqttSubscribeIntegration.success(List.of(first, second));

    assertTrue(two.isSuccess());
    assertEquals(2, two.getResult().get("messageCount"), "messageCount counts the messages");
    assertEquals(
        List.of("messages", "messageCount"),
        List.copyOf(two.getResult().keySet()),
        "the result keys, in order");
  }

  /** The first guard fires before any broker contact, so a blank filter never opens a socket. */
  @Test
  void executeWithoutATopicFilterReportsTheMissingInputAndContactsNoBroker() {
    IntegrationResponse response =
        new MqttSubscribeIntegration()
            .execute(emptyDescriptor(), emptyDescriptor(), null);

    assertFalse(response.isSuccess(), "a blank topic filter must fail");
    assertEquals("Invalid input", response.getError().getTitle());
    assertTrue(
        response.getError().getMessage().toLowerCase().contains("topic filter"),
        "the message must name the offending input: " + response.getError().getMessage());
  }
}
