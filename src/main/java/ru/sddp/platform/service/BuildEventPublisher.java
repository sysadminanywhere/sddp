package ru.sddp.platform.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import ru.sddp.platform.api.ApiModels;
import java.util.Map;

@Component
public class BuildEventPublisher {
    private final ObjectProvider<KafkaTemplate<String, String>> kafka; private final ObjectMapper mapper;
    private final String topic = System.getenv().getOrDefault("SDDP_BUILD_LOG_TOPIC", "sddp.build.logs");
    private final boolean enabled = Boolean.parseBoolean(System.getenv().getOrDefault("SDDP_KAFKA_ENABLED", "false"));
    public BuildEventPublisher(ObjectProvider<KafkaTemplate<String, String>> kafka, ObjectMapper mapper) { this.kafka=kafka; this.mapper=mapper; }
    public void publish(String event, ApiModels.IrManifest manifest) {
        try { KafkaTemplate<String, String> template = kafka.getIfAvailable(); if (enabled && template != null) template.send(topic, manifest.system_name(), mapper.writeValueAsString(Map.of("event", event, "system", manifest.system_name(), "environment", manifest.environment(), "generated_at", manifest.generated_at()))); }
        catch (JacksonException e) { throw new IllegalStateException("Cannot serialize build event", e); }
    }
}
