package ru.sddp.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BackwardCompatibilityEngineTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final BackwardCompatibilityEngine engine = new BackwardCompatibilityEngine();
    @Test void rejectsNestedBreakingChanges() throws Exception {
        var oldSchema = mapper.readTree("{\"properties\":{\"order\":{\"properties\":{\"id\":{}}}}}");
        var removed = mapper.readTree("{\"properties\":{\"order\":{\"properties\":{}}}}");
        var required = mapper.readTree("{\"properties\":{\"order\":{\"properties\":{\"id\":{}},\"required\":[\"id\"]}}}");
        assertThrows(CompatibilityViolationException.class, () -> engine.assertCompatible(oldSchema, removed, null, null));
        assertThrows(CompatibilityViolationException.class, () -> engine.assertCompatible(null, null, oldSchema, required));
    }
    @Test void rejectsRemovedResponseField() throws Exception {
        var oldSchema = mapper.readTree("{\"properties\":{\"id\":{},\"status\":{}}}");
        var newSchema = mapper.readTree("{\"properties\":{\"id\":{}}}");
        var ex = assertThrows(CompatibilityViolationException.class, () -> engine.assertCompatible(oldSchema, newSchema, null, null));
        assertTrue(ex.getMessage().contains("status"));
    }
    @Test void rejectsNewRequiredRequestField() throws Exception {
        var oldSchema = mapper.readTree("{\"properties\":{\"id\":{}},\"required\":[\"id\"]}");
        var newSchema = mapper.readTree("{\"properties\":{\"id\":{},\"amount\":{}},\"required\":[\"id\",\"amount\"]}");
        assertThrows(CompatibilityViolationException.class, () -> engine.assertCompatible(null, null, oldSchema, newSchema));
    }
    @Test void acceptsAdditiveOptionalRequestAndResponse() throws Exception {
        var oldSchema = mapper.readTree("{\"properties\":{\"id\":{}},\"required\":[\"id\"]}");
        var newSchema = mapper.readTree("{\"properties\":{\"id\":{},\"note\":{}},\"required\":[\"id\"]}");
        assertDoesNotThrow(() -> engine.assertCompatible(oldSchema, newSchema, oldSchema, newSchema));
    }
}
