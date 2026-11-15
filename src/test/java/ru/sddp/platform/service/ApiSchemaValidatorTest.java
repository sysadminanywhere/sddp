package ru.sddp.platform.service;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApiSchemaValidatorTest {
    private final ObjectMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();
    private final ApiSchemaValidator validator = new ApiSchemaValidator();
    @Test void permitsLocalReferences() throws Exception {
        var schema = mapper.readTree("{\"$defs\":{\"id\":{\"type\":\"string\"}},\"$ref\":\"#/$defs/id\"}");
        assertDoesNotThrow(() -> validator.validate(schema));
    }
    @Test void rejectsRemoteReferencesAtAnyDepth() throws Exception {
        var schema = mapper.readTree("{\"properties\":{\"id\":{\"$ref\":\"https://example.com/schema\"}}}");
        assertThrows(IllegalArgumentException.class, () -> validator.validate(schema));
    }
    @Test void rejectsNonObjectSchemas() throws Exception {
        var schema = mapper.readTree("[]");
        assertThrows(IllegalArgumentException.class, () -> validator.validate(schema));
    }
    @Test void rejectsInvalidSchemaTypes() throws Exception {
        var schema = mapper.readTree("{\"type\":\"not-a-json-schema-type\"}");
        assertThrows(IllegalArgumentException.class, () -> validator.validate(schema));
    }
}
