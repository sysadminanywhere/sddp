package ru.sddp.platform.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.lang.model.SourceVersion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sddp.platform.api.HttpOperation;
import ru.sddp.platform.api.OpenApiModels;
import ru.sddp.platform.domain.ApiPattern;
import ru.sddp.platform.domain.ServiceApi;
import ru.sddp.platform.repository.ServiceApiRepository;
import ru.sddp.platform.repository.ServiceNodeRepository;
import org.snakeyaml.engine.v2.api.LoadSettings;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;

import static ru.sddp.platform.api.OpenApiModels.MAX_SELECTION;

@Service
public class OpenApiImportService {
    private static final int MAX_CATALOG_OPERATIONS = 10_000;
    private static final YAMLMapper YAML = YAMLMapper.builder(YAMLFactory.builder()
        .loadSettings(LoadSettings.builder().setCodePointLimit(10_000_000).build())
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(100).build())
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();

    private final ServiceCatalogService catalog;
    private final ServiceNodeRepository services;
    private final ServiceApiRepository apis;
    private final ApiSchemaValidator validator;
    private final ObjectMapper mapper;

    public OpenApiImportService(ServiceCatalogService catalog, ServiceNodeRepository services,
                                ServiceApiRepository apis, ApiSchemaValidator validator, ObjectMapper mapper) {
        this.catalog = catalog;
        this.services = services;
        this.apis = apis;
        this.validator = validator;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public OpenApiModels.Preview preview(UUID projectId, UUID serviceId, JsonNode document) {
        return preview(projectId, serviceId, new OpenApiModels.PreviewRequest(document, null, null));
    }

    @Transactional(readOnly = true)
    public OpenApiModels.Preview preview(UUID projectId, UUID serviceId, OpenApiModels.PreviewRequest request) {
        catalog.requireService(projectId, serviceId);
        Inventory inventory = inventory(document(request.document(), request.yaml()));
        Set<String> existing = new HashSet<>();
        apis.findByServiceIdOrderByNameAscVersionAsc(serviceId).stream()
            .filter(api -> inventory.version().equals(api.getVersion()))
            .forEach(api -> existing.add(api.getName()));
        boolean selectionRequired = request.operations() == null && inventory.operations().size() > MAX_SELECTION;
        if (selectionRequired) {
            var operations = inventory.operations().values().stream()
                .map(candidate -> describe(candidate, existing)).toList();
            return new OpenApiModels.Preview(inventory.version(), operations, inventory.warnings(), true);
        }

        Parsed parsed = parse(inventory, request.operations());
        var operations = parsed.operations().keySet().stream()
            .map(key -> describe(inventory.operations().get(key), existing)).toList();
        return new OpenApiModels.Preview(inventory.version(), operations, parsed.warnings(), false);
    }

    private OpenApiModels.Operation describe(Candidate operation, Set<String> existing) {
        return new OpenApiModels.Operation(operation.key(), operation.name(), operation.method(),
            operation.path(), existing.contains(operation.name()));
    }

    @Transactional
    public OpenApiModels.ImportResult importSelected(UUID projectId, UUID serviceId, OpenApiModels.ImportRequest request) {
        Inventory inventory = inventory(document(request.document(), request.yaml()));
        Parsed parsed = parse(inventory, request.operations());
        var keys = new LinkedHashSet<>(request.operations());
        if (!parsed.operations().keySet().containsAll(keys)) {
            throw new IllegalArgumentException("The selection contains an operation that cannot be imported");
        }

        var service = services.lockByIdAndProjectId(serviceId, projectId)
            .orElseThrow(() -> new ResourceNotFoundException("Service not found: " + serviceId));
        Map<String, UUID> existing = new LinkedHashMap<>();
        apis.findByServiceIdOrderByNameAscVersionAsc(serviceId).stream()
            .filter(api -> inventory.version().equals(api.getVersion()))
            .forEach(api -> existing.put(api.getName(), api.getId()));

        var created = new ArrayList<UUID>();
        var kept = new ArrayList<UUID>();
        for (String key : keys) {
            Contract operation = parsed.operations().get(key);
            UUID current = existing.get(operation.name());
            if (current != null) {
                kept.add(current);
                continue;
            }
            var added = apis.save(new ServiceApi(service, operation.name(), inventory.version(), operation.description(),
                ApiPattern.REQUEST_RESPONSE, operation.request(), operation.response(), operation.http()));
            created.add(added.getId());
        }
        return new OpenApiModels.ImportResult(created, kept);
    }

    private JsonNode document(JsonNode json, String yaml) {
        if ((json == null) == (yaml == null)) {
            throw new IllegalArgumentException("Supply either an OpenAPI JSON document or YAML text");
        }
        if (json != null) return json;
        try {
            return YAML.readTree(yaml);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Invalid OpenAPI YAML document", e);
        }
    }

    private Inventory inventory(JsonNode document) {
        JsonNode paths = document.path("paths");
        if (!document.path("openapi").asText("").matches("3\\.[01]\\.\\d+") || !paths.isObject()) {
            throw new IllegalArgumentException("Supply an OpenAPI 3.0 or 3.1 document with paths");
        }
        String version = document.path("info").path("version").asText("").trim();
        if (!document.path("info").path("version").isTextual() || version.isBlank() || version.length() > 255) {
            throw new IllegalArgumentException("OpenAPI info.version must be a non-empty string of at most 255 characters");
        }
        var resolver = new Resolver(document);
        var operations = new LinkedHashMap<String, Candidate>();
        var warnings = new ArrayList<String>();
        var names = new LinkedHashMap<String, String>();
        for (var pathEntry : paths.properties()) {
            String path = pathEntry.getKey();
            if (!path.startsWith("/")) continue;
            if (path.length() > 2048) {
                warnings.add("An OpenAPI path exceeds 2048 characters");
                continue;
            }
            JsonNode pathItem;
            try {
                pathItem = resolver.reference(pathEntry.getValue(), new HashSet<>(), 0);
                if (!pathItem.isObject()) throw new IllegalArgumentException("Invalid path item");
            } catch (IllegalArgumentException e) {
                warnings.add(path + " — " + e.getMessage());
                continue;
            }
            for (var entry : pathItem.properties()) {
                String method = entry.getKey().toUpperCase(Locale.ROOT);
                if (!HttpOperation.METHODS.contains(method) && !"TRACE".equals(method)) continue;
                if (operations.size() >= MAX_CATALOG_OPERATIONS) {
                    throw new IllegalArgumentException("OpenAPI supports at most " + MAX_CATALOG_OPERATIONS + " listed operations");
                }
                String key = method + " " + path;
                JsonNode operation = entry.getValue();
                String name = operationName(operation, key);
                var candidate = new Candidate(key, name, method, path, pathItem, operation);
                if (operations.putIfAbsent(key, candidate) != null) {
                    throw new IllegalArgumentException("Duplicate OpenAPI operation: " + key);
                }
                names.putIfAbsent(name, key);
            }
        }
        return new Inventory(version, operations, names, warnings, resolver);
    }

    private String operationName(JsonNode operation, String key) {
        String name = operation.path("operationId").asText("").trim();
        if (name.isEmpty()) name = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("_$", "");
        if (name.length() <= 255 && name.matches("[A-Za-z_$][A-Za-z0-9_$]*") && !SourceVersion.isKeyword(name)) {
            return name;
        }
        String stem = name.substring(0, Math.min(name.length(), 200)).replaceAll("[^A-Za-z0-9_$]+", "_");
        return "op_" + stem + "_" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
    }

    private Parsed parse(Inventory inventory, List<String> selection) {
        var keys = selection == null ? inventory.operations().keySet() : new LinkedHashSet<>(selection);
        if (keys.size() > MAX_SELECTION || (selection != null && (selection.isEmpty() || selection.size() > MAX_SELECTION))) {
            throw new IllegalArgumentException("Select between 1 and " + MAX_SELECTION + " OpenAPI operations");
        }
        if (!inventory.operations().keySet().containsAll(keys)) {
            throw new IllegalArgumentException("The selection contains an unknown OpenAPI operation");
        }
        var operations = new LinkedHashMap<String, Contract>();
        var warnings = new ArrayList<>(inventory.warnings());
        for (String key : keys) {
            Candidate candidate = inventory.operations().get(key);
            try {
                if (!key.equals(inventory.names().get(candidate.name()))) {
                    throw new IllegalArgumentException("Duplicate operation name: " + candidate.name());
                }
                operations.put(key, readOperation(candidate, inventory.resolver()));
            } catch (IllegalArgumentException e) {
                warnings.add(key + " — " + e.getMessage());
            }
        }
        return new Parsed(operations, warnings);
    }

    private Contract readOperation(Candidate candidate, Resolver resolver) {
        JsonNode operation = candidate.operation();
        if (!operation.isObject()) throw new IllegalArgumentException("Invalid operation");
        Map<String, HttpOperation.Parameter> parameters = new LinkedHashMap<>();
        addParameters(parameters, candidate.pathItem().path("parameters"), resolver);
        addParameters(parameters, operation.path("parameters"), resolver);
        JsonNode body = resolver.reference(operation.path("requestBody"), new HashSet<>(), 0);
        boolean hasBody = !body.isMissingNode();
        var http = new HttpOperation(candidate.method(), candidate.path(), hasBody, body.path("required").asBoolean(false),
            List.copyOf(parameters.values()));
        JsonNode request = hasBody ? jsonSchema(body, resolver) : mapper.createObjectNode().put("type", "object");
        JsonNode responses = operation.path("responses");
        if (!responses.isObject()) throw new IllegalArgumentException("Operation has no response definitions");
        String success = responses.properties().stream().map(Map.Entry::getKey)
            .filter(code -> code.matches("2[0-9][0-9]") || "2XX".equals(code)).sorted().findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Operation has no successful response"));
        JsonNode response = resolver.reference(responses.path(success), new HashSet<>(), 0);
        if (!response.isObject()) throw new IllegalArgumentException("Invalid successful response");
        JsonNode responseSchema = response.has("content") ? jsonSchema(response, resolver) : null;
        validator.validate(request);
        if (responseSchema != null) validator.validate(responseSchema);
        String description = operation.path("description").asText(operation.path("summary").asText(""));
        return new Contract(candidate.name(), description.isBlank() ? null : description, request, responseSchema, http);
    }

    private void addParameters(Map<String, HttpOperation.Parameter> parameters, JsonNode definitions, Resolver resolver) {
        if (definitions.isMissingNode()) return;
        if (!definitions.isArray() || definitions.size() > 50) throw new IllegalArgumentException("Invalid operation parameters");
        var current = new HashSet<String>();
        for (JsonNode definition : definitions) {
            JsonNode parameter = resolver.reference(definition, new HashSet<>(), 0);
            String location = parameter.path("in").asText("");
            String style = "path".equals(location) ? "simple" : "form";
            if (!style.equals(parameter.path("style").asText(style)) || parameter.path("allowReserved").asBoolean(false)) {
                throw new IllegalArgumentException("Unsupported parameter serialization");
            }
            JsonNode schema = resolver.reference(parameter.path("schema"), new HashSet<>(), 0);
            if (!Set.of("string", "integer", "number", "boolean").contains(schema.path("type").asText(""))) {
                throw new IllegalArgumentException("Parameters must use scalar schemas");
            }
            var value = new HttpOperation.Parameter(parameter.path("name").asText(""), location,
                parameter.path("required").asBoolean(false));
            String key = location + ":" + value.name();
            if (!current.add(key)) throw new IllegalArgumentException("Duplicate parameter: " + value.name());
            parameters.put(key, value);
        }
    }

    private JsonNode jsonSchema(JsonNode object, Resolver resolver) {
        JsonNode schema = object.path("content").path("application/json").path("schema");
        if (schema.isMissingNode()) throw new IllegalArgumentException("Expected an application/json schema");
        return resolver.expandSchema(schema, new HashSet<>(), 0);
    }

    private final class Resolver {
        private final JsonNode root;
        private int expandedNodes;

        private Resolver(JsonNode root) { this.root = root; }

        private String pointer(JsonNode value) {
            String ref = value.path("$ref").asText("");
            if (!ref.startsWith("#/") || ref.length() > 2048) throw new IllegalArgumentException("Only local OpenAPI references are supported");
            return ref.substring(1);
        }

        private JsonNode target(String pointer, Set<String> active, int depth) {
            if (depth > 40 || !active.add(pointer)) throw new IllegalArgumentException("Recursive or deeply nested OpenAPI reference");
            JsonNode target = root.at(pointer);
            if (target.isMissingNode()) throw new IllegalArgumentException("Unresolved OpenAPI reference");
            return target;
        }

        private JsonNode reference(JsonNode node, Set<String> active, int depth) {
            if (!node.has("$ref")) return node;
            String pointer = pointer(node);
            return reference(target(pointer, active, depth), active, depth + 1);
        }

        private JsonNode expandSchema(JsonNode node, Set<String> active, int depth) {
            if (++expandedNodes > 40_000 || depth > 40) throw new IllegalArgumentException("OpenAPI schema expansion limit exceeded");
            if (node.has("$ref")) {
                if (node.size() != 1) throw new IllegalArgumentException("Schema references with sibling fields are unsupported");
                String pointer = pointer(node);
                JsonNode resolved = expandSchema(target(pointer, active, depth), active, depth + 1);
                active.remove(pointer);
                return resolved;
            }
            if (node.isObject()) {
                if (node.has("$schema") || node.has("$id") || node.has("$dynamicRef") || node.has("$recursiveRef")) {
                    throw new IllegalArgumentException("Custom schema dialects and reference scopes are unsupported");
                }
                ObjectNode result = mapper.createObjectNode();
                for (var entry : node.properties()) result.set(entry.getKey(), expandSchema(entry.getValue(), active, depth + 1));
                if (result.path("nullable").asBoolean(false) && result.path("type").isTextual()) {
                    String type = result.path("type").asText();
                    result.remove("nullable");
                    result.putArray("type").add(type).add("null");
                }
                return result;
            }
            if (node.isArray()) {
                var result = mapper.createArrayNode();
                for (JsonNode child : node) result.add(expandSchema(child, active, depth + 1));
                return result;
            }
            return node;
        }
    }

    private record Contract(String name, String description, JsonNode request, JsonNode response, HttpOperation http) { }
    private record Candidate(String key, String name, String method, String path, JsonNode pathItem, JsonNode operation) { }
    private record Inventory(String version, Map<String, Candidate> operations, Map<String, String> names,
                             List<String> warnings, Resolver resolver) { }
    private record Parsed(Map<String, Contract> operations, List<String> warnings) { }
}
