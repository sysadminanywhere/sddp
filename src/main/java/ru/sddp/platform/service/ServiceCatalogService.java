package ru.sddp.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sddp.platform.api.ApiModels;
import ru.sddp.platform.api.TopologyRequests;
import ru.sddp.platform.domain.ServiceApi;
import ru.sddp.platform.domain.ServiceNode;
import ru.sddp.platform.repository.ServiceApiRepository;
import ru.sddp.platform.repository.ServiceNodeRepository;
import ru.sddp.platform.repository.TopologyConnectionRepository;
import java.util.List;
import java.util.UUID;

@Service
public class ServiceCatalogService {
    private final ProjectService projects;
    private final ServiceNodeRepository services;
    private final ServiceApiRepository apis;
    private final TopologyConnectionRepository connections;
    private final BackwardCompatibilityEngine compatibility;
    private final ApiSchemaValidator schemaValidator;

    public ServiceCatalogService(ProjectService projects, ServiceNodeRepository services, ServiceApiRepository apis,
                                 TopologyConnectionRepository connections, BackwardCompatibilityEngine compatibility,
                                 ApiSchemaValidator schemaValidator) {
        this.projects = projects;
        this.services = services;
        this.apis = apis;
        this.connections = connections;
        this.compatibility = compatibility;
        this.schemaValidator = schemaValidator;
    }

    @Transactional(readOnly = true)
    public List<ApiModels.ServiceResponse> listServices(UUID projectId) {
        projects.require(projectId);
        return services.findByProjectIdOrderByNameAsc(projectId).stream().map(this::serviceResponse).toList();
    }

    @Transactional(readOnly = true)
    public ApiModels.ServiceResponse getService(UUID projectId, UUID serviceId) {
        return serviceResponse(requireService(projectId, serviceId));
    }

    @Transactional
    public ApiModels.ServiceResponse createService(UUID projectId, TopologyRequests.CreateServiceRequest request) {
        if (services.existsByProjectIdAndName(projectId, request.name())) {
            throw new IllegalArgumentException("Service name already exists in this project: " + request.name());
        }
        ServiceNode service = services.save(new ServiceNode(projects.require(projectId), request.name().trim(),
            request.serviceType(), normalizeDescription(request.description())));
        return serviceResponse(service);
    }

    @Transactional
    public ApiModels.ServiceResponse updateService(UUID projectId, UUID serviceId, TopologyRequests.UpdateServiceRequest request) {
        ServiceNode service = requireService(projectId, serviceId);
        if (services.existsByProjectIdAndNameAndIdNot(projectId, request.name(), serviceId)) {
            throw new IllegalArgumentException("Service name already exists in this project: " + request.name());
        }
        service.update(request.name().trim(), request.serviceType(), normalizeDescription(request.description()));
        if (request.positionX() != null && request.positionY() != null) {
            service.setPosition(request.positionX(), request.positionY());
        }
        return serviceResponse(service);
    }

