package ru.sddp.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sddp.platform.api.ApiModels;
import ru.sddp.platform.api.TopologySnapshot;
import ru.sddp.platform.domain.*;
import ru.sddp.platform.repository.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class SnapshotImportService {
    private final ObjectMapper mapper; private final ProjectRepository projects; private final ServiceNodeRepository nodes; private final ContractRepository contracts; private final TopologyConnectionRepository connections; private final ContractSchemaValidator schemaValidator; private final ConnectionValidator connectionValidator;
    public SnapshotImportService(ObjectMapper mapper, ProjectRepository p, ServiceNodeRepository n, ContractRepository c, TopologyConnectionRepository tc, ContractSchemaValidator schemaValidator, ConnectionValidator connectionValidator) { this.mapper=mapper; projects=p; nodes=n; contracts=c; connections=tc; this.schemaValidator=schemaValidator; this.connectionValidator=connectionValidator; }
    @Transactional
    public ApiModels.ProjectResponse importSnapshot(JsonNode snapshot) {
        try {
            TopologySnapshot manifest = mapper.treeToValue(snapshot, TopologySnapshot.class);
            if (manifest.formatVersion() != 1 || manifest.projectName() == null || manifest.projectName().isBlank() || manifest.services() == null || manifest.contracts() == null || manifest.connections() == null) throw new IllegalArgumentException("Invalid topology snapshot");
            String importedName = manifest.projectName();
            if (projects.existsByName(importedName)) importedName = importedName + "-imported-" + UUID.randomUUID().toString().substring(0, 8);
            Project project = projects.save(new Project(importedName));
            Map<UUID, ServiceNode> serviceMap = new HashMap<>();
            Map<UUID, Contract> contractMap = new HashMap<>();
            for (var service : manifest.services()) {
                if (service.id() == null || serviceMap.containsKey(service.id())) throw new IllegalArgumentException("Invalid service ID");
                serviceMap.put(service.id(), nodes.save(new ServiceNode(project, service.name(), service.external())));
            }
            for (var item : manifest.contracts()) {
                if (item.id() == null || contractMap.containsKey(item.id())) throw new IllegalArgumentException("Invalid contract ID");
                schemaValidator.validate(item.requestSchema());
                if (item.responseSchema() != null && !item.responseSchema().isNull()) schemaValidator.validate(item.responseSchema());
                contractMap.put(item.id(), contracts.save(new Contract(project, item.name(), item.version(), item.requestSchema(), item.responseSchema())));
            }
            for (var connection : manifest.connections()) {
                connectionValidator.validate(connection.environment(), connection.transportType(), connection.connectionSettings(), connection.mappingRules());
                ServiceNode source = serviceMap.get(connection.sourceId()); ServiceNode target = serviceMap.get(connection.targetId());
                if (source == null || target == null) throw new IllegalArgumentException("Snapshot references unknown service");
                Contract contract = connection.contractId() == null ? null : contractMap.get(connection.contractId());
                if (connection.contractId() != null && contract == null) throw new IllegalArgumentException("Snapshot references unknown contract");
                connections.save(new TopologyConnection(project, connection.environment(), source, target, contract, connection.transportType(), connection.connectionSettings(), connection.mappingRules()));
            }
            return new ApiModels.ProjectResponse(project.getId(), project.getName(), project.getCreatedAt());
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("Invalid topology snapshot: " + e.getMessage(), e); }
    }
}
