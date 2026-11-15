package ru.sddp.platform.service;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.sddp.platform.domain.Project;
import ru.sddp.platform.domain.ProjectEnvironment;
import ru.sddp.platform.domain.ServiceApi;
import ru.sddp.platform.domain.ServiceNode;
import ru.sddp.platform.domain.TopologyConnection;
import ru.sddp.platform.repository.TopologyConnectionRepository;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EnvironmentComparisonServiceTest {
    private final ObjectMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();
    private final EnvironmentService environments = mock(EnvironmentService.class);
    private final TopologyConnectionRepository connections = mock(TopologyConnectionRepository.class);
    private final EnvironmentComparisonService service = new EnvironmentComparisonService(environments, connections);

    private final UUID projectId = UUID.randomUUID();
    private final Project project = new Project("comparison-test");

    private ProjectEnvironment environment(String code) {
        return new ProjectEnvironment(project, code, code, 0, false);
    }

    private ServiceNode node(UUID id, String name) {
        ServiceNode node = mock(ServiceNode.class);
        when(node.getId()).thenReturn(id);
        when(node.getName()).thenReturn(name);
        return node;
    }

    private ServiceApi api(UUID id, String name, String version) {
        ServiceApi api = mock(ServiceApi.class);
        when(api.getId()).thenReturn(id);
        when(api.getName()).thenReturn(name);
        when(api.getVersion()).thenReturn(version);
        return api;
    }

    private TopologyConnection connection(String environment, ServiceNode source, ServiceNode target,
                                          ServiceApi sourceApi, ServiceApi targetApi, String host,
                                          String mappingTo) throws Exception {
        var settings = mapper.readTree("{\"host\":\"" + host + "\",\"port\":8080,\"target_endpoint\":\"/orders\"}");
        var mapping = mapper.readTree("{\"target_api\":\"payment.charge\",\"transformations\":[{\"type\":\"DIRECT_COPY\",\"from\":\"$.a\",\"to\":\"" + mappingTo + "\"}]}");
        return new TopologyConnection(project, environment, source, target, sourceApi, targetApi, "REST", settings, mapping);
    }

    @Test void reportsMissingAndChangedConnections() throws Exception {
        var sourceEnv = environment("DEV");
        var targetEnv = environment("STAGE");
        when(environments.require(projectId, "DEV")).thenReturn(sourceEnv);
        when(environments.require(projectId, "STAGE")).thenReturn(targetEnv);

        var sourceNode = node(UUID.randomUUID(), "order-core");
        var targetNode = node(UUID.randomUUID(), "payment-core");
        var sourceApi = api(UUID.randomUUID(), "order.created", "1");
        var targetApi = api(UUID.randomUUID(), "payment.charge", "1");

        var changedSource = connection("DEV", sourceNode, targetNode, sourceApi, targetApi, "payment", "$.b");
        var changedTarget = connection("STAGE", sourceNode, targetNode, sourceApi, targetApi, "payment", "$.other");
        var onlySourceApi = api(UUID.randomUUID(), "order.cancelled", "1");
        var onlySource = connection("DEV", sourceNode, targetNode, sourceApi, onlySourceApi, "legacy", "$.b");

        var otherNode = node(UUID.randomUUID(), "billing-core");
        var onlyTarget = connection("STAGE", sourceNode, otherNode, sourceApi, targetApi, "billing", "$.b");

        when(connections.findByProjectIdAndEnvironment(projectId, "DEV")).thenReturn(List.of(changedSource, onlySource));
        when(connections.findByProjectIdAndEnvironment(projectId, "STAGE")).thenReturn(List.of(changedTarget, onlyTarget));

        var result = service.compare(projectId, "DEV", "STAGE");

        assertEquals("DEV", result.sourceEnvironment());
        assertEquals("STAGE", result.targetEnvironment());
        assertEquals(2, result.sourceConnections());
        assertEquals(2, result.targetConnections());
        assertEquals(3, result.differences().size());

        var changed = result.differences().stream()
            .filter(difference -> "CHANGED".equals(difference.kind())).findFirst().orElseThrow();
        assertTrue(changed.changedFields().contains("mapping_rules"));
        assertFalse(changed.changedFields().contains("connection_settings"));

        assertEquals(1, result.differences().stream()
            .filter(difference -> "MISSING_IN_TARGET".equals(difference.kind())).count());
        assertEquals(1, result.differences().stream()
            .filter(difference -> "MISSING_IN_SOURCE".equals(difference.kind())).count());
    }

    @Test void returnsNoDifferencesForIdenticalEnvironments() throws Exception {
        var sourceEnv = environment("DEV");
        var targetEnv = environment("STAGE");
        when(environments.require(projectId, "DEV")).thenReturn(sourceEnv);
        when(environments.require(projectId, "STAGE")).thenReturn(targetEnv);

        var sourceNode = node(UUID.randomUUID(), "order-core");
        var targetNode = node(UUID.randomUUID(), "payment-core");
        var sourceApi = api(UUID.randomUUID(), "order.created", "1");
        var targetApi = api(UUID.randomUUID(), "payment.charge", "1");
        var left = connection("DEV", sourceNode, targetNode, sourceApi, targetApi, "payment", "$.b");
        var right = connection("STAGE", sourceNode, targetNode, sourceApi, targetApi, "payment", "$.b");

        when(connections.findByProjectIdAndEnvironment(projectId, "DEV")).thenReturn(List.of(left));
        when(connections.findByProjectIdAndEnvironment(projectId, "STAGE")).thenReturn(List.of(right));

        var result = service.compare(projectId, "DEV", "STAGE");

        assertTrue(result.differences().isEmpty());
    }
}
