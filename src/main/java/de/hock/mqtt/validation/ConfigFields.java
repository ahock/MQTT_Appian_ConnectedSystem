package de.hock.mqtt.validation;

/**
 * Shared field identifiers used to scope configuration validation errors.
 *
 * <p>These constants give every validator (and the Connected System descriptor that renders the
 * errors) a single source of truth for field ids, so an error produced by {@link ConfigValidator}
 * and a field declared in the descriptor agree on the same key. Sibling validators (LWT,
 * reconnection, certificate load) reference the same constants rather than hard-coding literals.
 */
public final class ConfigFields {

  /** Broker host field. */
  public static final String BROKER_HOST = "brokerHost";

  /** Broker port field. */
  public static final String BROKER_PORT = "brokerPort";

  /** Client identifier field. */
  public static final String CLIENT_IDENTIFIER = "clientIdentifier";

  /** Connection timeout (seconds) field. */
  public static final String CONNECTION_TIMEOUT_SECONDS = "connectionTimeoutSeconds";

  /** Authentication user name field. */
  public static final String USERNAME = "username";

  /** Authentication password field. */
  public static final String PASSWORD = "password";

  /** Clean-session flag field. */
  public static final String CLEAN_SESSION = "cleanSession";

  /** Keep-alive interval (seconds) field. */
  public static final String KEEP_ALIVE_SECONDS = "keepAliveSeconds";

  // --- TLS group (Req 3) ---

  /** Whether TLS is enabled for the connection. */
  public static final String TLS_ENABLED = "tlsEnabled";

  /** CA certificate used to validate the broker (optional). */
  public static final String CA_CERTIFICATE = "caCertificate";

  /** Client certificate for mutual TLS (document upload, optional). */
  public static final String CLIENT_CERTIFICATE = "clientCertificate";

  /** Client private key for mutual TLS (sensitive, optional). */
  public static final String CLIENT_PRIVATE_KEY = "clientPrivateKey";

  /** Passphrase protecting the client private key (sensitive, optional). */
  public static final String KEY_PASSPHRASE = "keyPassphrase";

  // --- Reconnection group (Req 4) ---

  /** Whether automatic reconnection is enabled. */
  public static final String AUTO_RECONNECT_ENABLED = "autoReconnectEnabled";

  /** Maximum number of reconnection attempts (0–10, default 3). */
  public static final String MAX_RECONNECT_ATTEMPTS = "maxReconnectAttempts";

  /** Interval between reconnection attempts in seconds (1–120, default 5). */
  public static final String RECONNECT_INTERVAL_SECONDS = "reconnectIntervalSeconds";

  // --- Last Will and Testament group (Req 7) ---

  /** Last Will and Testament topic (optional). */
  public static final String LWT_TOPIC = "lwtTopic";

  /** Last Will and Testament payload (optional). */
  public static final String LWT_PAYLOAD = "lwtPayload";

  /** Last Will and Testament QoS level (0, 1, or 2). */
  public static final String LWT_QOS = "lwtQos";

  /** Whether the Last Will message is retained. */
  public static final String LWT_RETAINED = "lwtRetained";

  private ConfigFields() {
    // no instances
  }
}
