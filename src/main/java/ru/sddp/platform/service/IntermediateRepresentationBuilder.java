package ru.sddp.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sddp.platform.api.ApiModels;
import ru.sddp.platform.domain.TopologyConnection;
import ru.sddp.platform.repository.ServiceNodeRepository;
import ru.sddp.platform.repository.TopologyConnectionRepository;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class IntermediateRepresentationBuilder {
    private static final Set<String> TRANSPORTS = Set.of("REST", "GRPC", "KAFKA", "RABBITMQ");
    private final ProjectService projectService;
    private final ServiceNodeRepository nodes;
    private final TopologyConnectionRepository connections;
    private final ObjectMapper mapper;
    public IntermediateRepresentationBuilder(ProjectService p, ServiceNodeRepository n, TopologyConnectionRepository c, ObjectMapper mapper) { projectService=p; nodes=n; connections=c; this.mapper=mapper; }

    @Transactional(readOnly = true)
    public ApiModels.IrManifest build(UUID projectId, String environment) {
        var project = projectService.require(projectId);
        var allNodes = nodes.findByProjectId(projectId);
        var outgoing = connections.findByProjectIdAndEnvironment(projectId, environment).stream().collect(Collectors.groupingBy(c -> c.getSource().getId()));
        var services = allNodes.stream().map(node -> new ApiModels.IrService(node.getName(), node.isExternal(), outgoing.getOrDefault(node.getId(), List.of()).stream().map(this::connection).toList())).toList();
        var manifest = new ApiModels.IrManifest(project.getName(), environment, Instant.now(), services);
        validate(manifest);
        return manifest;
    }
    private ApiModels.IrConnection connection(TopologyConnection c) {
        if (!TRANSPORTS.contains(c.getTransportType())) throw new IllegalArgumentException("Unsupported transport: " + c.getTransportType());
        JsonNode settings = c.getConnectionSettings();
        require(settings, "host", "port", "target_endpoint");
        var contract = c.getContract();
        if (contract == null) throw new IllegalArgumentException("Connection " + c.getId() + " has no contract");
        String pattern = Set.of("KAFKA", "RABBITMQ").contains(c.getTransportType()) ? "PUB_SUB" : "REQUEST_RESPONSE";
        var method = new ApiModels.IrMethod(contract.getName(), pattern, contract.getRequestSchema(), contract.getResponseSchema() == null ? mapper.createObjectNode() : contract.getResponseSchema());
        return new ApiModels.IrConnection(c.getSource().getName(), c.getTarget().getName(), c.getTransportType(), settings, List.of(method), c.getMappingRules());
    }
    private void validate(ApiModels.IrManifest manifest) {
        if (manifest.system_name() == null || manifest.system_name().isBlank() || manifest.services() == null) throw new IllegalArgumentException("Invalid IR manifest");
        if (!Set.of("DEV", "STAGE", "PROD").contains(manifest.environment())) throw new IllegalArgumentException("Invalid environment: " + manifest.environment());
        try (var schemaStream = getClass().getResourceAsStream("/schemas/ir-schema.json")) {
            if (schemaStream == null) throw new IllegalStateException("IR schema resource is missing");
            JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(mapper.readTree(schemaStream));
            var violations = schema.validate(mapper.valueToTree(manifest));
            if (!violations.isEmpty()) throw new IllegalArgumentException("IR schema validation failed: " + violations);
        } catch (java.io.IOException e) { throw new IllegalStateException("Cannot load IR schema", e); }
    }
    private void require(JsonNode node, String... fields) { for (String field : fields) if (node == null || !node.has(field)) throw new IllegalArgumentException("Missing connection setting: " + field); }
}
