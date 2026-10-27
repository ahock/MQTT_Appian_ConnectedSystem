package de.hock.mqtt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.appian.connectedsystems.templateframework.sdk.configuration.ConfigurationDescriptor;
import com.appian.connectedsystems.templateframework.sdk.configuration.PropertyPath;
import com.appian.connectedsystems.templateframework.sdk.configuration.PropertyState;
import de.hock.mqtt.template.MqttConnectedSystemTemplate;
import org.junit.jupiter.api.Test;

/**
 * The four configuration rules 1.0.6 demoted from design time to run time, each still covered.
 *
 * <p>Until 1.0.5 these rules attached messages to field paths and were tested by reading them back
 * through {@code getErrorsAtPath}. Appian turned out to discard those messages, so the rules now
 * return a sentence from {@link ConnectionConfigValidator#firstError} and both integrations turn it
 * into an {@code Invalid configuration} error response. The coverage moved with them rather than
 * being deleted.
 *
 * <p>Each test builds a real connected system state the way the platform does — render the template
 * with the empty descriptor Appian supplies for a brand-new object, then {@code setValueAtPath} —
 * so the keys and nested state shape are the genuine ones, not a hand-rolled stand-in. No broker and
 * no Appian runtime are involved.
 */
class ConnectionConfigValidatorTest {

  private static final String VALID_HOST = "broker.example.test";

  /** A freshly rendered, untouched connected system state. */
  private static PropertyState freshState() {
    ConfigurationDescriptor rendered =
        new MqttConnectedSystemTemplate()
            .getConfigurationDescriptor(
                ConfigurationDescriptor.builder().build(), new PropertyPath(), null);
    PropertyState rootState = rendered.getRootState();
    assertNotNull(rootState, "root state must be generated before values can be set");
    return rootState;
  }

  private static PropertyState withValue(PropertyState state, String key, Object value) {
    state.setValueAtPath(new PropertyPath(key), value);
    return state;
  }

  /** A valid host and port, with every optional field blank. */
  private static PropertyState validState() {
    PropertyState state = freshState();
    withValue(state, MqttConnectedSystemTemplate.BROKER_HOST, VALID_HOST);
    withValue(state, MqttConnectedSystemTemplate.BROKER_PORT, 1883);
    return state;
  }

  /** Rule 1: the broker host is required, and a {@code null} state reports the same thing. */
  @Test
  void missingOrBlankBrokerHostIsReported() {
    String fresh = ConnectionConfigValidator.firstError(freshState());
    assertNotNull(fresh, "an untouched connected system has no broker host");
    assertTrue(fresh.contains("Broker host"), "the message must name the broker host: " + fresh);

    PropertyState blank = withValue(freshState(), MqttConnectedSystemTemplate.BROKER_HOST, "   ");
    withValue(blank, MqttConnectedSystemTemplate.BROKER_PORT, 1883);
    String blankError = ConnectionConfigValidator.firstError(blank);
    assertNotNull(blankError, "whitespace is not a broker host");
    assertTrue(blankError.contains("Broker host"), blankError);

    String nullState = ConnectionConfigValidator.firstError(null);
    assertNotNull(nullState, "a connected system carrying no state at all must be reported");
    assertTrue(nullState.contains("Broker host"), nullState);
  }

  /** Rule 2: the broker port is required and must be a valid TCP port. */
  @Test
  void missingOrOutOfRangeBrokerPortIsReported() {
    PropertyState hostOnly = freshState();
    withValue(hostOnly, MqttConnectedSystemTemplate.BROKER_HOST, VALID_HOST);
    String missing = ConnectionConfigValidator.firstError(hostOnly);
    assertNotNull(missing, "a blank port must be reported");
    assertTrue(missing.contains("Broker port"), missing);

    PropertyState tooHigh = validState();
    withValue(tooHigh, MqttConnectedSystemTemplate.BROKER_PORT, 99999);
    String tooHighError = ConnectionConfigValidator.firstError(tooHigh);
    assertNotNull(tooHighError, "port 99999 is out of range");
    assertTrue(tooHighError.contains("99999"), "the message must name the value: " + tooHighError);

    PropertyState zero = validState();
    withValue(zero, MqttConnectedSystemTemplate.BROKER_PORT, 0);
    assertNotNull(ConnectionConfigValidator.firstError(zero), "port 0 is out of range");
  }

