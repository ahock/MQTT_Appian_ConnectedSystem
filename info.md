# MQTT Broker Connected System

An Appian Connected System plug-in for publishing messages to, and collecting messages from, an MQTT
broker.

- **Plug-in key:** `de.hock.mqtt`
- **Vendor:** Hock
- **Version:** 1.0.7
- **Minimum Appian version:** 26.9.0.0

**Built up from a proven baseline.** 1.0.4 validated that the plug-in installs on Appian 26.9, that
the bundled Eclipse Paho library loads at run time, that the Connected System configuration page
renders, and that one integration can publish to a broker. 1.0.5 added four session settings. 1.0.6
added the subscribe integration. 1.0.7 adds server-side TLS: the broker is verified over `ssl://`,
against either the Appian server's own trusted certificate authorities or a single pasted CA
certificate. Configuration is validated at run time, inside the integrations, because Appian
discards design-time errors attached to a connected system. **Mutual** TLS, Last Will and Testament,
multi-topic subscribe, reconnection tuning and diagnostics remain deliberately absent; see
`README.md`.

## Contents

- **Connected System — MQTT Broker:** broker host, port, optional server-side TLS (enable TLS, CA
  certificate), client identifier, optional username and password, and four session settings
  (connection timeout, keep-alive interval, clean session, automatic reconnect).
- **Integration — Publish MQTT Message** (`WRITE` request policy): publishes a single message to a
  topic at a chosen QoS.
- **Integration — Subscribe to MQTT Topics** (`READ` request policy): collects messages from one
  topic filter, returning as soon as the message count is reached or the wait elapses. An empty
  result is a success.

## Third-party software

Bundles Eclipse Paho MQTT client `org.eclipse.paho.client.mqttv3` 1.2.5, redistributed unmodified
under the Eclipse Distribution License 1.0 (EDL-1.0). See `THIRD-PARTY-NOTICES.md` in the plug-in
jar for details.
