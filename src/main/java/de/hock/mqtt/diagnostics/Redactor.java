package de.hock.mqtt.diagnostics;

import de.hock.mqtt.MqttConnectionConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Scrubs configured sensitive values from any text so they can never leak into diagnostics, error
 * messages, or error categories (Requirement 9.4).
 *
 * <p>A redactor is built from the exact sensitive values of a configuration — the username, the
 * password, and the client private key (plus the key passphrase, which is equally sensitive). Each
 * non-blank secret is replaced, wherever it occurs as a literal substring, with the fixed
 * {@link #REDACTION_MARKER}. Null, empty, and blank secrets are ignored so a blank password never
 * causes unrelated text to be rewritten.
 *
 * <p>Sensitive values held as {@code char[]}/{@code byte[]} on {@link MqttConnectionConfig} are read
 * into {@link String} form only transiently to perform matching; callers remain responsible for
 * clearing the underlying arrays after use.
 */
public final class Redactor {

  /** Replacement text substituted for every occurrence of a sensitive value. */
  public static final String REDACTION_MARKER = "***";

  /**
   * Secrets to scrub, ordered longest-first so that a secret that contains another secret as a
   * substring is redacted whole before the shorter one is considered.
   */
  private final List<String> secrets;

  private Redactor(List<String> secrets) {
    this.secrets = secrets;
  }

  /**
   * Builds a redactor from the sensitive values of a connection configuration: username, password,
   * client private key, and key passphrase (Requirement 9.4). A {@code null} configuration yields a
   * no-op redactor.
   *
   * @param config the connection configuration whose secrets must never appear in output
   * @return a redactor that scrubs the configuration's sensitive values
   */
  public static Redactor forConfig(MqttConnectionConfig config) {
    if (config == null) {
      return new Redactor(List.of());
    }
    return of(
        config.username(),
        config.password(),
        config.clientPrivateKey(),
        config.keyPassphrase());
  }

  /**
   * Builds a redactor from explicit sensitive values. Each argument may be {@code null}; blank
   * values are ignored so an empty secret never triggers redaction.
   *
   * @param username the authentication user name, or {@code null}
   * @param password the authentication password, or {@code null}
   * @param privateKey the client private key characters, or {@code null}
   * @param passphrase the private-key passphrase, or {@code null}
   * @return a redactor scrubbing the supplied secrets
   */
  public static Redactor of(String username, char[] password, char[] privateKey, char[] passphrase) {
    List<String> collected = new ArrayList<>();
    addSecret(collected, username);
    addSecret(collected, password == null ? null : new String(password));
    addSecret(collected, privateKey == null ? null : new String(privateKey));
    addSecret(collected, passphrase == null ? null : new String(passphrase));
    // Longest-first so overlapping secrets are redacted whole, independent of insertion order.
    collected.sort(Comparator.comparingInt(String::length).reversed());
    return new Redactor(collected);
  }

  private static void addSecret(List<String> collected, String secret) {
    if (secret != null && !secret.isBlank()) {
      collected.add(secret);
    }
  }

  /**
   * Returns {@code text} with every occurrence of every tracked secret replaced by the redaction
   * marker. A {@code null} input is returned unchanged, as is any text that contains no secret.
   *
   * @param text the text to scrub
   * @return the redacted text, or {@code null} if {@code text} was {@code null}
   */
  public String redact(String text) {
    if (text == null || secrets.isEmpty()) {
      return text;
    }
    String result = text;
    for (String secret : secrets) {
      if (result.contains(secret)) {
        result = result.replace(secret, REDACTION_MARKER);
      }
    }
    return result;
  }

  /**
   * Redacts an arbitrary value for inclusion in a diagnostics map. String values are scrubbed;
   * other values are returned unchanged (sensitive data is only ever carried as text here).
   *
   * @param value the diagnostic value
   * @return the redacted value
   */
  public Object redactValue(Object value) {
    return value instanceof String s ? redact(s) : value;
  }
}
