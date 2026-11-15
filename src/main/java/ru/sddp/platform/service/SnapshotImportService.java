package ru.sddp.platform.service;

import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sddp.platform.api.ApiModels;
import ru.sddp.platform.domain.*;
import ru.sddp.platform.repository.*;
import java.util.*;

@Service
public class SnapshotImportService {
    private final ProjectRepository projects;
    private final ServiceNodeRepository nodes;
    private final ServiceApiRepository apis;
    private final TopologyConnectionRepository connections;
    private final ApiSchemaValidator schemaValidator;
    private final ConnectionValidator connectionValidator;
    private final ProjectEnvironmentRepository environmentRepository;

    public SnapshotImportService(ProjectRepository p, ServiceNodeRepository n, ServiceApiRepository a,
                                 TopologyConnectionRepository tc, ApiSchemaValidator schemaValidator,
                                 ConnectionValidator connectionValidator,
                                 ProjectEnvironmentRepository environmentRepository) {
        projects=p; nodes=n; apis=a; connections=tc;
        this.schemaValidator=schemaValidator; this.connectionValidator=connectionValidator;
        this.environmentRepository = environmentRepository;
    }

    @Transactional
    public ApiModels.ProjectResponse importSnapshot(JsonNode snapshot) {
        if (snapshot == null || !snapshot.isObject()) throw new IllegalArgumentException("Invalid topology snapshot");
        JsonNode versionNode = snapshot.get("formatVersion");
        if (versionNode == null || !versionNode.isIntegralNumber() || !versionNode.canConvertToInt())
            throw new IllegalArgumentException("Invalid topology snapshot: formatVersion must be an integer");
        int version = versionNode.intValue();
        if (version != 1) throw new IllegalArgumentException("Unsupported topology snapshot format version: " + version);
        String projectName = requiredText(snapshot, "projectName");
        String importedName = projectName;
        if (projects.existsByName(importedName)) importedName = importedName + "-imported-" + UUID.randomUUID().toString().substring(0, 8);
        Project project = projects.save(new Project(importedName));
        JsonNode environmentNodes = requiredArray(snapshot, "environments");
        if (environmentNodes.isEmpty()) throw new IllegalArgumentException("Invalid topology snapshot: environments must not be empty");
        Set<String> environmentCodes = new HashSet<>();
        for (JsonNode environment : environmentNodes) {
            if (!environment.isObject()) throw new IllegalArgumentException("Invalid topology snapshot: environment must be an object");
            String code = EnvironmentService.normalizeCode(requiredText(environment, "code"));
            if (!environmentCodes.add(code)) throw new IllegalArgumentException("Duplicate environment: " + code);
            JsonNode position = environment.get("position"), protectedFlag = environment.get("isProtected");
            if (position == null || !position.isIntegralNumber() || !position.canConvertToInt()) throw new IllegalArgumentException("Invalid environment position");
            if (protectedFlag == null || !protectedFlag.isBoolean()) throw new IllegalArgumentException("Invalid environment isProtected");
            environmentRepository.save(new ProjectEnvironment(project, code, requiredText(environment, "name"), position.intValue(), protectedFlag.booleanValue()));
        }
        importSnapshotData(snapshot, project, environmentCodes);
        return new ApiModels.ProjectResponse(project.getId(), project.getName(), project.getCreatedAt());
    }

    private void importSnapshotData(JsonNode root, Project project, Set<String> environmentCodes) {
        Map<UUID, ServiceNode> serviceMap = new HashMap<>();
        for (JsonNode service : requiredArray(root, "services")) {
            UUID id = requiredUuid(service, "id");
            if (serviceMap.containsKey(id)) throw new IllegalArgumentException("Invalid service ID");
            ServiceType type = parseEnum(ServiceType.class, requiredText(service, "serviceType"), "service type");
            ServiceNode node = new ServiceNode(project, requiredText(service, "name"), type, optionalText(service, "description"));
            node.setPosition(optionalInteger(service, "positionX"), optionalInteger(service, "positionY"));
            serviceMap.put(id, nodes.save(node));
        }

        Map<UUID, ServiceApi> apiMap = new HashMap<>();
        for (JsonNode api : requiredArray(root, "apis")) {
            UUID id = requiredUuid(api, "id");
            if (apiMap.containsKey(id)) throw new IllegalArgumentException("Invalid API ID");
            UUID serviceId = requiredUuid(api, "serviceId");
            ServiceNode service = serviceMap.get(serviceId);
            if (service == null) throw new IllegalArgumentException("Snapshot references unknown service");
            ApiPattern pattern = parseEnum(ApiPattern.class, requiredText(api, "pattern"), "API pattern");
            schemaValidator.validate(requiredSchema(api, "requestSchema"));
            JsonNode response = optionalSchema(api, "responseSchema");
            if (response != null) schemaValidator.validate(response);
            apiMap.put(id, apis.save(new ServiceApi(service, requiredText(api, "name"), requiredText(api, "version"),
                optionalText(api, "description"), pattern, requiredSchema(api, "requestSchema"), response)));
        }

        for (JsonNode connection : requiredArray(root, "connections")) {
            String environment = EnvironmentService.normalizeCode(requiredText(connection, "environment"));
            if (!environmentCodes.contains(environment)) throw new IllegalArgumentException("Snapshot references unknown environment: " + environment);
            String transportType = requiredText(connection, "transportType");
            JsonNode settings = requiredSchema(connection, "connectionSettings");
            JsonNode mapping = optionalSchema(connection, "mappingRules");
            connectionValidator.validate(environment, transportType, settings, mapping);
            ServiceNode source = serviceMap.get(requiredUuid(connection, "sourceId"));
            ServiceNode target = serviceMap.get(requiredUuid(connection, "targetId"));
            if (source == null || target == null) throw new IllegalArgumentException("Snapshot references unknown service");
            ServiceApi sourceApi = resolveApi(apiMap, optionalUuid(connection, "sourceApiId"), source, "source");
            ServiceApi targetApi = resolveApi(apiMap, optionalUuid(connection, "targetApiId"), target, "target");
            connections.save(new TopologyConnection(project, environment, source, target, sourceApi, targetApi,
                transportType, settings, mapping));
        }
    }

    private ServiceApi resolveApi(Map<UUID, ServiceApi> apiMap, UUID apiId, ServiceNode service, String side) {
        if (apiId == null) return null;
        ServiceApi api = apiMap.get(apiId);
        if (api == null || !api.getService().getId().equals(service.getId())) {
            throw new IllegalArgumentException("Snapshot " + side + " API does not belong to the selected service");
        }
        return api;
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String value, String label) {
        try { return Enum.valueOf(type, value); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid " + label + ": " + value); }
    }

    private JsonNode requiredArray(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isArray()) throw new IllegalArgumentException("Invalid topology snapshot: " + field + " must be an array");
        return value;
    }

    private Integer optionalInteger(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw new IllegalArgumentException("Invalid integer in " + field);
        return value.intValue();
    }

    private UUID requiredUuid(JsonNode node, String field) {
        String value = requiredText(node, field);
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid UUID in " + field + ": " + value); }
    }

    private UUID optionalUuid(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null) return null;
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid UUID in " + field + ": " + value); }
    }

    private String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null) throw new IllegalArgumentException("Invalid topology snapshot: missing " + field);
        return value;
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException("Invalid topology snapshot: " + field + " must be a string");
        return value.asText().isBlank() ? null : value.asText();
    }

    private JsonNode requiredSchema(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) throw new IllegalArgumentException("Invalid topology snapshot: missing " + field);
        return value;
    }

    private JsonNode optionalSchema(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value;
    }
}
