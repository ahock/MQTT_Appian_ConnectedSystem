package de.hock.mqtt.protocol;

import java.util.Objects;
import java.util.function.BiConsumer;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

/**
 * Production {@link MqttClientAdapter} backed by an Eclipse Paho
 * {@code org.eclipse.paho.client.mqttv3.MqttAsyncClient}.
 *
 * <p>Each adapter method issues the matching asynchronous Paho call and then
 * {@linkplain org.eclipse.paho.client.mqttv3.IMqttToken#waitForCompletion() waits for completion},
 * turning the event-driven Paho API into the blocking, synchronous surface the
 * {@link MqttClientAdapter} contract (and Appian's request/response model) expects. Inbound messages
 * delivered through the Paho {@link MqttCallback} are forwarded to the registered message listener.
 *
 * <p>Requirements: 4.1, 5.2, 5.5, 6.2.
 */
public final class PahoMqttClientAdapter implements MqttClientAdapter {

  private final MqttAsyncClient client;

  private volatile BiConsumer<String, MqttMessage> messageListener;

  /**
   * Creates an adapter wrapping an existing Paho asynchronous client.
   *
   * @param client the Paho client to delegate to (must not be {@code null})
   */
  public PahoMqttClientAdapter(MqttAsyncClient client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  @Override
  public void setMessageListener(BiConsumer<String, MqttMessage> onMessage) {
    this.messageListener = Objects.requireNonNull(onMessage, "onMessage");
    client.setCallback(new ForwardingCallback());
  }

  @Override
  public void connect(MqttConnectOptions options) throws MqttException {
    Objects.requireNonNull(options, "options");
    client.connect(options).waitForCompletion();
  }

  @Override
  public void publish(String topic, byte[] payload, int qos, boolean retained)
      throws MqttException {
    MqttMessage message = new MqttMessage(payload == null ? new byte[0] : payload);
    message.setQos(qos);
    message.setRetained(retained);
    client.publish(topic, message).waitForCompletion();
  }

  @Override
  public void subscribe(String[] filters, int qos) throws MqttException {
    int[] qosLevels = new int[filters.length];
    for (int i = 0; i < qosLevels.length; i++) {
      qosLevels[i] = qos;
    }
    client.subscribe(filters, qosLevels).waitForCompletion();
  }

  @Override
  public void unsubscribe(String[] filters) throws MqttException {
    client.unsubscribe(filters).waitForCompletion();
  }

  @Override
  public boolean isConnected() {
    return client.isConnected();
  }

  @Override
  public void disconnect() throws MqttException {
    client.disconnect().waitForCompletion();
  }

  /** Routes Paho delivery callbacks to the registered message listener. */
  private final class ForwardingCallback implements MqttCallback {

    @Override
    public void connectionLost(Throwable cause) {
      // Connection-loss handling (reconnect/DISCONNECTION mapping) is owned by the connection
      // manager; the adapter itself does not react to loss here.
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
      BiConsumer<String, MqttMessage> listener = messageListener;
      if (listener != null) {
        listener.accept(topic, message);
      }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
      // Delivery acknowledgment is observed via waitForCompletion() on the publish token; no
      // additional handling is required here.
    }
  }
}
