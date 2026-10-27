package de.hock.mqtt.protocol;

import de.hock.mqtt.MqttConnectionConfig;
import de.hock.mqtt.MqttErrorCategory;
import de.hock.mqtt.validation.CertificateLoader;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;

/**
 * Pure transform from an {@link MqttConnectionConfig} to a configured Eclipse Paho
 * {@link MqttConnectOptions}.
 *
 * <p>This factory never contacts a broker. It only maps configuration onto the Paho options object
 * that the connection manager later hands to the client. The mapping covers:
 *
 * <ul>
 *   <li><b>Credentials</b> — {@code setUserName}/{@code setPassword} only when <em>both</em> a
 *       username and a password are present; otherwise the options stay anonymous (Req 2.2,
 *       2.3).</li>
 *   <li><b>Transport</b> — when TLS is enabled, a {@link SSLSocketFactory} is installed that
 *       negotiates TLS 1.2 or higher, validates the broker against the configured CA certificate,
 *       and presents the client certificate/key for mutual TLS (Req 3.2, 3.3, 3.5); when TLS is
 *       disabled no socket factory is set, so the client connects over plain TCP (Req 3.7).</li>
 *   <li><b>Timeout</b> — {@code setConnectionTimeout} from the configured connect timeout (Req 1.3,
 *       4.2).</li>
 *   <li><b>Last Will and Testament</b> — {@code setWill} only when both an LWT topic and payload are
 *       configured (Req 7.2).</li>
 *   <li><b>Session</b> — {@code setCleanSession} (Req 8.1–8.3) and {@code setKeepAliveInterval}
 *       (Req 8.4).</li>
 *   <li><b>Reconnection</b> — {@code setAutomaticReconnect} with the reconnection interval bounding
 *       Paho's backoff via {@code setMaxReconnectDelay}; the attempt count is enforced by the
 *       connection manager (Req 4.5).</li>
 * </ul>
 *
 * <p>The class is stateless; the single entry point is {@link #build(MqttConnectionConfig)}.
 */
public final class ConnectOptionsFactory {

  /**
   * Minimum TLS protocol version required for an encrypted connection (Req 3.2). The SSL context is
   * created for TLS 1.2, and the socket factory is further restricted so that only TLS 1.2+
   * protocols are offered.
   */
  private static final String TLS_PROTOCOL = "TLSv1.2";

  /** TLS protocols permitted on sockets produced by the TLS socket factory (TLS 1.2 and higher). */
  private static final String[] ENABLED_TLS_PROTOCOLS = {"TLSv1.2", "TLSv1.3"};

  /** Alias used for the client key entry in the in-memory key store. */
  private static final String CLIENT_KEY_ALIAS = "client";

  private ConnectOptionsFactory() {
    // Utility class; not instantiable.
  }

  /**
   * Builds Paho {@link MqttConnectOptions} from the supplied connection configuration.
   *
   * <p>This is a pure transform: it reads configuration and produces options, performing no network
   * I/O. When TLS is enabled, certificate/key material is parsed and an {@link SSLSocketFactory} is
   * constructed; malformed material or an unavailable TLS 1.2 provider is reported as a
   * {@link ConnectOptionsException} rather than deferred to connect time.
   *
   * @param config the immutable connection configuration (must not be {@code null})
   * @return a fully populated {@link MqttConnectOptions}
   * @throws ConnectOptionsException if TLS material cannot be loaded or the TLS context cannot be
   *     built
   */
  public static MqttConnectOptions build(MqttConnectionConfig config)
      throws ConnectOptionsException {
    if (config == null) {
      throw new ConnectOptionsException(
          MqttErrorCategory.CONFIGURATION, "Connection configuration is required.");
    }

    MqttConnectOptions options = new MqttConnectOptions();

    applyCredentials(config, options);

    if (config.tlsEnabled()) {
      options.setSocketFactory(buildSslSocketFactory(config));
    }

    options.setConnectionTimeout(config.connectionTimeoutSeconds());
    options.setCleanSession(config.cleanSession());
    options.setKeepAliveInterval(config.keepAliveSeconds());

    applyWill(config, options);
    applyReconnect(config, options);

    return options;
  }

  /**
   * Applies username/password only when both are present; otherwise leaves the options anonymous
   * (Req 2.2, 2.3). A blank username or an empty password is treated as "not present".
   */
  private static void applyCredentials(MqttConnectionConfig config, MqttConnectOptions options) {
    String username = config.username();
    char[] password = config.password();
    boolean hasUsername = username != null && !username.isBlank();
    boolean hasPassword = password != null && password.length > 0;
    if (hasUsername && hasPassword) {
      options.setUserName(username);
      options.setPassword(password);
    }
  }

