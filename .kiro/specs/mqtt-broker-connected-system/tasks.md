# Implementation Plan: MQTT Broker Connected System

## Overview

This plan builds the Appian Connected System plug-in incrementally in Java (Corretto 17) on the Appian Connected System SDK, using the Eclipse Paho MQTT client for protocol work and jqwik for property-based testing. It starts with the Maven project and dependency scaffolding, then builds the pure data models and error-category enum, the design-time and runtime validators (each paired with its property tests), the Paho session/connection manager and connect-options mapping, the Connected System template, the Publish and Subscribe integrations, the diagnostics/redaction layer, integration tests against an embedded broker, and finally plug-in packaging. Each task builds on prior tasks and ends by wiring new code into the existing structure so no code is left orphaned.

All property-based test tasks use jqwik with a minimum of 100 generated cases per property and annotate each test with a comment of the form `// Feature: mqtt-broker-connected-system, Property {number}: {property_text}`.

## Tasks

- [x] 1. Set up Maven Appian plug-in project structure and dependencies
  - Create the Maven project layout (`src/main/java`, `src/main/resources`, `src/test/java`) under a base package such as `de.hock.mqtt`
  - Configure `pom.xml` targeting Java 17 (Corretto): add the Appian Connected System SDK dependency (`appian-plugin-sdk` / connected-system SDK), the Eclipse Paho client (`org.eclipse.paho.client.mqttv3`), jqwik and JUnit 5 (test scope), and the embedded-broker dependency (Moquette or HiveMQ test container, test scope)
  - Configure the build plugin that packages the Appian plug-in (jar + plug-in descriptor) and the Surefire/Failsafe setup so jqwik property tests and integration tests run
  - Add placeholder plug-in descriptor resources so the module compiles and packages end-to-end
  - _Requirements: 1.1_

- [x] 2. Implement core data models and the error-category enum
  - [x] 2.1 Implement MqttErrorCategory enum
    - Create the fixed, closed enum with values `VALIDATION, AUTHENTICATION, CONNECTION_TIMEOUT, TLS_VALIDATION, DELIVERY_TIMEOUT, DISCONNECTION, CONFIGURATION, UNKNOWN`
    - _Requirements: 9.2, 9.6_

  - [x] 2.2 Implement immutable data model records
    - Create `MqttConnectionConfig`, `PublishRequest`, `SubscribeRequest`, `ReceivedMessage`, and `OperationResult` records matching the design's Data Models section, holding sensitive fields (password, private key, passphrase) as `char[]`
    - _Requirements: 1.1, 2.1, 3.1, 5.1, 6.1, 6.3, 7.1, 8.1, 9.1_

- [x] 3. Implement configuration validators with property tests
  - [x] 3.1 Implement connection, credential, and session field validators
    - Build a `ConfigValidator` producing field-scoped validation results (field id + message + retained values) for: broker host (non-blank, 1–253), port (1–65535), client identifier (1–65535), connection timeout (integer 1–300, default 30), username/password both-or-neither (each 1–256), keep-alive (integer 0–65535, default 60), and persistent-session-requires-client-id
    - _Requirements: 1.1, 1.3, 1.4, 1.5, 1.7, 2.1, 2.4, 8.4, 8.5, 8.6_

  - [x] 3.2 Implement client identifier auto-generation
    - Generate a 1–23 character client identifier derived from host+port that is stable per host/port and distinct across different host/port combinations
    - _Requirements: 1.2_

  - [x] 3.3 Implement LWT and reconnection validators
    - Validate LWT (payload-requires-topic, topic 1–65535, payload 0–268435455, QoS ∈ {0,1,2}) and reconnection bounds (max attempts integer 0–10 default 3, interval integer 1–120 default 5)
    - _Requirements: 4.5, 7.1, 7.4, 7.5_

  - [x] 3.4 Implement certificate load validation
    - Add a certificate/key loader that parses client certificate + private key bytes and returns a certificate-load error for malformed input, rejecting the configuration on failure
    - _Requirements: 3.6_

  - [ ]* 3.5 Write property test for connection field bounds validation
    - **Property 1: Connection field bounds validation** — jqwik, 100+ iterations, tagged `// Feature: mqtt-broker-connected-system, Property 1: ...`
    - **Validates: Requirements 1.1, 1.3, 1.4, 1.5, 1.7**

  - [ ]* 3.6 Write property test for client identifier auto-generation
    - **Property 2: Client identifier auto-generation bounds and stability** — jqwik, 100+ iterations, tagged accordingly
    - **Validates: Requirements 1.2**

  - [ ]* 3.7 Write property test for credential both-or-neither validation
    - **Property 3: Credential field validation (both-or-neither)** — jqwik, 100+ iterations, tagged accordingly
    - **Validates: Requirements 2.1, 2.4**

  - [ ]* 3.8 Write property test for certificate load validation
    - **Property 5: Certificate load validation** — jqwik, 100+ iterations, tagged accordingly
    - **Validates: Requirements 3.6**

  - [ ]* 3.9 Write property test for reconnection bounds validation
    - **Property 6: Reconnection bounds validation** — jqwik, 100+ iterations, tagged accordingly
    - **Validates: Requirements 4.5**

  - [ ]* 3.10 Write property test for LWT configuration validation
    - **Property 17: LWT configuration validation** — jqwik, 100+ iterations, tagged accordingly
    - **Validates: Requirements 7.1, 7.4, 7.5**

  - [ ]* 3.11 Write property test for persistent-session client identifier requirement
    - **Property 20: Persistent-session requires client identifier** — jqwik, 100+ iterations, tagged accordingly
    - **Validates: Requirements 8.5**

  - [ ]* 3.12 Write property test for keep-alive bounds validation
    - **Property 21: Keep-alive bounds validation** — jqwik, 100+ iterations, tagged accordingly
    - **Validates: Requirements 8.4, 8.6**

