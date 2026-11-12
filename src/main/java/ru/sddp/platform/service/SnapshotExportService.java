package ru.sddp.platform.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sddp.platform.api.TopologySnapshot;
import ru.sddp.platform.repository.*;
import java.util.UUID;

@Service
public class SnapshotExportService {
    private final ProjectService projects;
    private final ServiceNodeRepository nodes;
    private final ContractRepository contracts;
    private final TopologyConnectionRepository connections;
    public SnapshotExportService(ProjectService projects, ServiceNodeRepository nodes, ContractRepository contracts, TopologyConnectionRepository connections) {
        this.projects=projects; this.nodes=nodes; this.contracts=contracts; this.connections=connections;
    }
    @Transactional(readOnly=true)
    public TopologySnapshot export(UUID id) {
        var project = projects.require(id);
        return new TopologySnapshot(1, project.getName(),
            nodes.findByProjectId(id).stream().map(n -> new TopologySnapshot.Node(n.getId(), n.getName(), n.isExternal())).toList(),
            contracts.findByProjectId(id).stream().map(c -> new TopologySnapshot.Contract(c.getId(), c.getName(), c.getVersion(), c.getRequestSchema(), c.getResponseSchema())).toList(),
            connections.findByProjectId(id).stream().map(c -> new TopologySnapshot.Connection(c.getEnvironment(), c.getSource().getId(), c.getTarget().getId(), c.getContract() == null ? null : c.getContract().getId(), c.getTransportType(), c.getConnectionSettings(), c.getMappingRules())).toList());
    }
}
