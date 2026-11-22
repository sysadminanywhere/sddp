package ru.sddp.platform.web;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import ru.sddp.platform.api.ApiModels;
import ru.sddp.platform.api.TopologyRequests;
import ru.sddp.platform.service.EnvironmentService;
import ru.sddp.platform.service.TopologyWriteService;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class TopologyWriteController {
    private final TopologyWriteService topology;

    public TopologyWriteController(TopologyWriteService topology) { this.topology = topology; }

    @PostMapping("/connections") @ResponseStatus(HttpStatus.CREATED)
    public ApiModels.Edge connection(@PathVariable UUID projectId, @Valid @RequestBody TopologyRequests.CreateConnectionRequest request,
                                     @RequestHeader(value = EnvironmentService.CONFIRM_HEADER, defaultValue = "false")
                                     boolean confirmProtected) {
        return topology.addConnection(projectId, request, confirmProtected);
    }

    @PutMapping("/connections/{connectionId}")
    public ApiModels.Edge updateConnection(@PathVariable UUID projectId, @PathVariable UUID connectionId,
                                           @Valid @RequestBody TopologyRequests.CreateConnectionRequest request,
                                           @RequestHeader(value = EnvironmentService.CONFIRM_HEADER, defaultValue = "false")
                                           boolean confirmProtected) {
        return topology.updateConnection(projectId, connectionId, request, confirmProtected);
    }

    @PutMapping("/connections/{connectionId}/mapping")
    public ApiModels.Edge updateConnectionMapping(@PathVariable UUID projectId, @PathVariable UUID connectionId,
                                                  @RequestBody TopologyRequests.UpdateConnectionMappingRequest request,
                                                  @RequestHeader(value = EnvironmentService.CONFIRM_HEADER, defaultValue = "false")
                                                  boolean confirmProtected) {
        return topology.updateConnectionMapping(projectId, connectionId, request.mappingRules(), confirmProtected);
    }

    @PutMapping("/connections/{connectionId}/target-version")
    public ApiModels.Edge rebindTargetVersion(@PathVariable UUID projectId, @PathVariable UUID connectionId,
                                              @Valid @RequestBody TopologyRequests.RebindTargetVersionRequest request,
                                              @RequestHeader(value = EnvironmentService.CONFIRM_HEADER, defaultValue = "false")
                                              boolean confirmProtected) {
        return topology.rebindTargetVersion(projectId, connectionId, request, confirmProtected);
    }

    @DeleteMapping("/connections/{connectionId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteConnection(@PathVariable UUID projectId, @PathVariable UUID connectionId,
                                 @RequestHeader(value = EnvironmentService.CONFIRM_HEADER, defaultValue = "false")
                                 boolean confirmProtected) {
        topology.deleteConnection(projectId, connectionId, confirmProtected);
    }
}
