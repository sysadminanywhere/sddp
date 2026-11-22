package ru.sddp.platform.service;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.zip.ZipInputStream;
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
import tools.jackson.databind.node.ObjectNode;
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
    @Autowired JavaArtifactService artifacts;
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

    @Test void validatesAndImportsSelectedOperationsFromLargeContracts() {
        var mapper = JsonMapper.builder().build();
        var project = projectService.create(new ApiModels.CreateProjectRequest("openapi-large")).id();
        var target = catalog.createService(project, new TopologyRequests.CreateServiceRequest("external", ServiceType.EXTERNAL, null));
        var document = mapper.createObjectNode().put("openapi", "3.0.3");
        document.putObject("info").put("version", "1");
        var paths = document.putObject("paths");
        for (int i = 0; i < 200; i++) {
            paths.putObject("/invalid/" + i).putObject("get").putObject("responses").putObject("200")
                .put("$ref", "#/missing");
        }
        paths.putObject("/items").putObject("get").put("operationId", "list/items").putObject("responses")
            .putObject("204").put("description", "No content");
        paths.putObject("/other").putObject("get").put("operationId", "list-items").putObject("responses")
            .putObject("204").put("description", "No content");
        paths.putObject("/legacy").putObject("get").put("operationId", "list_items").putObject("responses")
            .putObject("204").put("description", "No content");
        var inventory = openApi.preview(project, target.id(), document);
        assertTrue(inventory.selectionRequired());
        assertEquals(203, inventory.operations().size());
        assertTrue(inventory.warnings().isEmpty());

        var keys = List.of("GET /legacy", "GET /other", "GET /items");
        var preview = openApi.preview(project, target.id(), new OpenApiModels.PreviewRequest(document, null, keys));
        assertFalse(preview.selectionRequired());
        assertTrue(preview.warnings().isEmpty());
        assertEquals(keys, preview.operations().stream().map(OpenApiModels.Operation::key).toList());
        assertEquals("list_items", preview.operations().getFirst().name());
        assertEquals(3, preview.operations().stream().map(OpenApiModels.Operation::name).distinct().count());
        assertThrows(IllegalArgumentException.class, () -> openApi.importSelected(project, target.id(),
            new OpenApiModels.ImportRequest(document, List.of("GET /items", "GET /invalid/1"))));
        assertTrue(catalog.listApis(project, target.id()).isEmpty());
        var selected = new OpenApiModels.ImportRequest(document, keys);
        var imported = openApi.importSelected(project, target.id(), selected);
        assertEquals(3, imported.created().size());
        var repeated = openApi.importSelected(project, target.id(), new OpenApiModels.ImportRequest(document, keys.reversed()));
        assertEquals(imported.created().reversed(), repeated.existing());
        assertTrue(repeated.created().isEmpty());
    }

    @Test void importsYamlAndRejectsAmbiguousDocuments() {
        var project = projectService.create(new ApiModels.CreateProjectRequest("openapi-yaml")).id();
        var target = catalog.createService(project, new TopologyRequests.CreateServiceRequest("external", ServiceType.EXTERNAL, null));
        var yaml = """
            openapi: 3.0.3
            info:
              version: '1'
            paths:
              /items:
                get:
                  operationId: list/items
                  responses:
                    200:
                      $ref: '#/components/responses/Items'
            components:
              responses:
                Items:
                  description: Items
                  content:
                    application/json:
                      schema:
                        type: array
                        items:
                          type: string
            """;
        var keys = List.of("GET /items");
        var preview = openApi.preview(project, target.id(), new OpenApiModels.PreviewRequest(null, yaml, keys));
        assertEquals(keys, preview.operations().stream().map(OpenApiModels.Operation::key).toList());
        var imported = openApi.importSelected(project, target.id(), new OpenApiModels.ImportRequest(null, yaml, keys));
        assertEquals(1, imported.created().size());
        assertEquals("array", catalog.getApi(project, target.id(), imported.created().getFirst()).responseSchema().path("type").asText());
        assertThrows(IllegalArgumentException.class, () -> openApi.preview(project, target.id(),
            new OpenApiModels.PreviewRequest(null, yaml + "---\npaths: {}\n", null)));
        assertThrows(IllegalArgumentException.class, () -> openApi.preview(project, target.id(),
            new OpenApiModels.PreviewRequest(null, yaml + "paths: {}\n", null)));
    }

    @Test void previewsContractDriftAndKeepsConnectionsOnTheOriginalVersion() throws Exception {
        var mapper = JsonMapper.builder().build();
        var original = mapper.readTree(Files.readString(Path.of("docs/examples/orders.openapi.json")));
        var project = projectService.create(new ApiModels.CreateProjectRequest("openapi-diff")).id();
        var client = catalog.createService(project, new TopologyRequests.CreateServiceRequest("client", ServiceType.CORE, null));
        var target = catalog.createService(project, new TopologyRequests.CreateServiceRequest("external", ServiceType.EXTERNAL, null));
        var selection = List.of("POST /orders");
        var old = openApi.importSelected(project, target.id(), new OpenApiModels.ImportRequest(original, selection)).created().getFirst();
        var settings = mapper.createObjectNode().put("host", "127.0.0.1").put("port", 8081).put("target_endpoint", "/");
        var mapping = mapper.readTree("""
            {"target_api":"createOrder v1.0.0",
             "transformations":[{"type":"HARDCODED_VALUE","to":"$.productCode","value":"SKU-1"}]}
            """);
        var connection = topology.addConnection(project, new TopologyRequests.CreateConnectionRequest("DEV",
            client.id(), target.id(), null, old, "REST", settings, mapping), false);
        assertEquals(1, promotion.copy(project, new TopologyRequests.CopyConnectionsRequest("DEV", "PROD", "MERGE"), true).copied());

        var changed = original.deepCopy();
        ((ObjectNode) changed.at("/components/schemas/CreateOrder/properties")).putObject("quantity").put("type", "integer");
        ((ObjectNode) changed.at("/components/schemas/Order/properties")).remove("id");
        var operation = openApi.preview(project, target.id(), new OpenApiModels.PreviewRequest(changed, null, selection))
            .operations().getFirst();
        assertTrue(operation.exists());
        assertEquals("1.0.0", operation.comparison().baselineVersion());
        assertEquals(Set.of(
            new OpenApiModels.Change("/requestSchema/properties/quantity", OpenApiModels.Change.Kind.ADDED),
            new OpenApiModels.Change("/responseSchema/properties/id", OpenApiModels.Change.Kind.REMOVED)),
            Set.copyOf(operation.comparison().changes()));
        assertThrows(CompatibilityViolationException.class, () ->
            openApi.importSelected(project, target.id(), new OpenApiModels.ImportRequest(changed, selection)));
        var request = new OpenApiModels.ImportRequest(changed, null, selection, "1.1.0");
        var added = openApi.importSelected(project, target.id(), request).created().getFirst();
        assertNotEquals(old, added);
        assertEquals(List.of(added), openApi.importSelected(project, target.id(), request).existing());
        assertFalse(catalog.getApi(project, target.id(), old).requestSchema().path("properties").has("quantity"));
        assertTrue(catalog.getApi(project, target.id(), added).requestSchema().path("properties").has("quantity"));
        assertFalse(ir.build(project, "DEV").services().stream().flatMap(service -> service.outbound_connections().stream())
            .findFirst().orElseThrow().methods().getFirst().request_schema().path("properties").has("quantity"));
        var invalid = mapper.readTree("""
            {"target_api":"createOrder v1.1.0",
             "transformations":[{"type":"HARDCODED_VALUE","to":"$.quantity","value":"not an integer"}]}
            """);
        assertThrows(CompatibilityViolationException.class, () -> topology.rebindTargetVersion(project, connection.id(),
            new TopologyRequests.RebindTargetVersionRequest(old, added, invalid), false));
        var moved = topology.rebindTargetVersion(project, connection.id(),
            new TopologyRequests.RebindTargetVersionRequest(old, added, null), false);
        assertEquals(added, moved.targetApiId());
        assertEquals("createOrder v1.1.0", moved.mappingRules().path("target_api").asText());
        assertTrue(ir.build(project, "DEV").services().stream().flatMap(service -> service.outbound_connections().stream())
            .findFirst().orElseThrow().methods().getFirst().request_schema().path("properties").has("quantity"));
        assertFalse(ir.build(project, "PROD").services().stream().flatMap(service -> service.outbound_connections().stream())
            .findFirst().orElseThrow().methods().getFirst().request_schema().path("properties").has("quantity"));
        assertThrows(CompatibilityViolationException.class, () -> topology.rebindTargetVersion(project, connection.id(),
            new TopologyRequests.RebindTargetVersionRequest(old, added, null), false));
    }

    @Test void structuredOpenApiRequestsSurviveSnapshotsAndSendCorrectWireFormats(@TempDir Path directory) throws Exception {
        var mapper = JsonMapper.builder().build();
        var document = mapper.readTree("""
            {
              "openapi":"3.1.0", "info":{"version":"1"}, "paths":{
                "/customers/{id}":{"get":{
                  "operationId":"listCustomers",
                  "parameters":[
                    {"name":"id","in":"path","required":true,"schema":{"oneOf":[{"type":"string"},{"type":"integer"}]}},
                    {"name":"created","in":"query","style":"deepObject","explode":true,
                      "schema":{"anyOf":[{"type":"integer"},{"type":"object"}]}},
                    {"name":"expand","in":"query","style":"deepObject","explode":true,
                      "schema":{"type":"array","items":{"type":"string"}}},
                    {"name":"labels","in":"query","style":"form","explode":false,"schema":{"type":"array","items":{"type":"string"}}},
                    {"name":"not[labels]","in":"query","schema":{"type":"array","items":{"type":"string"}}}
                  ],
                  "responses":{"200":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Customer"}}}}}
                }},
                "/customers":{"post":{
                  "operationId":"createCustomer",
                  "requestBody":{"required":true,"content":{"application/x-www-form-urlencoded":{
                    "schema":{"type":"object","properties":{"name":{"$ref":"#/components/schemas/Name","maxLength":42}}},
                    "encoding":{"address":{"style":"deepObject","explode":true},"expand":{"style":"deepObject","explode":true}}
                  }}},
                  "responses":{"204":{"description":"No content"}}
                }}
              },
              "components":{"schemas":{
                "Name":{"type":"string"},
                "Customer":{"type":"object","properties":{"parent":{"$ref":"#/components/schemas/Customer"}}}
              }}
            }
            """);
        var project = projectService.create(new ApiModels.CreateProjectRequest("openapi-wire")).id();
        var source = catalog.createService(project, new TopologyRequests.CreateServiceRequest("internal", ServiceType.CORE, null));
        var target = catalog.createService(project, new TopologyRequests.CreateServiceRequest("external", ServiceType.EXTERNAL, null));
        var selection = List.of("GET /customers/{id}", "POST /customers");
        var preview = openApi.preview(project, target.id(), document);
        assertTrue(preview.warnings().isEmpty(), preview.warnings().toString());
        var apis = openApi.importSelected(project, target.id(), new OpenApiModels.ImportRequest(document, selection)).created();
        assertTrue(catalog.getApi(project, target.id(), apis.getFirst()).responseSchema().has("definitions"));
        assertEquals(42, catalog.getApi(project, target.id(), apis.getLast()).requestSchema()
            .at("/properties/name/allOf/1/maxLength").asInt());

        var requests = new LinkedBlockingQueue<List<String>>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/customers", exchange -> {
            try (exchange) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                requests.add(List.of(exchange.getRequestMethod(), exchange.getRequestURI().toASCIIString(),
                    Objects.toString(exchange.getRequestHeaders().getFirst("Content-Type"), ""), body));
                byte[] result = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, result.length);
                exchange.getResponseBody().write(result);
            }
        });
        server.start();
        try {
            var settings = mapper.createObjectNode().put("host", "127.0.0.1").put("port", server.getAddress().getPort())
                .put("target_endpoint", "/").put("retries", 0);
            for (UUID id : apis) {
                topology.addConnection(project, new TopologyRequests.CreateConnectionRequest("DEV",
                    source.id(), target.id(), null, id, "REST", settings, null), false);
            }
            var restored = importer.importSnapshot(mapper.valueToTree(exporter.export(project))).id();
            var files = codegen.generate(new CodegenModels.GenerateRequest(restored, "DEV", "pilot", "Customers", null)).files();
            compile(files, directory);
            try (var loader = new URLClassLoader(new URL[]{directory.toUri().toURL()}, getClass().getClassLoader())) {
                var get = generatedClass(files, loader, "listCustomers");
                var reader = get.getConstructor(RestClient.Builder.class).newInstance(RestClient.builder());
                get.getMethod("listCustomers", Map.class, Map.class).invoke(reader, Map.of("id", "a/b"),
                    Map.of("created", Map.of("gte", 42), "expand", List.of("one", "two"),
                        "labels", List.of("a,b", "c&d"), "not[labels]", List.of("first", "second")));
                var post = generatedClass(files, loader, "createCustomer");
                var creator = post.getConstructor(RestClient.Builder.class).newInstance(RestClient.builder());
                post.getMethod("createCustomer", Object.class).invoke(creator,
                    Map.of("name", "Ada + B", "address", Map.of("city", "New York"), "expand", List.of("default_source")));
            }
            var get = requests.remove();
            assertEquals("GET", get.getFirst());
            assertEquals("", get.getLast());
            var uri = URI.create(get.get(1));
            assertEquals("/customers/a%2Fb", uri.getRawPath());
            assertEquals(Set.of("created%5Bgte%5D=42", "expand%5B%5D=one", "expand%5B%5D=two", "labels=a%2Cb,c%26d",
                "not%5Blabels%5D=first", "not%5Blabels%5D=second"), Set.of(uri.getRawQuery().split("&")));
            var post = requests.remove();
            assertEquals(List.of("POST", "/customers"), post.subList(0, 2));
            assertTrue(post.get(2).startsWith("application/x-www-form-urlencoded"), post.toString());
            assertEquals(Set.of("name=Ada+%2B+B", "address%5Bcity%5D=New+York", "expand%5B%5D=default_source"),
                Set.of(post.getLast().split("&")));
        } finally {
            server.stop(0);
        }
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
            compile(files, directory);
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

            var artifactRequest = new CodegenModels.ArtifactRequest(restored, "PROD", "pilot", "Orders", "1.0");
            byte[] artifact = artifacts.build(artifactRequest);
            assertArrayEquals(artifact, artifacts.build(artifactRequest), "Archive should be reproducible for a restored project");
            var entries = new TreeMap<String, byte[]>();
            try (var zip = new ZipInputStream(new ByteArrayInputStream(artifact))) {
                for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                    entries.put(entry.getName(), zip.readAllBytes());
                }
            }
            var metadata = mapper.readTree(entries.get("sddp-artifact.json"));
            assertEquals("PROD", metadata.path("environment").asText());
            assertEquals("1.0", metadata.path("version").asText());
            assertEquals(2, metadata.path("contracts").size());
            assertEquals("1.0.0", metadata.path("contracts").get(0).path("version").asText());
            assertTrue(entries.containsKey("SHA256SUMS"));
            assertTrue(entries.containsKey("pom.xml"));
            var artifactSources = new TreeMap<String, String>();
            for (var entry : entries.entrySet()) {
                if (entry.getKey().endsWith(".java")) artifactSources.put(Path.of(entry.getKey()).getFileName().toString(),
                    new String(entry.getValue(), StandardCharsets.UTF_8));
            }
            assertEquals(4, artifactSources.size(), "Both Java clients should have response DTOs");
            compile(artifactSources, directory);
            try (var loader = new URLClassLoader(new URL[]{directory.toUri().toURL()}, getClass().getClassLoader())) {
                String clientFile = artifactSources.entrySet().stream().filter(entry -> entry.getValue().contains("getOrder(Map"))
                    .findFirst().orElseThrow().getKey();
                var client = loader.loadClass("pilot." + clientFile.replace(".java", ""));
                var instance = client.getConstructor(RestClient.Builder.class).newInstance(RestClient.builder());
                var dto = client.getMethod("getOrder", Map.class, Map.class)
                    .invoke(instance, Map.of("id", "typed"), Map.of());
                assertTrue(dto.getClass().isRecord());
                assertEquals("test-order", dto.getClass().getMethod("id").invoke(dto));
            }
        } finally {
            server.stop(0);
        }
    }

    private void compile(Map<String, String> files, Path directory) throws Exception {
        var compilerArgs = new ArrayList<>(List.of("-classpath", System.getProperty("java.class.path"), "-d", directory.toString()));
        for (var entry : files.entrySet()) {
            var javaFile = directory.resolve(entry.getKey());
            Files.writeString(javaFile, entry.getValue());
            compilerArgs.add(javaFile.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, compilerArgs.toArray(String[]::new)));
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