  /**
   * Registers the Last Will and Testament only when both an LWT topic and payload are configured
   * (Req 7.2). A payload-without-topic configuration is rejected earlier by design-time validation;
   * here it simply means no will is set.
   */
  private static void applyWill(MqttConnectionConfig config, MqttConnectOptions options) {
    String topic = config.lwtTopic();
    byte[] payload = config.lwtPayload();
    boolean hasTopic = topic != null && !topic.isBlank();
    boolean hasPayload = payload != null;
    if (hasTopic && hasPayload) {
      options.setWill(topic, payload.clone(), config.lwtQos(), config.lwtRetained());
    }
  }

  /**
   * Enables automatic reconnection and bounds Paho's reconnect backoff by the configured interval
   * (Req 4.5). Paho caps its exponential backoff at {@code maxReconnectDelay} (milliseconds); the
   * maximum attempt count is enforced by the connection manager, not the options object.
   */
  private static void applyReconnect(MqttConnectionConfig config, MqttConnectOptions options) {
    options.setAutomaticReconnect(config.autoReconnectEnabled());
    if (config.autoReconnectEnabled()) {
      int intervalSeconds = Math.max(1, config.reconnectIntervalSeconds());
      options.setMaxReconnectDelay(intervalSeconds * 1000);
    }
  }

  /**
   * Builds a TLS 1.2+ {@link SSLSocketFactory} from the configured CA certificate (server trust)
   * and client certificate/key (mutual TLS). When no CA certificate is configured the platform
   * default trust store is used; when no client certificate/key is configured no key material is
   * presented.
   *
   * @throws ConnectOptionsException if certificate/key material is malformed, or the TLS context
   *     cannot be constructed
   */
  private static SSLSocketFactory buildSslSocketFactory(MqttConnectionConfig config)
      throws ConnectOptionsException {
    TrustManager[] trustManagers = buildTrustManagers(config.caCertificate());
    KeyManager[] keyManagers =
        buildKeyManagers(config.clientCertificate(), config.clientPrivateKey());

    try {
      SSLContext sslContext = SSLContext.getInstance(TLS_PROTOCOL);
      sslContext.init(keyManagers, trustManagers, null);
      return new TlsVersionConstrainingSocketFactory(
          sslContext.getSocketFactory(), ENABLED_TLS_PROTOCOLS);
    } catch (GeneralSecurityException e) {
      throw new ConnectOptionsException(
          MqttErrorCategory.TLS_VALIDATION,
          "Unable to initialize a TLS 1.2 context for the connection.",
          e);
    }
  }

  /**
   * Builds trust managers that validate the broker certificate against the configured CA
   * certificate. When no CA certificate is configured, {@code null} is returned so the platform
   * default trust store is used.
   *
   * @throws ConnectOptionsException if the CA certificate bytes cannot be parsed
   */
  private static TrustManager[] buildTrustManagers(byte[] caCertificate)
      throws ConnectOptionsException {
    if (caCertificate == null || caCertificate.length == 0) {
      return null;
    }
    try {
      CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
      Collection<? extends Certificate> caCertificates =
          certificateFactory.generateCertificates(new ByteArrayInputStream(caCertificate));
      if (caCertificates.isEmpty()) {
        throw new ConnectOptionsException(
            MqttErrorCategory.CONFIGURATION,
            "The configured certificate authority certificate contains no certificates.");
      }

      KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
      trustStore.load(null, null);
      int index = 0;
      for (Certificate certificate : caCertificates) {
        trustStore.setCertificateEntry("ca-" + index++, certificate);
      }

      TrustManagerFactory trustManagerFactory =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trustManagerFactory.init(trustStore);
      return trustManagerFactory.getTrustManagers();
    } catch (ConnectOptionsException e) {
      throw e;
    } catch (GeneralSecurityException | java.io.IOException e) {
      throw new ConnectOptionsException(
          MqttErrorCategory.CONFIGURATION,
          "The configured certificate authority certificate could not be loaded.",
          e);
    }
  }

