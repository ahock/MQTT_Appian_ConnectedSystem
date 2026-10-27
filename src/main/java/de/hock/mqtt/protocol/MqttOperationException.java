package de.hock.mqtt.protocol;

import de.hock.mqtt.MqttErrorCategory;

/**
 * Signals that an MQTT operation against the broker failed while a session was being acquired, used,
 * or released.
 *
 * <p>Unlike {@link ConnectOptionsException} — which is a pure build-time failure raised before any
 * broker interaction — this exception represents a failure that occurred while interacting with (or
 * attempting to interact with) the broker: a failed CONNECT exchange, a connection lost mid-operation,
 * or a configuration/transport problem surfaced during session acquisition. The carried
 * {@link #category()} lets callers surface the failure under the fixed error-category enum
 * (Requirement 9.2, 9.6) without parsing the message text.
 *
 * <p>A connection lost during an operation is mapped to {@link MqttErrorCategory#DISCONNECTION} with a
 * message naming the broker host and port (Requirement 4.4).
 */
public class MqttOperationException extends Exception {

  private static final long serialVersionUID = 1L;

  private final transient MqttErrorCategory category;

  /**
   * Creates an operation failure.
   *
   * @param category the fixed error category describing the failure
   * @param message a human-readable description of the failure
   */
  public MqttOperationException(MqttErrorCategory category, String message) {
    super(message);
    this.category = category;
  }

  /**
   * Creates an operation failure with an underlying cause.
   *
   * @param category the fixed error category describing the failure
   * @param message a human-readable description of the failure
   * @param cause the underlying cause
   */
  public MqttOperationException(MqttErrorCategory category, String message, Throwable cause) {
    super(message, cause);
    this.category = category;
  }

  /**
   * Returns the fixed error category classifying this failure.
   *
   * @return the error category
   */
  public MqttErrorCategory category() {
    return category;
  }
}
