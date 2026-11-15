package ru.sddp.platform.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import ru.sddp.platform.domain.*;
import ru.sddp.platform.repository.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = SnapshotIntegrationTest.Config.class, properties = {
    "spring.datasource.url=jdbc:h2:mem:snapshot;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false"
})
class SnapshotIntegrationTest {
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("ru.sddp.platform.domain")
    @EnableJpaRepositories("ru.sddp.platform.repository")
    @Import({SnapshotExportService.class, SnapshotImportService.class, ProjectService.class,
        EnvironmentService.class, ApiSchemaValidator.class, ConnectionValidator.class})
    static class Config { }

    @Autowired ProjectRepository projects;
    @Autowired ProjectEnvironmentRepository environments;
    @Autowired ServiceNodeRepository nodes;
    @Autowired ServiceApiRepository apis;
    @Autowired TopologyConnectionRepository connections;
    @Autowired SnapshotExportService exporter;
    @Autowired SnapshotImportService importer;
    private final ObjectMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();

    @Test void restoresTopologyAndRollsBackInvalidImports() throws Exception {
        var project = projects.save(new Project("snapshot-integration"));
        environments.save(new ProjectEnvironment(project, "CUSTOM", "Protected test", 77, true));
        environments.save(new ProjectEnvironment(project, "DEV", "Development", 10, false));
        var source = new ServiceNode(project, "source", ServiceType.CORE, null);
        source.setPosition(11, 22);
        source = nodes.save(source);
        var target = nodes.save(new ServiceNode(project, "target", ServiceType.EXTERNAL, null));
        var schema = mapper.readTree("{\"type\":\"object\",\"properties\":{}}");
        var api = apis.save(new ServiceApi(target, "send", "1", null, ApiPattern.REQUEST_RESPONSE, schema, null));
        var settings = mapper.readTree("{\"host\":\"example\",\"port\":8080,\"target_endpoint\":\"/\"}");
        var mapping = mapper.readTree("{\"target_api\":\"send\",\"transformations\":[]}");
        connections.save(new TopologyConnection(project, "CUSTOM", source, target, null, api, "REST", settings, mapping));

        var json = mapper.valueToTree(exporter.export(project.getId()));
        var imported = importer.importSnapshot(json);
        var restored = exporter.export(imported.id());
        assertEquals(1, restored.formatVersion());
        var protectedEnvironment = restored.environments().stream().filter(e -> e.code().equals("CUSTOM")).findFirst().orElseThrow();
        assertEquals("Protected test", protectedEnvironment.name());
        assertTrue(protectedEnvironment.isProtected());
        assertEquals(77, protectedEnvironment.position());
        assertEquals(2, restored.environments().size());
        assertEquals(11, restored.services().stream().filter(s -> s.name().equals("source")).findFirst().orElseThrow().positionX());
        assertEquals(22, restored.services().stream().filter(s -> s.name().equals("source")).findFirst().orElseThrow().positionY());
        assertEquals(schema, restored.apis().getFirst().requestSchema());
        assertEquals(mapping, restored.connections().getFirst().mappingRules());
        assertEquals(restored.apis().getFirst().id(), restored.connections().getFirst().targetApiId());

        var selected = exporter.export(project.getId(), "CUSTOM");
        var selectedImport = importer.importSnapshot(mapper.valueToTree(selected));
        assertEquals(1, exporter.export(selectedImport.id()).environments().size());

        ((ObjectNode) json).put("projectName", "invalid-snapshot");
        ((ObjectNode) json.path("connections").get(0)).put("environment", "UNKNOWN");
        assertThrows(IllegalArgumentException.class, () -> importer.importSnapshot(json));
        assertFalse(projects.existsByName("invalid-snapshot"));
        ((ObjectNode) json).put("formatVersion", "1");
        assertThrows(IllegalArgumentException.class, () -> importer.importSnapshot(json));
        ((ObjectNode) json).put("formatVersion", 1);
        ((ObjectNode) json).put("environments", "CUSTOM");
        assertThrows(IllegalArgumentException.class, () -> importer.importSnapshot(json));
        assertFalse(projects.existsByName("invalid-snapshot"));
    }
}
