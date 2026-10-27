package de.hock.mqtt.diagnostics;

import java.net.SocketTimeoutException;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import de.hock.mqtt.MqttErrorCategory;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttSecurityException;

/**
 * Single, centralized translation of Eclipse Paho exceptions (and their reason codes) into the
 * fixed {@link MqttErrorCategory} enum.
 *
 * <p>Every failure surfaced by the connection manager or the integrations funnels through here so
 * that the exception-to-category mapping lives in exactly one place and is reused rather than
 * duplicated at each call site (Requirement 9.2, 9.6). An exception that does not match any known
 * shape maps to {@link MqttErrorCategory#UNKNOWN} rather than leaking an unclassified failure.
 *
 * <p>Mapping summary:
 *
 * <ul>
 *   <li>{@link MqttSecurityException} or the authentication reason codes
 *       ({@code REASON_CODE_FAILED_AUTHENTICATION}, {@code REASON_CODE_NOT_AUTHORIZED}) ->
 *       {@link MqttErrorCategory#AUTHENTICATION}.</li>
 *   <li>An SSL/TLS handshake or validation failure — whether surfaced directly as an
 *       {@link SSLException} cause or via {@code REASON_CODE_SSL_CONFIG_ERROR} /
 *       {@code REASON_CODE_SOCKET_FACTORY_MISMATCH} -> {@link MqttErrorCategory#TLS_VALIDATION}.</li>
 *   <li>A connect/socket timeout ({@code REASON_CODE_CLIENT_TIMEOUT},
 *       {@code REASON_CODE_SERVER_CONNECT_ERROR}, or a {@link SocketTimeoutException} cause) ->
 *       {@link MqttErrorCategory#CONNECTION_TIMEOUT}.</li>
 *   <li>A lost or dropped connection ({@code REASON_CODE_CONNECTION_LOST},
 *       {@code REASON_CODE_CLIENT_NOT_CONNECTED}) -> {@link MqttErrorCategory#DISCONNECTION}.</li>
 *   <li>A missing publish acknowledgment / delivery timeout ({@code REASON_CODE_WRITE_TIMEOUT},
 *       {@code REASON_CODE_MAX_INFLIGHT}) -> {@link MqttErrorCategory#DELIVERY_TIMEOUT}.</li>
 *   <li>Anything else -> {@link MqttErrorCategory#UNKNOWN}.</li>
 * </ul>
 *
 * <p>Requirements: 9.2, 9.6.
 */
public final class ExceptionCategoryTranslator {

  private ExceptionCategoryTranslator() {
    // Utility class: no instances.
  }

  /**
   * Translates a throwable raised during an MQTT operation into the fixed error category.
   *
   * <p>Both the throwable itself and its cause chain are inspected so that TLS/timeout failures that
   * Paho wraps inside an {@link MqttException} are still classified correctly. A {@code null} input
   * maps to {@link MqttErrorCategory#UNKNOWN}.
   *
   * @param throwable the failure to classify (may be {@code null})
   * @return the matching {@link MqttErrorCategory}, never {@code null}
   */
  public static MqttErrorCategory categorize(Throwable throwable) {
    if (throwable == null) {
      return MqttErrorCategory.UNKNOWN;
    }

    // Authentication is signalled either by the dedicated exception type or by auth reason codes.
    if (throwable instanceof MqttSecurityException) {
      return MqttErrorCategory.AUTHENTICATION;
    }

    // A TLS/SSL failure may surface anywhere in the cause chain, independent of Paho reason codes.
    if (hasCause(throwable, SSLException.class) || hasCause(throwable, SSLHandshakeException.class)) {
      return MqttErrorCategory.TLS_VALIDATION;
    }

    if (throwable instanceof MqttException mqttException) {
      MqttErrorCategory fromReasonCode = fromReasonCode(mqttException.getReasonCode());
      if (fromReasonCode != MqttErrorCategory.UNKNOWN) {
        return fromReasonCode;
      }
    }

    // A plain socket timeout (e.g. during connect) that was not wrapped with a timeout reason code.
    if (hasCause(throwable, SocketTimeoutException.class)) {
      return MqttErrorCategory.CONNECTION_TIMEOUT;
    }

    return MqttErrorCategory.UNKNOWN;
  }

  /**
   * Maps a Paho reason code to its error category, returning {@link MqttErrorCategory#UNKNOWN} for
   * codes that carry no specific classification here.
   *
   * @param reasonCode a Paho {@link MqttException} reason code
   * @return the matching category, or {@link MqttErrorCategory#UNKNOWN} if none applies
   */
  private static MqttErrorCategory fromReasonCode(int reasonCode) {
    switch (reasonCode) {
      case MqttException.REASON_CODE_FAILED_AUTHENTICATION:
      case MqttException.REASON_CODE_NOT_AUTHORIZED:
        return MqttErrorCategory.AUTHENTICATION;
      case MqttException.REASON_CODE_SSL_CONFIG_ERROR:
      case MqttException.REASON_CODE_SOCKET_FACTORY_MISMATCH:
        return MqttErrorCategory.TLS_VALIDATION;
      case MqttException.REASON_CODE_CLIENT_TIMEOUT:
      case MqttException.REASON_CODE_SERVER_CONNECT_ERROR:
        return MqttErrorCategory.CONNECTION_TIMEOUT;
      case MqttException.REASON_CODE_CONNECTION_LOST:
      case MqttException.REASON_CODE_CLIENT_NOT_CONNECTED:
        return MqttErrorCategory.DISCONNECTION;
      case MqttException.REASON_CODE_WRITE_TIMEOUT:
      case MqttException.REASON_CODE_MAX_INFLIGHT:
        return MqttErrorCategory.DELIVERY_TIMEOUT;
      default:
        return MqttErrorCategory.UNKNOWN;
    }
  }

  /**
   * Returns whether {@code throwable} or any throwable in its cause chain is an instance of the
   * given type. Guards against self-referential cause cycles.
   */
  private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
    Throwable current = throwable;
    while (current != null) {
      if (type.isInstance(current)) {
        return true;
      }
      Throwable next = current.getCause();
      if (next == current) {
        break;
      }
      current = next;
    }
    return false;
  }
}
