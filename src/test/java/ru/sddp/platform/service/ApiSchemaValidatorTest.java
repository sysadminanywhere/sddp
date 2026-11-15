package ru.sddp.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApiSchemaValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
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
}
