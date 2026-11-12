package ru.sddp.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import ru.sddp.platform.api.ApiModels;
import ru.sddp.platform.api.CodegenModels;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "SDDP_DOCKER_IT", matches = "true")
class DockerPluginIntegrationTest {
    private final DockerPluginExecutor executor = new DockerPluginExecutor(new ObjectMapper().registerModule(new JavaTimeModule()));
    private final CodegenModels.PluginDescriptor plugin = new CodegenModels.PluginDescriptor("TEST", "DOCKER", "sddp-plugin-fixture:local");
    private ApiModels.IrManifest manifest(String name) { return new ApiModels.IrManifest(name, "DEV", Instant.parse("2026-01-01T00:00:00Z"), List.of()); }
    @Test void returnsGeneratedFiles() {
        var result = executor.execute(manifest("fixture-test"), plugin);
        assertTrue(result.files().get("result.json").contains("fixture-test"));
    }
    @Test void reportsExitCodeAndStderr() {
        var error = assertThrows(PluginGenerationException.class, () -> executor.execute(manifest("fail-test"), plugin));
        assertEquals(7, error.getExitCode());
        assertTrue(error.getMessage().contains("fixture plugin failed"));
    }
}
