package ru.sddp.platform.api;

import tools.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;

public record TopologySnapshot(int formatVersion, String projectName, List<Environment> environments,
                               List<Service> services, List<Api> apis, List<Connection> connections) {
    public record Environment(String code, String name, int position, boolean isProtected) { }
    public record Service(UUID id, String name, String serviceType, String description, Integer positionX, Integer positionY) { }
    public record Api(UUID id, UUID serviceId, String name, String version, String description, String pattern,
                      JsonNode requestSchema, JsonNode responseSchema,
                      @JsonInclude(JsonInclude.Include.NON_NULL) HttpOperation httpOperation) {
        public Api(UUID id, UUID serviceId, String name, String version, String description, String pattern,
                   JsonNode requestSchema, JsonNode responseSchema) {
            this(id, serviceId, name, version, description, pattern, requestSchema, responseSchema, null);
        }
    }
    public record Connection(String environment, UUID sourceId, UUID targetId, UUID sourceApiId, UUID targetApiId,
                             String transportType, JsonNode connectionSettings, JsonNode mappingRules) { }
}
