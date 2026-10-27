# MQTT Broker Connected System

An Appian Connected System plug-in for publishing messages to an MQTT broker.

- **Plug-in key:** `de.hock.mqtt`
- **Vendor:** Hock
- **Version:** 1.0.4
- **Minimum Appian version:** 26.9.0.0

**Minimal smoke-test build.** This release validates four things only: that the plug-in installs on
Appian 26.9, that the bundled Eclipse Paho library loads at run time, that the Connected System
configuration page renders, and that one integration can publish to a broker. TLS, the subscribe
integration, Last Will and Testament, reconnection tuning, diagnostics and design-time validation
were deliberately removed; see `README.md`.

## Contents

- **Connected System — MQTT Broker:** broker host, port, client identifier, and optional username
  and password.
- **Integration — Publish MQTT Message** (`WRITE` request policy): publishes a single message to a
  topic at a chosen QoS.

## Third-party software

Bundles Eclipse Paho MQTT client `org.eclipse.paho.client.mqttv3` 1.2.5, redistributed unmodified
under the Eclipse Distribution License 1.0 (EDL-1.0). See `THIRD-PARTY-NOTICES.md` in the plug-in
jar for details.
