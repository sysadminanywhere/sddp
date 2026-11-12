package ru.sddp.platform.service;

import ru.sddp.platform.api.TopologyRequests;
import ru.sddp.platform.domain.*;
import ru.sddp.platform.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Set;

@Service
public class TopologyWriteService {
    private static final Set<String> ENVIRONMENTS = Set.of("DEV", "STAGE", "PROD");
    private static final Set<String> TRANSPORTS = Set.of("REST", "GRPC", "KAFKA", "RABBITMQ");
    private final ProjectService projects; private final ServiceNodeRepository nodes; private final ContractRepository contracts; private final TopologyConnectionRepository connections; private final ContractSchemaValidator validator; private final ConnectionValidator connectionValidator;
    public TopologyWriteService(ProjectService p, ServiceNodeRepository n, ContractRepository c, TopologyConnectionRepository tc, ContractSchemaValidator validator, ConnectionValidator connectionValidator) { projects=p; nodes=n; contracts=c; connections=tc; this.validator=validator; this.connectionValidator=connectionValidator; }
    @Transactional public ServiceNode addService(java.util.UUID projectId, TopologyRequests.CreateServiceRequest request) { return nodes.save(new ServiceNode(projects.require(projectId), request.name(), request.external())); }
    @Transactional public Contract addContract(java.util.UUID projectId, TopologyRequests.CreateContractRequest request) { validator.validate(request.requestSchema()); if (request.responseSchema() != null) validator.validate(request.responseSchema()); return contracts.save(new Contract(projects.require(projectId), request.name(), request.version(), request.requestSchema(), request.responseSchema())); }
    @Transactional public TopologyConnection addConnection(java.util.UUID projectId, TopologyRequests.CreateConnectionRequest request) {
        if (!ENVIRONMENTS.contains(request.environment())) throw new IllegalArgumentException("Invalid environment: " + request.environment());
        if (!TRANSPORTS.contains(request.transportType())) throw new IllegalArgumentException("Unsupported transport: " + request.transportType());
        connectionValidator.validate(request.environment(), request.transportType(), request.connectionSettings(), request.mappingRules());
        Project project = projects.require(projectId);
        ServiceNode source = nodes.findByIdAndProjectId(request.sourceServiceId(), projectId).orElseThrow(() -> new ResourceNotFoundException("Source service not found"));
        ServiceNode target = nodes.findByIdAndProjectId(request.targetServiceId(), projectId).orElseThrow(() -> new ResourceNotFoundException("Target service not found"));
        Contract contract = request.contractId() == null ? null : contracts.findByIdAndProjectId(request.contractId(), projectId).orElseThrow(() -> new ResourceNotFoundException("Contract not found"));
        return connections.save(new TopologyConnection(project, request.environment(), source, target, contract, request.transportType(), request.connectionSettings(), request.mappingRules()));
    }
}
