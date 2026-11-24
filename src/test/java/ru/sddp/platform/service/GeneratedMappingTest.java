package ru.sddp.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import freemarker.template.Configuration;
import freemarker.template.Template;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.web.client.RestClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import ru.sddp.platform.api.ApiModels;
import ru.sddp.platform.api.CodegenModels;
import ru.sddp.platform.api.HttpOperation;
import tools.jackson.databind.json.JsonMapper;

class GeneratedMappingTest {
    @Test void generatedJwtAdapterRequiresBearerTokenConstructor() throws Exception {
        var config = new Configuration(Configuration.VERSION_2_3_34);
        config.setClassLoaderForTemplateLoading(getClass().getClassLoader(), "templates");
        Template template = config.getTemplate("proxy-adapter.java.ftl");
        var source = new StringWriter();
        template.process(Map.of("package_name", "example", "client_name", "JwtClient", "base_url", "https://api.example", "target_endpoint", "/orders", "methods", List.of(Map.of("name", "send", "javadoc", "")), "mapping_json", "", "timeout_ms", 5000, "retries", 3, "security_jwt", true), source);
        String generated = source.toString();
        assertTrue(generated.contains("String bearerToken"));
        assertTrue(generated.contains("headers.setBearerAuth(bearerToken)"));
        assertTrue(generated.contains("Objects.requireNonNull(bearerToken"));
    }

