package de.hock.mqtt.protocol;

import de.hock.mqtt.MqttErrorCategory;

/**
 * Signals that an {@link org.eclipse.paho.client.mqttv3.MqttConnectOptions} could not be built from
 * an {@link de.hock.mqtt.MqttConnectionConfig}.
 *
 * <p>This is a pure build-time failure: it is raised while transforming configuration into connect
 * options (for example when TLS is enabled but the client certificate/key cannot be loaded, or the
 * TLS 1.2 context cannot be constructed). It never represents a broker interaction. The carried
 * {@link #category()} lets callers surface the failure under the fixed error-category enum
 * (Requirement 9.2, 9.6): certificate/key load problems map to {@link
 * MqttErrorCategory#CONFIGURATION}, and TLS material/handshake-setup problems map to {@link
 * MqttErrorCategory#TLS_VALIDATION}.
 */
public class ConnectOptionsException extends Exception {

  private static final long serialVersionUID = 1L;

  private final transient MqttErrorCategory category;

  /**
   * Creates a connect-options build failure.
   *
   * @param category the fixed error category describing the failure
   * @param message a human-readable description of the failure
   */
  public ConnectOptionsException(MqttErrorCategory category, String message) {
    super(message);
    this.category = category;
  }

  /**
   * Creates a connect-options build failure with an underlying cause.
   *
   * @param category the fixed error category describing the failure
   * @param message a human-readable description of the failure
   * @param cause the underlying cause
   */
  public ConnectOptionsException(MqttErrorCategory category, String message, Throwable cause) {
    super(message, cause);
    this.category = category;
  }

  /** @return the fixed error category classifying this failure */
  public MqttErrorCategory category() {
    return category;
  }
}
