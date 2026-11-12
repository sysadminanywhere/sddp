package ru.sddp.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.*;

public final class ApiModels {
    private ApiModels() { }
    public record CreateProjectRequest(@NotBlank String name) { }
    public record ProjectResponse(UUID id, String name, Instant createdAt) { }
    public record TopologyResponse(UUID projectId, String environment, List<Node> nodes, List<Edge> edges) { }
    public record Node(UUID id, String name, boolean external) { }
    public record Edge(UUID id, UUID source, UUID target, String transportType, JsonNode connectionSettings, JsonNode mappingRules) { }
    public record IrManifest(String system_name, String environment, Instant generated_at, List<IrService> services) { }
    public record IrService(String service_name, boolean is_external, List<IrConnection> outbound_connections) { }
    public record IrConnection(String client_name, String target_service_name, String transport_type, JsonNode connection_settings, List<IrMethod> methods, @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) JsonNode mapping_rules) { }
    public record IrMethod(String method_name, String pattern, JsonNode request_schema, JsonNode response_schema) { }
}
