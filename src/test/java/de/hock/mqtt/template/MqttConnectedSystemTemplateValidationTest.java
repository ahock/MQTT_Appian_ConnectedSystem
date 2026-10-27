package de.hock.mqtt.template;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.appian.connectedsystems.templateframework.sdk.configuration.ConfigurationDescriptor;
import com.appian.connectedsystems.templateframework.sdk.configuration.PropertyPath;
import com.appian.connectedsystems.templateframework.sdk.configuration.PropertyState;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Design-time validation tests for the connected system, driving the real framework entry point
 * {@code getConfigurationDescriptor} with the empty descriptor Appian supplies for a brand-new
 * object and reading messages back through {@link PropertyState#getErrorsAtPath}.
 *
 * <p>A re-render is simulated the way the platform does it: mutate the root state of the descriptor
 * the template just returned, then hand that same descriptor back in. That is what makes the
 * error-clearing test meaningful — the SDK carries existing messages forward into the regenerated
 * state, so a validator that only ever attaches leaves stale messages on corrected fields.
 *
 * <p>No Appian runtime and no broker are involved; the {@code ExecutionContext} is unused on this
 * path and is passed as {@code null}.
 */
class MqttConnectedSystemTemplateValidationTest {

  private static final String VALID_HOST = "broker.example.test";

  /** The descriptor Appian supplies for a brand-new object: its root state is {@code null}. */
  private static ConfigurationDescriptor emptyDescriptor() {
    return ConfigurationDescriptor.builder().build();
  }

  private static ConfigurationDescriptor render(ConfigurationDescriptor incoming) {
    return new MqttConnectedSystemTemplate()
        .getConfigurationDescriptor(incoming, new PropertyPath(), null);
  }

  private static List<String> errorsAt(ConfigurationDescriptor rendered, String key) {
    PropertyState rootState = rendered.getRootState();
    assertNotNull(rootState, "root state must be generated before errors can be read");
    List<String> errors = rootState.getErrorsAtPath(new PropertyPath(key));
    return errors == null ? Collections.emptyList() : errors;
  }

  private static List<String> rootErrorsOf(ConfigurationDescriptor rendered) {
    List<String> errors = rendered.getRootState().getErrors();
    return errors == null ? Collections.emptyList() : errors;
  }

  /** Sets a value on the rendered descriptor's root state, simulating designer input. */
  private static ConfigurationDescriptor withValue(
      ConfigurationDescriptor rendered, String key, Object value) {
    rendered.getRootState().setValueAtPath(new PropertyPath(key), value);
    return rendered;
  }

  /**
   * Pins the deliberate behaviour of release 1.0.5: validation is <strong>ungated</strong>, so a
   * brand-new connected system opens with errors already showing on the two required fields.
   *
   * <p>This test exists to stop anyone "fixing" that. The release is a probe for whether Appian
   * displays connected system field errors set through {@code setErrorAtPath} at all; a first-render
   * gate would make a clean page ambiguous between "the gate worked" and "Appian discards our
   * errors". If the probe's answer makes gating the right call, change the behaviour and this test
   * together, on purpose.
   *
   * <p>It also covers the companion rule: an optional field left blank must not report anything, and
   * no message may land on the root.
   */
  @Test
  void brandNewConnectedSystemReportsRequiredErrorsOnBrokerHostAndBrokerPortOnly() {
    ConfigurationDescriptor rendered = render(emptyDescriptor());

    assertFalse(
        errorsAt(rendered, MqttConnectedSystemTemplate.BROKER_HOST).isEmpty(),
        "a brand-new connected system must report that Broker Host is required — ungated by design");
    assertFalse(
        errorsAt(rendered, MqttConnectedSystemTemplate.BROKER_PORT).isEmpty(),
        "a brand-new connected system must report that Broker Port is required — ungated by design");

    for (String optionalKey :
        List.of(
            MqttConnectedSystemTemplate.CLIENT_IDENTIFIER,
            MqttConnectedSystemTemplate.USERNAME,
            MqttConnectedSystemTemplate.PASSWORD,
            MqttConnectedSystemTemplate.CONNECTION_TIMEOUT_SECONDS,
            MqttConnectedSystemTemplate.KEEP_ALIVE_SECONDS,
            MqttConnectedSystemTemplate.CLEAN_SESSION,
            MqttConnectedSystemTemplate.AUTO_RECONNECT_ENABLED)) {
      assertTrue(
          errorsAt(rendered, optionalKey).isEmpty(),
          "optional field " + optionalKey + " must report nothing when left blank");
    }

    assertTrue(
        rootErrorsOf(rendered).isEmpty(),
        "every message belongs on its own field path, never on the root");
  }

  /** A filled-in host and port leave both fields clean. */
  @Test
  void validBrokerHostAndPortLeaveThoseFieldsFreeOfErrors() {
    ConfigurationDescriptor first = render(emptyDescriptor());
    withValue(first, MqttConnectedSystemTemplate.BROKER_HOST, VALID_HOST);
    withValue(first, MqttConnectedSystemTemplate.BROKER_PORT, 1883);

    ConfigurationDescriptor rendered = render(first);

    assertTrue(
        errorsAt(rendered, MqttConnectedSystemTemplate.BROKER_HOST).isEmpty(),
        "a supplied broker host must report nothing");
    assertTrue(
        errorsAt(rendered, MqttConnectedSystemTemplate.BROKER_PORT).isEmpty(),
        "port 1883 is in range and must report nothing");
    assertTrue(rootErrorsOf(rendered).isEmpty(), "nothing may land on the root");
  }

  /** An out-of-range port is reported on the port field alone — not on the host, not on the root. */
  @Test
  void outOfRangeBrokerPortIsReportedOnTheBrokerPortPathNotTheRoot() {
    ConfigurationDescriptor first = render(emptyDescriptor());
    withValue(first, MqttConnectedSystemTemplate.BROKER_HOST, VALID_HOST);
    withValue(first, MqttConnectedSystemTemplate.BROKER_PORT, 99999);

    ConfigurationDescriptor rendered = render(first);

    assertFalse(
        errorsAt(rendered, MqttConnectedSystemTemplate.BROKER_PORT).isEmpty(),
        "port 99999 is out of range and must be reported");
    assertTrue(
        errorsAt(rendered, MqttConnectedSystemTemplate.BROKER_HOST).isEmpty(),
        "the host is valid, so it must stay clean");
    assertTrue(
        rootErrorsOf(rendered).isEmpty(),
        "the port message belongs on the port field, not at the top of the form");
  }

  /**
   * The 1.0.3 sticky-error regression: an error attached on one render must not survive a render in
   * which the value is correct.
   *
   * <p>1.0.3 only ever attached messages and never cleared them, so a corrected field kept showing
   * the old complaint. This fails if the {@code clearErrorsAtPath} pass in
   * {@code ConfigValidator.validate} is dropped.
   */
  @Test
  void aCorrectedValueClearsThePreviouslyAttachedError() {
    ConfigurationDescriptor first = render(emptyDescriptor());
    assertFalse(
        errorsAt(first, MqttConnectedSystemTemplate.BROKER_HOST).isEmpty(),
        "premise: the first render must have attached an error to clear");
    assertFalse(
        errorsAt(first, MqttConnectedSystemTemplate.BROKER_PORT).isEmpty(),
        "premise: the first render must have attached an error to clear");

    withValue(first, MqttConnectedSystemTemplate.BROKER_HOST, VALID_HOST);
    withValue(first, MqttConnectedSystemTemplate.BROKER_PORT, 1883);

    ConfigurationDescriptor second = render(first);

    assertTrue(
        errorsAt(second, MqttConnectedSystemTemplate.BROKER_HOST).isEmpty(),
        "the broker host error must be cleared once the value is corrected");
    assertTrue(
        errorsAt(second, MqttConnectedSystemTemplate.BROKER_PORT).isEmpty(),
        "the broker port error must be cleared once the value is corrected");
  }
}
