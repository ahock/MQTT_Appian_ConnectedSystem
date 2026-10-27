package de.hock.mqtt.protocol;

import de.hock.mqtt.MqttConnectionConfig;
import de.hock.mqtt.MqttErrorCategory;
import de.hock.mqtt.validation.ClientIdentifierGenerator;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

/**
 * Establishes, reuses, and releases {@link MqttSession}s against an MQTT broker on behalf of the
 * Publish and Subscribe integrations.
 *
 * <p><b>Reuse (Req 4.1).</b> The manager caches an active session keyed by a <em>connection
 * fingerprint</em> derived from the configuration (host, port, credentials presence, TLS material,
 * session flags, LWT). Concurrent operations that share a configuration therefore receive the
 * <em>same</em> live session rather than each opening its own connection. Each {@link #acquire} that
 * returns a cached session increments a reference count on the entry.
 *
 * <p><b>Release (Req 4.3).</b> {@link #release} decrements the reference count for the session's
 * entry; when the count reaches zero — i.e. no active subscription or operation still needs the
 * connection — the manager closes the session and releases its network resources. Callers release in
 * a {@code finally} block so a failed operation still returns or closes its session and cannot leak a
 * connection.
 *
 * <p><b>Lost connection (Req 4.4).</b> If the connection is lost while a session is being acquired or
 * used, the failure is mapped to a {@link MqttErrorCategory#DISCONNECTION} {@link
 * MqttOperationException} whose message names the broker host and port.
 *
 * <p><b>Testability.</b> The client used to build a session is produced by an injectable factory
 * ({@code Function<MqttConnectionConfig, MqttClientAdapter>}). The default production factory builds
 * a Paho {@link MqttAsyncClient} wrapped in a {@link PahoMqttClientAdapter}; tests inject a factory
 * that returns an in-memory fake adapter so the manager's reuse/release logic can be exercised
 * without a live broker.
 *
 * <p>This class is thread-safe: cache entries are held in a {@link ConcurrentHashMap} and each
 * entry's reference count is mutated under the entry's own monitor.
 *
 * <p>Requirements: 4.1, 4.3, 4.4.
 */
public final class MqttConnectionManager {

  private final Function<MqttConnectionConfig, MqttClientAdapter> clientFactory;

  private final ConcurrentHashMap<String, SessionEntry> sessions = new ConcurrentHashMap<>();

  /**
   * Creates a manager that opens real Paho-backed connections.
   */
  public MqttConnectionManager() {
    this(MqttConnectionManager::defaultClientFactory);
  }

  /**
   * Creates a manager that builds its client adapters through the supplied factory.
   *
   * <p>This constructor is the testability seam: a fake factory lets the manager's reuse and release
   * logic be exercised without a live broker.
   *
   * @param clientFactory produces a {@link MqttClientAdapter} for a given configuration (must not be
   *     {@code null})
   */
  public MqttConnectionManager(Function<MqttConnectionConfig, MqttClientAdapter> clientFactory) {
    this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
  }

  /**
   * Returns a live session for the given configuration, reusing an active cached session when one
   * exists for the same connection fingerprint (Req 4.1) and otherwise establishing a new one.
   *
   * <p>Each successful call increments the reference count on the backing cache entry; the caller
   * must balance it with exactly one {@link #release} of the returned session.
   *
   * @param config the connection configuration (must not be {@code null})
   * @return a connected {@link MqttSession}
   * @throws MqttOperationException if a session cannot be established, if options cannot be built, or
   *     if the connection is lost while acquiring the session
   */
  public MqttSession acquire(MqttConnectionConfig config) throws MqttOperationException {
    Objects.requireNonNull(config, "config");
    String fingerprint = fingerprint(config);

    while (true) {
      SessionEntry entry =
          sessions.computeIfAbsent(fingerprint, key -> new SessionEntry());

      synchronized (entry) {
        if (entry.evicted) {
          // Lost a race with a concurrent release that removed this entry; retry with a fresh one.
          continue;
        }
        if (entry.session != null) {
          if (!entry.session.isConnected()) {
            // The cached session dropped its connection; surface a disconnection and evict it so a
            // later acquire re-establishes rather than handing out a dead session (Req 4.4).
            evict(fingerprint, entry);
            throw disconnection(config);
          }
          entry.refCount++;
          return entry.session;
        }

        // First acquirer for this fingerprint builds and connects the session while holding the
        // entry monitor, so concurrent acquirers for the same config block and then reuse it.
        MqttClientAdapter client = buildClient(config);
        entry.client = client;
        entry.session = new MqttSession(client);
        entry.refCount = 1;
        return entry.session;
      }
    }
  }

