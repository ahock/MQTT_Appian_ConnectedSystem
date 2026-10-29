package de.hock.mqtt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.GeneralSecurityException;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.junit.jupiter.api.Test;

/**
 * Proves the four session settings on the connected system are not inert: each one reaches
 * {@link MqttConnectOptions}, and each documented default applies when the value is absent.
 *
 * <p>The 1.0.3 build shipped a configurable {@code maxReconnectAttempts} that nothing ever enforced,
 * which is a trap for designers. Every setting this build exposes is read back here through Paho's
 * own getters, so a field that stops being honoured fails the build. No broker and no network are
 * involved — {@code buildConnectOptions} only constructs the options object.
 */
class MqttPublisherConnectOptionsTest {

  /** The no-inert-field proof: all four configured values show up on the options object. */
  @Test
  void configuredSessionValuesReachMqttConnectOptions() throws GeneralSecurityException {
    MqttConnectOptions options =
        MqttPublisher.buildConnectOptions(
            Boolean.FALSE, null, null, null, 17, 45, Boolean.FALSE, Boolean.TRUE);

    assertEquals(17, options.getConnectionTimeout(), "connectionTimeoutSeconds must be applied");
    assertEquals(45, options.getKeepAliveInterval(), "keepAliveSeconds must be applied");
    assertFalse(options.isCleanSession(), "cleanSession must be applied");
    assertTrue(options.isAutomaticReconnect(), "autoReconnectEnabled must be applied");
  }

  /**
   * The documented defaults: absent values take them, and an out-of-range value falls back to the
   * same default rather than throwing — a mistyped keep-alive must not refuse a message.
   */
  @Test
  void absentOrOutOfRangeSessionValuesFallBackToTheDocumentedDefaults()
      throws GeneralSecurityException {
    MqttConnectOptions absent =
        MqttPublisher.buildConnectOptions(Boolean.FALSE, null, null, null, null, null, null, null);

    assertEquals(
        MqttPublisher.CONNECT_TIMEOUT_SECONDS,
        absent.getConnectionTimeout(),
        "an absent connect timeout falls back to the plug-in's own 5 seconds");
    assertEquals(
        MqttConnectOptions.KEEP_ALIVE_INTERVAL_DEFAULT,
        absent.getKeepAliveInterval(),
        "an absent keep-alive leaves Paho's own default of 60 seconds in place");
    assertTrue(absent.isCleanSession(), "an absent cleanSession means true");
    assertFalse(absent.isAutomaticReconnect(), "an absent autoReconnectEnabled means false");

    MqttConnectOptions outOfRange =
        MqttPublisher.buildConnectOptions(Boolean.FALSE, null, null, null, 0, -1, null, null);

    assertEquals(
        MqttPublisher.CONNECT_TIMEOUT_SECONDS,
        outOfRange.getConnectionTimeout(),
        "a connect timeout of 0 is out of range and falls back to 5 seconds");
    assertEquals(
        MqttConnectOptions.KEEP_ALIVE_INTERVAL_DEFAULT,
        outOfRange.getKeepAliveInterval(),
        "a negative keep-alive is out of range and leaves Paho's 60 seconds in place");

    // 0 is legal MQTT — it disables keep-alive — so it is in range and must pass through.
    MqttConnectOptions keepAliveDisabled =
        MqttPublisher.buildConnectOptions(Boolean.FALSE, null, null, null, null, 0, null, null);

    assertEquals(
        0,
        keepAliveDisabled.getKeepAliveInterval(),
        "0 is a legal keep-alive interval and disables keep-alive, so it is not a fallback case");
  }
}
