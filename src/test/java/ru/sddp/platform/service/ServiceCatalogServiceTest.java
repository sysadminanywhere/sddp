package ru.sddp.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.sddp.platform.api.TopologyRequests;
import ru.sddp.platform.domain.*;
import ru.sddp.platform.repository.ServiceApiRepository;
import ru.sddp.platform.repository.ServiceNodeRepository;
import ru.sddp.platform.repository.TopologyConnectionRepository;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ServiceCatalogServiceTest {
    @Test void newApiVersionPreservesOriginalOperation() throws Exception {
        var mapper = new ObjectMapper();
        var oldSchema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}");
        var nextSchema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"note\":{\"type\":\"string\"}}}");
        var project = new Project("test");
        var service = new ServiceNode(project, "orders", ServiceType.CORE, null);
        var original = new ServiceApi(service, "createOrder", "1", "Create an order", ApiPattern.REQUEST_RESPONSE, oldSchema, oldSchema);
        UUID projectId = UUID.randomUUID(), serviceId = UUID.randomUUID(), apiId = UUID.randomUUID();
        var services = mock(ServiceNodeRepository.class);
        var apis = mock(ServiceApiRepository.class);
        var projects = mock(ProjectService.class);
        when(projects.require(projectId)).thenReturn(project);
        when(services.findByIdAndProjectId(serviceId, projectId)).thenReturn(Optional.of(service));
        when(apis.findByIdAndServiceId(apiId, serviceId)).thenReturn(Optional.of(original));
        when(apis.save(any(ServiceApi.class))).thenAnswer(call -> call.getArgument(0));
        var catalog = new ServiceCatalogService(projects, services, apis, mock(TopologyConnectionRepository.class),
            new BackwardCompatibilityEngine(), new ApiSchemaValidator());

        var updated = catalog.createApiVersion(projectId, serviceId, apiId,
            new TopologyRequests.CreateApiVersionRequest("2", "Create an order v2", ApiPattern.REQUEST_RESPONSE, nextSchema, nextSchema));

        assertEquals("1", original.getVersion());
        assertEquals(oldSchema, original.getRequestSchema());
        assertEquals("2", updated.version());
        assertEquals(nextSchema, updated.requestSchema());
        assertEquals("createOrder", updated.name());
    }

    @Test void updateApiEditsExistingVersionInPlace() throws Exception {
        var mapper = new ObjectMapper();
        var oldSchema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}");
        var nextSchema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"amount\":{\"type\":\"integer\"}}}");
        var project = new Project("test");
        var service = new ServiceNode(project, "payments", ServiceType.CORE, null);
        var original = new ServiceApi(service, "payment.charge", "1", "Old description", ApiPattern.REQUEST_RESPONSE, oldSchema, oldSchema);
        UUID projectId = UUID.randomUUID(), serviceId = UUID.randomUUID(), apiId = UUID.randomUUID();
        var services = mock(ServiceNodeRepository.class);
        var apis = mock(ServiceApiRepository.class);
        var projects = mock(ProjectService.class);
        when(projects.require(projectId)).thenReturn(project);
        when(services.findByIdAndProjectId(serviceId, projectId)).thenReturn(Optional.of(service));
        when(apis.findByIdAndServiceId(apiId, serviceId)).thenReturn(Optional.of(original));
        var catalog = new ServiceCatalogService(projects, services, apis, mock(TopologyConnectionRepository.class),
            new BackwardCompatibilityEngine(), new ApiSchemaValidator());

        var updated = catalog.updateApi(projectId, serviceId, apiId,
            new TopologyRequests.UpdateApiRequest("payment.charge", "1", "Charge a payment", ApiPattern.REQUEST_RESPONSE, nextSchema, nextSchema));

        assertEquals("1", updated.version());
        assertEquals("Charge a payment", updated.description());
        assertEquals(nextSchema, updated.requestSchema());
        assertEquals(nextSchema, updated.responseSchema());
        verify(apis, never()).save(any(ServiceApi.class));
    }

    @Test void deleteApiRemovesUnusedOperation() throws Exception {
        var mapper = new ObjectMapper();
        var schema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}");
        var project = new Project("test");
        var service = new ServiceNode(project, "payments", ServiceType.CORE, null);
        var api = new ServiceApi(service, "payment.charge", "1", null, ApiPattern.REQUEST_RESPONSE, schema, schema);
        UUID projectId = UUID.randomUUID(), serviceId = UUID.randomUUID(), apiId = UUID.randomUUID();
        var services = mock(ServiceNodeRepository.class);
        var apis = mock(ServiceApiRepository.class);
        var projects = mock(ProjectService.class);
        var connections = mock(TopologyConnectionRepository.class);
        when(projects.require(projectId)).thenReturn(project);
        when(services.findByIdAndProjectId(serviceId, projectId)).thenReturn(Optional.of(service));
        when(apis.findByIdAndServiceId(apiId, serviceId)).thenReturn(Optional.of(api));
        when(connections.countBySourceApiIdOrTargetApiId(apiId, apiId)).thenReturn(0L);
        var catalog = new ServiceCatalogService(projects, services, apis, connections,
            new BackwardCompatibilityEngine(), new ApiSchemaValidator());

        catalog.deleteApi(projectId, serviceId, apiId);

        verify(apis).delete(api);
    }

    @Test void deleteApiRejectsOperationUsedByConnections() throws Exception {
        var mapper = new ObjectMapper();
        var schema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}");
        var project = new Project("test");
        var service = new ServiceNode(project, "payments", ServiceType.CORE, null);
        var api = new ServiceApi(service, "payment.charge", "1", null, ApiPattern.REQUEST_RESPONSE, schema, schema);
        UUID projectId = UUID.randomUUID(), serviceId = UUID.randomUUID(), apiId = UUID.randomUUID();
        var services = mock(ServiceNodeRepository.class);
        var apis = mock(ServiceApiRepository.class);
        var projects = mock(ProjectService.class);
        var connections = mock(TopologyConnectionRepository.class);
        when(projects.require(projectId)).thenReturn(project);
        when(services.findByIdAndProjectId(serviceId, projectId)).thenReturn(Optional.of(service));
        when(apis.findByIdAndServiceId(apiId, serviceId)).thenReturn(Optional.of(api));
        when(connections.countBySourceApiIdOrTargetApiId(apiId, apiId)).thenReturn(2L);
        var catalog = new ServiceCatalogService(projects, services, apis, connections,
            new BackwardCompatibilityEngine(), new ApiSchemaValidator());

        var error = assertThrows(ResourceInUseException.class, () -> catalog.deleteApi(projectId, serviceId, apiId));

        assertTrue(error.getMessage().contains("used by 2 connection"));
        verify(apis, never()).delete(any(ServiceApi.class));
    }

    @Test void deleteServiceRemovesServiceWithItsApis() {
        var project = new Project("test");
        var service = new ServiceNode(project, "payments", ServiceType.CORE, null);
        UUID projectId = UUID.randomUUID(), serviceId = UUID.randomUUID();
        var services = mock(ServiceNodeRepository.class);
        var apis = mock(ServiceApiRepository.class);
        var projects = mock(ProjectService.class);
        var connections = mock(TopologyConnectionRepository.class);
        when(projects.require(projectId)).thenReturn(project);
        when(services.findByIdAndProjectId(serviceId, projectId)).thenReturn(Optional.of(service));
        when(connections.countBySourceIdOrTargetId(serviceId, serviceId)).thenReturn(0L);
        var catalog = new ServiceCatalogService(projects, services, apis, connections,
            new BackwardCompatibilityEngine(), new ApiSchemaValidator());

        catalog.deleteService(projectId, serviceId);

        verify(apis).deleteByServiceId(serviceId);
        verify(services).delete(service);
    }

    @Test void deleteServiceRejectsServiceUsedByConnections() {
        var project = new Project("test");
        var service = new ServiceNode(project, "payments", ServiceType.CORE, null);
        UUID projectId = UUID.randomUUID(), serviceId = UUID.randomUUID();
        var services = mock(ServiceNodeRepository.class);
        var apis = mock(ServiceApiRepository.class);
        var projects = mock(ProjectService.class);
        var connections = mock(TopologyConnectionRepository.class);
        when(projects.require(projectId)).thenReturn(project);
        when(services.findByIdAndProjectId(serviceId, projectId)).thenReturn(Optional.of(service));
        when(connections.countBySourceIdOrTargetId(serviceId, serviceId)).thenReturn(3L);
        var catalog = new ServiceCatalogService(projects, services, apis, connections,
            new BackwardCompatibilityEngine(), new ApiSchemaValidator());

        var error = assertThrows(ResourceInUseException.class, () -> catalog.deleteService(projectId, serviceId));

        assertTrue(error.getMessage().contains("used by 3 connection"));
        verify(apis, never()).deleteByServiceId(any());
        verify(services, never()).delete(any(ServiceNode.class));
    }
}
