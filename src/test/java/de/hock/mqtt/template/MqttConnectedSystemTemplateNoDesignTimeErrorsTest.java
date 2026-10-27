package de.hock.mqtt.template;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.appian.connectedsystems.templateframework.sdk.configuration.ConfigurationDescriptor;
import com.appian.connectedsystems.templateframework.sdk.configuration.PropertyPath;
import com.appian.connectedsystems.templateframework.sdk.configuration.PropertyState;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the intended 1.0.6 behaviour: the connected system attaches <strong>no</strong> design-time
 * errors, to any field or to the root.
 *
 * <p>This replaces 1.0.5's {@code MqttConnectedSystemTemplateValidationTest}, which asserted the
 * opposite on purpose. That release attached errors ungated through
 * {@code PropertyState.setErrorAtPath} as a probe, because the 26.9 restrictions page only documents
 * {@code setErrors} as ignored. The probe was deployed to a live Appian 26.9 instance and no message
 * appeared, so the restriction covers {@code setErrorAtPath} too and the whole clear-and-attach
 * machinery was dead code. 1.0.6 deleted it; the same rules now run at invocation time in
 * {@code de.hock.mqtt.ConnectionConfigValidator}, covered by {@code ConnectionConfigValidatorTest}.
 *
 * <p>Do not "restore" design-time validation here. {@code isRequired} on the descriptors is the only
 * design-time guard Appian honours.
 *
 * <p>No Appian runtime and no broker are involved; the {@code ExecutionContext} is unused on this
 * path and is passed as {@code null}.
 */
class MqttConnectedSystemTemplateNoDesignTimeErrorsTest {

  /** All nine field keys, so the assertion covers the required ones as well as the optional ones. */
  private static final List<String> ALL_FIELD_KEYS =
      List.of(
          MqttConnectedSystemTemplate.BROKER_HOST,
          MqttConnectedSystemTemplate.BROKER_PORT,
          MqttConnectedSystemTemplate.CLIENT_IDENTIFIER,
          MqttConnectedSystemTemplate.USERNAME,
          MqttConnectedSystemTemplate.PASSWORD,
          MqttConnectedSystemTemplate.CONNECTION_TIMEOUT_SECONDS,
          MqttConnectedSystemTemplate.KEEP_ALIVE_SECONDS,
          MqttConnectedSystemTemplate.CLEAN_SESSION,
          MqttConnectedSystemTemplate.AUTO_RECONNECT_ENABLED);

  private static List<String> errorsAt(PropertyState rootState, String key) {
    List<String> errors = rootState.getErrorsAtPath(new PropertyPath(key));
    return errors == null ? Collections.emptyList() : errors;
  }

  /** A brand-new connected system opens clean — no field error, no root error. */
  @Test
  void brandNewConnectedSystemRendersWithNoAttachedErrors() {
    ConfigurationDescriptor rendered =
        new MqttConnectedSystemTemplate()
            .getConfigurationDescriptor(
                ConfigurationDescriptor.builder().build(), new PropertyPath(), null);

    PropertyState rootState = rendered.getRootState();
    assertNotNull(rootState, "root state must be generated");

    for (String key : ALL_FIELD_KEYS) {
      assertTrue(
          errorsAt(rootState, key).isEmpty(),
          "1.0.6 attaches no design-time error, but " + key + " carries one");
    }

    List<String> rootErrors = rootState.getErrors();
    assertTrue(
        rootErrors == null || rootErrors.isEmpty(),
        "nothing may land on the root either");
  }
}
