/**
 * Protocol layer for the MQTT Connected System plug-in.
 *
 * <p>Translates an immutable {@link de.hock.mqtt.MqttConnectionConfig} into Eclipse Paho client
 * state and performs the publish/subscribe operations against the broker. {@link
 * de.hock.mqtt.protocol.MqttSession} drives a single connected session through the {@link
 * de.hock.mqtt.protocol.MqttClientAdapter} seam, whose production implementation ({@link
 * de.hock.mqtt.protocol.PahoMqttClientAdapter}) wraps a Paho {@code MqttAsyncClient} while a fake
 * adapter can be substituted in property tests. New protocol concerns are added by introducing new
 * classes alongside these rather than editing existing ones.
 */
package de.hock.mqtt.protocol;