  /**
   * Releases a previously {@link #acquire acquired} session, decrementing its reference count and
   * closing the connection once no active subscription or operation still needs it (Req 4.3).
   *
   * <p>A {@code null} session or a session that is not (or no longer) cached is ignored, so callers
   * can release unconditionally from a {@code finally} block.
   *
   * @param session the session to release (may be {@code null})
   */
  public void release(MqttSession session) {
    if (session == null) {
      return;
    }
    for (var mapEntry : sessions.entrySet()) {
      SessionEntry entry = mapEntry.getValue();
      synchronized (entry) {
        if (entry.session != session) {
          continue;
        }
        if (entry.refCount > 0) {
          entry.refCount--;
        }
        if (entry.refCount == 0) {
          evict(mapEntry.getKey(), entry);
        }
        return;
      }
    }
  }

  /**
   * Builds and connects a new client for the configuration, translating build and connect failures
   * into categorized {@link MqttOperationException}s.
   */
  private MqttClientAdapter buildClient(MqttConnectionConfig config) throws MqttOperationException {
    MqttConnectOptions options;
    try {
      options = ConnectOptionsFactory.build(config);
    } catch (ConnectOptionsException e) {
      throw new MqttOperationException(e.category(), e.getMessage(), e);
    }

    MqttClientAdapter client = clientFactory.apply(config);
    try {
      client.connect(options);
    } catch (MqttException e) {
      // A connect that fails because the connection could not be established or was lost is a
      // disconnection naming host and port (Req 4.4); otherwise classify generically.
      throw new MqttOperationException(classify(e), connectionMessage(config, e), e);
    }
    return client;
  }

  /**
   * Removes a cache entry and closes its client, swallowing disconnect failures since the session is
   * being discarded regardless.
   */
  private void evict(String fingerprint, SessionEntry entry) {
    entry.evicted = true;
    sessions.remove(fingerprint, entry);
    MqttClientAdapter client = entry.client;
    entry.session = null;
    entry.client = null;
    entry.refCount = 0;
    if (client != null) {
      try {
        client.disconnect();
      } catch (MqttException ignored) {
        // The session is being released; a failure to cleanly disconnect must not propagate.
      }
    }
  }

  /** Maps a Paho exception that occurred during connect to the closest fixed error category. */
  private static MqttErrorCategory classify(MqttException e) {
    switch (e.getReasonCode()) {
      case MqttException.REASON_CODE_FAILED_AUTHENTICATION:
      case MqttException.REASON_CODE_NOT_AUTHORIZED:
        return MqttErrorCategory.AUTHENTICATION;
      case MqttException.REASON_CODE_CLIENT_TIMEOUT:
      case MqttException.REASON_CODE_CONNECT_IN_PROGRESS:
        return MqttErrorCategory.CONNECTION_TIMEOUT;
      case MqttException.REASON_CODE_SSL_CONFIG_ERROR:
        return MqttErrorCategory.TLS_VALIDATION;
      default:
        return MqttErrorCategory.DISCONNECTION;
    }
  }

  /**
   * Builds a {@link MqttErrorCategory#DISCONNECTION} failure naming the broker host and port
   * (Req 4.4).
   */
  private static MqttOperationException disconnection(MqttConnectionConfig config) {
    return new MqttOperationException(
        MqttErrorCategory.DISCONNECTION,
        "Connection to MQTT broker "
            + config.brokerHost()
            + ":"
            + config.brokerPort()
            + " was lost.");
  }

  /** Builds a human-readable connect-failure message naming the broker host and port. */
  private static String connectionMessage(MqttConnectionConfig config, MqttException e) {
    return "Failed to establish a connection to MQTT broker "
        + config.brokerHost()
        + ":"
        + config.brokerPort()
        + ": "
        + e.getMessage();
  }

