package ru.sddp.platform.service;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.sddp.platform.domain.Project;
import ru.sddp.platform.domain.ProjectEnvironment;
import ru.sddp.platform.domain.ServiceApi;
import ru.sddp.platform.domain.ServiceNode;
import ru.sddp.platform.domain.ServiceType;
import ru.sddp.platform.domain.TopologyConnection;
import ru.sddp.platform.repository.ServiceApiRepository;
import ru.sddp.platform.repository.TopologyConnectionRepository;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TopologyWriteServiceTest {
    private final ObjectMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();

    private TopologyWriteService service(TopologyConnectionRepository connections) {
        var environments = mock(EnvironmentService.class);
        when(environments.requireWritable(any(), anyString(), anyBoolean())).thenAnswer(invocation -> {
            String code = invocation.getArgument(1);
            return new ProjectEnvironment(new Project("environment-test"), code, code, 0, false);
        });
        return new TopologyWriteService(mock(ProjectService.class), mock(ServiceCatalogService.class),
            mock(ServiceApiRepository.class), connections, new ConnectionValidator(), environments);
    }

    @Test void updatesMappingOfExistingConnection() throws Exception {
        UUID projectId = UUID.randomUUID(), connectionId = UUID.randomUUID();
        var project = new Project("test");
        var core = new ServiceNode(project, "core", ServiceType.CORE, null);
        var legacy = new ServiceNode(project, "legacy", ServiceType.EXTERNAL, null);
        var targetApi = new ServiceApi(legacy, "createPayment", "3", null, ru.sddp.platform.domain.ApiPattern.REQUEST_RESPONSE,
            mapper.readTree("{\"type\":\"object\",\"properties\":{}}"), mapper.readTree("{\"type\":\"object\",\"properties\":{}}"));
        var connection = new TopologyConnection(project, "DEV", core, legacy, null, targetApi,
            "REST", mapper.readTree("{\"host\":\"legacy\",\"port\":8080,\"target_endpoint\":\"/orders\"}"), null);
        var repository = mock(TopologyConnectionRepository.class);
        when(repository.findByIdAndProjectId(connectionId, projectId)).thenReturn(Optional.of(connection));

        var mapping = mapper.readTree("{\"source_api\":\"core/createOrder v1\",\"target_api\":\"legacy/createPayment v3\",\"transformations\":[{\"type\":\"DIRECT_COPY\",\"from\":\"$.id\",\"to\":\"$.order_id\"}]}");
        var updated = service(repository).updateConnectionMapping(projectId, connectionId, mapping, false);

        assertEquals(connection.getId(), updated.id());
        assertEquals(targetApi.getId(), updated.targetApiId());
        assertEquals(mapping, connection.getMappingRules());
    }

    @Test void rejectsMappingForUnknownConnection() {
        var repository = mock(TopologyConnectionRepository.class);
        when(repository.findByIdAndProjectId(any(), any())).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service(repository).updateConnectionMapping(UUID.randomUUID(), UUID.randomUUID(), null, false));
    }
}
