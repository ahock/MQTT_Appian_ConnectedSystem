package de.hock.mqtt.template;

import com.appian.connectedsystems.simplified.sdk.SimpleConnectedSystemTemplate;
import com.appian.connectedsystems.simplified.sdk.configuration.SimpleConfiguration;
import com.appian.connectedsystems.templateframework.sdk.ExecutionContext;
import com.appian.connectedsystems.templateframework.sdk.TemplateId;
import com.appian.connectedsystems.templateframework.sdk.configuration.BooleanPropertyDescriptor;
import com.appian.connectedsystems.templateframework.sdk.configuration.EncryptedTextPropertyDescriptor;
import com.appian.connectedsystems.templateframework.sdk.configuration.IntegerPropertyDescriptor;
import com.appian.connectedsystems.templateframework.sdk.configuration.PropertyDescriptor;
import com.appian.connectedsystems.templateframework.sdk.configuration.PropertyPath;
import com.appian.connectedsystems.templateframework.sdk.configuration.PropertyState;
import com.appian.connectedsystems.templateframework.sdk.configuration.TextPropertyDescriptor;

/**
 * Connected System template declaring the broker configuration of the minimal smoke-test build.
 *
 * <p>This is the design-time configuration surface a Designer fills in once on an <em>MQTT
 * Broker</em> Connected System object; both the publish and the subscribe integration then reuse
 * it. The build declares nine fields — broker host, broker port, client identifier, username,
 * password, connection timeout, keep-alive interval, clean session and automatic reconnect — every
 * one of which reaches Paho at run time. TLS and Last Will and Testament are not present; the full
 * 1.0.3 surface is preserved in git commit {@code d023a76}.
 *
 * <p><strong>SDK shape.</strong> The class extends {@link SimpleConnectedSystemTemplate} from
 * {@code com.appian:connected-systems-client} and declares its fields in
 * {@link #getConfiguration(SimpleConfiguration, ExecutionContext)}. The facade owns the two things
 * the low-level {@code ConnectedSystemTemplate} interface leaves to the implementer: it generates
 * the root {@link PropertyState} from the declared properties (tolerating the {@code null} state
 * Appian supplies for a brand-new connected system) and registers the generated root
 * {@code LocalTypeDescriptor} in the descriptor's type map. Implementing the low-level interface
 * instead is what made {@code ConfigurationDescriptor.getRootState()} {@code null} and broke the
 * New &gt; Connected System form. Do not revert to it.
 *
 * <p><strong>No field here may be expressionable.</strong> Appian's
 * {@code ConnectedSystemPropertiesValidator.checkPropertiesModifiers} rejects the whole descriptor
 * at conversion time with "Property Descriptors in Connected System Template must not be
 * Expressionable (APNX-1-4198-000)", so New &gt; Connected System then fails to render. This class
 * therefore never touches the expressionable modifier at all — the base builder already seeds the
 * flag {@code false}, and the absence of that builder call in this file is itself a checked
 * invariant. The restriction is specific to connected systems: integration inputs may be
 * expressionable, and the publish integration's three inputs and the subscribe integration's four
 * deliberately are.
 *
 * <p>A second trap follows from the same rule: some SDK descriptor builders set the flag
 * themselves and refuse to build without it. {@code DocumentPropertyDescriptor} and
 * {@code AppianValuePropertyDescriptor} seed it in their constructor and reject a cleared flag in
 * {@code checkValidState}, and {@code ListTypePropertyDescriptor} couples it to an
 * {@code EXPRESSION} display hint — so those types are unusable on a connected system template.
 * Only {@link TextPropertyDescriptor}, {@link IntegerPropertyDescriptor},
 * {@link BooleanPropertyDescriptor} and {@link EncryptedTextPropertyDescriptor} are used below;
 * each of those base builders seeds the modifier {@code false} and none forces it on.
 *
 * <p><strong>There is no design-time validation here, and there must not be.</strong> Release 1.0.5
 * ran one deliberately ungated, as a probe: the 26.9 restrictions page says {@code setErrors} is
 * ignored on a connected system, but this plug-in attached errors through
 * {@code PropertyState.setErrorAtPath}, a different method, so only observation could settle whether
 * field errors display at all. Observed on a live Appian 26.9 instance: <em>they do not</em>. The
 * page renders normally and the messages are discarded, so the restriction covers
 * {@code setErrorAtPath} as well. 1.0.6 therefore removed the clear-and-attach machinery entirely
 * and moved the same rules to invocation time in {@link de.hock.mqtt.ConnectionConfigValidator},
 * which both integrations call before they contact a broker. {@code isRequired} below is the only
 * design-time guard Appian honours; a brand-new connected system now opens clean.
 *
 * <p>The {@code password} field is an {@link EncryptedTextPropertyDescriptor} with
 * {@code masked(true)}, so the platform stores it encrypted and does not render it back after save.
 */
