package ru.sddp.platform.service;

import tools.jackson.databind.JsonNode;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.SchemaLocation;
import org.springframework.stereotype.Service;

@Service
public class ApiSchemaValidator {
    public void validate(JsonNode schema) {
        if (schema == null || !schema.isObject()) throw new IllegalArgumentException("API schema must be a JSON object");
        rejectRemoteReferences(schema);
        try {
            var registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7);
            var errors = registry.getSchema(SchemaLocation.of(SpecificationVersion.DRAFT_7.getDialectId())).validate(schema);
            if (!errors.isEmpty()) throw new IllegalArgumentException(errors.toString());
            registry.getSchema(schema).initializeValidators();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid JSON schema: " + e.getMessage(), e);
        }
    }

    private void rejectRemoteReferences(JsonNode node) {
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                if (entry.getKey().equals("$ref") && (!entry.getValue().isTextual() || !entry.getValue().asText().startsWith("#"))) {
                    throw new IllegalArgumentException("Only local JSON Schema references are permitted");
                }
                rejectRemoteReferences(entry.getValue());
            });
        } else if (node.isArray()) {
            node.forEach(this::rejectRemoteReferences);
        }
    }
}
