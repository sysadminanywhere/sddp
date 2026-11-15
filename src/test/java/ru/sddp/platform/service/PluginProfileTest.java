package ru.sddp.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import static org.junit.jupiter.api.Assertions.*;

class PluginProfileTest {
    @Configuration
    static class MapperConfig {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }

    private Class<?> executorFor(String profile) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles(profile);
            context.register(MapperConfig.class, DockerPluginExecutor.class, KubernetesJobPluginExecutor.class);
            context.refresh();
            var executors = context.getBeansOfType(PluginExecutor.class);
            assertEquals(1, executors.size(), "exactly one plugin executor must be active for profile " + profile);
            return executors.values().iterator().next().getClass();
        }
    }

    @Test void dockerProfileRegistersOnlyDockerExecutor() {
        assertEquals(DockerPluginExecutor.class, executorFor("docker"));
    }

    @Test void k8sProfileRegistersOnlyKubernetesExecutor() {
        assertEquals(KubernetesJobPluginExecutor.class, executorFor("k8s"));
    }
}
