package ru.sddp.platform.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sddp.platform.api.TopologySnapshot;
import ru.sddp.platform.domain.ProjectEnvironment;
import ru.sddp.platform.repository.*;
import java.util.List;
import java.util.UUID;

@Service
public class SnapshotExportService {
    private final ProjectService projects;
    private final ServiceNodeRepository nodes;
    private final ServiceApiRepository apis;
    private final TopologyConnectionRepository connections;
    private final ProjectEnvironmentRepository environments;

    public SnapshotExportService(ProjectService projects, ServiceNodeRepository nodes, ServiceApiRepository apis,
                                 TopologyConnectionRepository connections, ProjectEnvironmentRepository environments) {
        this.projects=projects; this.nodes=nodes; this.apis=apis; this.connections=connections; this.environments=environments;
    }

    @Transactional(readOnly=true)
    public TopologySnapshot export(UUID id) {
        return export(id, null);
    }

    @Transactional(readOnly=true)
    public TopologySnapshot export(UUID id, String environment) {
        var project = projects.require(id);
        List<ProjectEnvironment> projectEnvironments = environments.findByProjectIdOrderByPositionAscCodeAsc(id);
        String selected = environment == null || environment.isBlank() ? null : requireEnvironment(id, environment).getCode();
        var snapshotEnvironments = projectEnvironments.stream()
            .filter(e -> selected == null || selected.equals(e.getCode()))
            .map(e -> new TopologySnapshot.Environment(e.getCode(), e.getName(), e.getPosition(), e.isProtected())).toList();
        var projectConnections = selected == null
            ? connections.findByProjectId(id)
            : connections.findByProjectIdAndEnvironment(id, selected);
        return new TopologySnapshot(1, project.getName(), snapshotEnvironments,
            nodes.findByProjectIdOrderByNameAsc(id).stream()
                .map(n -> new TopologySnapshot.Service(n.getId(), n.getName(), n.getServiceType().name(), n.getDescription(), n.getPositionX(), n.getPositionY())).toList(),
            apis.findByServiceProjectIdOrderByServiceNameAscNameAscVersionAsc(id).stream()
                .map(a -> new TopologySnapshot.Api(a.getId(), a.getService().getId(), a.getName(), a.getVersion(),
                    a.getDescription(), a.getPattern().name(), a.getRequestSchema(), a.getResponseSchema())).toList(),
            projectConnections.stream()
                .map(c -> new TopologySnapshot.Connection(c.getEnvironment(), c.getSource().getId(), c.getTarget().getId(),
                    c.getSourceApi() == null ? null : c.getSourceApi().getId(),
                    c.getTargetApi() == null ? null : c.getTargetApi().getId(),
                    c.getTransportType(), c.getConnectionSettings(), c.getMappingRules())).toList());
    }

    private ProjectEnvironment requireEnvironment(UUID projectId, String code) {
        String value = code == null ? "" : code.trim();
        return environments.findByProjectIdAndCode(projectId, value)
            .or(() -> environments.findByProjectIdAndCode(projectId, value.toUpperCase(java.util.Locale.ROOT)))
            .orElseThrow(() -> new IllegalArgumentException("Unknown environment: " + code));
    }
}
