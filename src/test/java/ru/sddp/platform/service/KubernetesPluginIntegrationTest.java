package ru.sddp.platform.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import ru.sddp.platform.api.ApiModels;
import ru.sddp.platform.api.CodegenModels;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "SDDP_K8S_IT", matches = "true")
class KubernetesPluginIntegrationTest {
    private final KubernetesJobPluginExecutor executor = new KubernetesJobPluginExecutor(tools.jackson.databind.json.JsonMapper.builder().build());
    private final CodegenModels.PluginDescriptor plugin = new CodegenModels.PluginDescriptor("TEST", "DOCKER", "sddp-plugin-fixture:local");
    private ApiModels.IrManifest manifest(String name) { return new ApiModels.IrManifest(name, "DEV", Instant.parse("2026-01-01T00:00:00Z"), List.of()); }

    @Test void runsJobAndReturnsFiles() {
        assertTrue(executor.execute(manifest("fixture-test"), plugin).files().get("result.json").contains("fixture-test"));
    }

    @Test void reportsPluginFailureAndRejectsUnsafeOutput() {
        assertThrows(PluginGenerationException.class, () -> executor.execute(manifest("fail-test"), plugin));
        assertThrows(PluginGenerationException.class, () -> executor.execute(manifest("link-test"), plugin));
        assertThrows(PluginGenerationException.class, () -> executor.execute(manifest("many-test"), plugin));
    }
}
