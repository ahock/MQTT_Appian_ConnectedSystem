package de.hock.mqtt;

import java.util.List;

/**
 * Immutable inputs for a bounded subscribe operation.
 *
 * <p>Requirements: 6.1.
 *
 * @param topicFilters the topic filters to subscribe to (1–10 filters, each non-blank)
 * @param qos the requested quality of service (0, 1, or 2)
 * @param maxWaitSeconds maximum time to collect messages (1–300 seconds)
 * @param maxMessageCount maximum number of messages to collect (1–1000)
 */
public record SubscribeRequest(List<String> topicFilters, int qos, int maxWaitSeconds, int maxMessageCount) {
}
