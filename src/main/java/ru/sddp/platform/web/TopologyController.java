package ru.sddp.platform.web;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.sddp.platform.api.ApiModels;
import ru.sddp.platform.domain.ServiceApi;
import ru.sddp.platform.domain.TopologyConnection;
import ru.sddp.platform.repository.ServiceNodeRepository;
import ru.sddp.platform.repository.TopologyConnectionRepository;
import ru.sddp.platform.service.EnvironmentService;
import ru.sddp.platform.service.IntermediateRepresentationBuilder;
import ru.sddp.platform.service.ProjectService;
import java.util.UUID;

@RestController @RequestMapping("/api/v1/projects/{projectId}")
public class TopologyController {
    private final ProjectService projects; private final ServiceNodeRepository nodes; private final TopologyConnectionRepository connections; private final IntermediateRepresentationBuilder ir; private final ObjectMapper mapper; private final ru.sddp.platform.service.SnapshotExportService snapshots; private final EnvironmentService environments;
    public TopologyController(ProjectService p, ServiceNodeRepository n, TopologyConnectionRepository c, IntermediateRepresentationBuilder ir, ObjectMapper mapper, ru.sddp.platform.service.SnapshotExportService snapshots, EnvironmentService environments) { projects=p; nodes=n; connections=c; this.ir=ir; this.mapper=mapper; this.snapshots=snapshots; this.environments=environments; }

    @GetMapping("/topology")
    @Transactional(readOnly = true)
    public ApiModels.TopologyResponse topology(@PathVariable UUID projectId, @RequestParam(required = false) String environment) {
        projects.require(projectId);
        String code = environment == null || environment.isBlank()
            ? environments.defaultCode(projectId)
            : environments.require(projectId, environment).getCode();
        var nodeResponse = nodes.findByProjectIdOrderByNameAsc(projectId).stream()
            .map(n -> new ApiModels.Node(n.getId(), n.getName(), n.getServiceType().name(), n.getDescription())).toList();
        var edgeResponse = connections.findByProjectIdAndEnvironment(projectId, code).stream().map(this::edge).toList();
        return new ApiModels.TopologyResponse(projectId, code, nodeResponse, edgeResponse);
    }

    @PostMapping("/ir")
    public ApiModels.IrManifest ir(@PathVariable UUID projectId, @RequestParam(required = false) String environment) { return ir.build(projectId, environment); }

    @PostMapping(value = "/snapshot", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> snapshot(@PathVariable UUID projectId,
                                           @RequestParam(required = false) String environment) throws com.fasterxml.jackson.core.JsonProcessingException {
        byte[] payload = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(snapshots.export(projectId, environment));
        String suffix = environment == null || environment.isBlank()
            ? "" : "-" + environment.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=topology-snapshot" + suffix + ".json")
            .body(payload);
    }

    private ApiModels.Edge edge(TopologyConnection connection) {
        return new ApiModels.Edge(connection.getId(), connection.getSource().getId(), connection.getTarget().getId(),
            apiId(connection.getSourceApi()), apiId(connection.getTargetApi()),
            apiLabel(connection.getSourceApi()), apiLabel(connection.getTargetApi()),
            connection.getTransportType(), connection.getConnectionSettings(), connection.getMappingRules());
    }

    private UUID apiId(ServiceApi api) { return api == null ? null : api.getId(); }
    private String apiLabel(ServiceApi api) { return api == null ? null : api.getName() + " v" + api.getVersion(); }
}
