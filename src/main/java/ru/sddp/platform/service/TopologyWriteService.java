package ru.sddp.platform.service;

import ru.sddp.platform.api.TopologyRequests;
import ru.sddp.platform.api.ApiModels;
import ru.sddp.platform.domain.*;
import ru.sddp.platform.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Set;
import java.util.UUID;

@Service
public class TopologyWriteService {
    private static final Set<String> TRANSPORTS = Set.of("REST", "GRPC", "KAFKA", "RABBITMQ");

    private final ProjectService projects;
    private final ServiceCatalogService catalog;
    private final ServiceApiRepository apis;
    private final TopologyConnectionRepository connections;
    private final ConnectionValidator connectionValidator;
    private final EnvironmentService environments;

    public TopologyWriteService(ProjectService projects, ServiceCatalogService catalog, ServiceApiRepository apis,
                                TopologyConnectionRepository connections, ConnectionValidator connectionValidator,
                                EnvironmentService environments) {
        this.projects = projects;
        this.catalog = catalog;
        this.apis = apis;
        this.connections = connections;
        this.connectionValidator = connectionValidator;
        this.environments = environments;
    }

    @Transactional
    public ApiModels.Edge addConnection(UUID projectId, TopologyRequests.CreateConnectionRequest request,
                                        boolean confirmProtected) {
        Project project = projects.require(projectId);
        String environment = environments.requireWritable(projectId, request.environment(), confirmProtected).getCode();
        validateTransport(environment, request.transportType(), request.connectionSettings(), request.mappingRules());
        ServiceNode source = catalog.requireService(projectId, request.sourceServiceId());
        ServiceNode target = catalog.requireService(projectId, request.targetServiceId());
        if (source.getId().equals(target.getId())) throw new IllegalArgumentException("Source and target service must differ");
        ServiceApi sourceApi = resolveApi(request.sourceApiId(), source.getId(), "Source");
        ServiceApi targetApi = requireApi(request.targetApiId(), target.getId(), "Target");
        TopologyConnection connection = new TopologyConnection(project, environment, source, target,
            sourceApi, targetApi, request.transportType(), request.connectionSettings(), request.mappingRules());
        connections.save(connection);
        return edge(connection);
    }

    @Transactional
    public ApiModels.Edge updateConnection(UUID projectId, UUID connectionId,
                                           TopologyRequests.CreateConnectionRequest request, boolean confirmProtected) {
        TopologyConnection connection = requireConnection(projectId, connectionId);
        environments.requireWritable(projectId, connection.getEnvironment(), confirmProtected);
        String environment = environments.requireWritable(projectId, request.environment(), confirmProtected).getCode();
        validateTransport(environment, request.transportType(), request.connectionSettings(), request.mappingRules());
        ServiceNode source = catalog.requireService(projectId, request.sourceServiceId());
        ServiceNode target = catalog.requireService(projectId, request.targetServiceId());
        if (source.getId().equals(target.getId())) throw new IllegalArgumentException("Source and target service must differ");
        ServiceApi sourceApi = resolveApi(request.sourceApiId(), source.getId(), "Source");
        ServiceApi targetApi = requireApi(request.targetApiId(), target.getId(), "Target");
        connection.update(environment, source, target, sourceApi, targetApi, request.transportType(),
            request.connectionSettings(), request.mappingRules());
        return edge(connection);
    }

    @Transactional
    public ApiModels.Edge updateConnectionMapping(UUID projectId, UUID connectionId, JsonNode mappingRules,
                                                  boolean confirmProtected) {
        TopologyConnection connection = requireConnection(projectId, connectionId);
        environments.requireWritable(projectId, connection.getEnvironment(), confirmProtected);
        connectionValidator.validate(connection.getEnvironment(), connection.getTransportType(), connection.getConnectionSettings(), mappingRules);
        connection.updateMapping(mappingRules);
        return edge(connection);
    }

    @Transactional
    public void deleteConnection(UUID projectId, UUID connectionId, boolean confirmProtected) {
        TopologyConnection connection = requireConnection(projectId, connectionId);
        environments.requireWritable(projectId, connection.getEnvironment(), confirmProtected);
        connections.delete(connection);
    }

    private void validateTransport(String environment, String transport, JsonNode settings, JsonNode mapping) {
        if (!TRANSPORTS.contains(transport)) throw new IllegalArgumentException("Unsupported transport: " + transport);
        connectionValidator.validate(environment, transport, settings, mapping);
    }

    private TopologyConnection requireConnection(UUID projectId, UUID connectionId) {
        return connections.findByIdAndProjectId(connectionId, projectId)
            .orElseThrow(() -> new ResourceNotFoundException("Connection not found: " + connectionId));
    }

    private ServiceApi resolveApi(UUID apiId, UUID serviceId, String side) {
        if (apiId == null) return null;
        return requireApi(apiId, serviceId, side);
    }

    private ServiceApi requireApi(UUID apiId, UUID serviceId, String side) {
        return apis.findByIdAndServiceId(apiId, serviceId)
            .orElseThrow(() -> new ResourceNotFoundException(side + " API does not belong to the selected service: " + apiId));
    }

    private ApiModels.Edge edge(TopologyConnection connection) {
        return new ApiModels.Edge(connection.getId(), connection.getSource().getId(), connection.getTarget().getId(),
            apiId(connection.getSourceApi()), apiId(connection.getTargetApi()),
            apiLabel(connection.getSourceApi()), apiLabel(connection.getTargetApi()),
            connection.getTransportType(), connection.getConnectionSettings(), connection.getMappingRules());
    }

    private UUID apiId(ServiceApi api) { return api == null ? null : api.getId(); }

    private String apiLabel(ServiceApi api) {
        return api == null ? null : api.getName() + " v" + api.getVersion();
    }
}
