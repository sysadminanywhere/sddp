package ru.sddp.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.springframework.stereotype.Service;

@Service
public class ContractSchemaValidator {
    public void validate(JsonNode schema) {
        if (schema == null || !schema.isObject()) throw new IllegalArgumentException("Contract schema must be a JSON object");
        rejectRemoteReferences(schema);
        try { JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(schema); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Invalid JSON schema: " + e.getMessage(), e); }
    }
    private void rejectRemoteReferences(JsonNode node) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (entry.getKey().equals("$ref") && (!entry.getValue().isTextual() || !entry.getValue().asText().startsWith("#"))) throw new IllegalArgumentException("Only local JSON Schema references are permitted");
                rejectRemoteReferences(entry.getValue());
            });
        } else if (node.isArray()) node.forEach(this::rejectRemoteReferences);
    }
}
