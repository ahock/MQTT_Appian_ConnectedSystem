package de.hock.mqtt;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

/**
 * Server-side TLS for the two Paho call sites: the broker URI scheme, and an in-memory
 * {@link SSLContext} built from an optional pasted CA certificate.
 *
 * <p>Added at 1.0.7. Server-side only — the plug-in verifies the broker, and presents no client
 * certificate of its own. Mutual TLS is deliberately out of scope; a broker that demands a client
 * certificate will fail the handshake.
 *
 * <p><strong>Three connection modes</strong>, and the whole feature is these three rows:
 *
 * <table border="1">
 *   <caption>Connection modes</caption>
 *   <tr><th>{@code tlsEnabled}</th><th>{@code caCertificatePem}</th><th>URI</th>
 *       <th>Trust anchors</th></tr>
 *   <tr><td>{@code false} or absent</td><td>ignored</td><td>{@code tcp://host:port}</td>
 *       <td>none — no TLS at all, and no socket factory is installed</td></tr>
 *   <tr><td>{@code true}</td><td>blank</td><td>{@code ssl://host:port}</td>
 *       <td>the JVM default trust store, which is right for a publicly trusted broker
 *           certificate</td></tr>
 *   <tr><td>{@code true}</td><td>PEM</td><td>{@code ssl://host:port}</td>
 *       <td><strong>only</strong> the pasted certificate(s) — the self-signed / private-CA
 *           mode</td></tr>
 * </table>
 *
 * <p><strong>The port is the designer's responsibility.</strong> 1883 is the convention for plain
 * TCP and 8883 for TLS, but enabling TLS never silently rewrites the configured port.
 *
 * <p><strong>Everything happens in memory.</strong> The PEM is parsed with
 * {@link CertificateFactory} out of a {@link ByteArrayInputStream}, loaded into a
 * {@link KeyStore#getInstance(String)} of the platform default type opened empty with
 * {@code load(null, null)}, fed to a {@link TrustManagerFactory}, and the resulting
 * {@link SSLContext}'s socket factory is handed to Paho. Two Appian plug-in policies each rule out
 * one of the usual shortcuts, which is why this class exists in the shape it does: the ban on
 * JVM-global mutation rules out configuring trust through global JSSE settings (which would also
 * change TLS behaviour for every other plug-in on the server), and the ban on direct filesystem
 * manipulation rules out writing a key store to disk. The 1.0.3 code did the latter. Nothing here
 * opens an operating-system resource — the only stream is over a byte array already in memory — so
 * there is nothing to close.
 *
 * <p><strong>Protocol choice: {@code "TLS"}, not a pinned version.</strong>
 * {@code SSLContext.getInstance("TLS")} asks the JDK for its best available protocol set and
 * inherits the platform's own deprecation schedule; on Corretto 17 a socket from such a context
 * reports {@code [TLSv1.3, TLSv1.2]}, with TLS 1.0 and 1.1 already disabled by the JDK's
 * {@code jdk.tls.disabledAlgorithms} default. Pinning {@code "TLSv1.2"}, as 1.0.3 did, would
 * <em>exclude</em> TLS 1.3.
 *
 * <p><strong>There is no way to disable verification, and none will be added.</strong> No
 * trust-everything manager exists in this plug-in, not even behind a flag. Mode 3 already covers a
 * self-signed broker properly by trusting that specific certificate.
 *
 * <p><strong>Hostname verification is Paho's, and it works.</strong> This class does not wrap the
 * socket factory, because measuring Paho 1.2.5's bytecode showed a wrapper would be redundant:
 * {@code SSLNetworkModuleFactory} (whose supported scheme set is exactly {@code {"ssl"}}) copies
 * {@code MqttConnectOptions.isHttpsHostnameVerificationEnabled()} — seeded {@code true} in that
 * class's constructor — onto the network module, and {@code SSLNetworkModule.start()} then applies
 * {@code SSLParameters.setEndpointIdentificationAlgorithm("HTTPS")} to the socket after connect and
 * before {@code startHandshake()}. A certificate whose subject alternative name does not match the
 * configured host is therefore rejected. {@link MqttPublisher} sets that flag explicitly rather than
 * inheriting it. A wrapper would additionally have been a hazard: Paho creates its socket through
 * the <em>no-argument</em> {@code SocketFactory.createSocket()}, the one overload 1.0.3's wrapper
 * did not override, whose inherited implementation throws
 * {@code SocketException("Unconnected sockets not implemented")}.
 *
 * <p><strong>No message ever carries certificate material.</strong> Every failure here is a
 * {@link GeneralSecurityException} with a fixed sentence and the original exception as its cause, so
 * a parse failure cannot echo back the pasted field contents.
 */
public final class TlsSupport {

  /** The PEM header every X.509 certificate block starts with. */
  static final String PEM_CERTIFICATE_HEADER = "-----BEGIN CERTIFICATE-----";

  /** Fixed message for a value that {@link CertificateFactory} could not read at all. */
  private static final String PARSE_FAILED =
      "The configured CA certificate could not be parsed as an X.509 certificate.";

  /** Fixed message for a value that parsed without yielding a single certificate. */
  private static final String NO_CERTIFICATES =
      "The configured CA certificate contains no X.509 certificate.";

  private TlsSupport() {
    // Utility class: no instances.
  }

