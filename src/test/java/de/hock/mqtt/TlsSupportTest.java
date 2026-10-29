package de.hock.mqtt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;

/**
 * The three connection modes of the 1.0.7 TLS feature, and the certificate handling behind them.
 *
 * <p>The test that matters most here is {@link #tlsEnabledWithACaTrustsOnlyThatCa}: it does not
 * settle for "a socket factory was produced", which is the easy and useless assertion. It reads the
 * trust manager back and checks that a certificate signed by the configured CA is accepted while an
 * unrelated one is <strong>rejected</strong> — the only offline evidence that trust is genuinely
 * restricted rather than merely configured.
 *
 * <p>All certificates are generated inside the JVM by {@link CertificateFixtures}. No broker, no
 * network, no key material in the repository.
 *
 * <p>Numeric assertions are deliberately loose. Maven runs here on a different JDK from the Java 17
 * the plug-in targets, and the JVM default trust store changes with every JDK update, so bounds are
 * stated as "more than 20 issuers" rather than an exact count.
 */
class TlsSupportTest {

  /** Mode 1 regression lock: TLS off must stay exactly the 1.0.6 behaviour. */
  @Test
  void tlsDisabledProducesATcpUriAndNoSocketFactory() throws Exception {
    assertEquals("tcp://broker.example.test:1883",
        TlsSupport.brokerUri(Boolean.FALSE, "broker.example.test", 1883));
    assertEquals(
        "tcp://broker.example.test:1883",
        TlsSupport.brokerUri(null, "broker.example.test", 1883),
        "an absent flag — a connected system saved before 1.0.7 — means plain TCP");
    assertFalse(TlsSupport.isTlsEnabled(null));
    assertFalse(TlsSupport.isTlsEnabled(Boolean.FALSE));
    assertTrue(TlsSupport.isTlsEnabled(Boolean.TRUE));

    // The important half: a CA left behind in a saved configuration must not pull TLS in.
    String caPem = CertificateFixtures.toPem(CertificateFixtures.newCa("Test MQTT CA").certificate());
    assertNull(
        MqttPublisher.buildConnectOptions(
                Boolean.FALSE, caPem, null, null, null, null, null, null)
            .getSocketFactory(),
        "TLS off must install no socket factory, even with a valid CA certificate configured");
  }

  /** Mode 2: TLS on, no CA — the JVM default trust store, which is right for a public CA. */
  @Test
  void tlsEnabledWithoutACaUsesTheDefaultTrustStoreOverAnSslUri() throws Exception {
    assertEquals(
        "ssl://broker.example.test:8883",
        TlsSupport.brokerUri(Boolean.TRUE, "broker.example.test", 8883));

    assertNotNull(TlsSupport.socketFactory(""), "a blank CA means the default trust store");
    assertNotNull(TlsSupport.socketFactory(null), "a null CA means the default trust store");
    assertNotNull(
        MqttPublisher.buildConnectOptions(Boolean.TRUE, "", null, null, null, null, null, null)
            .getSocketFactory(),
        "TLS on must install a socket factory");

    X509TrustManager trustManager = x509Of(TlsSupport.trustManagers(""));
    assertTrue(
        trustManager.getAcceptedIssuers().length > 20,
        "the JVM default trust store carries the platform's public CAs, but had only "
            + trustManager.getAcceptedIssuers().length);

    // The default trust store must not accept a throwaway self-signed certificate.
    X509Certificate rogue =
        CertificateFixtures.newSelfSignedCa("Rogue Broker", "broker.example.test").certificate();
    assertThrows(
        CertificateException.class,
        () -> trustManager.checkServerTrusted(new X509Certificate[] {rogue}, "RSA"),
        "mode 2 must reject a certificate no public CA signed");

    // Pins the SSLContext.getInstance("TLS") decision: modern protocols on, obsolete ones off.
    SSLSocketFactory factory = TlsSupport.socketFactory("");
    try (SSLSocket socket = (SSLSocket) factory.createSocket()) {
      List<String> enabled = Arrays.asList(socket.getEnabledProtocols());
      assertTrue(enabled.contains("TLSv1.2"), "TLS 1.2 must be available, but had " + enabled);
      assertFalse(enabled.contains("TLSv1"), "TLS 1.0 must not be enabled: " + enabled);
      assertFalse(enabled.contains("TLSv1.1"), "TLS 1.1 must not be enabled: " + enabled);
    }
  }

