package ru.sddp.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sddp.platform.api.ApiModels;
import ru.sddp.platform.domain.*;
import ru.sddp.platform.repository.*;
import java.util.*;

@Service
public class SnapshotImportService {
    private final ObjectMapper mapper;
    private final ProjectRepository projects;
    private final ServiceNodeRepository nodes;
    private final ServiceApiRepository apis;
    private final TopologyConnectionRepository connections;
    private final ApiSchemaValidator schemaValidator;
    private final ConnectionValidator connectionValidator;
    private final EnvironmentService environments;

    public SnapshotImportService(ObjectMapper mapper, ProjectRepository p, ServiceNodeRepository n, ServiceApiRepository a,
                                 TopologyConnectionRepository tc, ApiSchemaValidator schemaValidator,
                                 ConnectionValidator connectionValidator, EnvironmentService environments) {
        this.mapper=mapper; projects=p; nodes=n; apis=a; connections=tc;
        this.schemaValidator=schemaValidator; this.connectionValidator=connectionValidator; this.environments=environments;
    }

    @Transactional
    public ApiModels.ProjectResponse importSnapshot(JsonNode snapshot) {
        if (snapshot == null || !snapshot.isObject()) throw new IllegalArgumentException("Invalid topology snapshot");
        int version = snapshot.path("formatVersion").asInt(0);
        String projectName = requiredText(snapshot, "projectName");
        String importedName = projectName;
        if (projects.existsByName(importedName)) importedName = importedName + "-imported-" + UUID.randomUUID().toString().substring(0, 8);
        Project project = projects.save(new Project(importedName));
        environments.ensureDefaults(project);
        for (JsonNode environment : snapshot.path("environments")) {
            if (environment.isTextual() && !environment.asText().isBlank()) {
                environments.ensure(project, environment.asText());
            }
        }
        if (version == 1) importV1(snapshot, project);
        else if (version == 2) importV2(snapshot, project);
        else throw new IllegalArgumentException("Unsupported topology snapshot format version: " + version);
        return new ApiModels.ProjectResponse(project.getId(), project.getName(), project.getCreatedAt());
    }

