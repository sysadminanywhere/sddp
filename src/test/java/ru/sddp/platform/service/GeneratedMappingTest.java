package ru.sddp.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import freemarker.template.Configuration;
import freemarker.template.Template;
import org.junit.jupiter.api.Test;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.web.client.RestClient;

class GeneratedMappingTest {
    @Test void generatedAdapterCompilesAndTransformsDocumentedRules() throws Exception {
        var mapper = new ObjectMapper();
        String rules = "{\"transformations\":[{\"type\":\"DIRECT_COPY\",\"from\":\"$.order.id\",\"to\":\"$.transaction_uuid\"},{\"type\":\"CONCATENATE\",\"from\":[\"$.user.first_name\",\"$.user.last_name\"],\"to\":\"$.payment_details.receiver_name\",\"separator\":\" \"},{\"type\":\"HARDCODED_VALUE\",\"value\":\"RUB\",\"to\":\"$.currency\"}]}";
        var config = new Configuration(Configuration.VERSION_2_3_34);
        config.setClassLoaderForTemplateLoading(getClass().getClassLoader(), "templates");
        Template template = config.getTemplate("proxy-adapter.java.ftl");
        var source = new StringWriter();
        template.process(Map.of("package_name", "example", "client_name", "GeneratedClient", "base_url", "http://localhost:8080", "target_endpoint", "/orders", "methods", List.of("send"), "mapping_json", rules, "timeout_ms", 5000, "retries", 3), source);
        var directory = Files.createTempDirectory("sddp-generated-test-");
        try {
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
            try (var files = Files.walk(directory)) { for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file); }
        }
    }
}
