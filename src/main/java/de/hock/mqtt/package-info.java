/**
 * Appian Connected System plug-in for MQTT.
 *
 * <p>This base package holds the whole plug-in: {@link de.hock.mqtt.MqttPublisher} and
 * {@link de.hock.mqtt.MqttSubscriber}, the two Eclipse Paho call sites, and
 * {@link de.hock.mqtt.ConnectionConfigValidator}, the run-time configuration check both of them are
 * gated by — plus the Connected System template in {@code de.hock.mqtt.template} and the two
 * integrations in {@code de.hock.mqtt.integration}.
 *
 * <p>1.0.4 proved four things on a live Appian 26.9 instance: that the plug-in installs, that the
 * bundled Paho library loads and is usable at run time, that a Connected System configuration form
 * renders, and that one integration can publish to a real broker. 1.0.5 added four session settings
 * that each reach {@code MqttConnectOptions}, plus a deliberately ungated design-time validation
 * probe whose answer was negative: Appian discards field errors attached to a connected system.
 *
 * <p>1.0.6 acts on that answer and adds the second integration. Design-time validation is gone; the
 * same rules now run at invocation time in {@link de.hock.mqtt.ConnectionConfigValidator}, and
 * {@code isRequired} is the only design-time guard left. The new
 * {@code de.hock.mqtt.integration.MqttSubscribeIntegration} collects messages from one topic filter
 * within bounded count and time limits.
 *
 * <p>1.0.7 adds <strong>server-side</strong> TLS in {@link de.hock.mqtt.TlsSupport}: two new
 * connected system fields take it to eleven, the broker URI becomes {@code ssl://} when TLS is on,
 * and the trust material is assembled entirely in memory and handed to Paho as a socket factory —
 * never through JVM-global settings and never through a key store on disk, both of which Appian's
 * plug-in policies forbid. The broker's host name is verified, which was measured rather than
 * assumed.
 *
 * <p><strong>Mutual</strong> TLS (a client certificate and private key), Last Will and Testament,
 * multi-topic subscribe and the diagnostics/redaction layer remain deliberately absent; the complete
 * 1.0.3 source is archived at git commit {@code d023a76}.
 */
package de.hock.mqtt;