    /** Imports the legacy snapshot shape where contracts are project-scoped and connections point at them. */
    private void importV1(JsonNode root, Project project) {
        Map<UUID, ServiceNode> serviceMap = new HashMap<>();
        for (JsonNode service : requiredArray(root, "services")) {
            UUID id = requiredUuid(service, "id");
            if (serviceMap.containsKey(id)) throw new IllegalArgumentException("Invalid service ID");
            boolean external = service.path("external").asBoolean(false);
            serviceMap.put(id, nodes.save(new ServiceNode(project, requiredText(service, "name"),
                external ? ServiceType.EXTERNAL : ServiceType.CORE, null)));
        }

        Map<UUID, JsonNode> contractMap = new LinkedHashMap<>();
        for (JsonNode contract : requiredArray(root, "contracts")) {
            UUID id = requiredUuid(contract, "id");
            if (contractMap.put(id, contract) != null) throw new IllegalArgumentException("Invalid contract ID");
            schemaValidator.validate(requiredSchema(contract, "requestSchema"));
            JsonNode response = optionalSchema(contract, "responseSchema");
            if (response != null) schemaValidator.validate(response);
        }

        JsonNode connectionNodes = requiredArray(root, "connections");
        Map<UUID, Set<UUID>> contractTargets = new LinkedHashMap<>();
        Map<UUID, Boolean> contractPubSub = new HashMap<>();
        for (JsonNode connection : connectionNodes) {
            UUID contractId = optionalUuid(connection, "contractId");
            if (contractId == null) continue;
            UUID targetId = requiredUuid(connection, "targetId");
            if (!serviceMap.containsKey(targetId)) throw new IllegalArgumentException("Snapshot references unknown service");
            contractTargets.computeIfAbsent(contractId, key -> new LinkedHashSet<>()).add(targetId);
            contractPubSub.merge(contractId, Set.of("KAFKA", "RABBITMQ").contains(requiredText(connection, "transportType")), (left, right) -> left || right);
        }

        Map<UUID, Map<UUID, ServiceApi>> apiMap = new HashMap<>();
        for (var entry : contractTargets.entrySet()) {
            JsonNode contract = contractMap.get(entry.getKey());
            if (contract == null) throw new IllegalArgumentException("Snapshot references unknown contract");
            boolean pubSub = contractPubSub.getOrDefault(entry.getKey(), false);
            for (UUID targetId : entry.getValue()) {
                ServiceApi api = apis.save(new ServiceApi(serviceMap.get(targetId), requiredText(contract, "name"),
                    requiredText(contract, "version"), "Imported from snapshot v1",
                    pubSub ? ApiPattern.PUB_SUB : ApiPattern.REQUEST_RESPONSE,
                    requiredSchema(contract, "requestSchema"), optionalSchema(contract, "responseSchema")));
                apiMap.computeIfAbsent(entry.getKey(), key -> new HashMap<>()).put(targetId, api);
            }
        }

        for (JsonNode connection : connectionNodes) {
            String environment = environments.ensure(project, requiredText(connection, "environment")).getCode();
            String transportType = requiredText(connection, "transportType");
            JsonNode settings = requiredSchema(connection, "connectionSettings");
            JsonNode mapping = optionalSchema(connection, "mappingRules");
            connectionValidator.validate(environment, transportType, settings, mapping);
            UUID sourceId = requiredUuid(connection, "sourceId");
            UUID targetId = requiredUuid(connection, "targetId");
            ServiceNode source = serviceMap.get(sourceId);
            ServiceNode target = serviceMap.get(targetId);
            if (source == null || target == null) throw new IllegalArgumentException("Snapshot references unknown service");
            UUID contractId = optionalUuid(connection, "contractId");
            ServiceApi targetApi = contractId == null ? null : apiMap.get(contractId).get(targetId);
            if (contractId != null && targetApi == null) throw new IllegalArgumentException("Snapshot references unknown contract");
            connections.save(new TopologyConnection(project, environment, source, target, null, targetApi,
                transportType, settings, mapping));
        }
    }

    private void importV2(JsonNode root, Project project) {
        Map<UUID, ServiceNode> serviceMap = new HashMap<>();
        for (JsonNode service : requiredArray(root, "services")) {
            UUID id = requiredUuid(service, "id");
            if (serviceMap.containsKey(id)) throw new IllegalArgumentException("Invalid service ID");
            ServiceType type = parseEnum(ServiceType.class, service.path("serviceType").asText("CORE"), "service type");
            serviceMap.put(id, nodes.save(new ServiceNode(project, requiredText(service, "name"), type,
                optionalText(service, "description"))));
        }

        Map<UUID, ServiceApi> apiMap = new HashMap<>();
        for (JsonNode api : requiredArray(root, "apis")) {
            UUID id = requiredUuid(api, "id");
            if (apiMap.containsKey(id)) throw new IllegalArgumentException("Invalid API ID");
            UUID serviceId = requiredUuid(api, "serviceId");
            ServiceNode service = serviceMap.get(serviceId);
            if (service == null) throw new IllegalArgumentException("Snapshot references unknown service");
            ApiPattern pattern = parseEnum(ApiPattern.class, api.path("pattern").asText("REQUEST_RESPONSE"), "API pattern");
            schemaValidator.validate(requiredSchema(api, "requestSchema"));
            JsonNode response = optionalSchema(api, "responseSchema");
            if (response != null) schemaValidator.validate(response);
            apiMap.put(id, apis.save(new ServiceApi(service, requiredText(api, "name"), requiredText(api, "version"),
                optionalText(api, "description"), pattern, requiredSchema(api, "requestSchema"), response)));
        }

        for (JsonNode connection : requiredArray(root, "connections")) {
            String environment = environments.ensure(project, requiredText(connection, "environment")).getCode();
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
        return value == null || !value.isTextual() || value.asText().isBlank() ? null : value.asText();
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
