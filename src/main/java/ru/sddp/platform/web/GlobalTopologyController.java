package ru.sddp.platform.web;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import ru.sddp.platform.api.GlobalConnectionRequest;
import ru.sddp.platform.domain.TopologyConnection;
import ru.sddp.platform.service.TopologyWriteService;
@RestController @RequestMapping("/api/v1/topology")
public class GlobalTopologyController {
    private final TopologyWriteService topology;
    public GlobalTopologyController(TopologyWriteService topology) { this.topology=topology; }
    @PostMapping("/connections") @ResponseStatus(HttpStatus.CREATED)
    public TopologyConnection connection(@Valid @RequestBody GlobalConnectionRequest request) { return topology.addConnection(request.projectId(), request.connection()); }
}