    @Test void generatedAdapterCompilesAndTransformsDocumentedRules() throws Exception {
        var mapper = new ObjectMapper();
        String rules = "{\"transformations\":[{\"type\":\"DIRECT_COPY\",\"from\":\"$.order.id\",\"to\":\"$.transaction_uuid\"},{\"type\":\"CONCATENATE\",\"from\":[\"$.user.first_name\",\"$.user.last_name\"],\"to\":\"$.payment_details.receiver_name\",\"separator\":\" \"},{\"type\":\"HARDCODED_VALUE\",\"value\":\"RUB\",\"to\":\"$.currency\"}]}";
        var config = new Configuration(Configuration.VERSION_2_3_34);
        config.setClassLoaderForTemplateLoading(getClass().getClassLoader(), "templates");
        Template template = config.getTemplate("proxy-adapter.java.ftl");
        var directory = Files.createTempDirectory("sddp-generated-test-");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/orders", exchange -> {
            try (exchange) {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                if (!"application/json".equals(exchange.getRequestHeaders().getFirst("Content-Type"))) {
                    exchange.sendResponseHeaders(415, -1);
                    return;
                }
                byte[] payload = exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
            }
        });
        try {
            server.start();
            var source = new StringWriter();
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            template.process(Map.of("package_name", "example", "client_name", "GeneratedClient", "base_url", baseUrl, "target_endpoint", "/orders", "methods", List.of(Map.of("name", "send", "javadoc", "")), "mapping_json", rules, "timeout_ms", 5000, "retries", 3, "security_jwt", false), source);

            var javaFile = directory.resolve("GeneratedClient.java");
            Files.writeString(javaFile, source.toString(), StandardCharsets.UTF_8);
            int result = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-classpath", System.getProperty("java.class.path"), "-d", directory.toString(), javaFile.toString());
            assertEquals(0, result, source.toString());
            try (var loader = new java.net.URLClassLoader(new java.net.URL[]{directory.toUri().toURL()}, getClass().getClassLoader())) {
                var type = loader.loadClass("example.GeneratedClient");
                RestClient.Builder builder = mock(RestClient.Builder.class);
                when(builder.requestFactory(any())).thenReturn(builder);
                when(builder.baseUrl(anyString())).thenReturn(builder);
                RestClient client = mock(RestClient.class, RETURNS_DEEP_STUBS);
                when(builder.build()).thenReturn(client);
                RestClient.RequestBodyUriSpec requestSpec = mock(RestClient.RequestBodyUriSpec.class, RETURNS_DEEP_STUBS);
                when(client.post()).thenThrow(new org.springframework.web.client.ResourceAccessException("connection failure")).thenReturn(requestSpec);
                var instance = type.getConstructor(RestClient.Builder.class, ObjectMapper.class).newInstance(builder, mapper);
                var transform = type.getDeclaredMethod("transform", Object.class); transform.setAccessible(true);
                var input = mapper.readTree("{\"order\":{\"id\":\"42\"},\"user\":{\"first_name\":\"Ada\",\"last_name\":\"Lovelace\"}}");
                var output = (com.fasterxml.jackson.databind.JsonNode) transform.invoke(instance, input);
                assertEquals("42", output.path("transaction_uuid").asText());
                assertEquals("Ada Lovelace", output.path("payment_details").path("receiver_name").asText());
                assertEquals("RUB", output.path("currency").asText());
                assertNull(type.getMethod("send", Object.class).invoke(instance, input));
                verify(client, times(2)).post();

                var httpClient = type.getConstructor(RestClient.Builder.class, ObjectMapper.class)
                    .newInstance(RestClient.builder(), mapper);
                Object response = type.getMethod("send", Object.class).invoke(httpClient, input);
                assertEquals(output, mapper.valueToTree(response));
                RestClient failingClient = mock(RestClient.class);
                when(failingClient.post()).thenThrow(new org.springframework.web.client.ResourceAccessException("offline"));
                when(builder.build()).thenReturn(failingClient);
                var failingInstance = type.getConstructor(RestClient.Builder.class, ObjectMapper.class).newInstance(builder, mapper);
                var send = type.getMethod("send", Object.class);
                for (int i = 0; i < 5; i++) {
                    var error = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> send.invoke(failingInstance, input));
                    assertInstanceOf(org.springframework.web.client.ResourceAccessException.class, error.getCause());
                }
                var open = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> send.invoke(failingInstance, input));
                assertInstanceOf(io.github.resilience4j.circuitbreaker.CallNotPermittedException.class, open.getCause());
                verify(failingClient, times(20)).post();
            }
        } finally {
            server.stop(0);
            try (var files = Files.walk(directory)) { for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file); }
        }
    }

    @Test void generatedRestSourcesDocumentOperationsAndRecordComponents(@TempDir Path directory) throws Exception {
        var mapper = JsonMapper.builder().build();
        var request = mapper.readTree("""
            {"type":"object","properties":{"code":{"type":"string","description":"Product code <SKU>"},"note":{"type":"string"}}}
            """);
        var response = mapper.readTree("""
            {"type":"object","properties":{"id":{"type":"string","description":"Order identifier"}}}
            """);
        var http = new HttpOperation("POST", "/orders", true, true, List.of());
        var operation = new ApiModels.IrMethod("createOrder", "REQUEST_RESPONSE", request, response, http, null,
            "Creates an order with <items> & notes. */\r\n@return literal " + "\\u002a/");
        var settings = mapper.createObjectNode().put("host", "api.example").put("port", 80).put("target_endpoint", "/");
        var connection = new ApiModels.IrConnection("orders", "api", "REST", settings, List.of(operation), null);
        var manifest = new ApiModels.IrManifest("orders", "DEV", Instant.now(), List.of(new ApiModels.IrService("store", false,
            List.of(connection))));
        var builder = mock(IntermediateRepresentationBuilder.class);
        when(builder.build(any(), eq("DEV"))).thenReturn(manifest);
        var config = new Configuration(Configuration.VERSION_2_3_34);
        config.setClassLoaderForTemplateLoading(getClass().getClassLoader(), "templates");
        var generator = new CodeGenerationService(builder, config, null, mock(BuildEventPublisher.class), null,
            new SimpleMeterRegistry());
        var generation = new CodegenModels.GenerateRequest(UUID.randomUUID(), "DEV", "example", "OrderClient", null);
        var expectedDescription = "     * Creates an order with &lt;items&gt; &amp; notes. *&#47;\n"
            + "     * &#64;return literal &#92;u002a/\n";
        assertTrue(generator.generate(generation).files().get("OrderClient.java").contains(expectedDescription));
        var files = generator.generateArtifact(generation).files();
        assertTrue(files.get("OrderClient.java").contains(expectedDescription
            + "     */\n    public OrderClientResponse createOrder"));
        assertTrue(files.get("OrderClientRequest.java").contains("@param code Product code &lt;SKU&gt;"));
        assertFalse(files.get("OrderClientRequest.java").contains("@param note"));
        assertTrue(files.get("OrderClientResponse.java").contains("@param id Order identifier"));

        var args = new java.util.ArrayList<>(List.of("-encoding", "UTF-8", "-classpath", System.getProperty("java.class.path"),
            "-d", directory.toString(), "-Xdoclint:all,-missing"));
        for (var file : files.entrySet()) {
            args.add(Files.writeString(directory.resolve(file.getKey()), file.getValue()).toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
    }
}