- [x] 4. Implement runtime input validators with property tests
  - [x] 4.1 Implement publish and subscribe input validators
    - Build an `InputValidator` for publish (topic 1–65535 bytes, no `+`/`#` wildcards, payload 0–268435455 bytes, QoS ∈ {0,1,2}) and subscribe (1–10 filters, each non-blank, QoS ∈ {0,1,2}, maxWaitSeconds 1–300, maxMessageCount 1–1000), returning a VALIDATION result identifying the offending input
    - _Requirements: 5.1, 5.3, 5.4, 6.1, 6.4, 6.7, 6.8, 6.9_

  - [ ]* 4.2 Write property test for publish input validation
    - **Property 9: Publish input validation** — jqwik, 100+ iterations, tagged accordingly
    - **Validates: Requirements 5.1, 5.3, 5.4**

  - [ ]* 4.3 Write property test for subscribe filter and QoS validation
    - **Property 11: Subscribe filter and QoS validation** — jqwik, 100+ iterations, tagged accordingly
    - **Validates: Requirements 6.1, 6.7, 6.8, 6.9**

  - [ ]* 4.4 Write property test for subscribe collection bounds validation
    - **Property 14: Subscribe collection bounds validation** — jqwik, 100+ iterations, tagged accordingly
    - **Validates: Requirements 6.4**

- [x] 5. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 6. Implement connect-options mapping and the Paho session/connection manager
  - [x] 6.1 Implement MqttConnectOptions mapping from MqttConnectionConfig
    - Map config to Paho `MqttConnectOptions`: username/password when both present (anonymous otherwise), `SSLSocketFactory` (TLS ≥ 1.2) from CA/client cert/key when TLS enabled (plain TCP otherwise), connection timeout, `setWill` from LWT when configured, clean session, keep-alive, and automatic reconnect with attempt/interval bounds
    - _Requirements: 2.2, 2.3, 3.2, 3.3, 3.5, 3.7, 4.2, 7.2, 8.1, 8.2, 8.3, 8.4, 4.5_

  - [x] 6.2 Implement MqttSession wrapping Paho MqttAsyncClient
    - Implement `publish`, `subscribe` (with a `messageArrived` callback sink), `unsubscribe`, and `isConnected`; build `ReceivedMessage` preserving topic, payload, QoS, and retained flag, Base64-encoding binary payloads with a binary indicator
    - Introduce a client abstraction (interface + Paho-backed impl) so logic can be exercised with a fake client in property tests
    - _Requirements: 4.1, 5.2, 5.5, 6.2, 6.3, 6.10_

  - [x] 6.3 Implement MqttConnectionManager with session reuse and release
    - Cache an active session keyed by a connection fingerprint, hand the same session to concurrent operations, and close/release the session (in `finally`) when no active subscription needs it; map lost-connection to a DISCONNECTION error naming host/port
    - _Requirements: 4.1, 4.3, 4.4_

  - [ ]* 6.4 Write property test for credential-to-connect-options mapping
    - **Property 4: Credential-to-connect-options mapping** — jqwik with a fake client, 100+ iterations, tagged accordingly
    - **Validates: Requirements 2.2, 2.3**

  - [ ]* 6.5 Write property test for clean-session options mapping
    - **Property 19: Clean-session options mapping** — jqwik, 100+ iterations, tagged accordingly
    - **Validates: Requirements 8.1, 8.2, 8.3**

  - [ ]* 6.6 Write property test for LWT-to-connect-options mapping
    - **Property 18: LWT-to-connect-options mapping** — jqwik, 100+ iterations, tagged accordingly
    - **Validates: Requirements 7.2**

  - [ ]* 6.7 Write property test for publish message propagation
    - **Property 10: Publish message propagation** — jqwik with a fake client, 100+ iterations, tagged accordingly
    - **Validates: Requirements 5.2, 5.5**

  - [ ]* 6.8 Write property test for subscription registration propagation
    - **Property 12: Subscription registration propagation** — jqwik with a fake client, 100+ iterations, tagged accordingly
    - **Validates: Requirements 6.2**

  - [ ]* 6.9 Write property test for received message field preservation
    - **Property 13: Received message field preservation** — jqwik, 100+ iterations, tagged accordingly
    - **Validates: Requirements 6.3**

  - [ ]* 6.10 Write property test for binary payload Base64 round-trip
    - **Property 16: Binary payload Base64 round-trip** — jqwik, 100+ iterations, tagged accordingly
    - **Validates: Requirements 6.10**

  - [ ]* 6.11 Write property tests for active session reuse and idle release
    - **Property 7: Active session reuse** and **Property 8: Session release when idle** — jqwik with a fake connection manager, 100+ iterations each, tagged accordingly
    - **Validates: Requirements 4.1, 4.3**

