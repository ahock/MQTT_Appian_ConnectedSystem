# Third-Party Notices

The MQTT Broker Connected System plug-in bundles one third-party library inside the shipped
plug-in jar, as a nested jar under `META-INF/lib/`.

## Eclipse Paho Java Client (MQTT v3)

- Coordinates: `org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5`
- Bundled as: `META-INF/lib/org.eclipse.paho.client.mqttv3-1.2.5.jar`
- Project: https://github.com/eclipse-paho/paho.mqtt.java
- License: dual-licensed under the Eclipse Public License 2.0 (EPL-2.0) and the Eclipse
  Distribution License 1.0 (EDL-1.0)
- License relied upon for redistribution: **EDL-1.0**, a permissive BSD-3-Clause style license
  (https://www.eclipse.org/org/documents/edl-v10.php). The library is redistributed unmodified.

## Copyleft

No copyleft-licensed library is bundled in this plug-in.

## Not shipped

`com.appian:connected-systems-core:1.9.1` is declared with Maven scope `provided`. It is supplied
by the Appian runtime at deploy time and is never packaged into or redistributed with this
plug-in.
