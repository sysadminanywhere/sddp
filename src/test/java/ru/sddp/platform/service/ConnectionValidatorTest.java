package ru.sddp.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConnectionValidator validator = new ConnectionValidator();
    @Test void acceptsDocumentedMapping() throws Exception {
        var settings = mapper.readTree("{\"host\":\"legacy\",\"port\":8080,\"target_endpoint\":\"/orders\"}");
        var mapping = mapper.readTree("{\"source_contract\":\"order\",\"target_contract\":\"payment\",\"transformations\":[{\"type\":\"DIRECT_COPY\",\"from\":\"$.order.id\",\"to\":\"$.id\"},{\"type\":\"CONCATENATE\",\"from\":[\"$.first\",\"$.last\"],\"to\":\"$.name\",\"separator\":\" \"},{\"type\":\"HARDCODED_VALUE\",\"value\":\"RUB\",\"to\":\"$.currency\"}]}");
        assertDoesNotThrow(() -> validator.validate("DEV", "REST", settings, mapping));
    }
    @Test void rejectsUnknownRulesAndInvalidPort() throws Exception {
        var settings = mapper.readTree("{\"host\":\"legacy\",\"port\":0,\"target_endpoint\":\"/\"}");
        assertThrows(IllegalArgumentException.class, () -> validator.validate("DEV", "REST", settings, null));
        var validSettings = mapper.readTree("{\"host\":\"legacy\",\"port\":80,\"target_endpoint\":\"/\"}");
        var mapping = mapper.readTree("{\"source_contract\":\"a\",\"target_contract\":\"b\",\"transformations\":[{\"type\":\"SCRIPT\",\"to\":\"$.x\"}]}");
        assertThrows(IllegalArgumentException.class, () -> validator.validate("DEV", "REST", validSettings, mapping));
    }
}
