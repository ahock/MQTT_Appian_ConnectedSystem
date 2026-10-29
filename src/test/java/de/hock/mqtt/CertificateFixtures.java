package de.hock.mqtt;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.TimeZone;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;

/**
 * Throwaway X.509 certificates, generated inside the test JVM, for the TLS tests.
 *
 * <p><strong>Why this file is 200 lines of ASN.1 rather than three lines of API.</strong> There is
 * no public JDK API that <em>creates</em> an X.509 certificate. {@link CertificateFactory} only
 * parses, and {@code sun.security.x509.CertAndKeyGen} is not exported from {@code java.base}. So
 * this class writes the DER encoding by hand and signs it with {@link Signature} — all public API,
 * no BouncyCastle, no {@code --add-exports}, no {@code keytool} subprocess.
 *
 * <p><strong>No key material is committed, and none is written anywhere.</strong> Every key pair is
 * generated per call and exists only as an in-memory {@link PrivateKey} inside the test JVM; nothing
 * is serialised, printed or stored. Certificates are valid from an hour ago to thirty days hence, so
 * nothing can expire mid-suite and no long-dated artefact is produced.
 *
 * <p>Deliberately <strong>not</strong> named {@code Test*} or {@code *Test}: Surefire's default
 * includes would try to run it as a test class.
 */
final class CertificateFixtures {

  /** An RSA key pair with its self-signed CA certificate. */
  record Ca(X509Certificate certificate, KeyPair keyPair) {}

  /** A leaf certificate with its own key pair, signed by some issuer. */
  record Leaf(X509Certificate certificate, KeyPair keyPair) {}

  private static final String SHA_256_WITH_RSA_OID = "1.2.840.113549.1.1.11";
  private static final String COMMON_NAME_OID = "2.5.4.3";
  private static final String BASIC_CONSTRAINTS_OID = "2.5.29.19";
  private static final String KEY_USAGE_OID = "2.5.29.15";
  private static final String SUBJECT_ALT_NAME_OID = "2.5.29.17";

  private static final SecureRandom RANDOM = new SecureRandom();

  private CertificateFixtures() {
    // Fixture factory: no instances.
  }

  // ---------------------------------------------------------------------------------------------
  // Public fixture API
  // ---------------------------------------------------------------------------------------------

  /** A self-signed CA certificate with {@code basicConstraints cA=TRUE} and {@code keyCertSign}. */
  static Ca newCa(String commonName) throws Exception {
    KeyPair keyPair = newKeyPair();
    byte[] tbs =
        tbsCertificate(commonName, commonName, keyPair.getPublic(), true, null);
    return new Ca(sign(tbs, keyPair.getPrivate()), keyPair);
  }

  /** A leaf certificate for {@code dnsSan}, signed by {@code issuer}. */
  static Leaf newLeaf(String commonName, String dnsSan, Ca issuer) throws Exception {
    KeyPair keyPair = newKeyPair();
    byte[] tbs =
        tbsCertificate(
            subjectCommonNameOf(issuer.certificate()),
            commonName,
            keyPair.getPublic(),
            false,
            dnsSan);
    return new Leaf(sign(tbs, issuer.keyPair().getPrivate()), keyPair);
  }

  /**
   * A self-signed certificate that is also a CA, for the "unrelated anchor" and "rogue broker"
   * cases: it is a legitimate certificate signed by nobody the plug-in has been told to trust.
   */
  static Ca newSelfSignedCa(String commonName, String dnsSan) throws Exception {
    KeyPair keyPair = newKeyPair();
    byte[] tbs = tbsCertificate(commonName, commonName, keyPair.getPublic(), true, dnsSan);
    return new Ca(sign(tbs, keyPair.getPrivate()), keyPair);
  }

  /** The PEM form of one certificate, as a designer would paste it. */
  static String toPem(X509Certificate certificate) throws Exception {
    return "-----BEGIN CERTIFICATE-----\n"
        + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(certificate.getEncoded())
        + "\n-----END CERTIFICATE-----\n";
  }

  /** The PEM form of several certificates concatenated, as a chain is normally pasted. */
  static String toPemChain(X509Certificate... certificates) throws Exception {
    StringBuilder pem = new StringBuilder();
    for (X509Certificate certificate : certificates) {
      pem.append(toPem(certificate));
    }
    return pem.toString();
  }

