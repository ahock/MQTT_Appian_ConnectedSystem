package de.hock.mqtt.validation;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;

/**
 * Loads and validates a client certificate and its associated private key.
 *
 * <p>When TLS is enabled, a {@code Designer} may upload a {@code Client_Certificate} and its
 * private key for mutual TLS. Before the connection configuration is accepted, those bytes must be
 * parseable: if either the certificate or the key cannot be loaded, the configuration is rejected
 * with a certificate-load error rather than failing later during the TLS handshake (Requirement
 * 3.6).
 *
 * <p>This loader is deliberately defensive: any malformed input yields a certificate-load
 * {@link ValidationResult} rather than propagating an uncaught exception. It accepts both PEM
 * (base64 wrapped in {@code -----BEGIN ...-----} markers) and raw DER encodings:
 *
 * <ul>
 *   <li><b>Certificate</b> — parsed as X.509 via {@link CertificateFactory}. PEM input is parsed
 *       directly by the factory; DER input is parsed from the raw bytes.</li>
 *   <li><b>Private key</b> — parsed as an unencrypted PKCS#8 key via {@link KeyFactory} and a
 *       {@link PKCS8EncodedKeySpec}. PEM {@code PRIVATE KEY} blocks are base64-decoded first; raw
 *       DER bytes are used as-is. Because the key spec does not carry the algorithm, the loader
 *       tries the common asymmetric algorithms (RSA, EC, DSA, Ed25519).</li>
 * </ul>
 *
 * <p>The class is stateless; the single entry point is the static {@link #load(byte[], byte[])}
 * method. It does not retain references to the supplied private-key bytes.
 */
public final class CertificateLoader {

  /** Field id used to scope certificate-load validation errors. */
  public static final String FIELD_ID = "clientCertificate";

  /** Private-key algorithms attempted, in order, when reconstructing a PKCS#8 key. */
  private static final String[] KEY_ALGORITHMS = {"RSA", "EC", "DSA", "Ed25519"};

  private static final String KEY_PEM_HEADER = "-----BEGIN";
  private static final String KEY_PEM_FOOTER = "-----END";

  private CertificateLoader() {
    // Utility class; not instantiable.
  }

  /**
   * Result of a successful certificate/key load.
   *
   * @param certificate the parsed X.509 client certificate (never {@code null})
   * @param privateKey the parsed private key (never {@code null})
   */
  public record LoadedCertificate(X509Certificate certificate, PrivateKey privateKey) {
  }

  /**
   * Outcome of a certificate/key load attempt.
   *
   * <p>Exactly one of {@link #loaded()} or {@link #error()} is present: a load either succeeds and
   * carries a {@link LoadedCertificate}, or fails and carries a certificate-load
   * {@link ValidationResult}. Callers reject the configuration whenever {@link #isSuccess()} is
   * {@code false}.
   *
   * @param loaded the loaded certificate/key pair when the load succeeded, otherwise empty
   * @param error the certificate-load validation error when the load failed, otherwise empty
   */
  public record LoadOutcome(Optional<LoadedCertificate> loaded, Optional<ValidationResult> error) {

    /** @return {@code true} when the certificate and key were loaded successfully */
    public boolean isSuccess() {
      return loaded.isPresent();
    }

    private static LoadOutcome success(LoadedCertificate loaded) {
      return new LoadOutcome(Optional.of(loaded), Optional.empty());
    }

    private static LoadOutcome failure(ValidationResult error) {
      return new LoadOutcome(Optional.empty(), Optional.of(error));
    }
  }

