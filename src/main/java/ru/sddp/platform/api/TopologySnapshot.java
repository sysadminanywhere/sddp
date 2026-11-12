package ru.sddp.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

public record TopologySnapshot(int formatVersion, String projectName, List<Node> services, List<Contract> contracts, List<Connection> connections) {
    public record Node(UUID id, String name, boolean external) { }
    public record Contract(UUID id, String name, String version, JsonNode requestSchema, JsonNode responseSchema) { }
    public record Connection(String environment, UUID sourceId, UUID targetId, UUID contractId, String transportType, JsonNode connectionSettings, JsonNode mappingRules) { }
}
