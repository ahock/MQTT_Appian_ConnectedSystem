# MQTT Broker Connected System — Plug-in Guide

An Appian Connected System plug-in that lets Appian applications publish messages to, and
collect messages from, an MQTT broker. It packages one **Connected System template** (reusable
connection, authentication, TLS, Last Will, and session configuration) and two **Integration
templates** (Publish and Subscribe).

- **Runtime:** Java 17 (Amazon Corretto), Eclipse Paho MQTT client (`mqttv3`)
- **Deployable artifact:** `target/mqtt-broker-connected-system-1.0.0-plugin.jar`
- **Plug-in key:** `de.hock.mqtt`
- **Minimum Appian version:** 26.9.0.0

---

## What it does

| Component | Appian name | Purpose |
|-----------|-------------|---------|
| Connected System | **MQTT Broker** | Holds the reusable broker connection, auth, TLS, reconnection, Last Will, and session settings. |
| Integration | **Publish MQTT Message** | Publishes a single message to a topic at a chosen QoS, optionally retained. |
| Integration | **Subscribe to MQTT Topics** | Subscribes to one or more topic filters and collects messages within bounded count/time limits, then returns them. |

### Why a bounded Subscribe

Appian integrations are synchronous (invoke → return a result), but MQTT delivery is
event-driven. The Subscribe integration therefore uses a **bounded collection** pattern: it
connects, subscribes, buffers inbound messages, and returns as soon as **either** the maximum
message count is reached **or** the maximum wait time elapses. If no messages arrive before the
wait elapses, it returns a successful empty collection — not an error.

---

## Typical use cases

- **Publish events to an MQTT broker** — e.g. notify IoT devices or an external system when an
  Appian process reaches a milestone.
- **Command/control** — publish a command payload to a device topic from a process or interface.
- **Poll for device telemetry** — subscribe to a sensor topic and collect the latest readings
  into an Appian process for storage or decisioning.
- **Bridge MQTT ↔ Appian records** — subscribe on a schedule, write collected messages into a
  record type.

> The Subscribe integration collects a bounded batch per invocation; it is **not** a persistent
> long-lived listener. For continuous ingestion, invoke it on a schedule (e.g. a timer-driven
> process) and tune the count/wait limits.

---

## Configuring the Connected System (MQTT Broker)

Create a **Connected System** object and choose the **MQTT Broker** template. Fields are grouped:

### Connection
| Field | Notes |
|-------|-------|
| Broker Host | Host name or address, 1–253 characters. Required. |
| Broker Port | 1–65535. Typically 1883 (plain) or 8883 (TLS). Required. |
| Client Identifier | 1–65535 characters. **Auto-generated** (stable, derived from host+port) when left blank. |
| Connection Timeout (seconds) | 1–300. Defaults to **30**. |

### Authentication
| Field | Notes |
|-------|-------|
| Username | 1–256 characters. Optional. |
| Password | 1–256 characters. **Encrypted & masked** — not shown after save. |

> Credentials are **both-or-neither**: provide both a username and a password, or leave both
> blank for an anonymous connection. A one-sided entry is rejected at design time.

### TLS
| Field | Notes |
|-------|-------|
| Enable TLS | Uses TLS 1.2 or higher. |
| CA Certificate | Validates the broker certificate (optional; falls back to the platform trust store). |
| Client Certificate | For mutual TLS (optional). |
| Client Private Key | **Encrypted & masked.** Required alongside a client certificate for mutual TLS. |
| Key Passphrase | **Encrypted & masked.** Optional. |

### Reconnection
| Field | Notes |
|-------|-------|
| Enable Automatic Reconnect | Reconnect after a lost connection. |
| Max Reconnect Attempts | 0–10. Defaults to **3**. |
| Reconnect Interval (seconds) | 1–120. Defaults to **5**. |

### Last Will and Testament (LWT)
| Field | Notes |
|-------|-------|
| LWT Topic | 1–65535 characters. Optional. Required if an LWT payload is set. |
| LWT Payload | 0–268,435,455 bytes. Optional. |
| LWT QoS | 0, 1, or 2. |
| LWT Retained | Whether the broker retains the Last Will message. Default false. |

### Session
| Field | Notes |
|-------|-------|
| Clean Session | Default **true**. Disable for a persistent session — a client identifier is then **required**. |
| Keep Alive (seconds) | 0–65535. Defaults to **60**. |

Design-time validation reports field-specific errors and **retains the values you entered** on a
rejected save. A brand-new Connected System opens clean with no validation messages before any
input; validation begins on the next render, as soon as you interact with the form or save it.

---

## Publish MQTT Message

Create an **Integration** object bound to the MQTT Broker Connected System and choose **Publish
MQTT Message**.