@TemplateId(name = "MqttConnectedSystemTemplate")
public class MqttConnectedSystemTemplate extends SimpleConnectedSystemTemplate {

  /** Field key: broker host name or address. */
  public static final String BROKER_HOST = "brokerHost";

  /** Field key: broker TCP port. */
  public static final String BROKER_PORT = "brokerPort";

  /** Field key: MQTT client identifier; auto-generated by Paho when left blank. */
  public static final String CLIENT_IDENTIFIER = "clientIdentifier";

  /** Field key: broker user name. */
  public static final String USERNAME = "username";

  /** Field key: broker password, stored encrypted and masked. */
  public static final String PASSWORD = "password";

  /** Field key: connect timeout in seconds; defaults to 5 when left blank. */
  public static final String CONNECTION_TIMEOUT_SECONDS = "connectionTimeoutSeconds";

  /** Field key: keep-alive interval in seconds; defaults to Paho's own 60 when left blank. */
  public static final String KEEP_ALIVE_SECONDS = "keepAliveSeconds";

  /** Field key: MQTT clean session flag. */
  public static final String CLEAN_SESSION = "cleanSession";

  /** Field key: Paho automatic reconnect flag. */
  public static final String AUTO_RECONNECT_ENABLED = "autoReconnectEnabled";

  /**
   * Declares the nine configuration fields.
   *
   * <p>{@link SimpleConfiguration#setProperties(PropertyDescriptor...)} builds the root local type,
   * registers it in the descriptor's type map, and generates the root {@link PropertyState} from it
   * — including on the very first render, where Appian hands in a descriptor whose root state is
   * {@code null}. Nothing else happens here: declaring the properties is the whole design-time
   * contract now that attached field errors are known to be discarded.
   *
   * @param configuration the configuration wrapper for the (possibly empty) incoming descriptor
   * @param executionContext the design-time execution context, unused
   * @return the same configuration with the nine properties declared
   */
  @Override
  protected SimpleConfiguration getConfiguration(
      SimpleConfiguration configuration, ExecutionContext executionContext) {

    configuration.setProperties(
        TextPropertyDescriptor.builder()
            .key(BROKER_HOST)
            .label("Broker Host")
            .instructionText("MQTT broker host name or address.")
            .isRequired(true)
            .build(),
        IntegerPropertyDescriptor.builder()
            .key(BROKER_PORT)
            .label("Broker Port")
            .instructionText("Broker TCP port. Typically 1883 for a plain TCP connection.")
            .placeholder("1883")
            .isRequired(true)
            .build(),
        TextPropertyDescriptor.builder()
            .key(CLIENT_IDENTIFIER)
            .label("Client Identifier")
            .instructionText("MQTT client identifier. Auto-generated when left blank.")
            .isRequired(false)
            .build(),
        TextPropertyDescriptor.builder()
            .key(USERNAME)
            .label("Username")
            .instructionText(
                "Broker user name. Leave username and password blank for an anonymous connection.")
            .isRequired(false)
            .build(),
        EncryptedTextPropertyDescriptor.builder()
            .key(PASSWORD)
            .label("Password")
            .instructionText("Broker password. Stored encrypted and masked.")
            .masked(true)
            .isRequired(false)
            .build(),
        IntegerPropertyDescriptor.builder()
            .key(CONNECTION_TIMEOUT_SECONDS)
            .label("Connection Timeout")
            .instructionText(
                "Connect timeout in seconds, 1 to 300. Defaults to 5 seconds when left blank, so an"
                    + " unreachable broker fails fast.")
            .placeholder("5")
            .isRequired(false)
            .build(),
        IntegerPropertyDescriptor.builder()
            .key(KEEP_ALIVE_SECONDS)
            .label("Keep Alive Interval")
            .instructionText(
                "Keep-alive interval in seconds, 0 to 65535, where 0 disables MQTT keep-alive."
                    + " Defaults to the MQTT client's own 60 seconds when left blank.")
            .placeholder("60")
            .isRequired(false)
            .build(),
        BooleanPropertyDescriptor.builder()
            .key(CLEAN_SESSION)
            .label("Clean Session")
            .instructionText(
                "Start a clean MQTT session rather than resuming a persistent one. Treated as true"
                    + " when no value is stored at all.")
            .isRequired(false)
            .build(),
        BooleanPropertyDescriptor.builder()
            .key(AUTO_RECONNECT_ENABLED)
            .label("Automatic Reconnect")
            .instructionText(
                "Let the MQTT client reconnect automatically. Defaults to false. A publish connects,"
                    + " sends and disconnects within one request, so this has very little room to"
                    + " act.")
            .isRequired(false)
            .build());

    return configuration;
  }