  /** Rules 3 and 4: the two session ranges, with keep-alive 0 accepted as a legal disable. */
  @Test
  void outOfRangeConnectionTimeoutOrKeepAliveIsReported() {
    PropertyState timeoutTooLow = validState();
    withValue(timeoutTooLow, MqttConnectedSystemTemplate.CONNECTION_TIMEOUT_SECONDS, 0);
    String timeoutError = ConnectionConfigValidator.firstError(timeoutTooLow);
    assertNotNull(timeoutError, "a connect timeout of 0 is out of range");
    assertTrue(timeoutError.contains("Connection timeout"), timeoutError);

    PropertyState timeoutTooHigh = validState();
    withValue(timeoutTooHigh, MqttConnectedSystemTemplate.CONNECTION_TIMEOUT_SECONDS, 301);
    assertNotNull(
        ConnectionConfigValidator.firstError(timeoutTooHigh), "301 seconds is out of range");

    PropertyState keepAliveNegative = validState();
    withValue(keepAliveNegative, MqttConnectedSystemTemplate.KEEP_ALIVE_SECONDS, -1);
    String keepAliveError = ConnectionConfigValidator.firstError(keepAliveNegative);
    assertNotNull(keepAliveError, "a negative keep-alive is out of range");
    assertTrue(keepAliveError.contains("Keep alive"), keepAliveError);

    PropertyState keepAliveTooHigh = validState();
    withValue(keepAliveTooHigh, MqttConnectedSystemTemplate.KEEP_ALIVE_SECONDS, 65536);
    assertNotNull(
        ConnectionConfigValidator.firstError(keepAliveTooHigh),
        "65536 exceeds the MQTT 16-bit ceiling");

    // 0 is legal MQTT: it disables keep-alive, so it must not be reported.
    PropertyState keepAliveDisabled = validState();
    withValue(keepAliveDisabled, MqttConnectedSystemTemplate.KEEP_ALIVE_SECONDS, 0);
    assertNull(
        ConnectionConfigValidator.firstError(keepAliveDisabled),
        "keep-alive 0 is a legal disable, not an error");
  }

  /** The happy path: host and port set, every optional field left blank, nothing reported. */
  @Test
  void aValidConfigurationWithBlankOptionalsReportsNothing() {
    assertNull(
        ConnectionConfigValidator.firstError(validState()),
        "a host and an in-range port are all this validator requires");

    PropertyState fullyConfigured = validState();
    withValue(fullyConfigured, MqttConnectedSystemTemplate.CLIENT_IDENTIFIER, "appian-client");
    withValue(fullyConfigured, MqttConnectedSystemTemplate.CONNECTION_TIMEOUT_SECONDS, 300);
    withValue(fullyConfigured, MqttConnectedSystemTemplate.KEEP_ALIVE_SECONDS, 65535);
    assertNull(
        ConnectionConfigValidator.firstError(fullyConfigured),
        "the inclusive upper bounds must be accepted");

    // Boundary: the lowest legal values on each rule.
    PropertyState lowBounds = validState();
    withValue(lowBounds, MqttConnectedSystemTemplate.BROKER_PORT, 1);
    withValue(lowBounds, MqttConnectedSystemTemplate.CONNECTION_TIMEOUT_SECONDS, 1);
    assertNull(ConnectionConfigValidator.firstError(lowBounds), "the lower bounds are inclusive");

    assertEquals(
        65535, ConnectionConfigValidator.PORT_MAX, "the documented port ceiling must not drift");
  }
}