### Inputs
| Input | Type | Rules |
|-------|------|-------|
| `topic` | Text | 1–65535 UTF-8 bytes. No `+` or `#` wildcards. Required. |
| `payload` | Text | Sent as UTF-8 bytes. 0–268,435,455 bytes. |
| `qos` | Choice | 0, 1, or 2. |
| `retained` | Boolean | Whether the broker retains the message as the topic's last value. |

### Result
- **QoS 1 or 2** → success with status `delivered` once the broker acknowledges.
- **QoS 0** → success with status `dispatched` (fire-and-forget, no acknowledgment).
- Missing acknowledgment within the timeout (QoS 1/2) → failure with category `DELIVERY_TIMEOUT`.
- Invalid input → failure with category `VALIDATION`, **without contacting the broker**.

Result data includes: `status`, `topic`, `qos`.

---

## Subscribe to MQTT Topics

Create an **Integration** object bound to the MQTT Broker Connected System and choose **Subscribe
to MQTT Topics**.

### Inputs
| Input | Type | Rules |
|-------|------|-------|
| `topicFilters` | List of Text | 1–10 filters, each non-empty. MQTT wildcards (`+`, `#`) are allowed in filters. |
| `qos` | Choice | 0, 1, or 2. |
| `maxWaitSeconds` | Integer | 1–300. Upper bound on how long the operation waits. |
| `maxMessageCount` | Integer | 1–1000. Upper bound on how many messages to collect. |

### Result
A successful result carries `messages` (a list) and `messageCount`. Each message preserves:

| Field | Meaning |
|-------|---------|
| `topic` | The originating topic the message was published on. |
| `payload` | Text payload as-is, or a **Base64 string** when the payload is binary. |
| `binary` | `true` when `payload` is Base64-encoded binary; `false` for text. |
| `qos` | QoS the message was delivered at. |
| `retained` | Whether the message was flagged retained. |

- Returns as soon as `maxMessageCount` is reached or `maxWaitSeconds` elapses.
- No messages before the wait elapses → successful **empty** collection (not an error).
- Invalid input → `VALIDATION` failure, without contacting the broker.

### Handling binary payloads

When `binary` is `true`, decode the Base64 `payload` in your expression (e.g. with the
appropriate Appian/base64 handling) to recover the original bytes.

---

## Error categories

Every failure maps to one value from a **fixed, documented set**, so expressions can branch on the
category without parsing message text. Error messages are capped at 500 characters, and
usernames, passwords, and private keys are **never** present in diagnostics, error messages, or
the error category.

| Category | Meaning |
|----------|---------|
| `VALIDATION` | Design-time or runtime input failed validation. |
| `AUTHENTICATION` | The broker rejected the supplied credentials. |
| `CONNECTION_TIMEOUT` | The connection attempt did not complete within the timeout. |
| `TLS_VALIDATION` | TLS handshake or certificate validation failed. |
| `DELIVERY_TIMEOUT` | A QoS 1/2 publish was not acknowledged in time. |
| `DISCONNECTION` | An established connection was lost (message names host and port). |
| `CONFIGURATION` | The connection configuration is invalid or unusable. |
| `UNKNOWN` | An unrecognized failure that could not be classified. |

Each integration also records diagnostics: broker host, operation name, outcome, and (on failure)
the error category.

---

## Behavior notes

- **Connection reuse:** concurrent operations sharing the same connection configuration reuse a
  single active session; it is released when no active operation still needs it.
- **Resource safety:** sessions are released in `finally` blocks, so a failed operation never
  leaks a connection.
- **Secret handling:** sensitive values are held as character arrays and redacted from all output.

---

## Build and install

Build the deployable artifact (requires a Java 17 JDK; the system default here is 23, so point
`JAVA_HOME` at Corretto 17):

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-17.jdk/Contents/Home mvn clean package
```

The build produces exactly one jar in `target/`:
`target/mqtt-broker-connected-system-1.0.0-plugin.jar`. That is the artifact to deploy to Appian
and to submit to the AppMarket. It bundles the Eclipse Paho client as a nested jar under
`META-INF/lib/`, which is where Appian's plug-in classloader resolves nested jars from, and it
carries its own `.java` sources alongside the compiled classes as AppMarket requires.

Install it via **Admin Console → Plug-ins → Deploy Plug-in**, then confirm the plug-in loads with
the Connected System and two integrations listed. See the broader install steps in your Appian
administration docs.

---

## Current test coverage (important)

This is an MVP build. Only a scaffolding smoke test runs in the automated suite — the
property-based tests, unit tests, and embedded-broker integration tests described in the design
were deferred. The code compiles and packages cleanly, but publish/subscribe behavior against a
real broker has **not** been exercised by an automated test. Run those tests (or at least a manual
smoke test against a test broker) before relying on the plug-in in production.
