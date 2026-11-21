package ru.sddp.platform.service;

import java.sql.DriverManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.sddp.platform.SddpApplication;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "SDDP_POSTGRES_IT", matches = "true")
@SpringBootTest(classes = SddpApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true", "sddp.max-request-bytes=1024"})
class SnapshotPostgresIntegrationTest extends SnapshotIntegrationTest {
    @LocalServerPort int port;
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

    @AfterAll
    static void cleanupSchema() throws Exception {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }
}
