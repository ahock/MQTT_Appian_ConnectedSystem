package de.hock.mqtt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Hostname verification: the security crux of the 1.0.7 release, pinned from both directions.
 *
 * <p><strong>The finding these tests encode.</strong> Paho 1.2.5 <em>does</em> verify the broker's
 * host name on an {@code ssl://} connection, by default. Measured in its bytecode:
 * {@code SSLNetworkModuleFactory}, whose supported scheme set is exactly {@code {"ssl"}}, copies
 * {@code MqttConnectOptions.isHttpsHostnameVerificationEnabled()} onto the network module; that
 * field is seeded {@code true} in the {@code MqttConnectOptions} constructor; and
 * {@code SSLNetworkModule.start()} applies
 * {@code SSLParameters.setEndpointIdentificationAlgorithm("HTTPS")} to the socket after
 * {@code connect} and before {@code startHandshake()}. The guarantee in modes 2 and 3 is therefore
 * the full one — "a certificate chain the configured anchor signed, whose subject alternative name
 * matches the configured host" — not the weaker "signed by someone" that an unverified TLS
 * connection would give.
 *
 * <p><strong>Why no socket-factory wrapper.</strong> It would be redundant, and it would have been a
 * hazard. Paho creates its socket through the <em>no-argument</em>
 * {@code SocketFactory.createSocket()}, whose inherited implementation throws
 * {@code SocketException("Unconnected sockets not implemented")}. The 1.0.3 TLS code wrapped six
 * overloads but not that one, so every TLS connection it attempted would have failed — a third
 * defect in that code, alongside the never-resolved document id and the unusable passphrase.
 *
 * <p>The two handshake tests replicate Paho's socket sequence exactly, against a loopback
 * {@link SSLServerSocket} on an ephemeral port, with the plug-in's own
 * {@link TlsSupport#socketFactory} on the client side. <strong>Paho is not instantiated</strong>:
 * an {@code MqttClient} starts non-daemon threads that would outlive the suite.
 */
class TlsHostnameVerificationTest {

  /**
   * The options contract, with no socket involved: Paho's default is on, the plug-in sets it
   * explicitly anyway, and the contradictory custom-verifier path is left alone.
   *
   * <p>Asserting Paho's own default here means a future dependency bump that silently flipped it
   * would fail the build rather than quietly weakening every TLS connection.
   */
  @Test
  void pahoAppliesHttpsEndpointIdentificationOnSslByDefaultAndThePluginSetsItExplicitly()
      throws Exception {
    assertTrue(
        new MqttConnectOptions().isHttpsHostnameVerificationEnabled(),
        "Paho 1.2.5 seeds httpsHostnameVerificationEnabled true; the whole finding rests on it");

    MqttConnectOptions options =
        MqttPublisher.buildConnectOptions(Boolean.TRUE, "", null, null, null, null, null, null);
    assertTrue(
        options.isHttpsHostnameVerificationEnabled(),
        "the plug-in sets the flag explicitly rather than inheriting a default it cannot see");
    assertNull(
        options.getSSLHostnameVerifier(),
        "Paho runs a custom verifier ONLY when the HTTPS flag is false, so setting both would be "
            + "contradictory; this one must stay null");

    // Inert when TLS is off, which is why setting it unconditionally is safe.
    MqttConnectOptions plain =
        MqttPublisher.buildConnectOptions(Boolean.FALSE, null, null, null, null, null, null, null);
    assertTrue(plain.isHttpsHostnameVerificationEnabled());
    assertNull(plain.getSocketFactory(), "TLS off still installs no socket factory");
  }

  /** A broker certificate whose SAN matches the connected host completes the handshake. */
  @Test
  @Timeout(30)
  void aBrokerCertificateMatchingTheConfiguredHostCompletesTheHandshake() throws Exception {
    CertificateFixtures.Ca ca = CertificateFixtures.newCa("Test MQTT CA");
    CertificateFixtures.Leaf broker = CertificateFixtures.newLeaf("localhost", "localhost", ca);

    handshake(broker, ca, "localhost");
  }

  /**
   * A broker certificate for a different host fails the handshake, even though the CA is trusted.
   *
   * <p>This is the test that distinguishes real authentication from mere encryption. Without
   * endpoint identification, this handshake would succeed and an attacker holding any certificate
   * from the same CA could impersonate the broker.
   */
  @Test
  @Timeout(30)
  void aBrokerCertificateForADifferentHostFailsTheHandshake() throws Exception {
    CertificateFixtures.Ca ca = CertificateFixtures.newCa("Test MQTT CA");
    CertificateFixtures.Leaf broker =
        CertificateFixtures.newLeaf("wrong.example.com", "wrong.example.com", ca);

    SSLHandshakeException thrown =
        assertThrows(
            SSLHandshakeException.class,
            () -> handshake(broker, ca, "localhost"),
            "a certificate whose SAN does not cover the connected host must be rejected");

    String message = String.valueOf(thrown.getMessage());
    assertTrue(
        message.contains("subject alternative")
            || message.contains("No name matching")
            || message.contains("doesn't match"),
        "the failure must be a host-name mismatch, but was: " + message);
    assertFalse(message.isBlank(), "the failure must carry a diagnosis");
  }

  /**
   * Connects to a loopback TLS server presenting {@code broker}, replicating Paho's exact socket
   * sequence, and returns once the handshake has completed.
   *
   * <p>The sequence matters: Paho calls the <strong>no-argument</strong> {@code createSocket()},
   * then {@code connect(new InetSocketAddress(host, port), timeout)}, then sets an
   * {@link SSLParameters} carrying only the SNI name, then a second {@link SSLParameters} carrying
   * only the endpoint identification algorithm, then handshakes. Reproducing it is what makes this
   * test evidence about Paho's behaviour rather than about an idealised TLS client — in particular,
   * it proves the host name survives the unconnected-socket pattern that is the classic way
   * hostname verification silently degrades.
   */
  private static void handshake(
      CertificateFixtures.Leaf broker, CertificateFixtures.Ca issuer, String connectHost)
      throws Exception {

    SSLServerSocket server =
        (SSLServerSocket)
            CertificateFixtures.serverSocketFactory(broker, issuer)
                .createServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
    try {
      int port = server.getLocalPort();

      Thread accept =
          new Thread(
              () -> {
                try (SSLSocket accepted = (SSLSocket) server.accept()) {
                  accepted.setSoTimeout(10_000);
                  accepted.startHandshake();
                  // Nothing speaks MQTT behind this socket; the handshake is the whole subject.
                  accepted.getInputStream().read();
                } catch (IOException expected) {
                  // A rejected client closes mid-handshake. That is the outcome under test.
                }
              },
              "tls-fixture-accept");
      accept.setDaemon(true);
      accept.start();

      SSLSocketFactory clientFactory =
          TlsSupport.socketFactory(CertificateFixtures.toPem(issuer.certificate()));
      try (SSLSocket socket = (SSLSocket) clientFactory.createSocket()) {
        socket.connect(new InetSocketAddress(connectHost, port), 5_000);
        socket.setSoTimeout(10_000);

        SSLParameters sni = new SSLParameters();
        sni.setServerNames(Collections.singletonList(new SNIHostName(connectHost)));
        socket.setSSLParameters(sni);

        SSLParameters identification = new SSLParameters();
        identification.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(identification);

        socket.startHandshake();
        assertNotNull(socket.getSession().getPeerCertificates(), "a completed handshake has a peer");
      }
    } finally {
      server.close();
    }
  }
}
