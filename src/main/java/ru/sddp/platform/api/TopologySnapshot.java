package ru.sddp.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

public record TopologySnapshot(int formatVersion, String projectName, List<String> environments,
                               List<Service> services, List<Api> apis, List<Connection> connections) {
    public TopologySnapshot(int formatVersion, String projectName, List<Service> services, List<Api> apis,
                            List<Connection> connections) {
        this(formatVersion, projectName, List.of(), services, apis, connections);
    }

    public record Service(UUID id, String name, String serviceType, String description) { }
    public record Api(UUID id, UUID serviceId, String name, String version, String description, String pattern,
                      JsonNode requestSchema, JsonNode responseSchema) { }
    public record Connection(String environment, UUID sourceId, UUID targetId, UUID sourceApiId, UUID targetApiId,
                             String transportType, JsonNode connectionSettings, JsonNode mappingRules) { }
}
