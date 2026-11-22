package ru.sddp.platform.api;

import tools.jackson.databind.JsonNode;

/** Kafka event binding selected from an AsyncAPI operation. */
public record EventOperation(Direction direction, String topic) {
    public enum Direction { SEND, RECEIVE }

    public EventOperation {
        if (direction == null || topic == null || !topic.matches("[A-Za-z0-9._-]{1,249}") || ".".equals(topic) || "..".equals(topic)) {
            throw new IllegalArgumentException("Invalid Kafka event binding");
        }
    }

    public static EventOperation fromSnapshot(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return null;
        if (!node.isObject() || !node.path("direction").isTextual() || !node.path("topic").isTextual()) {
            throw new IllegalArgumentException("Invalid Kafka event metadata in snapshot");
        }
        return new EventOperation(Direction.valueOf(node.path("direction").asText()), node.path("topic").asText());
    }
}
