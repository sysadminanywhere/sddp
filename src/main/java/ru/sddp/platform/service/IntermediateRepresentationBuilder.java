package ru.sddp.platform.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.networknt.schema.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sddp.platform.api.ApiModels;
import ru.sddp.platform.domain.ServiceApi;
import ru.sddp.platform.domain.ServiceType;
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
    private final EnvironmentService environments;
    private final ObjectMapper mapper;
    public IntermediateRepresentationBuilder(ProjectService p, ServiceNodeRepository n, TopologyConnectionRepository c,
                                             EnvironmentService environments, ObjectMapper mapper) {
        projectService=p; nodes=n; connections=c; this.environments=environments; this.mapper=mapper;
    }

    @Transactional(readOnly = true)
    public ApiModels.IrManifest build(UUID projectId, String environment) {
        var project = projectService.require(projectId);
        String requested = environment == null || environment.isBlank() ? environments.defaultCode(projectId) : environment;
        String code = environments.require(projectId, requested).getCode();
        var allNodes = nodes.findByProjectIdOrderByNameAsc(projectId);
        var outgoing = connections.findByProjectIdAndEnvironment(projectId, code).stream().collect(Collectors.groupingBy(c -> c.getSource().getId()));
        var services = allNodes.stream().map(node -> new ApiModels.IrService(node.getName(), node.getServiceType() == ServiceType.EXTERNAL,
            outgoing.getOrDefault(node.getId(), List.of()).stream().map(this::connection).toList())).toList();
        var manifest = new ApiModels.IrManifest(project.getName(), code, Instant.now(), services);
        validate(manifest);
        return manifest;
    }

    private ApiModels.IrConnection connection(TopologyConnection c) {
        if (!TRANSPORTS.contains(c.getTransportType())) throw new IllegalArgumentException("Unsupported transport: " + c.getTransportType());
        JsonNode settings = c.getConnectionSettings();
        require(settings, "host", "port", "target_endpoint");
        ServiceApi targetApi = c.getTargetApi();
        if (targetApi == null) throw new IllegalArgumentException("Connection " + c.getId() + " has no target API");
        var method = new ApiModels.IrMethod(targetApi.getName(), targetApi.getPattern().name(),
            targetApi.getRequestSchema(), targetApi.getResponseSchema() == null ? mapper.createObjectNode() : targetApi.getResponseSchema(),
            "REST".equals(c.getTransportType()) ? targetApi.getHttpOperation() : null,
            "KAFKA".equals(c.getTransportType()) ? targetApi.getEventOperation() : null);
        return new ApiModels.IrConnection(c.getSource().getName(), c.getTarget().getName(), c.getTransportType(), settings, List.of(method), c.getMappingRules());
    }

    private void validate(ApiModels.IrManifest manifest) {
        if (manifest.system_name() == null || manifest.system_name().isBlank() || manifest.services() == null) throw new IllegalArgumentException("Invalid IR manifest");
        if (manifest.environment() == null || manifest.environment().isBlank()) throw new IllegalArgumentException("Invalid environment");
        try (var schemaStream = getClass().getResourceAsStream("/schemas/ir-schema.json")) {
            if (schemaStream == null) throw new IllegalStateException("IR schema resource is missing");
            Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7).getSchema(mapper.readTree(schemaStream));
            var violations = schema.validate(mapper.valueToTree(manifest));
            if (!violations.isEmpty()) throw new IllegalArgumentException("IR schema validation failed: " + violations);
        } catch (java.io.IOException e) { throw new IllegalStateException("Cannot load IR schema", e); }
    }

    private void require(JsonNode node, String... fields) { for (String field : fields) if (node == null || !node.has(field)) throw new IllegalArgumentException("Missing connection setting: " + field); }
}
