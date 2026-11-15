package ru.sddp.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.sddp.platform.api.TopologyRequests;
import ru.sddp.platform.domain.Project;
import ru.sddp.platform.domain.ProjectEnvironment;
import ru.sddp.platform.domain.ServiceApi;
import ru.sddp.platform.domain.ServiceNode;
import ru.sddp.platform.domain.TopologyConnection;
import ru.sddp.platform.repository.TopologyConnectionRepository;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ConnectionPromotionServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final EnvironmentService environments = mock(EnvironmentService.class);
    private final TopologyConnectionRepository connections = mock(TopologyConnectionRepository.class);
    private final ConnectionValidator validator = new ConnectionValidator();
    private final ConnectionPromotionService service =
        new ConnectionPromotionService(environments, connections, validator);

    private final UUID projectId = UUID.randomUUID();
    private final Project project = new Project("promotion-test");

    private ProjectEnvironment environment(String code, boolean isProtected) {
        return new ProjectEnvironment(project, code, code, 0, isProtected);
    }

    private TopologyConnection connection(String environment, ServiceNode source, ServiceNode target,
                                          ServiceApi sourceApi, ServiceApi targetApi, String host) throws Exception {
        var settings = mapper.readTree("{\"host\":\"" + host + "\",\"port\":8080,\"target_endpoint\":\"/orders\"}");
        var mapping = mapper.readTree("{\"target_api\":\"payment.charge\",\"transformations\":[{\"type\":\"DIRECT_COPY\",\"from\":\"$.a\",\"to\":\"$.b\"}]}");
        return new TopologyConnection(project, environment, source, target, sourceApi, targetApi, "REST", settings, mapping);
    }

    private ServiceNode node(UUID id, String name) {
        ServiceNode node = mock(ServiceNode.class);
        when(node.getId()).thenReturn(id);
        when(node.getName()).thenReturn(name);
        return node;
    }

    private ServiceApi api(UUID id, String name) {
        ServiceApi api = mock(ServiceApi.class);
        when(api.getId()).thenReturn(id);
        when(api.getName()).thenReturn(name);
        return api;
    }

    @Test void mergeCopiesMissingConnectionsAndSkipsExistingOnes() throws Exception {
        var sourceEnv = environment("DEV", false);
        var targetEnv = environment("STAGE", false);
        when(environments.require(projectId, "DEV")).thenReturn(sourceEnv);
        when(environments.requireWritable(projectId, "STAGE", false)).thenReturn(targetEnv);

        var sourceNode = node(UUID.randomUUID(), "order-core");
        var targetNode = node(UUID.randomUUID(), "payment-core");
        var sourceApi = api(UUID.randomUUID(), "order.created");
        var targetApi = api(UUID.randomUUID(), "payment.charge");
        var existing = connection("STAGE", sourceNode, targetNode, sourceApi, targetApi, "payment");
        var missingTargetApi = api(UUID.randomUUID(), "payment.refund");
        var missing = connection("DEV", sourceNode, targetNode, sourceApi, missingTargetApi, "payment");

        when(connections.findByProjectIdAndEnvironment(projectId, "DEV")).thenReturn(List.of(existing, missing));
        when(connections.findByProjectIdAndEnvironment(projectId, "STAGE")).thenReturn(List.of(existing));

        var request = new TopologyRequests.CopyConnectionsRequest("DEV", "STAGE", "MERGE");
        var result = service.copy(projectId, request, false);

        assertEquals(1, result.copied());
        assertEquals(1, result.skipped());
        assertEquals(0, result.removed());
        verify(connections, times(1)).save(any(TopologyConnection.class));
        verify(connections, never()).deleteAll(any());
    }

    @Test void replaceDeletesTargetConnectionsBeforeCopying() throws Exception {
        var sourceEnv = environment("DEV", false);
        var targetEnv = environment("PROD", true);
        when(environments.require(projectId, "DEV")).thenReturn(sourceEnv);
        when(environments.requireWritable(projectId, "PROD", true)).thenReturn(targetEnv);

        var sourceNode = node(UUID.randomUUID(), "order-core");
        var targetNode = node(UUID.randomUUID(), "payment-core");
        var sourceApi = api(UUID.randomUUID(), "order.created");
        var targetApi = api(UUID.randomUUID(), "payment.charge");
        var sourceConnection = connection("DEV", sourceNode, targetNode, sourceApi, targetApi, "payment");
        var stale = connection("PROD", sourceNode, targetNode, sourceApi, targetApi, "old-payment");

        when(connections.findByProjectIdAndEnvironment(projectId, "DEV")).thenReturn(List.of(sourceConnection));
        when(connections.findByProjectIdAndEnvironment(projectId, "PROD")).thenReturn(List.of(stale));

        var request = new TopologyRequests.CopyConnectionsRequest("DEV", "PROD", "REPLACE");
        var result = service.copy(projectId, request, true);

        assertEquals(1, result.copied());
        assertEquals(0, result.skipped());
        assertEquals(1, result.removed());
        verify(connections).deleteAll(List.of(stale));
        verify(connections, times(1)).save(any(TopologyConnection.class));
    }

    @Test void rejectsWritesToProtectedTargetWithoutConfirmation() {
        when(environments.require(projectId, "DEV")).thenReturn(environment("DEV", false));
        when(environments.requireWritable(projectId, "PROD", false))
            .thenThrow(new ProtectedEnvironmentException("protected"));

        var request = new TopologyRequests.CopyConnectionsRequest("DEV", "PROD", "MERGE");
        assertThrows(ProtectedEnvironmentException.class, () -> service.copy(projectId, request, false));
        verify(connections, never()).save(any());
    }
}