  /**
   * A server socket factory presenting {@code leaf} with {@code issuer} as its chain.
   *
   * <p>Used by the two loopback handshake tests. The in-memory key store exists for the lifetime of
   * the call only; it is never written anywhere.
   */
  static SSLServerSocketFactory serverSocketFactory(Leaf leaf, Ca issuer) throws Exception {
    char[] passphrase = randomPassphrase();
    KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
    keyStore.load(null, null);
    keyStore.setKeyEntry(
        "server",
        leaf.keyPair().getPrivate(),
        passphrase,
        new Certificate[] {leaf.certificate(), issuer.certificate()});

    KeyManagerFactory keyManagers =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keyManagers.init(keyStore, passphrase);

    SSLContext context = SSLContext.getInstance("TLS");
    context.init(keyManagers.getKeyManagers(), null, null);
    return context.getServerSocketFactory();
  }

  /**
   * A single-use random passphrase for the in-memory server key store.
   *
   * <p>Not a credential: it protects a key that was generated seconds ago and is discarded when the
   * test method returns. It is random rather than a literal so that no string in this repository can
   * be mistaken for a password, which Appian's policies forbid shipping.
   */
  private static char[] randomPassphrase() {
    byte[] bytes = new byte[24];
    RANDOM.nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes).toCharArray();
  }

  private static KeyPair newKeyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }

  private static String subjectCommonNameOf(X509Certificate certificate) {
    // The fixtures always use a bare CN, so the RFC 2253 name is exactly "CN=<value>".
    String name = certificate.getSubjectX500Principal().getName();
    return name.startsWith("CN=") ? name.substring(3) : name;
  }

  // ---------------------------------------------------------------------------------------------
  // Certificate assembly
  // ---------------------------------------------------------------------------------------------

  /**
   * Builds the {@code TBSCertificate} DER: a v3 certificate with a random serial, SHA-256/RSA, the
   * requested issuer and subject, and the extensions each role needs.
   */
  private static byte[] tbsCertificate(
      String issuerCommonName,
      String subjectCommonName,
      PublicKey subjectPublicKey,
      boolean certificateAuthority,
      String dnsSan)
      throws Exception {

    ByteArrayOutputStream extensions = new ByteArrayOutputStream();
    // basicConstraints: critical. cA=TRUE for a CA, an empty SEQUENCE (cA defaults FALSE) for a leaf.
    extensions.write(
        extension(
            BASIC_CONSTRAINTS_OID,
            true,
            certificateAuthority ? seq(bool(true)) : seq(new byte[0])));
    if (certificateAuthority) {
      // keyUsage: critical, keyCertSign | cRLSign. 0x06 with one unused bit, per the BIT STRING form.
      extensions.write(
          extension(KEY_USAGE_OID, true, tlv(0x03, new byte[] {(byte) 0x01, (byte) 0x06})));
    }
    if (dnsSan != null) {
      // subjectAltName: non-critical, one dNSName. Context tag [2] IMPLICIT IA5String = 0x82.
      extensions.write(
          extension(
              SUBJECT_ALT_NAME_OID,
              false,
              seq(tlv(0x82, dnsSan.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))));
    }

    long now = System.currentTimeMillis();
    ByteArrayOutputStream tbs = new ByteArrayOutputStream();
    tbs.write(explicit(0, integer(BigInteger.valueOf(2)))); // version: v3
    tbs.write(integer(new BigInteger(64, RANDOM).add(BigInteger.ONE))); // serialNumber
    tbs.write(signatureAlgorithm());
    tbs.write(name(issuerCommonName));
    tbs.write(seq(concat(utcTime(now - 3_600_000L), utcTime(now + 2_592_000_000L))));
    tbs.write(name(subjectCommonName));
    tbs.write(subjectPublicKey.getEncoded()); // an RSA X509EncodedKeySpec IS a SubjectPublicKeyInfo
    tbs.write(explicit(3, seq(extensions.toByteArray()))); // extensions
    return seq(tbs.toByteArray());
  }

  /** Signs a {@code TBSCertificate} and parses the result back into a real {@link X509Certificate}. */
  private static X509Certificate sign(byte[] tbs, PrivateKey issuerKey) throws Exception {
    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(issuerKey);
    signer.update(tbs);
    byte[] signature = signer.sign();

    byte[] certificate =
        seq(
            concat(
                tbs,
                signatureAlgorithm(),
                tlv(0x03, concat(new byte[] {0x00}, signature)))); // BIT STRING, 0 unused bits

    return (X509Certificate)
        CertificateFactory.getInstance("X.509")
            .generateCertificate(new java.io.ByteArrayInputStream(certificate));
  }

  /** {@code AlgorithmIdentifier} for {@code sha256WithRSAEncryption}. */
  private static byte[] signatureAlgorithm() {
    return seq(concat(oid(SHA_256_WITH_RSA_OID), nul()));
  }

  /** {@code Name} carrying a single {@code CN} attribute. */
  private static byte[] name(String commonName) {
    byte[] attribute = seq(concat(oid(COMMON_NAME_OID), utf8(commonName)));
    return seq(tlv(0x31, attribute)); // SET OF
  }

  /** One {@code Extension}: {@code SEQUENCE { OID, BOOLEAN critical DEFAULT FALSE, OCTET STRING }}. */
  private static byte[] extension(String extensionOid, boolean critical, byte[] value) {
    byte[] body =
        critical
            ? concat(oid(extensionOid), bool(true), octets(value))
            : concat(oid(extensionOid), octets(value));
    return seq(body);
  }

  // ---------------------------------------------------------------------------------------------
  // Minimal DER writer
  // ---------------------------------------------------------------------------------------------

  /** Tag-length-value with both the short and the long length form. */
  private static byte[] tlv(int tag, byte[] value) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(tag);
    int length = value.length;
    if (length < 0x80) {
      out.write(length);
    } else {
      // Long form: 0x80 | number of length bytes, then the length big-endian with no leading zero.
      byte[] lengthBytes = BigInteger.valueOf(length).toByteArray();
      int offset = lengthBytes.length > 1 && lengthBytes[0] == 0 ? 1 : 0;
      out.write(0x80 | (lengthBytes.length - offset));
      out.write(lengthBytes, offset, lengthBytes.length - offset);
    }
    out.write(value, 0, value.length);
    return out.toByteArray();
  }

  private static byte[] seq(byte[] value) {
    return tlv(0x30, value);
  }

  private static byte[] explicit(int number, byte[] inner) {
    return tlv(0xA0 | number, inner);
  }

  private static byte[] integer(BigInteger value) {
    return tlv(0x02, value.toByteArray());
  }

  private static byte[] bool(boolean value) {
    return tlv(0x01, new byte[] {(byte) (value ? 0xFF : 0x00)});
  }

  private static byte[] nul() {
    return tlv(0x05, new byte[0]);
  }

  private static byte[] octets(byte[] value) {
    return tlv(0x04, value);
  }

  private static byte[] utf8(String value) {
    return tlv(0x0C, value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  /** {@code UTCTime} in the {@code yyMMddHHmmssZ} form X.509 requires, always UTC. */
  private static byte[] utcTime(long epochMillis) {
    SimpleDateFormat format = new SimpleDateFormat("yyMMddHHmmss");
    format.setTimeZone(TimeZone.getTimeZone("UTC"));
    String text = format.format(new Date(epochMillis)) + "Z";
    return tlv(0x17, text.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
  }

  /** An {@code OBJECT IDENTIFIER} from dotted decimal, with base-128 subidentifiers. */
  private static byte[] oid(String dotted) {
    String[] parts = dotted.split("\\.");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    // The first two arcs share one byte: 40 * arc1 + arc2.
    out.write(Integer.parseInt(parts[0]) * 40 + Integer.parseInt(parts[1]));
    for (int i = 2; i < parts.length; i++) {
      long value = Long.parseLong(parts[i]);
      // Base-128, most significant group first, continuation bit set on all but the last byte.
      int groups = 1;
      for (long probe = value >>> 7; probe > 0; probe >>>= 7) {
        groups++;
      }
      for (int group = groups - 1; group >= 0; group--) {
        int septet = (int) ((value >>> (7 * group)) & 0x7F);
        out.write(group == 0 ? septet : septet | 0x80);
      }
    }
    return tlv(0x06, out.toByteArray());
  }

  private static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] part : parts) {
      out.write(part, 0, part.length);
    }
    return out.toByteArray();
  }
}
