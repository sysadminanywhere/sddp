package ru.sddp.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.*;

public final class ApiModels {
    private ApiModels() { }

    public record CreateProjectRequest(@NotBlank String name) { }
    public record ProjectResponse(UUID id, String name, Instant createdAt) { }

    public record ServiceResponse(UUID id, String name, String serviceType, String description, Instant createdAt, long apiCount, Integer positionX, Integer positionY) { }
    public record ServiceApiResponse(UUID id, UUID serviceId, String name, String version, String description,
                                     String pattern, JsonNode requestSchema, JsonNode responseSchema, Instant createdAt) { }

    public record EnvironmentResponse(UUID id, UUID projectId, String code, String name, int position,
                                      boolean isProtected, long connectionCount) { }
    public record EnvironmentComparison(UUID projectId, String sourceEnvironment, String targetEnvironment,
                                        int sourceConnections, int targetConnections,
                                        List<EnvironmentDifference> differences) { }
    public record EnvironmentDifference(String kind, UUID sourceConnectionId, UUID targetConnectionId,
                                        String sourceLabel, String targetLabel, List<String> changedFields) { }
    public record ConnectionCopyResult(String sourceEnvironment, String targetEnvironment, String mode,
                                       int copied, int skipped, int removed) { }

    public record TopologyResponse(UUID projectId, String environment, List<Node> nodes, List<Edge> edges) { }
    public record Node(UUID id, String name, String serviceType, String description, Integer positionX, Integer positionY) { }
    public record Edge(UUID id, UUID source, UUID target, UUID sourceApiId, UUID targetApiId,
                       String sourceApi, String targetApi, String transportType,
                       JsonNode connectionSettings, JsonNode mappingRules) { }
    public record ConnectionDetail(String environment, Edge connection) { }

    public record IrManifest(String system_name, String environment, Instant generated_at, List<IrService> services) { }
    public record IrService(String service_name, boolean is_external, List<IrConnection> outbound_connections) { }
    public record IrConnection(String client_name, String target_service_name, String transport_type, JsonNode connection_settings, List<IrMethod> methods, @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) JsonNode mapping_rules) { }
    public record IrMethod(String method_name, String pattern, JsonNode request_schema, JsonNode response_schema) { }
}
