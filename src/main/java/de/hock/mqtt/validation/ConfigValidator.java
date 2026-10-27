package de.hock.mqtt.validation;

import com.appian.connectedsystems.templateframework.sdk.configuration.PropertyPath;
import com.appian.connectedsystems.templateframework.sdk.configuration.PropertyState;
import de.hock.mqtt.template.MqttConnectedSystemTemplate;

/**
 * Design-time validation of the MQTT Broker connected system configuration.
 *
 * <p><strong>This runs on every render, including the very first one. That is deliberate and must
 * not be "fixed".</strong> A brand-new connected system therefore opens with errors already showing
 * on Broker Host and Broker Port. Release 1.0.5 exists to settle a question open since 1.0.0: the
 * Appian 26.9 restrictions page states that {@code setErrors} is ignored on a connected system
 * template, but this plug-in attaches errors through {@link PropertyState#setErrorAtPath}, a
 * different method, so the documentation cannot settle whether field errors display at all. Running
 * validation ungated converts that question into one unambiguous observation: the designer opens
 * <em>New &gt; Connected System &gt; MQTT Broker</em> and either sees "Broker host is required." or
 * does not. A first-render gate — a freshness signal, an "untouched" check, or seeded default values
 * — would make a clean page ambiguous between "the gate worked" and "Appian discards our errors",
 * which is precisely the information being sought. Once the answer is known, 1.0.6 can refine the
 * experience. The 1.0.3 build gated this on a freshness signal derived from whether
 * {@code cleanSession} carried a generated value, which rested on an unverified assumption about
 * Appian round-tripping the descriptor the template last returned; that mechanism is not coming
 * back.
 *
 * <p><strong>Clear, then attach.</strong> {@link #validate(PropertyState)} calls
 * {@link PropertyState#clearErrorsAtPath(PropertyPath)} for <em>every</em> validated key before it
 * evaluates anything, then re-attaches only what still fails. The clearing pass is not optional:
 * errors survive a re-render because the SDK's {@code StateGenerator.clone} copies them into the
 * regenerated state, so {@code SimpleConfiguration.setProperties} preserves every error already
 * present in the incoming descriptor. The 1.0.3 build only ever attached, never cleared, so an error
 * raised on one render stayed visible after the designer corrected the value.
 *
 * <p><strong>Never call {@code addErrors} after a clear.</strong> {@code clearErrorsAtPath} installs
 * the immutable {@code Collections.emptyList()}, so a subsequent {@code addErrors} throws
 * {@link UnsupportedOperationException}. {@code setErrorAtPath} replaces the list with a fresh one
 * and is safe; it is the only attach call used here.
 *
 * <p><strong>Ordering.</strong> The SDK's {@code setErrorsAtPath} silently does nothing when the
 * path does not resolve, so validation must run <em>after</em>
 * {@code SimpleConfiguration.setProperties} has generated the nested per-field states.
 *
 * <p>Every message is attached to its own field path, never to the root, so each message appears
 * under the field it concerns. Optional fields left blank produce no error: a {@code null} integer
 * is accepted silently and only a supplied out-of-range value is reported.
 */
public final class ConfigValidator {

  /** Lowest valid TCP port. */
  private static final int PORT_MIN = 1;

  /** Highest valid TCP port. */
  private static final int PORT_MAX = 65535;

  /** Lowest accepted connect timeout, in seconds. */
  private static final int TIMEOUT_MIN = 1;

  /**
   * Highest accepted connect timeout, in seconds. Five minutes is already far longer than an Appian
   * request should wait on an unreachable broker; the bound exists to catch a mistyped value.
   */
  private static final int TIMEOUT_MAX = 300;

  /** Lowest accepted keep-alive interval. {@code 0} is legal MQTT and disables keep-alive. */
  private static final int KEEP_ALIVE_MIN = 0;

  /** Highest accepted keep-alive interval, in seconds — the MQTT protocol's own 16-bit ceiling. */
  private static final int KEEP_ALIVE_MAX = 65535;

  private ConfigValidator() {
    // Utility class: no instances.
  }

