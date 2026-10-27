package de.hock.mqtt.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.appian.connectedsystems.templateframework.sdk.configuration.AppianValuePropertyDescriptor;
import com.appian.connectedsystems.templateframework.sdk.configuration.ConfigurationDescriptor;
import com.appian.connectedsystems.templateframework.sdk.configuration.DocumentPropertyDescriptor;
import com.appian.connectedsystems.templateframework.sdk.configuration.ListTypePropertyDescriptor;
import com.appian.connectedsystems.templateframework.sdk.configuration.LocalTypeDescriptor;
import com.appian.connectedsystems.templateframework.sdk.configuration.PropertyDescriptor;
import com.appian.connectedsystems.templateframework.sdk.configuration.PropertyPath;
import com.appian.connectedsystems.templateframework.sdk.configuration.PropertyState;
import com.appian.connectedsystems.templateframework.sdk.configuration.RefreshPolicy;
import de.hock.mqtt.integration.MqttPublishIntegration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Design-time descriptor tests for the minimal smoke-test build.
 *
 * <p>Two platform contracts broke this plug-in before, and both are pinned here.
 *
 * <p>The first was a {@code NullPointerException}: opening <em>New &gt; Connected System</em> hands
 * the template a {@link ConfigurationDescriptor} whose root {@link PropertyState} is {@code null},
 * the low-level template dereferenced it, and the configuration form failed with an
 * expression-evaluation error. {@code ConfigurationDescriptor.builder().build()} is exactly that
 * input, so every test here drives the real framework entry point
 * ({@code getConfigurationDescriptor}, inherited from the {@code Simple*} base classes) with it.
 *
 * <p>The second was the <em>descriptor-modifier</em> rule, which only a real Appian instance
 * enforces: a connected system template must declare no expressionable property descriptor (Appian
 * rejects the whole descriptor with "Property Descriptors in Connected System Template must not be
 * Expressionable"), while integration inputs are permitted to be expressionable. Both halves are
 * asserted by reading the modifier back through the SDK's public {@link PropertyDescriptor}
 * accessors — the same object graph Appian's validator walks — rather than by inspecting source
 * text. A source grep would not have caught the forced-expressionable document fields that broke
 * 1.0.2.
 *
 * <p>No Appian runtime is involved: the SDK's configuration model is plain Java, and the
 * {@code ExecutionContext} is unused on this path, so it is passed as {@code null}. Nothing here
 * needs a broker.
 */
class MqttConnectedSystemTemplateDescriptorTest {

  /** The nine configuration fields the Connected System declares, in descriptor order. */
  private static final List<String> CONFIG_FIELD_KEYS =
      List.of(
          MqttConnectedSystemTemplate.BROKER_HOST,
          MqttConnectedSystemTemplate.BROKER_PORT,
          MqttConnectedSystemTemplate.CLIENT_IDENTIFIER,
          MqttConnectedSystemTemplate.USERNAME,
          MqttConnectedSystemTemplate.PASSWORD,
          MqttConnectedSystemTemplate.CONNECTION_TIMEOUT_SECONDS,
          MqttConnectedSystemTemplate.KEEP_ALIVE_SECONDS,
          MqttConnectedSystemTemplate.CLEAN_SESSION,
          MqttConnectedSystemTemplate.AUTO_RECONNECT_ENABLED);

  /** The descriptor Appian supplies for a brand-new object: its root state is {@code null}. */
  private static ConfigurationDescriptor emptyDescriptor() {
    return ConfigurationDescriptor.builder().build();
  }

  private static ConfigurationDescriptor renderConnectedSystem() {
    return new MqttConnectedSystemTemplate()
        .getConfigurationDescriptor(emptyDescriptor(), new PropertyPath(), null);
  }

  private static ConfigurationDescriptor renderPublish() {
    return new MqttPublishIntegration()
        .getConfigurationDescriptor(
            emptyDescriptor(), emptyDescriptor(), new PropertyPath(), null);
  }

  /** Resolves the root {@link LocalTypeDescriptor} a rendered descriptor registered. */
  private static LocalTypeDescriptor rootTypeOf(ConfigurationDescriptor rendered) {
    LocalTypeDescriptor rootType =
        rendered.getTypes().get(rendered.getRootState().getType().getUnqualifiedTypeName());
    assertNotNull(rootType, "the root state's type must resolve in the descriptor's type map");
    return rootType;
  }

  /**
   * Reads the expressionable modifier back through the SDK's public descriptor API, failing loudly
   * on a renamed key rather than silently passing.
   */
  private static boolean expressionable(LocalTypeDescriptor rootType, String key) {
    PropertyDescriptor<?> descriptor =
        rootType
            .getProperty(key)
            .orElseThrow(() -> new AssertionError("no such input descriptor: " + key));
    return descriptor.isExpressionable();
  }

  /**
   * The NPE regression guard, and the most valuable test in this suite: a brand-new connected
   * system must render a non-null root state whose type resolves.
   */
  @Test
  void brandNewConnectedSystemRendersANonNullRootStateWithAResolvableRootType() {
    ConfigurationDescriptor incoming = emptyDescriptor();
    // Guards the premise: this is the state Appian supplies on New > Connected System.
    assertTrue(incoming.getRootState() == null, "a new connected system has no root state yet");

    ConfigurationDescriptor rendered =
        new MqttConnectedSystemTemplate()
            .getConfigurationDescriptor(incoming, new PropertyPath(), null);

    assertNotNull(
        rendered.getRootState(),
        "root state must be generated, or the configuration UI fails with an NPE on getRootState()");
    String rootTypeName = rendered.getRootState().getType().getUnqualifiedTypeName();
    assertNotNull(rootTypeName, "root state must carry a type reference");
    assertFalse(rendered.getTypes().isEmpty(), "the descriptor must register its local types");
    assertTrue(
        rendered.getTypes().containsKey(rootTypeName),
        "the root state's type reference must resolve in the descriptor's type map");
  }

  /**
   * Exactly the nine expected fields, in order — which pins both "all nine present" and "no tenth
   * field crept back in".
   */
  @Test
  void exactlyTheNineExpectedConfigFieldKeysArePresent() {
    ConfigurationDescriptor rendered = renderConnectedSystem();
    PropertyState rootState = rendered.getRootState();

    assertEquals(
        CONFIG_FIELD_KEYS,
        rootTypeOf(rendered).getPropertyKeys(),
        "this build declares exactly these nine connected system fields, in this order");
    for (String key : CONFIG_FIELD_KEYS) {
      assertNotNull(
          rootState.getPropertyStateAtPath(new PropertyPath(key)),
          "generated state must contain field " + key);
    }
  }

  /**
   * The rule that broke New &gt; Connected System three times: Appian's
   * {@code ConnectedSystemPropertiesValidator.checkPropertiesModifiers} rejects a connected system
   * descriptor carrying <em>any</em> expressionable property, so the configuration form never
   * renders. This fails if {@code isExpressionable(true)} is reintroduced on any field.
   *
   * <p>It reports every offender rather than the first, because the modifier can also be set by the
   * SDK itself: {@code DocumentPropertyDescriptorBuilder}'s constructor seeds
   * {@code isExpressionable(true)} and its {@code checkValidState} refuses to build with it cleared
   * ({@code IllegalStateException: DOCUMENT property descriptors must be expressionable}), so such
   * a field is expressionable whether or not the source says so.
   */
  @Test
  void noConnectedSystemPropertyDescriptorIsExpressionable() {
    LocalTypeDescriptor rootType = rootTypeOf(renderConnectedSystem());

    List<String> offenders = new ArrayList<>();
    int inspected = 0;
    for (PropertyDescriptor<?> descriptor : rootType.getProperties()) {
      if (descriptor.isExpressionable()) {
        offenders.add(descriptor.getKey() + " (" + descriptor.getClass().getSimpleName() + ")");
      }
      inspected++;
    }

    assertEquals(9, inspected, "all nine connected system fields must have been inspected");
    assertTrue(
        offenders.isEmpty(),
        "Appian rejects a connected system template declaring any expressionable property "
            + "descriptor (APNX-1-4198-000), but these fields read back expressionable: "
            + offenders);
  }

  /**
   * The remaining documented connected-system descriptor rules: every field refreshes
   * {@link RefreshPolicy#NEVER}, no field is hidden, and no field uses a descriptor type whose
   * builder forces the expressionable flag on.
   */
  @Test
  void connectedSystemFieldsUseNoListDocumentOrAppianValueDescriptor() {
    for (PropertyDescriptor<?> descriptor : rootTypeOf(renderConnectedSystem()).getProperties()) {
      assertEquals(
          RefreshPolicy.NEVER,
          descriptor.getRefresh(),
          "connected system field " + descriptor.getKey() + " must use RefreshPolicy.NEVER");
      assertFalse(
          descriptor.isHidden(),
          "connected system field " + descriptor.getKey() + " must not be hidden");
      // These three descriptor types set isExpressionable(true) in their own builder constructors,
      // and Document/AppianValue additionally refuse to build with it cleared
      // (Guard.checkPropertyIsExpressionable). They are therefore unusable on a connected system
      // template, whatever the declaring code asks for.
      assertFalse(
          descriptor instanceof ListTypePropertyDescriptor,
          "connected system field " + descriptor.getKey() + " must not be LIST type");
      assertFalse(
          descriptor instanceof DocumentPropertyDescriptor,
          "connected system field "
              + descriptor.getKey()
              + " must not be a document field: the SDK forces document descriptors to be "
              + "expressionable, which Appian rejects on a connected system template");
      assertFalse(
          descriptor instanceof AppianValuePropertyDescriptor,
          "connected system field "
              + descriptor.getKey()
              + " must not be an Appian-value field: the SDK forces it to be expressionable");
    }
  }

  /**
   * The publish integration renders its own root state and its three inputs — and all three stay
   * expressionable, which is the deliberate asymmetry with the connected system above. Asserting it
   * per key stops a future repo-wide "expressionability cleanup" from stripping working integration
   * behavior.
   */
  @Test
  void publishIntegrationRendersItsRootStateAndItsThreeExpressionableInputs() {
    ConfigurationDescriptor rendered = renderPublish();

    assertNotNull(rendered.getRootState(), "publish integration root state");
    LocalTypeDescriptor rootType = rootTypeOf(rendered);
    assertEquals(
        List.of(
            MqttPublishIntegration.INPUT_TOPIC,
            MqttPublishIntegration.INPUT_PAYLOAD,
            MqttPublishIntegration.INPUT_QOS),
        rootType.getPropertyKeys(),
        "publish inputs, in display order");

    for (String key : rootType.getPropertyKeys()) {
      assertTrue(
          expressionable(rootType, key), "publish input " + key + " must stay expressionable");
    }
  }
}