  /**
   * Mode 3, and the point of the whole release: trust is <strong>restricted</strong> to the
   * configured CA, not merely configured from it.
   */
  @Test
  void tlsEnabledWithACaTrustsOnlyThatCa() throws Exception {
    CertificateFixtures.Ca ca = CertificateFixtures.newCa("Test MQTT CA");
    CertificateFixtures.Leaf broker =
        CertificateFixtures.newLeaf("broker.example.test", "broker.example.test", ca);
    X509Certificate unrelated =
        CertificateFixtures.newSelfSignedCa("Unrelated Anchor", "broker.example.test").certificate();

    String caPem = CertificateFixtures.toPem(ca.certificate());
    X509TrustManager trustManager = x509Of(TlsSupport.trustManagers(caPem));

    assertEquals(
        1,
        trustManager.getAcceptedIssuers().length,
        "mode 3 must trust exactly the one configured anchor, not it plus the default store");

    trustManager.checkServerTrusted(
        new X509Certificate[] {broker.certificate(), ca.certificate()}, "RSA");

    assertThrows(
        CertificateException.class,
        () -> trustManager.checkServerTrusted(new X509Certificate[] {unrelated}, "RSA"),
        "a certificate the configured CA did not sign must be REJECTED; if this passes, trust is "
            + "not being enforced and the release must not ship");

    assertNotNull(
        MqttPublisher.buildConnectOptions(Boolean.TRUE, caPem, null, null, null, null, null, null)
            .getSocketFactory());
  }

  /** A pasted chain of several PEM blocks loads every certificate under its own alias. */
  @Test
  void aMultiCertificatePemChainLoadsEveryCertificate() throws Exception {
    CertificateFixtures.Ca rootCa = CertificateFixtures.newCa("Test MQTT Root CA");
    CertificateFixtures.Ca otherCa = CertificateFixtures.newCa("Test MQTT Other CA");
    String chainPem =
        CertificateFixtures.toPemChain(rootCa.certificate(), otherCa.certificate());

    List<X509Certificate> parsed = TlsSupport.parseCertificates(chainPem);
    assertEquals(2, parsed.size(), "both PEM blocks must parse");
    assertEquals(rootCa.certificate(), parsed.get(0), "in pasted order");
    assertEquals(otherCa.certificate(), parsed.get(1), "in pasted order");

    assertEquals(
        2,
        x509Of(TlsSupport.trustManagers(chainPem)).getAcceptedIssuers().length,
        "distinct aliases, so neither certificate overwrites the other");
  }

  /**
   * Malformed input is rejected, and no message carries any part of it.
   *
   * <p>The no-echo assertion is the one with teeth: a parse failure must never put certificate — or
   * mistakenly pasted secret — text into a message a designer or a log could see.
   *
   * <p><strong>The bogus inputs are chosen to share no English with the fixed messages.</strong>
   * {@link #assertNoEcho} scans for any six-character run of the input, which cannot tell a leak
   * from a coincidence: an input of "not a certificate at all" trips the scan on {@code " certi"}
   * purely because the plug-in's own fixed sentence contains the word "certificate". Rather than
   * weaken the scan, the inputs avoid that collision — a leak of real certificate material, which is
   * base64 and digits, is what the check exists to catch.
   */
  @Test
  void malformedCaCertificateIsRejectedWithoutEchoingItsContent() throws Exception {
    String[] rejected = {
      "123456",
      "garbage pasted by mistake",
      "",
      "   ",
      "-----BEGIN CERTIFICATE-----\nd2hvbGx5IGJvZ3VzIGJ5dGVz!!!\n-----END CERTIFICATE-----\n"
    };

    for (String input : rejected) {
      GeneralSecurityException thrown =
          assertThrows(
              GeneralSecurityException.class,
              () -> TlsSupport.parseCertificates(input),
              "must be rejected: " + describe(input));
      assertNoEcho(thrown.getMessage(), input);

      // The socket factory path must refuse the same inputs, except blank, which means mode 2.
      if (!input.isBlank()) {
        assertNoEcho(
            assertThrows(GeneralSecurityException.class, () -> TlsSupport.socketFactory(input))
                .getMessage(),
            input);
      }
    }

    // A valid PEM pasted from a Windows editor — CRLF line endings and surrounding whitespace —
    // must still parse. This is the commonest real-world shape of the field's contents.
    String pem = CertificateFixtures.toPem(CertificateFixtures.newCa("Test MQTT CA").certificate());
    String padded = "  \r\n" + pem.replace("\n", "\r\n") + "\r\n  ";
    assertEquals(
        1, TlsSupport.parseCertificates(padded).size(), "a CRLF-padded PEM must still parse");
  }

  /** Asserts a message leaks neither the whole input nor any 6-character run of it. */
  private static void assertNoEcho(String message, String input) {
    assertNotNull(message, "a rejection must carry a message");
    String trimmed = input.trim();
    if (trimmed.isEmpty()) {
      return;
    }
    assertFalse(message.contains(trimmed), "the message echoes the whole input: " + message);
    for (int i = 0; i + 6 <= trimmed.length(); i++) {
      String run = trimmed.substring(i, i + 6);
      if (run.isBlank()) {
        continue;
      }
      assertFalse(
          message.contains(run),
          "the message echoes '" + run + "' from the configured value: " + message);
    }
  }

  private static String describe(String input) {
    return input.isBlank() ? "<" + input.length() + " blank characters>" : "a non-PEM value";
  }

  private static X509TrustManager x509Of(TrustManager[] trustManagers) {
    for (TrustManager trustManager : trustManagers) {
      if (trustManager instanceof X509TrustManager x509) {
        return x509;
      }
    }
    throw new AssertionError("no X509TrustManager was produced");
  }
}
