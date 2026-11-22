package ru.sddp.platform.service;

import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import java.util.HashSet;
import java.util.Set;

@Service
public class ConnectionValidator {
    void validateVersionMapping(JsonNode previousRequest, JsonNode sourceRequest, JsonNode targetRequest, JsonNode mapping) {
        if (mapping == null || mapping.isNull()) {
            var previousRequired = requiredFields(previousRequest);
            var newRequired = requiredFields(targetRequest);
            newRequired.removeAll(previousRequired);
            if (!newRequired.isEmpty()) throw new CompatibilityViolationException("New required fields need a mapping: " + newRequired);
            return;
        }
        validateMapping(mapping);
        var destinations = new HashSet<String>();
        for (JsonNode rule : mapping.path("transformations")) {
            String to = rule.path("to").asText();
            JsonNode target = field(targetRequest, to);
            if (destinations.stream().anyMatch(path -> path.equals(to) || path.startsWith(to + ".") || to.startsWith(path + "."))) {
                throw new CompatibilityViolationException("Overlapping mapping destination: " + to);
            }
            destinations.add(to);
            switch (rule.path("type").asText()) {
                case "DIRECT_COPY" -> {
                    if (sourceRequest != null) {
                        JsonNode source = field(sourceRequest, rule.path("from").asText());
                        checkType(source.path("type").asText(""), target, to);
                    }
                }
                case "CONCATENATE" -> {
                    checkType("string", target, to);
                    if (sourceRequest != null) {
                        for (JsonNode path : rule.path("from")) field(sourceRequest, path.asText());
                    }
                }
                case "HARDCODED_VALUE" -> checkType(jsonType(rule.path("value")), target, to);
                default -> throw new IllegalArgumentException("Unknown mapping rule type");
            }
        }
        for (String required : requiredFields(targetRequest)) {
            String prefix = "$." + required;
            if (destinations.stream().noneMatch(path -> path.equals(prefix) || path.startsWith(prefix + "."))) {
                throw new CompatibilityViolationException("Missing mapping for required destination: " + prefix);
            }
        }
    }

    private JsonNode field(JsonNode schema, String path) {
        JsonNode current = schema;
        for (String segment : path.substring(2).split("\\.", -1)) current = current.path("properties").path(segment);
        if (current.isMissingNode()) throw new CompatibilityViolationException("Unknown mapping field: " + path);
        return current;
    }

    private Set<String> requiredFields(JsonNode schema) {
        var required = new java.util.TreeSet<String>();
        schema.path("required").forEach(field -> required.add(field.asText()));
        return required;
    }

    private void checkType(String actual, JsonNode target, String path) {
        var expected = target.path("type");
        if (actual.isEmpty() || expected.isMissingNode()) return;
        boolean compatible = expected.isArray()
            ? expected.values().stream().anyMatch(type -> matches(actual, type.asText()))
            : matches(actual, expected.asText());
        if (!compatible) throw new CompatibilityViolationException("Incompatible mapping type at " + path);
    }

    private boolean matches(String actual, String expected) {
        return actual.equals(expected) || "integer".equals(actual) && "number".equals(expected);
    }

    private String jsonType(JsonNode value) {
        if (value.isIntegralNumber()) return "integer";
        if (value.isNumber()) return "number";
        if (value.isTextual()) return "string";
        if (value.isBoolean()) return "boolean";
        if (value.isArray()) return "array";
        if (value.isObject()) return "object";
        return "null";
    }

    public void validate(String environment, String transport, JsonNode settings, JsonNode mapping) {
        if (environment == null || environment.isBlank()) throw new IllegalArgumentException("Invalid environment");
        if (!Set.of("REST", "GRPC", "KAFKA", "RABBITMQ").contains(transport)) throw new IllegalArgumentException("Invalid transport");
        if (settings == null || !settings.isObject() || !settings.path("host").isTextual() || settings.path("host").asText().isBlank() || !settings.path("port").isIntegralNumber() || !settings.path("port").canConvertToInt() || settings.path("port").asInt() < 1 || settings.path("port").asInt() > 65535 || !settings.path("target_endpoint").isTextual()) throw new IllegalArgumentException("Connection requires host, port (1-65535), and target_endpoint");
        for (String field : new String[]{"timeout_ms", "retries"}) if (settings.has(field) && (!settings.path(field).isIntegralNumber() || !settings.path(field).canConvertToInt() || settings.path(field).asInt() < 0)) throw new IllegalArgumentException("Invalid " + field);
        if (settings.has("security_type") && (!settings.path("security_type").isTextual() || !Set.of("NONE", "MTLS", "JWT").contains(settings.path("security_type").asText()))) throw new IllegalArgumentException("Invalid security_type");
        String scheme = settings.path("scheme").asText("http").toLowerCase(java.util.Locale.ROOT);
        if ("REST".equals(transport) && !Set.of("http", "https").contains(scheme)) throw new IllegalArgumentException("Invalid REST scheme");
        if ("JWT".equals(settings.path("security_type").asText("NONE")) && !"https".equals(scheme)) throw new IllegalArgumentException("JWT connections require HTTPS");
        if (mapping != null && !mapping.isNull()) validateMapping(mapping);
    }

    private void validateMapping(JsonNode mapping) {
        if (!mapping.isObject() || !hasText(mapping, "target_api", "target_contract") || !mapping.path("transformations").isArray()) throw new IllegalArgumentException("Invalid mapping manifest");
        for (JsonNode rule : mapping.path("transformations")) {
            if (!rule.isObject() || !rule.path("type").isTextual() || !rule.path("to").isTextual() || !rule.path("to").asText().startsWith("$.")) throw new IllegalArgumentException("Invalid mapping rule");
            switch (rule.path("type").asText()) {
                case "DIRECT_COPY" -> { if (!rule.path("from").isTextual() || !rule.path("from").asText().startsWith("$.")) throw new IllegalArgumentException("Invalid DIRECT_COPY source"); }
                case "CONCATENATE" -> { if (!rule.path("from").isArray() || rule.path("from").isEmpty() || !rule.path("separator").isTextual()) throw new IllegalArgumentException("Invalid CONCATENATE rule"); for (JsonNode source : rule.path("from")) if (!source.isTextual() || !source.asText().startsWith("$.")) throw new IllegalArgumentException("Invalid CONCATENATE source"); }
                case "HARDCODED_VALUE" -> { if (!rule.has("value")) throw new IllegalArgumentException("Missing hardcoded value"); }
                default -> throw new IllegalArgumentException("Unknown mapping rule type");
            }
        }
    }

    private boolean hasText(JsonNode node, String preferred, String legacy) {
        return (node.path(preferred).isTextual() && !node.path(preferred).asText().isBlank())
            || (node.path(legacy).isTextual() && !node.path(legacy).asText().isBlank());
    }
}
