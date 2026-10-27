# Requirements Document

## Introduction

This feature defines an Appian Connected System that enables Appian applications to communicate with an MQTT broker. MQTT (Message Queuing Telemetry Transport) is a lightweight publish/subscribe messaging protocol commonly used for IoT and device telemetry.

The Connected System provides reusable, securely stored connection configuration (broker host, port, authentication credentials, TLS settings), and exposes integration operations that let Appian designers publish messages to topics and subscribe to topics to receive messages. The feature covers connection establishment and lifecycle, authentication (username/password and client certificates), TLS/SSL transport security, Quality of Service (QoS) delivery guarantees, retained messages, and Last Will and Testament (LWT) configuration.

The scope of this document is the behavioral requirements of the Connected System and its associated integration operations. Implementation details (specific MQTT client library, Appian SDK class structure, UI widget layout) are deferred to the design phase.

## Glossary

- **Connected System**: An Appian configuration object that stores reusable connection and authentication settings and is referenced by one or more Integration objects.
- **Integration**: An Appian object that invokes an operation (such as Publish or Subscribe) against a configured Connected System and returns a structured result to the calling Appian expression or process.
- **MQTT_Broker**: The external MQTT message server to which the Connected System connects.
- **MQTT_Client**: The protocol-level client, managed by the Connected System, that maintains the session with the MQTT_Broker.
- **Connected_System_Plugin**: The deployable Appian plug-in component that implements the Connected System and its integration operations.
- **Topic**: A hierarchical UTF-8 string used by the MQTT_Broker to route published messages to subscribers.
- **QoS**: Quality of Service level for message delivery; one of 0 (at most once), 1 (at least once), or 2 (exactly once).
- **Retained_Message**: A message the MQTT_Broker stores for a Topic and delivers to any client that subscribes to that Topic after publication.
- **LWT**: Last Will and Testament; a message the MQTT_Broker publishes on behalf of the MQTT_Client when the client disconnects unexpectedly.
- **TLS**: Transport Layer Security, used to encrypt the connection between the MQTT_Client and the MQTT_Broker.
- **Client_Certificate**: An X.509 certificate and private key presented by the MQTT_Client for mutual TLS authentication.
- **Designer**: An Appian user who configures the Connected System and Integration objects at design time.
- **Publish_Operation**: The integration operation that sends a message to a Topic on the MQTT_Broker.
- **Subscribe_Operation**: The integration operation that retrieves messages from one or more subscribed Topics.
- **Client_Identifier**: The unique string that identifies the MQTT_Client session to the MQTT_Broker.

## Requirements

### Requirement 1: Connection Configuration

**User Story:** As a Designer, I want to configure the MQTT broker connection details in a Connected System, so that multiple Integration objects can reuse the same connection settings securely.

#### Acceptance Criteria

1. THE Connected_System_Plugin SHALL present configuration fields for broker host accepting 1 to 253 characters, broker port, and a Client_Identifier accepting 1 to 65535 characters.
2. WHERE no Client_Identifier is provided by the Designer, THE Connected_System_Plugin SHALL generate a Client_Identifier of 1 to 23 characters that is unique per broker host and port for the MQTT_Client session.
3. THE Connected_System_Plugin SHALL present a configuration field for connection timeout as a whole number of seconds in the range 1 to 300, defaulting to 30.
4. IF the broker port value is outside the range 1 through 65535, THEN THE Connected_System_Plugin SHALL reject the configuration, display a validation message identifying the invalid port, and retain the entered values.
5. IF the broker host field is empty when the Designer saves the configuration, THEN THE Connected_System_Plugin SHALL reject the configuration, display a validation message identifying the missing host, and retain the entered values.
6. THE Connected_System_Plugin SHALL store configured credentials as encrypted sensitive fields that are not displayed in plain text after being saved.
7. IF the connection timeout value is empty, non-integer, or outside the range 1 to 300, THEN THE Connected_System_Plugin SHALL reject the configuration, display a validation message identifying the invalid timeout, and retain the entered values.

