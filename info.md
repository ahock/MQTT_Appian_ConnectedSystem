# MQTT Broker Connected System

An Appian Connected System plug-in for publishing messages to, and collecting messages from, an
MQTT broker.

- **Plug-in key:** `de.hock.mqtt`
- **Vendor:** Hock
- **Version:** 1.0.0
- **Minimum Appian version:** 26.9.0.0

## Contents

- **Connected System — MQTT Broker:** reusable broker connection, authentication, TLS,
  reconnection, Last Will and Testament, and session configuration.
- **Integration — Publish MQTT Message** (`WRITE` request policy): publishes a single message to a
  topic at a chosen QoS, optionally retained.
- **Integration — Subscribe to MQTT Topics** (`READ` request policy): subscribes to one or more
  topic filters and returns the messages collected within bounded count and time limits.

## Third-party software

Bundles Eclipse Paho MQTT client `org.eclipse.paho.client.mqttv3` 1.2.5, redistributed unmodified
under the Eclipse Distribution License 1.0 (EDL-1.0). See `THIRD-PARTY-NOTICES.md` in the plug-in
jar for details.
