package de.hock.mqtt.validation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Generates an MQTT client identifier derived from a broker host and port.
 *
 * <p>When a {@code Designer} does not supply a client identifier, the connected system
 * auto-generates one so the {@code MQTT_Client} session can still be uniquely identified to the
 * broker (Requirement 1.2). The generated identifier is:
 *
 * <ul>
 *   <li><b>Bounded</b> — always 1 to 23 characters, which satisfies the MQTT 3.1.1 server
 *       requirement to accept client identifiers up to 23 bytes.</li>
 *   <li><b>Stable</b> — the same host and port always produce the same identifier, so a
 *       persistent session can reconnect under a consistent identity.</li>
 *   <li><b>Distinct</b> — different host and port combinations produce different identifiers,
 *       backed by a SHA-256 digest to keep collisions negligible.</li>
 *   <li><b>Protocol-safe</b> — composed only of characters from {@code [0-9a-zA-Z]}, the
 *       alphabet an MQTT server is required to accept.</li>
 * </ul>
 *
 * <p>This class is stateless and holds no shared mutable state; the single entry point is the
 * static {@link #generate(String, int)} method.
 */
public final class ClientIdentifierGenerator {

  /**
   * Fixed prefix so the identifier is never empty and is recognizable as auto-generated.
   * Kept short to leave room for the hash suffix within the 23-character budget.
   */
  private static final String PREFIX = "appian-";

  /** Maximum length of an MQTT client identifier guaranteed to be accepted by a broker. */
  private static final int MAX_LENGTH = 23;

  /** Characters an MQTT server is required to accept for client identifiers. */
  private static final char[] SAFE_ALPHABET =
      "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

  private ClientIdentifierGenerator() {
    // Utility class; not instantiable.
  }

  /**
   * Generates a stable, distinct, 1–23 character client identifier for the given host and port.
   *
   * @param host the broker host; must not be {@code null}
   * @param port the broker port
   * @return a client identifier of 1–23 characters drawn from {@code [0-9a-zA-Z]}
   * @throws NullPointerException if {@code host} is {@code null}
   */
  public static String generate(String host, int port) {
    Objects.requireNonNull(host, "host must not be null");

    // Hash the exact host:port combination so distinct combinations map to distinct digests and
    // the same combination always maps to the same digest.
    byte[] digest = sha256(host + ":" + port);

    // Encode the digest into the safe alphabet, then truncate to fit the remaining budget after
    // the prefix. The prefix guarantees a non-empty result and keeps the total within 23 chars.
    int suffixLength = MAX_LENGTH - PREFIX.length();
    String suffix = encode(digest, suffixLength);
    return PREFIX + suffix;
  }

  private static byte[] sha256(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return md.digest(input.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is a mandatory algorithm on every Java platform, so this is unreachable.
      throw new IllegalStateException("SHA-256 algorithm is not available", e);
    }
  }

  /**
   * Encodes the leading bytes of {@code digest} into exactly {@code length} characters drawn from
   * {@link #SAFE_ALPHABET}. Each output character is derived from one input byte, so the encoding
   * is deterministic and preserves the digest's distinctness across inputs.
   */
  private static String encode(byte[] digest, int length) {
    StringBuilder sb = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      int value = digest[i % digest.length] & 0xFF;
      sb.append(SAFE_ALPHABET[value % SAFE_ALPHABET.length]);
    }
    return sb.toString();
  }
}
