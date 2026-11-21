package ru.sddp.platform.service;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import javax.tools.ToolProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.sddp.platform.SddpApplication;
import ru.sddp.platform.api.ApiModels;
import ru.sddp.platform.api.CodegenModels;
import ru.sddp.platform.api.OpenApiModels;
import ru.sddp.platform.api.TopologyRequests;
import ru.sddp.platform.domain.ServiceType;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "SDDP_POSTGRES_IT", matches = "true")
@SpringBootTest(classes = SddpApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true", "sddp.max-request-bytes=1024"})
class SnapshotPostgresIntegrationTest extends SnapshotIntegrationTest {
    @LocalServerPort int port;
    @Autowired ProjectService projectService;
    @Autowired ServiceCatalogService catalog;
    @Autowired OpenApiImportService openApi;
    @Autowired TopologyWriteService topology;
    @Autowired ConnectionPromotionService promotion;
    @Autowired IntermediateRepresentationBuilder ir;
    @Autowired CodeGenerationService codegen;
    private static final String SCHEMA = "snapshot_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String URL = Objects.requireNonNullElse(System.getenv("SddP_DB_URL"), "jdbc:postgresql://localhost:5432/sddp");
    private static final String USER = Objects.requireNonNullElse(System.getenv("SddP_DB_USERNAME"), "sddp");
    private static final String PASSWORD = Objects.requireNonNullElse(System.getenv("SddP_DB_PASSWORD"), "sddp");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) throws Exception {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
        }
        properties.add("spring.datasource.url", () -> URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        properties.add("spring.datasource.username", () -> USER);
        properties.add("spring.datasource.password", () -> PASSWORD);
    }

    @Test void jsonApiUsesJacksonTwoForRequestsAndResponses() throws Exception {
        var body = """
            {"formatVersion":1,"projectName":"http-import","environments":[{"code":"DEV","name":"Development","position":10,"isProtected":false}],"services":[],"apis":[],"connections":[]}
            """;
        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/projects/import"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode(), response.body());
        assertTrue(response.body().contains("http-import"), response.body());
        var id = tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.body()).path("id").asText();
        var export = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/projects/" + id + "/snapshot"))
            .POST(HttpRequest.BodyPublishers.noBody()).build();
        var exported = client.send(export, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, exported.statusCode(), exported.body());
        var snapshot = tools.jackson.databind.json.JsonMapper.builder().build().readTree(exported.body());
        assertEquals(1, snapshot.path("formatVersion").asInt());
        assertEquals("Development", snapshot.path("environments").get(0).path("name").asText());
    }

    @Test void rejectsOversizedKnownLengthAndChunkedBodies() throws Exception {
        var client = HttpClient.newHttpClient();
        byte[] body = new byte[1025];
        java.util.Arrays.fill(body, (byte) 'x');
        for (var publisher : java.util.List.of(HttpRequest.BodyPublishers.ofByteArray(body),
                HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(body)))) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/projects/import"))
                .header("Content-Type", "application/json").POST(publisher).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(413, response.statusCode(), response.body());
            assertTrue(response.body().contains("size limit"));
        }
        var atLimit = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/projects/import"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(new byte[1024])).build();
        assertEquals(400, client.send(atLimit, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test void importsPromotesAndCallsBothOpenApiOperations(@TempDir Path directory) throws Exception {
        var mapper = JsonMapper.builder().build();
        var document = mapper.readTree(Files.readString(Path.of("docs/examples/orders.openapi.json")));
        var project = projectService.create(new ApiModels.CreateProjectRequest("orders-pilot")).id();
        var source = catalog.createService(project, new TopologyRequests.CreateServiceRequest("store", ServiceType.CORE, null));
        var target = catalog.createService(project, new TopologyRequests.CreateServiceRequest("orders", ServiceType.EXTERNAL, null));
        var preview = openApi.preview(project, target.id(), document);
        assertTrue(preview.warnings().isEmpty(), preview.warnings().toString());
        assertEquals("1.0.0", preview.version());
        var selected = new OpenApiModels.ImportRequest(document, List.of("GET /orders/{id}", "POST /orders"));
        assertThrows(IllegalArgumentException.class, () -> openApi.importSelected(project, target.id(),
            new OpenApiModels.ImportRequest(document, List.of("GET /orders/{id}", "GET /invalid"))));
        assertTrue(catalog.listApis(project, target.id()).isEmpty());
        var imported = openApi.importSelected(project, target.id(), selected);
        assertEquals(2, imported.created().size());
        var operations = catalog.listApis(project, target.id());
        var get = operations.stream().filter(api -> api.name().equals("getOrder")).findFirst().orElseThrow();
        var post = operations.stream().filter(api -> api.name().equals("createOrder")).findFirst().orElseThrow();
        assertTrue(post.requestSchema().path("properties").has("productCode"));
        assertTrue(get.responseSchema().path("properties").has("id"));

        var requests = new LinkedBlockingQueue<List<String>>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/orders", exchange -> {
            try (exchange) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                requests.add(List.of(exchange.getRequestMethod(), exchange.getRequestURI().toASCIIString(), body));
                byte[] result = "{\"id\":\"test-order\",\"productCode\":\"SKU-1\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, result.length);
                exchange.getResponseBody().write(result);
            }
        });
        server.start();
        try {
            var settings = mapper.createObjectNode().put("host", "127.0.0.1").put("port", server.getAddress().getPort())
                .put("target_endpoint", "/").put("retries", 0);
            topology.addConnection(project, new TopologyRequests.CreateConnectionRequest("DEV", source.id(),
                target.id(), null, get.id(), "REST", settings, null), false);
            var mapping = mapper.readTree("""
                {"target_api":"createOrder v1.0.0","transformations":[
                    {"type":"DIRECT_COPY","from":"$.sku","to":"$.productCode"}]}
                """);
            var postConnection = topology.addConnection(project, new TopologyRequests.CreateConnectionRequest("DEV",
                source.id(), target.id(), null, post.id(), "REST", settings, mapping), false);
            var duplicate = openApi.importSelected(project, target.id(), selected);
            assertEquals(imported.created(), duplicate.existing());
            assertTrue(duplicate.created().isEmpty());
            assertEquals(post.id(), catalog.getApi(project, target.id(), postConnection.targetApiId()).id());

            var promotionRequest = new TopologyRequests.CopyConnectionsRequest("DEV", "PROD", "MERGE");
            assertThrows(ProtectedEnvironmentException.class, () -> promotion.copy(project, promotionRequest, false));
            assertEquals(2, promotion.copy(project, promotionRequest, true).copied());
            var snapshot = mapper.valueToTree(exporter.export(project));
            assertEquals(1, snapshot.path("formatVersion").asInt());
            var restored = importer.importSnapshot(snapshot).id();
            var manifest = ir.build(restored, "PROD");
            assertEquals(2, manifest.services().stream().flatMap(service -> service.outbound_connections().stream())
                .flatMap(connection -> connection.methods().stream()).filter(method -> method.http_operation() != null).count());

            var files = codegen.generate(new CodegenModels.GenerateRequest(restored, "PROD", "pilot", "OrdersClient", null)).files();
            var compilerArgs = new ArrayList<>(List.of("-classpath", System.getProperty("java.class.path"),
                "-d", directory.toString()));
            for (var entry : files.entrySet()) {
                var javaFile = directory.resolve(entry.getKey());
                Files.writeString(javaFile, entry.getValue());
                compilerArgs.add(javaFile.toString());
            }
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, compilerArgs.toArray(String[]::new)));
            try (var loader = new URLClassLoader(new URL[]{directory.toUri().toURL()}, getClass().getClassLoader())) {
                var readClient = generatedClass(files, loader, "getOrder");
                var createClient = generatedClass(files, loader, "createOrder");
                var reader = readClient.getConstructor(RestClient.Builder.class).newInstance(RestClient.builder());
                var creator = createClient.getConstructor(RestClient.Builder.class, com.fasterxml.jackson.databind.ObjectMapper.class)
                    .newInstance(RestClient.builder(), new com.fasterxml.jackson.databind.ObjectMapper());
                var order = readClient.getMethod("getOrder", Map.class, Map.class).invoke(
                    reader, Map.of("id", "test/42"), Map.of("view", "full + detail"));
                assertEquals("test-order", mapper.valueToTree(order).path("id").asText());
                createClient.getMethod("createOrder", Object.class).invoke(creator, Map.of("sku", "SKU-1"));
            }
            assertEquals(List.of("GET", "/orders/test%2F42?view=full%20%2B%20detail", ""), requests.remove());
            var sent = requests.remove();
            assertEquals(List.of("POST", "/orders"), sent.subList(0, 2));
            assertEquals(mapper.readTree("{\"productCode\":\"SKU-1\"}"), mapper.readTree(sent.get(2)));
        } finally {
            server.stop(0);
        }
    }

    private Class<?> generatedClass(Map<String, String> files, ClassLoader loader, String method) throws Exception {
        String file = files.entrySet().stream().filter(entry -> entry.getValue().contains("public Object " + method + "("))
            .findFirst().orElseThrow().getKey();
        return loader.loadClass("pilot." + file.substring(0, file.length() - ".java".length()));
    }

    @AfterAll
    static void cleanupSchema() throws Exception {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }
}
