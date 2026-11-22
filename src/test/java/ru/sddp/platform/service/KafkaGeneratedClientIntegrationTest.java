package ru.sddp.platform.service;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import ru.sddp.platform.api.ApiModels;
import ru.sddp.platform.api.CodegenModels;
import ru.sddp.platform.api.EventOperation;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class KafkaGeneratedClientIntegrationTest {
    private static final CodegenModels.PluginDescriptor PLUGIN =
        new CodegenModels.PluginDescriptor("JAVA_KAFKA", "DOCKER", "sddp-kafka-plugin:local");
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final int port = Integer.parseInt(System.getenv().getOrDefault("SDDP_KAFKA_PILOT_PORT", "9092"));

    @Test
    @EnabledIfEnvironmentVariable(named = "SDDP_KAFKA_NODE_IT", matches = "true")
    void exchangesARealEventUsingPluginSources(@TempDir Path directory) throws Exception {
        checkRoundTrip(directory, manifest -> {
            Path input = directory.resolve("ir.json");
            Path output = Files.createDirectory(directory.resolve("plugin-output"));
            Files.write(input, mapper.writeValueAsBytes(manifest));
            Process generator = new ProcessBuilder("node", "plugins/kafka/plugin.mjs", input.toString(), output.toString())
                .redirectErrorStream(true).start();
            assertTrue(generator.waitFor(20, TimeUnit.SECONDS), "Kafka generator timed out");
            assertEquals(0, generator.exitValue(), new String(generator.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            var files = new LinkedHashMap<String, String>();
            try (var paths = Files.list(output)) {
                for (Path file : paths.toList()) files.put(file.getFileName().toString(), Files.readString(file));
            }
            return files;
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "SDDP_KAFKA_DOCKER_IT", matches = "true")
    void exchangesARealEventUsingTheDockerPlugin(@TempDir Path directory) throws Exception {
        var executor = new DockerPluginExecutor(mapper);
        checkRoundTrip(directory, manifest -> executor.execute(manifest, PLUGIN).files());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "SDDP_KAFKA_K8S_IT", matches = "true")
    void exchangesARealEventUsingTheKubernetesPlugin(@TempDir Path directory) throws Exception {
        var executor = new KubernetesJobPluginExecutor(mapper);
        checkRoundTrip(directory, manifest -> executor.execute(manifest, PLUGIN).files());
    }

    private void checkRoundTrip(Path directory, GenerateFiles generator) throws Exception {
        String unique = UUID.randomUUID().toString();
        String topic = "orders.created-" + unique;
        createTopic(topic);
        var files = generator.generate(manifest(topic));
        assertEquals(Set.of("Kafka_publishOrderCreated.java", "Kafka_receiveOrderCreated.java"), files.keySet());
        var compilerArgs = new ArrayList<>(List.of("-classpath", System.getProperty("java.class.path"), "-d", directory.toString()));
        for (var file : files.entrySet()) {
            Path path = directory.resolve(file.getKey());
            Files.writeString(path, file.getValue());
            compilerArgs.add(path.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, compilerArgs.toArray(String[]::new)));

        try (var loader = new URLClassLoader(new URL[]{directory.toUri().toURL()}, getClass().getClassLoader())) {
            var sender = loader.loadClass("sddp.kafka.Kafka_publishOrderCreated");
            var payload = loader.loadClass(sender.getName() + "$Payload");
            var receiver = loader.loadClass("sddp.kafka.Kafka_receiveOrderCreated");
            var event = payload.getConstructor(String.class, String.class).newInstance("order-42", "SKU-1");
            assertNotNull(sender.getMethod("publishOrderCreated", payload).invoke(sender.getConstructor().newInstance(), event));
            var result = (Optional<?>) receiver.getMethod("receiveOrderCreated", String.class, Duration.class)
                .invoke(receiver.getConstructor().newInstance(), "sddp-pilot-" + unique, Duration.ofSeconds(25));
            var received = result.orElseThrow();
            assertEquals("order-42", received.getClass().getMethod("orderId").invoke(received));
            assertEquals("SKU-1", received.getClass().getMethod("productCode").invoke(received));
        }
    }

    private ApiModels.IrManifest manifest(String topic) {
        var schema = mapper.readTree("""
            {"type":"object","properties":{"orderId":{"type":"string"},"productCode":{"type":"string"}}}
            """);
        var settings = mapper.createObjectNode().put("host", "127.0.0.1").put("port", port).put("target_endpoint", topic);
        var publish = new ApiModels.IrMethod("publishOrderCreated", "PUB_SUB", schema, mapper.createObjectNode(),
            null, new EventOperation(EventOperation.Direction.SEND, topic));
        var receive = new ApiModels.IrMethod("receiveOrderCreated", "PUB_SUB", schema, mapper.createObjectNode(),
            null, new EventOperation(EventOperation.Direction.RECEIVE, topic));
        return new ApiModels.IrManifest("kafka-pilot", "DEV", Instant.now(), List.of(new ApiModels.IrService("orders", false,
            List.of(new ApiModels.IrConnection("orders", "broker", "KAFKA", settings, List.of(publish, receive), null)))));
    }

    private void createTopic(String name) throws Exception {
        try (var admin = Admin.create(Map.of("bootstrap.servers", "127.0.0.1:" + port, "default.api.timeout.ms", 60_000))) {
            admin.createTopics(List.of(new NewTopic(name, 1, (short) 1))).all().get(65, TimeUnit.SECONDS);
        }
    }

    @FunctionalInterface
    private interface GenerateFiles {
        Map<String, String> generate(ApiModels.IrManifest manifest) throws Exception;
    }
}