  /**
   * Validates the connected system configuration, clearing previous messages first.
   *
   * <p>Rules, each reported on its own field path:
   *
   * <ul>
   *   <li>{@code brokerHost} — required, non-blank after trimming.
   *   <li>{@code brokerPort} — required, {@value #PORT_MIN}–{@value #PORT_MAX}.
   *   <li>{@code connectionTimeoutSeconds} — optional; when supplied, {@value #TIMEOUT_MIN}–{@value
   *       #TIMEOUT_MAX} seconds.
   *   <li>{@code keepAliveSeconds} — optional; when supplied, {@value #KEEP_ALIVE_MIN}–{@value
   *       #KEEP_ALIVE_MAX} seconds, where {@code 0} disables MQTT keep-alive.
   * </ul>
   *
   * <p>{@code clientIdentifier}, {@code username}, {@code password}, {@code cleanSession} and
   * {@code autoReconnectEnabled} are optional and unvalidated.
   *
   * @param rootState the generated root state of the configuration descriptor; {@code null} is
   *     tolerated and validated as a no-op
   */
  public static void validate(PropertyState rootState) {
    if (rootState == null) {
      return;
    }

    clear(rootState, MqttConnectedSystemTemplate.BROKER_HOST);
    clear(rootState, MqttConnectedSystemTemplate.BROKER_PORT);
    clear(rootState, MqttConnectedSystemTemplate.CONNECTION_TIMEOUT_SECONDS);
    clear(rootState, MqttConnectedSystemTemplate.KEEP_ALIVE_SECONDS);

    String brokerHost =
        MqttConnectedSystemTemplate.textValue(rootState, MqttConnectedSystemTemplate.BROKER_HOST);
    if (brokerHost == null || brokerHost.isBlank()) {
      attach(rootState, MqttConnectedSystemTemplate.BROKER_HOST, "Broker host is required.");
    }

    Integer brokerPort =
        MqttConnectedSystemTemplate.integerValue(rootState, MqttConnectedSystemTemplate.BROKER_PORT);
    if (brokerPort == null) {
      attach(rootState, MqttConnectedSystemTemplate.BROKER_PORT, "Broker port is required.");
    } else if (brokerPort < PORT_MIN || brokerPort > PORT_MAX) {
      attach(
          rootState,
          MqttConnectedSystemTemplate.BROKER_PORT,
          "Broker port must be between " + PORT_MIN + " and " + PORT_MAX + ".");
    }

    Integer connectionTimeoutSeconds =
        MqttConnectedSystemTemplate.integerValue(
            rootState, MqttConnectedSystemTemplate.CONNECTION_TIMEOUT_SECONDS);
    if (connectionTimeoutSeconds != null
        && (connectionTimeoutSeconds < TIMEOUT_MIN || connectionTimeoutSeconds > TIMEOUT_MAX)) {
      attach(
          rootState,
          MqttConnectedSystemTemplate.CONNECTION_TIMEOUT_SECONDS,
          "Connection timeout must be between "
              + TIMEOUT_MIN
              + " and "
              + TIMEOUT_MAX
              + " seconds.");
    }

    Integer keepAliveSeconds =
        MqttConnectedSystemTemplate.integerValue(
            rootState, MqttConnectedSystemTemplate.KEEP_ALIVE_SECONDS);
    if (keepAliveSeconds != null
        && (keepAliveSeconds < KEEP_ALIVE_MIN || keepAliveSeconds > KEEP_ALIVE_MAX)) {
      attach(
          rootState,
          MqttConnectedSystemTemplate.KEEP_ALIVE_SECONDS,
          "Keep alive interval must be between "
              + KEEP_ALIVE_MIN
              + " and "
              + KEEP_ALIVE_MAX
              + " seconds.");
    }
  }

  /** Drops any message left on {@code key} by an earlier render. */
  private static void clear(PropertyState rootState, String key) {
    rootState.clearErrorsAtPath(new PropertyPath(key));
  }

  /** Attaches {@code message} to {@code key} alone, replacing whatever the clear pass left. */
  private static void attach(PropertyState rootState, String key, String message) {
    rootState.setErrorAtPath(new PropertyPath(key), message);
  }
}
