package ru.sddp.platform.web;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.sddp.platform.api.ApiModels;
import ru.sddp.platform.repository.ServiceNodeRepository;
import ru.sddp.platform.repository.TopologyConnectionRepository;
import ru.sddp.platform.service.IntermediateRepresentationBuilder;
import ru.sddp.platform.service.ProjectService;
import java.util.UUID;

@RestController @RequestMapping("/api/v1/projects/{projectId}")
public class TopologyController {
    private final ProjectService projects; private final ServiceNodeRepository nodes; private final TopologyConnectionRepository connections; private final IntermediateRepresentationBuilder ir; private final ObjectMapper mapper; private final ru.sddp.platform.service.SnapshotExportService snapshots;
    public TopologyController(ProjectService p, ServiceNodeRepository n, TopologyConnectionRepository c, IntermediateRepresentationBuilder ir, ObjectMapper mapper, ru.sddp.platform.service.SnapshotExportService snapshots) { projects=p; nodes=n; connections=c; this.ir=ir; this.mapper=mapper; this.snapshots=snapshots; }
    @GetMapping("/topology")
    public ApiModels.TopologyResponse topology(@PathVariable UUID projectId, @RequestParam(defaultValue = "DEV") String environment) {
        projects.require(projectId);
        var nodeResponse = nodes.findByProjectId(projectId).stream().map(n -> new ApiModels.Node(n.getId(), n.getName(), n.isExternal())).toList();
        var edgeResponse = connections.findByProjectIdAndEnvironment(projectId, environment).stream().map(c -> new ApiModels.Edge(c.getId(), c.getSource().getId(), c.getTarget().getId(), c.getTransportType(), c.getConnectionSettings(), c.getMappingRules())).toList();
        return new ApiModels.TopologyResponse(projectId, environment, nodeResponse, edgeResponse);
    }
    @PostMapping("/ir")
    public ApiModels.IrManifest ir(@PathVariable UUID projectId, @RequestParam(defaultValue = "DEV") String environment) { return ir.build(projectId, environment); }
    @PostMapping(value = "/snapshot", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> snapshot(@PathVariable UUID projectId, @RequestParam(defaultValue = "DEV") String environment) throws com.fasterxml.jackson.core.JsonProcessingException {
        byte[] payload = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(snapshots.export(projectId));
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=topology-snapshot.json").body(payload);
    }
}
