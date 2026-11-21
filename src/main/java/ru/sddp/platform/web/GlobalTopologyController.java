package ru.sddp.platform.web;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ru.sddp.platform.api.ApiModels;
import ru.sddp.platform.api.GlobalConnectionRequest;
import ru.sddp.platform.service.EnvironmentService;
import ru.sddp.platform.service.TopologyWriteService;
@RestController @RequestMapping("/api/v1/topology")
public class GlobalTopologyController {
    private final TopologyWriteService topology;
    public GlobalTopologyController(TopologyWriteService topology) { this.topology=topology; }
    @PostMapping("/connections") @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@projectAccessPolicy.isAllowedForProject(authentication, #p0.projectId().toString())")
    public ApiModels.Edge connection(@Valid @RequestBody GlobalConnectionRequest request,
                                     @RequestHeader(value = EnvironmentService.CONFIRM_HEADER, defaultValue = "false")
                                     boolean confirmProtected) {
        return topology.addConnection(request.projectId(), request.connection(), confirmProtected);
    }
}