### Requirement 2: Authentication

**User Story:** As a Designer, I want to configure authentication credentials, so that the Connected System can connect to brokers that require identity verification.

#### Acceptance Criteria

1. THE Connected_System_Plugin SHALL present a configuration field for a username accepting between 1 and 256 characters and a configuration field for a password accepting between 1 and 256 characters.
2. WHERE both a username and a password are configured, THE MQTT_Client SHALL present those credentials to the MQTT_Broker during connection establishment.
3. WHERE neither a username nor a password is configured, THE MQTT_Client SHALL attempt an anonymous connection to the MQTT_Broker.
4. IF exactly one of username or password is configured while the other is empty, THEN THE Connected_System_Plugin SHALL reject the configuration, return a validation error identifying that username and password must both be provided or both be omitted, and retain the entered values.
5. WHERE a Client_Certificate is configured, THE MQTT_Client SHALL present the Client_Certificate to the MQTT_Broker during the TLS handshake.
6. IF the MQTT_Broker rejects the provided credentials, THEN THE Connected_System_Plugin SHALL return an authentication error identifying the failure as a credential rejection, not establish the connection, and retain the configured credential values.
7. IF the MQTT_Broker does not respond to the credential presentation within 30 seconds, THEN THE Connected_System_Plugin SHALL abort the connection attempt and return an authentication timeout error.

### Requirement 3: TLS/SSL Transport Security

**User Story:** As a Designer, I want to enable TLS for the broker connection, so that messages and credentials are encrypted in transit.

#### Acceptance Criteria

1. THE Connected_System_Plugin SHALL present a configuration option to enable or disable TLS for the connection.
2. WHERE TLS is enabled, THE MQTT_Client SHALL establish the connection to the MQTT_Broker using an encrypted TLS channel of version 1.2 or higher.
3. WHERE TLS is enabled and a certificate authority certificate is configured, THE MQTT_Client SHALL validate the MQTT_Broker server certificate against the configured certificate authority certificate.
4. IF TLS is enabled and the MQTT_Broker server certificate fails validation, THEN THE MQTT_Client SHALL terminate the connection attempt, return a TLS validation error, and not transmit credentials or payloads over the connection.
5. WHERE TLS is enabled, THE Connected_System_Plugin SHALL present a configuration field to upload a Client_Certificate and its associated private key.
6. IF TLS is enabled and the configured Client_Certificate or private key cannot be loaded, THEN THE Connected_System_Plugin SHALL reject the configuration and return an error identifying the certificate load failure.
7. IF TLS is disabled, THEN THE MQTT_Client SHALL establish the connection to the MQTT_Broker over an unencrypted channel.

### Requirement 4: Connection Establishment and Lifecycle

**User Story:** As a Designer, I want the Connected System to manage the broker connection, so that integration operations can execute without manual connection handling.

#### Acceptance Criteria

1. WHEN an Integration operation is invoked and no active session to the MQTT_Broker exists, THE MQTT_Client SHALL establish a connection using the configured settings and reuse any existing active session for concurrently invoked operations before executing the operation.
2. IF the MQTT_Broker does not accept a connection within the configured connection timeout, THEN THE Connected_System_Plugin SHALL abort the connection attempt and return a connection timeout error identifying the broker host and port.
3. WHEN an Integration operation completes and no active subscription requires the connection, THE MQTT_Client SHALL close the connection and release its associated network resources.
4. IF the connection to the MQTT_Broker is lost during an operation, THEN THE Connected_System_Plugin SHALL terminate the operation and return a connection error identifying the broker host and port and indicating that the connection was lost.
5. WHERE automatic reconnection is enabled, WHEN a connection to the MQTT_Broker is lost, THE MQTT_Client SHALL attempt to re-establish the connection up to the configured maximum number of reconnection attempts, configurable from 0 to 10 with a default of 3, waiting the configured reconnection interval of 1 to 120 seconds with a default of 5 seconds between attempts.
6. IF automatic reconnection is enabled and all configured reconnection attempts fail, THEN THE Connected_System_Plugin SHALL stop further reconnection attempts and return a connection error identifying the broker host and port and indicating that the maximum number of reconnection attempts was exhausted.

