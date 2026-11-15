package ru.sddp.platform.web;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import ru.sddp.platform.api.ApiModels;
import ru.sddp.platform.api.TopologyRequests;
import ru.sddp.platform.service.ServiceCatalogService;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/services")
public class ServiceController {
    private final ServiceCatalogService catalog;

    public ServiceController(ServiceCatalogService catalog) { this.catalog = catalog; }

    @GetMapping
    public List<ApiModels.ServiceResponse> list(@PathVariable UUID projectId) { return catalog.listServices(projectId); }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ApiModels.ServiceResponse create(@PathVariable UUID projectId, @Valid @RequestBody TopologyRequests.CreateServiceRequest request) {
        return catalog.createService(projectId, request);
    }

    @GetMapping("/{serviceId}")
    public ApiModels.ServiceResponse get(@PathVariable UUID projectId, @PathVariable UUID serviceId) {
        return catalog.getService(projectId, serviceId);
    }

    @PutMapping("/{serviceId}")
    public ApiModels.ServiceResponse update(@PathVariable UUID projectId, @PathVariable UUID serviceId,
                                            @Valid @RequestBody TopologyRequests.UpdateServiceRequest request) {
        return catalog.updateService(projectId, serviceId, request);
    }

    @DeleteMapping("/{serviceId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID projectId, @PathVariable UUID serviceId) {
        catalog.deleteService(projectId, serviceId);
    }

    @GetMapping("/{serviceId}/apis")
    public List<ApiModels.ServiceApiResponse> listApis(@PathVariable UUID projectId, @PathVariable UUID serviceId) {
        return catalog.listApis(projectId, serviceId);
    }

    @PostMapping("/{serviceId}/apis") @ResponseStatus(HttpStatus.CREATED)
    public ApiModels.ServiceApiResponse createApi(@PathVariable UUID projectId, @PathVariable UUID serviceId,
                                                  @Valid @RequestBody TopologyRequests.CreateApiRequest request) {
        return catalog.createApi(projectId, serviceId, request);
    }

    @GetMapping("/{serviceId}/apis/{apiId}")
    public ApiModels.ServiceApiResponse getApi(@PathVariable UUID projectId, @PathVariable UUID serviceId,
                                               @PathVariable UUID apiId) {
        return catalog.getApi(projectId, serviceId, apiId);
    }

    @PutMapping("/{serviceId}/apis/{apiId}")
    public ApiModels.ServiceApiResponse updateApi(@PathVariable UUID projectId, @PathVariable UUID serviceId,
                                                  @PathVariable UUID apiId,
                                                  @Valid @RequestBody TopologyRequests.UpdateApiRequest request) {
        return catalog.updateApi(projectId, serviceId, apiId, request);
    }

    @DeleteMapping("/{serviceId}/apis/{apiId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteApi(@PathVariable UUID projectId, @PathVariable UUID serviceId, @PathVariable UUID apiId) {
        catalog.deleteApi(projectId, serviceId, apiId);
    }

    @PostMapping("/{serviceId}/apis/{apiId}/versions") @ResponseStatus(HttpStatus.CREATED)
    public ApiModels.ServiceApiResponse createApiVersion(@PathVariable UUID projectId, @PathVariable UUID serviceId,
                                                         @PathVariable UUID apiId,
                                                         @Valid @RequestBody TopologyRequests.CreateApiVersionRequest request) {
        return catalog.createApiVersion(projectId, serviceId, apiId, request);
    }
}
