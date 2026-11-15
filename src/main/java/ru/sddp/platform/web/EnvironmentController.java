package ru.sddp.platform.web;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import ru.sddp.platform.api.ApiModels;
import ru.sddp.platform.api.TopologyRequests;
import ru.sddp.platform.service.ConnectionPromotionService;
import ru.sddp.platform.service.EnvironmentComparisonService;
import ru.sddp.platform.service.EnvironmentService;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class EnvironmentController {
    private final EnvironmentService environments;
    private final ConnectionPromotionService promotions;
    private final EnvironmentComparisonService comparisons;

    public EnvironmentController(EnvironmentService environments, ConnectionPromotionService promotions,
                                 EnvironmentComparisonService comparisons) {
        this.environments = environments;
        this.promotions = promotions;
        this.comparisons = comparisons;
    }

    @GetMapping("/environments")
    public List<ApiModels.EnvironmentResponse> list(@PathVariable UUID projectId) {
        return environments.list(projectId);
    }

    @PostMapping("/environments") @ResponseStatus(HttpStatus.CREATED)
    public ApiModels.EnvironmentResponse create(@PathVariable UUID projectId,
                                               @Valid @RequestBody TopologyRequests.CreateEnvironmentRequest request) {
        return environments.create(projectId, request);
    }

    @PutMapping("/environments/{environmentId}")
    public ApiModels.EnvironmentResponse update(@PathVariable UUID projectId, @PathVariable UUID environmentId,
                                                @Valid @RequestBody TopologyRequests.UpdateEnvironmentRequest request) {
        return environments.update(projectId, environmentId, request);
    }

    @DeleteMapping("/environments/{environmentId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID projectId, @PathVariable UUID environmentId,
                       @RequestHeader(value = EnvironmentService.CONFIRM_HEADER, defaultValue = "false")
                       boolean confirmProtected) {
        environments.delete(projectId, environmentId, confirmProtected);
    }

    @GetMapping("/environments/compare")
    public ApiModels.EnvironmentComparison compare(@PathVariable UUID projectId,
                                                   @RequestParam String sourceEnvironment,
                                                   @RequestParam String targetEnvironment) {
        return comparisons.compare(projectId, sourceEnvironment, targetEnvironment);
    }

    @PostMapping("/connections/copy") @ResponseStatus(HttpStatus.CREATED)
    public ApiModels.ConnectionCopyResult copy(@PathVariable UUID projectId,
                                               @Valid @RequestBody TopologyRequests.CopyConnectionsRequest request,
                                               @RequestHeader(value = EnvironmentService.CONFIRM_HEADER, defaultValue = "false")
                                               boolean confirmProtected) {
        return promotions.copy(projectId, request, confirmProtected);
    }
}
