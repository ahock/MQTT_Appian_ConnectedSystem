package de.hock.mqtt.diagnostics;

import com.appian.connectedsystems.templateframework.sdk.diagnostics.IntegrationDesignerDiagnostic;
import de.hock.mqtt.MqttConnectionConfig;
import de.hock.mqtt.MqttErrorCategory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Accumulates the diagnostic entries recorded for a single MQTT integration operation and produces
 * the diagnostics representation attached to the operation's {@code IntegrationResponse}.
 *
 * <p>For every executed operation the builder records the broker host, the operation name, and the
 * operation outcome (success or failure), and on failure it additionally records the fixed
 * {@link MqttErrorCategory} (Requirement 9.3, 9.5). A {@link Redactor} built from the operation's
 * configuration scrubs configured sensitive values (usernames, passwords, private keys) from every
 * recorded diagnostic value and from any error-message string, so those values can never appear in
 * the diagnostics, the returned error message, or the returned error category (Requirement 9.4).
 *
 * <p>Typical use by the operation layer (tasks 8.2 / 10 / 11):
 *
 * <pre>{@code
 * DiagnosticsBuilder diag = DiagnosticsBuilder.forConfig(config)
 *     .operation("Publish")
 *     .brokerHost(config.brokerHost());
 * // ... run the operation ...
 * if (ok) {
 *   diag.success();
 * } else {
 *   diag.failure(MqttErrorCategory.AUTHENTICATION);
 * }
 * String safeMessage = diag.redact(rawErrorMessage);     // scrub before returning
 * IntegrationDesignerDiagnostic d = diag.buildDesignerDiagnostic();
 * }</pre>
 *
 * <p>Instances are mutable and intended to be used by a single operation on a single thread.
 */
public final class DiagnosticsBuilder {

  /** Diagnostics key under which the broker host is recorded. */
  public static final String KEY_BROKER_HOST = "brokerHost";
  /** Diagnostics key under which the operation name is recorded. */
  public static final String KEY_OPERATION = "operation";
  /** Diagnostics key under which the operation outcome is recorded. */
  public static final String KEY_OUTCOME = "outcome";
  /** Diagnostics key under which the error category is recorded on failure. */
  public static final String KEY_ERROR_CATEGORY = "errorCategory";

  /** Outcome value recorded for a successful operation. */
  public static final String OUTCOME_SUCCESS = "SUCCESS";
  /** Outcome value recorded for a failed operation. */
  public static final String OUTCOME_FAILURE = "FAILURE";

  private final Redactor redactor;
  private String brokerHost;
  private String operation;
  private String outcome;
  private MqttErrorCategory errorCategory;

  private DiagnosticsBuilder(Redactor redactor) {
    this.redactor = redactor;
  }

  /**
   * Creates a builder whose redactor scrubs the sensitive values of the given configuration.
   *
   * @param config the operation's connection configuration (may be {@code null})
   * @return a new diagnostics builder
   */
  public static DiagnosticsBuilder forConfig(MqttConnectionConfig config) {
    return new DiagnosticsBuilder(Redactor.forConfig(config));
  }

  /**
   * Creates a builder with an explicit redactor.
   *
   * @param redactor the redactor to apply to all recorded values and messages (non-{@code null})
   * @return a new diagnostics builder
   */
  public static DiagnosticsBuilder withRedactor(Redactor redactor) {
    if (redactor == null) {
      throw new IllegalArgumentException("redactor must not be null");
    }
    return new DiagnosticsBuilder(redactor);
  }

  /**
   * Records the broker host (Requirement 9.3).
   *
   * @param host the broker host name or address
   * @return this builder
   */
  public DiagnosticsBuilder brokerHost(String host) {
    this.brokerHost = host;
    return this;
  }

  /**
   * Records the operation name (Requirement 9.3).
   *
   * @param operationName the operation name, for example {@code "Publish"} or {@code "Subscribe"}
   * @return this builder
   */
  public DiagnosticsBuilder operation(String operationName) {
    this.operation = operationName;
    return this;
  }

  /**
   * Records a successful outcome (Requirement 9.3). Clears any previously recorded error category.
   *
   * @return this builder
   */
  public DiagnosticsBuilder success() {
    this.outcome = OUTCOME_SUCCESS;
    this.errorCategory = null;
    return this;
  }

  /**
   * Records a failed outcome together with the fixed error category (Requirement 9.5). A
   * {@code null} category is normalized to {@link MqttErrorCategory#UNKNOWN} so a failure is never
   * left uncategorized.
   *
   * @param category the error category classifying the failure
   * @return this builder
   */
  public DiagnosticsBuilder failure(MqttErrorCategory category) {
    this.outcome = OUTCOME_FAILURE;
    this.errorCategory = category == null ? MqttErrorCategory.UNKNOWN : category;
    return this;
  }

  /**
   * Redacts an arbitrary error-message string so no configured sensitive value appears in it
   * (Requirement 9.4). Returns {@code null} unchanged.
   *
   * @param message the raw message to scrub
   * @return the redacted message
   */
  public String redact(String message) {
    return redactor.redact(message);
  }

  /** @return the redactor used by this builder */
  public Redactor redactor() {
    return redactor;
  }

  /**
   * Produces the final diagnostics as a redacted, insertion-ordered map. The broker host, operation
   * name, and outcome are included when recorded, and the error category is included on failure
   * (Requirement 9.3, 9.5). Every value is passed through the redactor so sensitive values never
   * appear (Requirement 9.4).
   *
   * @return a new map of diagnostic entries
   */
  public Map<String, Object> buildDiagnosticsMap() {
    Map<String, Object> map = new LinkedHashMap<>();
    putRedacted(map, KEY_BROKER_HOST, brokerHost);
    putRedacted(map, KEY_OPERATION, operation);
    putRedacted(map, KEY_OUTCOME, outcome);
    if (errorCategory != null) {
      // The category comes from the fixed enum, so its name carries no sensitive text; it is still
      // routed through the redactor to honor Requirement 9.4 uniformly.
      putRedacted(map, KEY_ERROR_CATEGORY, errorCategory.name());
    }
    return map;
  }

  private void putRedacted(Map<String, Object> map, String key, String value) {
    if (value != null) {
      map.put(key, redactor.redact(value));
    }
  }

  /**
   * Produces the Appian {@link IntegrationDesignerDiagnostic} carrying the redacted diagnostics map
   * as a response diagnostic. The integration layer attaches this to its {@code IntegrationResponse}.
   *
   * @return the designer diagnostic for this operation
   */
  public IntegrationDesignerDiagnostic buildDesignerDiagnostic() {
    return IntegrationDesignerDiagnostic.builder()
        .addResponseDiagnostic(buildDiagnosticsMap())
        .build();
  }
}
