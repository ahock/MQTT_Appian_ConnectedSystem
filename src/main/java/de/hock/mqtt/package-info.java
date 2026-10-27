/**
 * Appian Connected System plug-in for MQTT — minimal smoke-test build.
 *
 * <p>This base package holds the whole plug-in: {@link de.hock.mqtt.MqttPublisher}, the single
 * Eclipse Paho call site, plus the Connected System template in
 * {@code de.hock.mqtt.template} and the one publish integration in {@code de.hock.mqtt.integration}.
 *
 * <p>The build exists to prove four things and nothing more: that the plug-in installs on Appian
 * 26.9, that the bundled Paho library loads and is usable at run time, that a Connected System
 * configuration form renders with a few basic parameters, and that one integration can publish to a
 * real broker. TLS and certificates, the subscribe integration, Last Will and Testament,
 * reconnection and session tuning, the diagnostics/redaction layer and design-time validation were
 * all deliberately removed; the complete 1.0.3 source is archived at
 * {@code .backup/mqtt-plugin-FULL-1.0.3-src.zip}.
 */
package de.hock.mqtt;
