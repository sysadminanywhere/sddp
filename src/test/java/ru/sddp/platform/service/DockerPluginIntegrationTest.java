package ru.sddp.platform.service;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import ru.sddp.platform.api.ApiModels;
import ru.sddp.platform.api.CodegenModels;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "SDDP_DOCKER_IT", matches = "true")
class DockerPluginIntegrationTest {
    private final DockerPluginExecutor executor = new DockerPluginExecutor(tools.jackson.databind.json.JsonMapper.builder().build());
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
    @Test void boundsLogsAndRejectsExcessFilesLinksAndDiskUsage() {
        var flood = assertThrows(PluginGenerationException.class, () -> executor.execute(manifest("flood-test"), plugin));
        assertEquals(7, flood.getExitCode());
        assertTrue(flood.getMessage().length() <= 65_600);
        assertThrows(PluginGenerationException.class, () -> executor.execute(manifest("many-test"), plugin));
        assertThrows(PluginGenerationException.class, () -> executor.execute(manifest("link-test"), plugin));
        var disk = assertThrows(PluginGenerationException.class, () -> executor.execute(manifest("disk-test"), plugin));
        assertEquals(9, disk.getExitCode());
        var root = assertThrows(PluginGenerationException.class, () -> executor.execute(manifest("root-test"), plugin));
        assertEquals(11, root.getExitCode());
    }
}
