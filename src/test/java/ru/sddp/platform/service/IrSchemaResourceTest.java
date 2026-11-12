package ru.sddp.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IrSchemaResourceTest {
    @Test void bundledIrSchemaIsValidJsonAndHasRequiredTopLevelFields() throws Exception {
        try (var stream = getClass().getResourceAsStream("/schemas/ir-schema.json")) {
            assertNotNull(stream);
            var schema = new ObjectMapper().readTree(stream);
            assertEquals("object", schema.path("type").asText());
            assertTrue(schema.path("required").toString().contains("system_name"));
            assertTrue(schema.path("required").toString().contains("services"));
        }
    }
}
