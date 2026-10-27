package de.hock.mqtt.validation;

/**
 * Design-time validator for the shape of the pasted certificate fields.
 *
 * <p>The CA and client certificate fields hold PEM text that a Designer pastes in. This validator
 * checks only that a supplied value <em>looks</em> like PEM — that it carries a
 * {@code -----BEGIN ...-----} header — and leaves the actual X.509 parse to
 * {@link CertificateLoader} (client certificate) and the runtime connection layer (CA certificate).
 *
 * <p>Its purpose is to turn two otherwise opaque failures into a clear, field-scoped message:
 *
 * <ul>
 *   <li>A Designer pastes something that is not a certificate (a file path, a fingerprint, a
 *       truncated copy without the header lines).</li>
 *   <li><b>Migration.</b> Both fields were {@code DocumentPropertyDescriptor} uploads in plug-in
 *       versions up to 1.0.2. A connected system object saved by one of those versions holds an
 *       opaque document reference, which this version reads as text — typically a bare numeric id.
 *       Without this check the only symptom would be a TLS handshake failure at run time, or
 *       silently no certificate at all. With it, reopening the object reports exactly what to do.
 *       The document shape could never be resolved to certificate bytes by this SDK version, so
 *       nothing that previously worked is lost.</li>
 * </ul>
 *
 * <p>Like the other validators in this package, it produces field-scoped {@link ValidationResult}
 * entries so the message appears against the offending field and the entered value is retained,
 * and it reuses the shared {@link ValidationResults} accumulator so results compose.
 *
 * <p>A blank or absent value is always valid: both certificates are optional.
 *
 * <p>Requirements: 3.5, 3.6.
 */
public final class PemValidator {

  /** Field identifier for the CA certificate. */
  public static final String FIELD_CA_CERTIFICATE = ConfigFields.CA_CERTIFICATE;

  /** Field identifier for the client certificate. */
  public static final String FIELD_CLIENT_CERTIFICATE = ConfigFields.CLIENT_CERTIFICATE;

  /** Marker every PEM encoding begins with. */
  private static final String PEM_HEADER = "-----BEGIN";

  /**
   * Validates the shape of both pasted certificate fields.
   *
   * @param caCertificateText the entered CA certificate text, or {@code null}/blank when absent
   * @param clientCertificateText the entered client certificate text, or {@code null}/blank when
   *     absent
   * @return an accumulator that is {@link ValidationResults#isValid() valid} when both supplied
   *     values look like PEM, or carries one field-scoped error per malformed field
   */
  public ValidationResults validate(String caCertificateText, String clientCertificateText) {
    ValidationResults results = new ValidationResults();
    checkPem(results, FIELD_CA_CERTIFICATE, "CA certificate", caCertificateText);
    checkPem(results, FIELD_CLIENT_CERTIFICATE, "Client certificate", clientCertificateText);
    return results;
  }

  /**
   * Reports a field whose supplied value carries no PEM header. The message names the expected
   * header so the fix is obvious, and calls out the upgrade case because a stored document id is
   * the likeliest way to reach this state.
   */
  private static void checkPem(
      ValidationResults results, String fieldId, String fieldLabel, String value) {
    if (value == null || value.isBlank()) {
      // Both certificates are optional.
      return;
    }
    if (value.contains(PEM_HEADER)) {
      return;
    }
    results.add(
        ValidationResult.of(
            fieldId,
            fieldLabel
                + " must be pasted PEM text containing a \""
                + PEM_HEADER
                + " CERTIFICATE-----\" line. If this connected system was configured with an "
                + "uploaded document in an earlier version of the plug-in, clear the field and "
                + "paste the certificate's PEM text instead.",
            value));
  }
}
