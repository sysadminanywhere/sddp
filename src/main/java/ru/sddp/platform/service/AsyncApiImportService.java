package ru.sddp.platform.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.UUID;
import javax.lang.model.SourceVersion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sddp.platform.api.AsyncApiModels;
import ru.sddp.platform.api.EventOperation;
import ru.sddp.platform.api.EventOperation.Direction;
import ru.sddp.platform.domain.ApiPattern;
import ru.sddp.platform.domain.ServiceApi;
import ru.sddp.platform.repository.ServiceApiRepository;
import ru.sddp.platform.repository.ServiceNodeRepository;
import tools.jackson.databind.JsonNode;

@Service
public class AsyncApiImportService {
    private final ServiceNodeRepository services;
    private final ServiceApiRepository apis;
    private final ApiSchemaValidator schemaValidator;

    public AsyncApiImportService(ServiceNodeRepository services, ServiceApiRepository apis, ApiSchemaValidator schemaValidator) {
        this.services = services;
        this.apis = apis;
        this.schemaValidator = schemaValidator;
    }

    @Transactional
    public AsyncApiModels.ImportResult importSelected(UUID projectId, UUID serviceId, AsyncApiModels.ImportRequest request) {
        var document = request.document();
        if (!"3.0.0".equals(document.path("asyncapi").asText()) || !document.path("operations").isObject()) {
            throw new IllegalArgumentException("Supply an AsyncAPI 3.0.0 JSON document with operations");
        }
        var versionField = document.path("info").path("version");
        String version = versionField.asText("").trim();
        if (!versionField.isTextual() || version.isBlank() || version.length() > 255) {
            throw new IllegalArgumentException("AsyncAPI info.version must be a non-empty string of at most 255 characters");
        }
        if (document.path("servers").properties().stream().noneMatch(server -> "kafka".equals(server.getValue().path("protocol").asText()))) {
            throw new IllegalArgumentException("The pilot requires a Kafka server declaration");
        }
        var service = services.lockByIdAndProjectId(serviceId, projectId)
            .orElseThrow(() -> new ResourceNotFoundException("Service not found: " + serviceId));
        var stored = new HashMap<String, ServiceApi>();
        apis.findByServiceIdOrderByNameAscVersionAsc(serviceId).stream().filter(api -> version.equals(api.getVersion()))
            .forEach(api -> stored.put(api.getName(), api));
        var created = new ArrayList<UUID>();
        var existing = new ArrayList<UUID>();
        for (String name : new LinkedHashSet<>(request.operations())) {
            var operation = readOperation(document, name);
            var current = stored.get(name);
            if (current != null) {
                if (!operation.schema().equals(current.getRequestSchema()) || !operation.event().equals(current.getEventOperation())) {
                    throw new CompatibilityViolationException("Event operation " + name + " changed. Use a new AsyncAPI version.");
                }
                existing.add(current.getId());
            } else {
                var api = apis.save(new ServiceApi(service, name, version, operation.description(), ApiPattern.PUB_SUB,
                    operation.schema(), null, null, operation.event()));
                created.add(api.getId());
            }
        }
        return new AsyncApiModels.ImportResult(created, existing);
    }

    private ImportedEvent readOperation(JsonNode document, String name) {
        if (name.length() > 255 || !name.matches("[A-Za-z_$][A-Za-z0-9_$]*") || SourceVersion.isKeyword(name)) {
            throw new IllegalArgumentException("Event operation must be a Java identifier: " + name);
        }
        var operation = resolve(document, document.path("operations").path(name));
        Direction direction = switch (operation.path("action").asText("")) {
            case "send" -> Direction.SEND;
            case "receive" -> Direction.RECEIVE;
            default -> throw new IllegalArgumentException("Choose an AsyncAPI send or receive operation: " + name);
        };
        var channelRef = operation.path("channel");
        var channel = resolve(document, channelRef);
        var messages = operation.path("messages");
        if (!messages.isArray() || messages.size() != 1) {
            throw new IllegalArgumentException("The pilot supports one message per AsyncAPI operation");
        }
        String prefix = channelRef.path("$ref").asText("") + "/messages/";
        if (!prefix.startsWith("#/channels/") || !messages.get(0).path("$ref").asText("").startsWith(prefix)) {
            throw new IllegalArgumentException("The message must reference a message defined on the operation channel");
        }
        var message = resolve(document, messages.get(0));
        String contentType = message.path("contentType").asText(document.path("defaultContentType").asText("application/json"));
        if (!"application/json".equals(contentType)) throw new IllegalArgumentException("The pilot requires JSON messages");
        var payload = message.path("payload");
        schemaValidator.validate(payload);
        var binding = new EventOperation(direction, channel.path("address").asText(""));
        return new ImportedEvent(operation.path("description").asText(null), payload, binding);
    }

    private JsonNode resolve(JsonNode document, JsonNode node) {
        var visited = new HashSet<String>();
        while (node.has("$ref")) {
            String ref = node.path("$ref").asText("");
            if (!ref.startsWith("#/") || ref.length() > 2048 || !visited.add(ref) || visited.size() > 25) {
                throw new IllegalArgumentException("Only bounded local AsyncAPI references are supported");
            }
            node = document.at(ref.substring(1));
        }
        if (!node.isObject()) throw new IllegalArgumentException("Missing AsyncAPI object");
        return node;
    }

    private record ImportedEvent(String description, JsonNode schema, EventOperation event) { }
}
