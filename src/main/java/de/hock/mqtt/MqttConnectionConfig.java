package de.hock.mqtt;

/**
 * Immutable connection configuration derived from the Connected System template.
 *
 * <p>Holds the reusable connection, authentication, TLS, reconnection, Last Will and Testament
 * (LWT), and session settings used to establish an MQTT session. Sensitive values (password,
 * client private key, key passphrase) are held as {@code char[]} rather than {@link String} so
 * they can be cleared from memory after use and are less likely to be captured by diagnostics or
 * heap inspection.
 *
 * <p>Requirements: 1.1, 2.1, 3.1, 7.1, 8.1 (and reconnection group, Req 4).
 *
 * @param brokerHost broker host name or address (non-blank, 1–253 chars)
 * @param brokerPort broker TCP port (1–65535)
 * @param clientIdentifier MQTT client identifier (1–65535; auto-generated 1–23 chars when blank)
 * @param connectionTimeoutSeconds connect timeout in seconds (1–300, default 30)
 * @param username authentication user name (optional, 1–256 chars)
 * @param password authentication password (sensitive; optional, 1–256 chars)
 * @param tlsEnabled whether the connection uses TLS
 * @param caCertificate CA certificate bytes used to validate the broker (optional)
 * @param clientCertificate client certificate bytes for mutual TLS (optional)
 * @param clientPrivateKey client private key bytes/characters for mutual TLS (sensitive, optional)
 * @param keyPassphrase passphrase protecting the client private key (sensitive, optional)
 * @param autoReconnectEnabled whether automatic reconnection is enabled
 * @param maxReconnectAttempts maximum reconnection attempts (0–10, default 3)
 * @param reconnectIntervalSeconds interval between reconnection attempts (1–120, default 5)
 * @param lwtTopic Last Will and Testament topic (optional, 1–65535 chars)
 * @param lwtPayload Last Will and Testament payload (optional, 0–268435455 bytes)
 * @param lwtQos Last Will and Testament QoS level (0, 1, or 2)
 * @param lwtRetained whether the Last Will message is retained
 * @param cleanSession clean-session flag (default true)
 * @param keepAliveSeconds keep-alive interval in seconds (0–65535, default 60)
 */
public record MqttConnectionConfig(
    String brokerHost,
    int brokerPort,
    String clientIdentifier,
    int connectionTimeoutSeconds,
    String username,
    char[] password,
    boolean tlsEnabled,
    byte[] caCertificate,
    byte[] clientCertificate,
    char[] clientPrivateKey,
    char[] keyPassphrase,
    boolean autoReconnectEnabled,
    int maxReconnectAttempts,
    int reconnectIntervalSeconds,
    String lwtTopic,
    byte[] lwtPayload,
    int lwtQos,
    boolean lwtRetained,
    boolean cleanSession,
    int keepAliveSeconds
) {
}