  /**
   * Reads a field value as text.
   *
   * <p>Text fields come back as {@link String}; an encrypted field may come back as either a
   * {@link String} or a {@code char[]}; an integer field comes back as a boxed number. A field that
   * was never set reads {@code null}, so blank-versus-set checks behave correctly.
   *
   * @param state the state to read from
   * @param key the field key
   * @return the string form of the value, or {@code null} when unset
   */
  public static String textValue(PropertyState state, String key) {
    Object value = state == null ? null : state.getValueAtPath(new PropertyPath(key));
    if (value == null) {
      return null;
    }
    if (value instanceof String s) {
      return s;
    }
    if (value instanceof char[] chars) {
      return new String(chars);
    }
    return String.valueOf(value);
  }

  /**
   * Reads an optional integer field, distinguishing "not supplied" from a supplied value.
   *
   * <p>The SDK seeds an {@code INTEGER} property to {@code null}, so a field the designer never
   * touched reads {@code null} here — which is what both the validator (skip the rule) and the
   * publisher (apply the documented default) need.
   *
   * @param state the state to read from
   * @param key the field key
   * @return the value, or {@code null} when unset, blank or not a whole number
   */
  public static Integer integerValue(PropertyState state, String key) {
    String text = textValue(state, key);
    if (text == null || text.isBlank()) {
      return null;
    }
    try {
      return Integer.valueOf(text.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Reads an optional boolean field, distinguishing "not supplied" from {@code false}.
   *
   * <p>Tolerant of both shapes a boolean field can come back as: a boxed {@link Boolean} and the
   * text {@code "true"} / {@code "false"}. Note that the SDK seeds a {@code BOOLEAN} property to
   * {@code Boolean.FALSE}, so {@code null} is returned only for a configuration that genuinely
   * carries no value for the key — for instance one saved by 1.0.4, before the field existed.
   *
   * @param state the state to read from
   * @param key the field key
   * @return {@link Boolean#TRUE} or {@link Boolean#FALSE}, or {@code null} when unset or
   *     unrecognised
   */
  public static Boolean booleanValue(PropertyState state, String key) {
    Object value = state == null ? null : state.getValueAtPath(new PropertyPath(key));
    if (value instanceof Boolean b) {
      return b;
    }
    if (value instanceof String s) {
      String trimmed = s.trim();
      if ("true".equalsIgnoreCase(trimmed)) {
        return Boolean.TRUE;
      }
      if ("false".equalsIgnoreCase(trimmed)) {
        return Boolean.FALSE;
      }
    }
    return null;
  }

  /**
   * Reads a field value as an {@code int}, falling back when it is blank or not a whole number.
   *
   * @param state the state to read from
   * @param key the field key
   * @param fallback the value to use when the field is blank or unparseable
   * @return the parsed value, or {@code fallback}
   */
  public static int intValue(PropertyState state, String key, int fallback) {
    String text = textValue(state, key);
    if (text == null || text.isBlank()) {
      return fallback;
    }
    try {
      return Integer.parseInt(text.trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }
}