- [x] 7. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. Implement diagnostics and sensitive-value redaction
  - [x] 8.1 Implement DiagnosticsBuilder with redaction
    - Record broker host, operation name, and outcome into diagnostics, add the error category on failure, and run a redaction filter guaranteeing usernames, passwords, and private keys never appear in diagnostics, error message, or error category
    - _Requirements: 9.3, 9.4, 9.5_

  - [x] 8.2 Implement OperationResult-to-IntegrationResponse mapping
    - Map `OperationResult` to `IntegrationResponse`: boolean success indicator, error category from the fixed enum on failure, and error messages truncated to 500 characters; centralize Paho-exception-to-category translation (auth, TLS, timeout, disconnection, delivery timeout, unknown)
    - _Requirements: 9.1, 9.2, 9.6_

  - [ ]* 8.3 Write property test for response shaping and bounded error category
    - **Property 22: Response shaping and bounded error category** — jqwik, 100+ iterations, tagged accordingly
    - **Validates: Requirements 9.1, 9.2, 9.6**

  - [ ]* 8.4 Write property test for diagnostics content
    - **Property 23: Diagnostics content** — jqwik, 100+ iterations, tagged accordingly
    - **Validates: Requirements 9.3, 9.5**

  - [ ]* 8.5 Write property test for sensitive value redaction
    - **Property 24: Sensitive value redaction** — jqwik with secrets embedded into configs, 100+ iterations, tagged accordingly
    - **Validates: Requirements 9.4**

- [x] 9. Implement the Connected System template
  - [x] 9.1 Implement MqttConnectedSystemTemplate configuration descriptor
    - Subclass `SimpleConnectedSystemTemplate`; declare the Connection, Authentication, TLS, Reconnection, LWT, and Session field groups, declaring password, private key, and passphrase as encrypted/sensitive property types; present TLS enable/disable and client-certificate upload fields
    - _Requirements: 1.1, 1.6, 2.1, 3.1, 3.5, 4.5, 7.1, 8.1, 8.4_

  - [x] 9.2 Wire design-time validation and config mapping
    - Implement `validate(...)` delegating to the ConfigValidator (field errors with values retained) and `toConnectionConfig(...)` producing an immutable `MqttConnectionConfig`
    - _Requirements: 1.4, 1.5, 1.7, 2.4, 3.6, 7.4, 7.5, 8.5, 8.6_

  - [ ]* 9.3 Write descriptor smoke tests
    - Assert sensitive fields are declared encrypted/masked and TLS/client-certificate fields are present in the descriptor
    - _Requirements: 1.6, 3.1, 3.5_

- [x] 10. Implement the Publish integration
  - [x] 10.1 Implement MqttPublishIntegration
    - Subclass `SimpleIntegrationTemplate` (WRITE policy); declare inputs `topic`, `payload`, `qos`, `retained`; validate inputs (return VALIDATION without contacting the broker), acquire a session, publish via Paho `MqttMessage`, release the session in `finally`, and return "delivered" (QoS 1/2 ack) or "dispatched" (QoS 0), with DELIVERY_TIMEOUT on missing ack within timeout
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8_

  - [ ]* 10.2 Write unit tests for publish result semantics
    - QoS 1/2 acknowledged publish returns "delivered"; QoS 0 returns "dispatched"
    - _Requirements: 5.6, 5.7_