  /**
   * Builds key managers that present the client certificate/key for mutual TLS. When no client
   * certificate or private key is configured, {@code null} is returned so no client material is
   * presented. Certificate/key parsing is delegated to the shared {@link CertificateLoader}; a load
   * failure is reported as a configuration error (Req 3.6).
   *
   * @throws ConnectOptionsException if the client certificate/key cannot be loaded or the key store
   *     cannot be assembled
   */
  private static KeyManager[] buildKeyManagers(byte[] clientCertificate, char[] clientPrivateKey)
      throws ConnectOptionsException {
    boolean hasCertificate = clientCertificate != null && clientCertificate.length > 0;
    boolean hasKey = clientPrivateKey != null && clientPrivateKey.length > 0;
    if (!hasCertificate && !hasKey) {
      return null;
    }
    if (hasCertificate != hasKey) {
      throw new ConnectOptionsException(
          MqttErrorCategory.CONFIGURATION,
          "Mutual TLS requires both a client certificate and its private key.");
    }

    byte[] privateKeyBytes = toBytes(clientPrivateKey);
    CertificateLoader.LoadOutcome outcome =
        CertificateLoader.load(clientCertificate, privateKeyBytes);
    java.util.Arrays.fill(privateKeyBytes, (byte) 0);
    if (!outcome.isSuccess()) {
      String message =
          outcome.error().map(e -> e.message()).orElse("Client certificate could not be loaded.");
      throw new ConnectOptionsException(MqttErrorCategory.CONFIGURATION, message);
    }

    CertificateLoader.LoadedCertificate loaded = outcome.loaded().orElseThrow();
    try {
      KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
      keyStore.load(null, null);
      char[] keyStorePassword = new char[0];
      keyStore.setKeyEntry(
          CLIENT_KEY_ALIAS,
          loaded.privateKey(),
          keyStorePassword,
          new X509Certificate[] {loaded.certificate()});

      KeyManagerFactory keyManagerFactory =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      keyManagerFactory.init(keyStore, keyStorePassword);
      return keyManagerFactory.getKeyManagers();
    } catch (GeneralSecurityException | java.io.IOException e) {
      throw new ConnectOptionsException(
          MqttErrorCategory.CONFIGURATION,
          "The client certificate and key could not be assembled for mutual TLS.",
          e);
    }
  }

  /** Converts key characters to UTF-8 bytes for the certificate loader without retaining copies. */
  private static byte[] toBytes(char[] chars) {
    java.nio.CharBuffer charBuffer = java.nio.CharBuffer.wrap(chars);
    java.nio.ByteBuffer byteBuffer = StandardCharsets.UTF_8.encode(charBuffer);
    byte[] bytes = new byte[byteBuffer.remaining()];
    byteBuffer.get(bytes);
    return bytes;
  }

  /**
   * Decorates an {@link SSLSocketFactory} so that every socket it produces offers only the supplied
   * TLS protocols (TLS 1.2 and higher), enforcing the minimum-version requirement at the socket
   * level in addition to the SSL context (Req 3.2).
   */
  private static final class TlsVersionConstrainingSocketFactory extends SSLSocketFactory {

    private final SSLSocketFactory delegate;
    private final String[] enabledProtocols;

    private TlsVersionConstrainingSocketFactory(
        SSLSocketFactory delegate, String[] enabledProtocols) {
      this.delegate = delegate;
      this.enabledProtocols = enabledProtocols.clone();
    }

    private java.net.Socket constrain(java.net.Socket socket) {
      if (socket instanceof javax.net.ssl.SSLSocket sslSocket) {
        sslSocket.setEnabledProtocols(enabledProtocols.clone());
      }
      return socket;
    }

    @Override
    public String[] getDefaultCipherSuites() {
      return delegate.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
      return delegate.getSupportedCipherSuites();
    }

    @Override
    public java.net.Socket createSocket(java.net.Socket s, String host, int port, boolean autoClose)
        throws java.io.IOException {
      return constrain(delegate.createSocket(s, host, port, autoClose));
    }

    @Override
    public java.net.Socket createSocket(String host, int port) throws java.io.IOException {
      return constrain(delegate.createSocket(host, port));
    }

    @Override
    public java.net.Socket createSocket(
        String host, int port, java.net.InetAddress localHost, int localPort)
        throws java.io.IOException {
      return constrain(delegate.createSocket(host, port, localHost, localPort));
    }

    @Override
    public java.net.Socket createSocket(java.net.InetAddress host, int port)
        throws java.io.IOException {
      return constrain(delegate.createSocket(host, port));
    }

    @Override
    public java.net.Socket createSocket(
        java.net.InetAddress address, int port, java.net.InetAddress localAddress, int localPort)
        throws java.io.IOException {
      return constrain(delegate.createSocket(address, port, localAddress, localPort));
    }
  }
}