### Requirement 5: Publish Messages

**User Story:** As a Designer, I want to publish a message to a topic, so that Appian applications can send data to MQTT subscribers.

#### Acceptance Criteria

1. THE Publish_Operation SHALL accept a Topic of 1 to 65535 UTF-8 bytes, a message payload of 0 to 268435455 bytes, and a QoS level as inputs.
2. WHEN the Publish_Operation is invoked with valid inputs, THE MQTT_Client SHALL publish the message payload to the specified Topic on the MQTT_Broker.
3. IF the provided QoS value is not 0, 1, or 2, THEN THE Publish_Operation SHALL reject the request without publishing and return a validation error identifying the invalid QoS value.
4. IF the provided Topic is empty, exceeds 65535 bytes, or contains wildcard characters, THEN THE Publish_Operation SHALL reject the request without publishing and return a validation error identifying the invalid Topic.
5. WHERE the retained flag input is set to true, THE MQTT_Client SHALL publish the message as a Retained_Message to the MQTT_Broker.
6. WHEN a publish request with QoS 1 or QoS 2 is acknowledged by the MQTT_Broker, THE Publish_Operation SHALL return a success result indicating the message was delivered.
7. WHEN a publish request with QoS 0 is sent to the MQTT_Broker, THE Publish_Operation SHALL return a success result indicating the message was dispatched.
8. IF the Publish_Operation does not receive broker acknowledgment within the configured connection timeout for a QoS 1 or QoS 2 message, THEN THE Publish_Operation SHALL return a delivery timeout error.

### Requirement 6: Subscribe to Topics

**User Story:** As a Designer, I want to subscribe to one or more topics, so that Appian applications can receive messages published by other MQTT clients.

#### Acceptance Criteria

1. THE Subscribe_Operation SHALL accept between 1 and 10 Topic filters and a single QoS level as inputs.
2. WHEN the Subscribe_Operation is invoked with valid inputs, THE MQTT_Client SHALL register a subscription for the specified Topic filters with the MQTT_Broker at the requested QoS level.
3. WHEN a message is received on a subscribed Topic, THE Subscribe_Operation SHALL return the message payload, the originating Topic, the QoS level, and the retained indicator to the calling Appian expression.
4. THE Subscribe_Operation SHALL accept a maximum wait duration in seconds in the range 1 through 300 and a maximum message count in the range 1 through 1000 that together bound how long the operation collects messages.
5. WHEN the maximum wait duration elapses or the maximum message count is reached, THE Subscribe_Operation SHALL return the messages collected up to that point.
6. WHEN the maximum wait duration elapses and no messages were received, THE Subscribe_Operation SHALL return a success result containing an empty message collection.
7. IF a provided Topic filter is empty, THEN THE Subscribe_Operation SHALL reject the request and return a validation error identifying the invalid Topic filter.
8. IF the number of provided Topic filters is 0 or exceeds 10, THEN THE Subscribe_Operation SHALL reject the request and return a validation error identifying the invalid Topic filter count.
9. IF the provided QoS value is not 0, 1, or 2, THEN THE Subscribe_Operation SHALL reject the request and return a validation error identifying the invalid QoS value.
10. WHERE a message payload is binary, THE Subscribe_Operation SHALL return the payload as a Base64-encoded string together with an indicator that the payload is binary.

### Requirement 7: Last Will and Testament

**User Story:** As a Designer, I want to configure a last will message, so that subscribers are notified when the Connected System disconnects unexpectedly.

