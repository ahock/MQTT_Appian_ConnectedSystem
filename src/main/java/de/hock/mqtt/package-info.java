/**
 * Appian Connected System plug-in for MQTT — minimal smoke-test build.
 *
 * <p>This base package holds the whole plug-in: {@link de.hock.mqtt.MqttPublisher}, the single
 * Eclipse Paho call site, plus the Connected System template in {@code de.hock.mqtt.template}, its
 * design-time validation in {@code de.hock.mqtt.validation} and the one publish integration in
 * {@code de.hock.mqtt.integration}.
 *
 * <p>1.0.4 proved four things on a live Appian 26.9 instance: that the plug-in installs, that the
 * bundled Paho library loads and is usable at run time, that a Connected System configuration form
 * renders, and that one integration can publish to a real broker. 1.0.5 adds exactly two things to
 * that baseline — design-time validation, deliberately <strong>ungated</strong> as a probe for
 * whether Appian displays connected system field errors at all (see
 * {@code de.hock.mqtt.validation.ConfigValidator}), and four session settings that each reach
 * {@code MqttConnectOptions}.
 *
 * <p>TLS and certificates, the subscribe integration, Last Will and Testament and the
 * diagnostics/redaction layer remain deliberately removed; the complete 1.0.3 source is archived at
 * git commit {@code d023a76}.
 */
package de.hock.mqtt;