  /**
   * Derives a stable connection fingerprint from the configuration so that two configurations that
   * would open the same logical connection share one cached session (Req 4.1), while any difference
   * that affects the connection (host, port, credentials presence, TLS material, session flags, LWT)
   * produces a distinct key.
   *
   * <p>Secret values are reduced to presence/length and content hashes rather than embedded verbatim
   * so the fingerprint never carries plaintext credentials or key material.
   */
  static String fingerprint(MqttConnectionConfig config) {
    StringBuilder sb = new StringBuilder(128);
    sb.append(effectiveClientId(config)).append('|');
    sb.append(config.brokerHost()).append('|');
    sb.append(config.brokerPort()).append('|');
    sb.append(config.connectionTimeoutSeconds()).append('|');

    // Credentials: identity by username and password presence/content, never plaintext password.
    sb.append("u:").append(config.username() == null ? "" : config.username()).append('|');
    sb.append("p:").append(hashChars(config.password())).append('|');

    // TLS material.
    sb.append("tls:").append(config.tlsEnabled()).append('|');
    sb.append("ca:").append(hashBytes(config.caCertificate())).append('|');
    sb.append("cc:").append(hashBytes(config.clientCertificate())).append('|');
    sb.append("ck:").append(hashChars(config.clientPrivateKey())).append('|');
    sb.append("kp:").append(hashChars(config.keyPassphrase())).append('|');

    // Reconnection.
    sb.append("ar:").append(config.autoReconnectEnabled()).append('|');
    sb.append("ma:").append(config.maxReconnectAttempts()).append('|');
    sb.append("ri:").append(config.reconnectIntervalSeconds()).append('|');

    // Last Will and Testament.
    sb.append("lt:").append(config.lwtTopic() == null ? "" : config.lwtTopic()).append('|');
    sb.append("lp:").append(hashBytes(config.lwtPayload())).append('|');
    sb.append("lq:").append(config.lwtQos()).append('|');
    sb.append("lr:").append(config.lwtRetained()).append('|');

    // Session flags.
    sb.append("cs:").append(config.cleanSession()).append('|');
    sb.append("ka:").append(config.keepAliveSeconds());

    return sb.toString();
  }

  /**
   * Resolves the client identifier used to connect: the configured identifier, or an auto-generated
   * one derived from host and port when blank (so the fingerprint and the connection agree).
   */
  private static String effectiveClientId(MqttConnectionConfig config) {
    String clientId = config.clientIdentifier();
    if (clientId == null || clientId.isBlank()) {
      return ClientIdentifierGenerator.generate(config.brokerHost(), config.brokerPort());
    }
    return clientId;
  }

  /**
   * Reduces secret characters to a presence+hash token so the fingerprint distinguishes different
   * secrets without carrying the plaintext.
   */
  private static String hashChars(char[] value) {
    if (value == null) {
      return "none";
    }
    if (value.length == 0) {
      return "empty";
    }
    return value.length + ":" + Arrays.hashCode(value);
  }

  /** Reduces secret/material bytes to a presence+hash token (see {@link #hashChars}). */
  private static String hashBytes(byte[] value) {
    if (value == null) {
      return "none";
    }
    if (value.length == 0) {
      return "empty";
    }
    return value.length + ":" + Arrays.hashCode(value);
  }

  /**
   * Default production client factory: builds a Paho {@link MqttAsyncClient} (client id from the
   * config or auto-generated when blank; {@code tcp://} or {@code ssl://} server URI based on
   * {@code tlsEnabled}) wrapped in a {@link PahoMqttClientAdapter}.
   */
  private static MqttClientAdapter defaultClientFactory(MqttConnectionConfig config) {
    String clientId = effectiveClientId(config);
    String scheme = config.tlsEnabled() ? "ssl://" : "tcp://";
    String serverUri = scheme + config.brokerHost() + ":" + config.brokerPort();
    try {
      MqttAsyncClient client =
          new MqttAsyncClient(serverUri, clientId, new MemoryPersistence());
      return new PahoMqttClientAdapter(client);
    } catch (MqttException e) {
      // A malformed server URI or persistence failure is a configuration problem; wrap it in an
      // unchecked exception so the factory's functional signature stays clean. acquire() surfaces
      // any failure through MqttOperationException at connect time; this guards construction.
      throw new IllegalStateException(
          "Unable to construct an MQTT client for "
              + config.brokerHost()
              + ":"
              + config.brokerPort(),
          e);
    }
  }

  /** Reference-counted cache entry for a single live session keyed by connection fingerprint. */
  private static final class SessionEntry {
    private MqttSession session;
    private MqttClientAdapter client;
    private int refCount;
    private boolean evicted;
  }
}