- [x] 11. Implement the Subscribe integration
  - [x] 11.1 Implement MqttSubscribeIntegration with bounded collection
    - Subclass `SimpleIntegrationTemplate`; declare inputs `topicFilters` (1–10), `qos`, `maxWaitSeconds` (1–300), `maxMessageCount` (1–1000); validate inputs, acquire a session, register a `messageArrived` sink into a bounded `LinkedBlockingQueue`, subscribe at the requested QoS, drain until maxCount reached or maxWait elapses, unsubscribe, release in `finally`, and return collected messages (empty success collection on timeout)
    - _Requirements: 6.2, 6.3, 6.5, 6.6, 6.10_

  - [ ]* 11.2 Write property test for bounded subscribe termination
    - **Property 15: Bounded subscribe termination** — jqwik over inbound message streams and bounds, 100+ iterations, tagged accordingly
    - **Validates: Requirements 6.5, 6.6**

  - [ ]* 11.3 Write unit test for empty-collection subscribe
    - Subscribe with no inbound messages returns a successful empty collection within the wait window
    - _Requirements: 6.6_

- [x] 12. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 13. Integration tests against an embedded broker
  - [ ]* 13.1 Write authentication integration tests
    - Against an embedded broker: credential acceptance/rejection, client-certificate presentation, and credential-presentation timeout
    - _Requirements: 2.5, 2.6, 2.7_

  - [ ]* 13.2 Write TLS integration tests
    - TLS channel establishment at ≥ 1.2, CA validation of the server certificate, and connection termination (no credential/payload transmission) on validation failure
    - _Requirements: 3.2, 3.3, 3.4, 3.7_

  - [ ]* 13.3 Write connection-lifecycle integration tests
    - Connection timeout against a non-accepting broker, lost-connection handling, and reconnect-attempt exhaustion
    - _Requirements: 4.2, 4.4, 4.6_

  - [ ]* 13.4 Write publish delivery-timeout integration test
    - Publish delivery timeout when the broker withholds acknowledgments for a QoS 1/2 message
    - _Requirements: 5.8_

  - [ ]* 13.5 Write LWT integration tests
    - Broker publishes the configured LWT after an unexpected disconnect, and the connection aborts on LWT registration failure
    - _Requirements: 7.3, 7.6_

- [x] 14. Package the Appian plug-in
  - [x] 14.1 Author plug-in descriptors and finalize packaging
    - Write the Appian plug-in descriptor (`appian-plugin.xml`) registering the connected-system template and both integration templates, add i18n/property resources for field labels, confirm the Maven build produces the deployable plug-in artifact, and wire all components (template, integrations, manager, validators, diagnostics) into the registered entry points
    - _Requirements: 1.1, 5.1, 6.1, 9.1_

- [x] 15. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP; they are the property-based, unit, descriptor-smoke, and integration test tasks.
- Each task references specific requirements and/or correctness properties for traceability.
- Property-based tests use jqwik with a minimum of 100 generated cases per property and are tagged `// Feature: mqtt-broker-connected-system, Property {number}: {property_text}`.
- Broker-, TLS-, and timing-dependent behavior (Requirements 2.5–2.7, 3.2–3.4, 4.2/4.4/4.6, 5.8, 7.3/7.6) is covered by integration tests against an embedded broker rather than property tests.
- Checkpoints provide incremental validation at natural boundaries (after validators, after the protocol layer, after the integrations).

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["2.1", "2.2"] },
    { "id": 2, "tasks": ["3.1", "3.2", "3.3", "3.4", "4.1"] },
    { "id": 3, "tasks": ["3.5", "3.6", "3.7", "3.8", "3.9", "3.10", "3.11", "3.12", "4.2", "4.3", "4.4"] },
    { "id": 4, "tasks": ["6.1", "6.2"] },
    { "id": 5, "tasks": ["6.3", "6.4", "6.5", "6.6", "6.7", "6.8", "6.9", "6.10"] },
    { "id": 6, "tasks": ["6.11", "8.1", "8.2"] },
    { "id": 7, "tasks": ["8.3", "8.4", "8.5", "9.1"] },
    { "id": 8, "tasks": ["9.2", "9.3", "10.1", "11.1"] },
    { "id": 9, "tasks": ["10.2", "11.2", "11.3", "13.1", "13.2", "13.3", "13.4", "13.5"] },
    { "id": 10, "tasks": ["14.1"] }
  ]
}
```