  /**
   * Builds the broker URI, choosing the scheme from the TLS flag.
   *
   * <p>The single place the scheme is decided, so {@link MqttPublisher} and {@link MqttSubscriber}
   * cannot disagree about it.
   *
   * @param tlsEnabled the configured TLS flag; {@code null} means "not configured", so plain TCP
   * @param brokerHost broker host name or address
   * @param brokerPort broker TCP port, unchanged by the TLS flag
   * @return {@code ssl://host:port} when TLS is on, otherwise {@code tcp://host:port}
   */
  public static String brokerUri(Boolean tlsEnabled, String brokerHost, int brokerPort) {
    return (isTlsEnabled(tlsEnabled) ? "ssl://" : "tcp://") + brokerHost + ":" + brokerPort;
  }

  /**
   * The one place the {@code null}-means-false rule lives.
   *
   * <p>A configuration saved by 1.0.6, before the field existed, carries no value for the key at
   * all and reads {@code null} here — so an existing connected system keeps working over plain TCP
   * untouched. Having both the URI scheme and the socket factory read this same method is what makes
   * it impossible for them to disagree.
   *
   * @param tlsEnabled the configured flag, possibly {@code null}
   * @return {@code true} only when TLS was explicitly enabled
   */
  static boolean isTlsEnabled(Boolean tlsEnabled) {
    return Boolean.TRUE.equals(tlsEnabled);
  }

  /**
   * Parses a PEM string into its certificates, in order.
   *
   * <p>{@link CertificateFactory#generateCertificates} reads <em>every</em>
   * {@code -----BEGIN CERTIFICATE-----} block in the input, so a certificate chain needs no extra
   * handling. Leading, trailing and CRLF whitespace is tolerated.
   *
   * <p>An empty result is an error, not an empty trust store. Measured on Corretto 17: an empty
   * input returns zero certificates <em>without throwing</em>, while a bare number or arbitrary
   * prose throws. Were a zero-length result allowed through, mode 3 would silently become "trust
   * nothing", or worse be mistaken for mode 2.
   *
   * @param pem the pasted certificate text
   * @return the parsed certificates, at least one
   * @throws GeneralSecurityException if the text is not a readable X.509 certificate; the message is
   *     fixed and never contains any part of the input
   */
  static List<X509Certificate> parseCertificates(String pem) throws GeneralSecurityException {
    if (pem == null || pem.isBlank()) {
      throw new GeneralSecurityException(NO_CERTIFICATES);
    }

    List<X509Certificate> certificates = new ArrayList<>();
    try {
      CertificateFactory factory = CertificateFactory.getInstance("X.509");
      for (Certificate certificate :
          factory.generateCertificates(
              new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)))) {
        if (certificate instanceof X509Certificate x509) {
          certificates.add(x509);
        }
      }
    } catch (CertificateException e) {
      // Deliberately dropping the cause's message would lose the diagnosis; dropping the INPUT is
      // the point, and the JDK's own message does not echo it. Keep it as the cause only.
      throw new GeneralSecurityException(PARSE_FAILED, e);
    }

    if (certificates.isEmpty()) {
      throw new GeneralSecurityException(NO_CERTIFICATES);
    }
    return certificates;
  }

  /**
   * Builds the trust managers for the configured mode.
   *
   * <p>This is the seam that makes "trust is actually restricted" testable rather than merely
   * "a socket factory was configured". Mode 2 returns real trust managers rather than {@code null}
   * — which {@link SSLContext#init} would also accept — precisely so a test can read the anchors
   * back.
   *
   * <p>Mode 2 (blank PEM) initialises the factory with a {@code null} key store, which is the JDK's
   * documented way of asking for the JVM default trust store. That <em>reads</em> the JSSE
   * configuration an Appian administrator may have set for the server, which is intended and is a
   * read, never a write.
   *
   * <p>Mode 3 (non-blank PEM) loads each parsed certificate under its own alias — {@code ca-0},
   * {@code ca-1}, … — so a chain cannot lose entries to alias collision, and initialises the factory
   * with that key store alone.
   *
   * @param caPem the pasted CA certificate text, or {@code null}/blank for the default trust store
   * @return the trust managers to initialise an {@link SSLContext} with
   * @throws GeneralSecurityException if a supplied PEM cannot be parsed
   */
  static TrustManager[] trustManagers(String caPem) throws GeneralSecurityException {
    TrustManagerFactory factory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());

    if (caPem == null || caPem.isBlank()) {
      factory.init((KeyStore) null);
      return factory.getTrustManagers();
    }

    List<X509Certificate> certificates = parseCertificates(caPem);
    KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
    try {
      // load(null, null) creates the store empty and in memory. Nothing is read from or written to
      // any storage medium.
      trustStore.load(null, null);
    } catch (IOException e) {
      // An in-memory initialisation cannot really fail; KeyStore.load declares it regardless.
      throw new GeneralSecurityException(PARSE_FAILED, e);
    }
    for (int i = 0; i < certificates.size(); i++) {
      trustStore.setCertificateEntry("ca-" + i, certificates.get(i));
    }

    factory.init(trustStore);
    return factory.getTrustManagers();
  }

  /**
   * Builds the socket factory to hand to Paho.
   *
   * @param caPem the pasted CA certificate text, or {@code null}/blank for the default trust store
   * @return a socket factory restricted to the configured trust anchors
   * @throws GeneralSecurityException if a supplied PEM cannot be parsed
   */
  public static SSLSocketFactory socketFactory(String caPem) throws GeneralSecurityException {
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, trustManagers(caPem), null);
    return context.getSocketFactory();
  }
}