  /**
   * Attempts to load a client certificate and private key from the supplied bytes.
   *
   * <p>Malformed, empty, or missing input produces a certificate-load error rather than throwing.
   * Both the certificate and the private key must be present and parseable for the load to
   * succeed.
   *
   * @param certificateBytes the client certificate bytes (PEM or DER); must be non-empty
   * @param privateKeyBytes the private key bytes (PKCS#8 PEM or DER); must be non-empty
   * @return a {@link LoadOutcome} that is either a success or a certificate-load error
   */
  public static LoadOutcome load(byte[] certificateBytes, byte[] privateKeyBytes) {
    if (certificateBytes == null || certificateBytes.length == 0) {
      return LoadOutcome.failure(error("Client certificate is empty or missing."));
    }
    if (privateKeyBytes == null || privateKeyBytes.length == 0) {
      return LoadOutcome.failure(error("Client private key is empty or missing."));
    }

    X509Certificate certificate;
    try {
      certificate = parseCertificate(certificateBytes);
    } catch (RuntimeException e) {
      return LoadOutcome.failure(
          error("Client certificate could not be loaded: it is not a valid X.509 certificate."));
    }

    PrivateKey privateKey;
    try {
      privateKey = parsePrivateKey(privateKeyBytes);
    } catch (RuntimeException e) {
      return LoadOutcome.failure(
          error("Client private key could not be loaded: it is not a valid PKCS#8 private key."));
    }

    return LoadOutcome.success(new LoadedCertificate(certificate, privateKey));
  }

  /**
   * Parses the bytes as an X.509 certificate. {@link CertificateFactory} accepts both PEM and DER
   * encodings for X.509, so the raw bytes are handed to it directly.
   *
   * @throws IllegalArgumentException if the bytes are not a valid X.509 certificate
   */
  private static X509Certificate parseCertificate(byte[] bytes) {
    try {
      CertificateFactory factory = CertificateFactory.getInstance("X.509");
      Object parsed = factory.generateCertificate(new ByteArrayInputStream(bytes));
      if (!(parsed instanceof X509Certificate x509)) {
        throw new IllegalArgumentException("Parsed certificate is not X.509.");
      }
      return x509;
    } catch (CertificateException e) {
      throw new IllegalArgumentException("Invalid X.509 certificate.", e);
    }
  }

  /**
   * Parses the bytes as an unencrypted PKCS#8 private key. PEM input is stripped of its armor and
   * base64-decoded; DER input is used directly. Because {@link PKCS8EncodedKeySpec} does not carry
   * the key algorithm, each candidate algorithm is tried until one reconstructs the key.
   *
   * @throws IllegalArgumentException if the bytes are not a valid PKCS#8 private key
   */
  private static PrivateKey parsePrivateKey(byte[] bytes) {
    byte[] der = toDer(bytes);
    PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(der);
    for (String algorithm : KEY_ALGORITHMS) {
      try {
        KeyFactory keyFactory = KeyFactory.getInstance(algorithm);
        return keyFactory.generatePrivate(keySpec);
      } catch (InvalidKeySpecException | RuntimeException e) {
        // Wrong algorithm for this key, or unavailable on the platform: try the next candidate.
      } catch (java.security.NoSuchAlgorithmException e) {
        // Algorithm not supported by the platform: try the next candidate.
      }
    }
    throw new IllegalArgumentException("Unrecognized or malformed PKCS#8 private key.");
  }

  /**
   * Converts possibly-PEM key bytes into raw DER. If the input contains PEM armor it is
   * base64-decoded; otherwise the input is assumed to already be DER and returned unchanged.
   */
  private static byte[] toDer(byte[] bytes) {
    String text = new String(bytes, StandardCharsets.US_ASCII);
    if (!text.contains(KEY_PEM_HEADER)) {
      return bytes;
    }
    StringBuilder base64 = new StringBuilder();
    for (String line : text.split("\\R")) {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith(KEY_PEM_HEADER) || trimmed.startsWith(KEY_PEM_FOOTER)) {
        continue;
      }
      base64.append(trimmed);
    }
    try {
      return Base64.getDecoder().decode(base64.toString());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("PEM private key body is not valid base64.", e);
    }
  }

  private static ValidationResult error(String message) {
    // Use the canonical constructor directly (stable across the shared type's evolution) rather
    // than a static factory, scoping the error to the client-certificate field with no retained
    // values to echo back.
    return new ValidationResult(FIELD_ID, message, java.util.Collections.emptyMap());
  }
}