    @Transactional(readOnly = true)
    public List<ApiModels.ServiceApiResponse> listApis(UUID projectId, UUID serviceId) {
        requireService(projectId, serviceId);
        return apis.findByServiceIdOrderByNameAscVersionAsc(serviceId).stream().map(this::apiResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<ApiModels.ServiceApiResponse> listProjectApis(UUID projectId) {
        projects.require(projectId);
        return apis.findByServiceProjectIdOrderByServiceNameAscNameAscVersionAsc(projectId).stream().map(this::apiResponse).toList();
    }

    @Transactional
    public ApiModels.ServiceApiResponse createApi(UUID projectId, UUID serviceId, TopologyRequests.CreateApiRequest request) {
        ServiceNode service = requireService(projectId, serviceId);
        validateSchemas(request.requestSchema(), request.responseSchema());
        if (apis.existsByServiceIdAndNameAndVersion(serviceId, request.name(), request.version())) {
            throw new IllegalArgumentException("API version already exists: " + request.name() + " " + request.version());
        }
        ServiceApi api = apis.save(new ServiceApi(service, request.name().trim(), request.version().trim(),
            normalizeDescription(request.description()), request.pattern(), request.requestSchema(), normalizeSchema(request.responseSchema())));
        return apiResponse(api);
    }

    @Transactional(readOnly = true)
    public ApiModels.ServiceApiResponse getApi(UUID projectId, UUID serviceId, UUID apiId) {
        requireService(projectId, serviceId);
        return apiResponse(apis.findByIdAndServiceId(apiId, serviceId)
            .orElseThrow(() -> new ResourceNotFoundException("API not found: " + apiId)));
    }

    @Transactional
    public void deleteService(UUID projectId, UUID serviceId) {
        ServiceNode service = requireService(projectId, serviceId);
        long usage = connections.countBySourceIdOrTargetId(serviceId, serviceId);
        if (usage > 0) {
            throw new ResourceInUseException("Service is used by " + usage + " connection(s): " + service.getName());
        }
        apis.deleteByServiceId(serviceId);
        services.delete(service);
    }

    @Transactional
    public void deleteApi(UUID projectId, UUID serviceId, UUID apiId) {
        requireService(projectId, serviceId);
        ServiceApi api = apis.findByIdAndServiceId(apiId, serviceId)
            .orElseThrow(() -> new ResourceNotFoundException("API not found: " + apiId));
        long usage = connections.countBySourceApiIdOrTargetApiId(apiId, apiId);
        if (usage > 0) {
            throw new ResourceInUseException("API is used by " + usage + " connection(s): "
                + api.getName() + " " + api.getVersion());
        }
        apis.delete(api);
    }

    @Transactional
    public ApiModels.ServiceApiResponse updateApi(UUID projectId, UUID serviceId, UUID apiId,
                                                  TopologyRequests.UpdateApiRequest request) {
        requireService(projectId, serviceId);
        ServiceApi api = apis.findByIdAndServiceId(apiId, serviceId)
            .orElseThrow(() -> new ResourceNotFoundException("API not found: " + apiId));
        if (apis.existsByServiceIdAndNameAndVersionAndIdNot(serviceId, request.name(), request.version(), apiId)) {
            throw new IllegalArgumentException("API version already exists: " + request.name() + " " + request.version());
        }
        validateSchemas(request.requestSchema(), request.responseSchema());
        api.update(request.name().trim(), request.version().trim(), normalizeDescription(request.description()),
            request.pattern(), request.requestSchema(), normalizeSchema(request.responseSchema()));
        return apiResponse(api);
    }

    @Transactional
    public ApiModels.ServiceApiResponse createApiVersion(UUID projectId, UUID serviceId, UUID apiId,
                                                         TopologyRequests.CreateApiVersionRequest request) {
        ServiceNode service = requireService(projectId, serviceId);
        ServiceApi current = apis.findByIdAndServiceId(apiId, serviceId)
            .orElseThrow(() -> new ResourceNotFoundException("API not found: " + apiId));
        if (request.pattern() != current.getPattern()) {
            throw new IllegalArgumentException("API pattern cannot change between versions");
        }
        validateSchemas(request.requestSchema(), request.responseSchema());
        compatibility.assertCompatible(current.getResponseSchema(), normalizeSchema(request.responseSchema()),
            current.getRequestSchema(), request.requestSchema());
        if (apis.existsByServiceIdAndNameAndVersion(serviceId, current.getName(), request.version())) {
            throw new IllegalArgumentException("API version already exists: " + current.getName() + " " + request.version());
        }
        ServiceApi next = apis.save(new ServiceApi(service, current.getName(), request.version().trim(),
            normalizeDescription(request.description() == null ? current.getDescription() : request.description()),
            request.pattern(), request.requestSchema(), normalizeSchema(request.responseSchema())));
        return apiResponse(next);
    }

    @Transactional(readOnly = true)
    public ServiceNode requireService(UUID projectId, UUID serviceId) {
        projects.require(projectId);
        return services.findByIdAndProjectId(serviceId, projectId)
            .orElseThrow(() -> new ResourceNotFoundException("Service not found: " + serviceId));
    }

    private void validateSchemas(JsonNode request, JsonNode response) {
        schemaValidator.validate(request);
        if (response != null && !response.isNull()) schemaValidator.validate(response);
    }

    private ApiModels.ServiceResponse serviceResponse(ServiceNode service) {
        return new ApiModels.ServiceResponse(service.getId(), service.getName(), service.getServiceType().name(),
            service.getDescription(), service.getCreatedAt(), apis.countByServiceId(service.getId()),
            service.getPositionX(), service.getPositionY());
    }

    private ApiModels.ServiceApiResponse apiResponse(ServiceApi api) {
        return new ApiModels.ServiceApiResponse(api.getId(), api.getService().getId(), api.getName(), api.getVersion(),
            api.getDescription(), api.getPattern().name(), api.getRequestSchema(), api.getResponseSchema(), api.getCreatedAt());
    }

    private JsonNode normalizeSchema(JsonNode schema) {
        return schema == null || schema.isNull() ? null : schema;
    }

    private String normalizeDescription(String description) {
        return description == null || description.isBlank() ? null : description.trim();
    }
}