#### Acceptance Criteria

1. THE Connected_System_Plugin SHALL present configuration fields for an LWT Topic accepting 1 to 65535 UTF-8 characters, an LWT payload accepting 0 to 268435455 bytes, an LWT QoS level restricted to 0, 1, or 2, and an LWT retained flag accepting a boolean that defaults to false.
2. WHERE an LWT Topic and an LWT payload are configured, THE MQTT_Client SHALL register the configured LWT Topic, LWT payload, LWT QoS level, and LWT retained flag with the MQTT_Broker as part of the CONNECT exchange before the connection is reported as established.
3. WHEN the MQTT_Client disconnects without sending a normal disconnect message, THE MQTT_Broker SHALL publish the configured LWT payload to the configured LWT Topic using the configured LWT QoS level and LWT retained flag.
4. IF an LWT payload is configured without an LWT Topic, THEN THE Connected_System_Plugin SHALL reject the configuration, display a validation message identifying that the LWT Topic is missing, and retain the entered values.
5. IF an LWT QoS level outside 0, 1, or 2 or an LWT Topic exceeding 65535 characters is configured, THEN THE Connected_System_Plugin SHALL reject the configuration and display a validation message identifying the invalid field and its permitted range.
6. IF LWT registration with the MQTT_Broker fails during the CONNECT exchange, THEN THE MQTT_Client SHALL abort the connection attempt and report a connection error indicating that LWT registration failed.

### Requirement 8: Session Configuration

**User Story:** As a Designer, I want to control session persistence behavior, so that I can choose between durable subscriptions and lightweight stateless connections.

#### Acceptance Criteria

1. THE Connected_System_Plugin SHALL present a boolean configuration option to enable or disable a clean session for the MQTT_Client, defaulting to enabled.
2. WHERE a clean session is disabled, THE MQTT_Client SHALL request that the MQTT_Broker retain session state across reconnections using the configured Client_Identifier.
3. WHERE a clean session is enabled, THE MQTT_Client SHALL request that the MQTT_Broker discard prior session state at connection establishment.
4. THE Connected_System_Plugin SHALL present a configuration field for the keep-alive interval as a whole number of seconds in the range 0 to 65535, defaulting to 60.
5. IF a clean session is disabled and no Client_Identifier is configured, THEN THE Connected_System_Plugin SHALL reject the configuration, return an error indicating a Client_Identifier is required for a persistent session, and retain the entered values.
6. IF the keep-alive interval is non-integer, negative, or greater than 65535, THEN THE Connected_System_Plugin SHALL reject the configuration, return a validation error identifying the invalid keep-alive interval, and retain the entered values.

### Requirement 9: Error Reporting and Diagnostics

**User Story:** As a Designer, I want integration operations to return clear outcomes, so that I can handle failures within Appian process and expression logic.

#### Acceptance Criteria

1. WHEN an Integration operation completes successfully, THE Connected_System_Plugin SHALL return a result that includes a success indicator set to true.
2. IF an Integration operation fails, THEN THE Connected_System_Plugin SHALL return a result that includes a success indicator set to false, an error category identifying the failure type (for example validation, authentication, connection timeout, TLS validation, delivery timeout, or disconnection), and a textual error message of at most 500 characters describing the failure.
3. WHEN an Integration operation executes, THE Connected_System_Plugin SHALL record the broker host, the operation name, and the operation outcome in the Appian integration diagnostics.
4. THE Connected_System_Plugin SHALL exclude all configured sensitive values, including usernames, passwords, and private keys, from the diagnostic output, the returned error message, and the returned error category.
5. WHEN an Integration operation fails, THE Connected_System_Plugin SHALL record the error category in the Appian integration diagnostics in addition to the broker host, operation name, and operation outcome.
6. THE Connected_System_Plugin SHALL return the error category as a value from a fixed, documented set of categories so that a calling Appian expression can branch on the category without parsing the error message text.
