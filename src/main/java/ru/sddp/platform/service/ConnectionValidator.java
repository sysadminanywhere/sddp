package ru.sddp.platform.service;

import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import java.util.Set;

@Service
public class ConnectionValidator {
    public void validate(String environment, String transport, JsonNode settings, JsonNode mapping) {
        if (environment == null || environment.isBlank()) throw new IllegalArgumentException("Invalid environment");
        if (!Set.of("REST", "GRPC", "KAFKA", "RABBITMQ").contains(transport)) throw new IllegalArgumentException("Invalid transport");
        if (settings == null || !settings.isObject() || !settings.path("host").isTextual() || settings.path("host").asText().isBlank() || !settings.path("port").isIntegralNumber() || !settings.path("port").canConvertToInt() || settings.path("port").asInt() < 1 || settings.path("port").asInt() > 65535 || !settings.path("target_endpoint").isTextual()) throw new IllegalArgumentException("Connection requires host, port (1-65535), and target_endpoint");
        for (String field : new String[]{"timeout_ms", "retries"}) if (settings.has(field) && (!settings.path(field).isIntegralNumber() || !settings.path(field).canConvertToInt() || settings.path(field).asInt() < 0)) throw new IllegalArgumentException("Invalid " + field);
        if (settings.has("security_type") && (!settings.path("security_type").isTextual() || !Set.of("NONE", "MTLS", "JWT").contains(settings.path("security_type").asText()))) throw new IllegalArgumentException("Invalid security_type");
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
