package ru.sddp.platform.web;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import ru.sddp.platform.api.TopologyRequests;
import ru.sddp.platform.domain.*;
import ru.sddp.platform.service.TopologyWriteService;
import ru.sddp.platform.service.ContractService;
import ru.sddp.platform.api.ContractUpdateRequest;
import java.util.UUID;

@RestController @RequestMapping("/api/v1/projects/{projectId}")
public class TopologyWriteController {
    private final TopologyWriteService topology; private final ContractService contracts; private final ru.sddp.platform.repository.ContractRepository contractRepository; private final ru.sddp.platform.service.ProjectService projects;
    public TopologyWriteController(TopologyWriteService topology, ContractService contracts, ru.sddp.platform.repository.ContractRepository contractRepository, ru.sddp.platform.service.ProjectService projects) { this.topology = topology; this.contracts = contracts; this.contractRepository = contractRepository; this.projects = projects; }
    @PostMapping("/services") @ResponseStatus(HttpStatus.CREATED)
    public ServiceNode service(@PathVariable UUID projectId, @Valid @RequestBody TopologyRequests.CreateServiceRequest request) { return topology.addService(projectId, request); }
    @PostMapping("/contracts") @ResponseStatus(HttpStatus.CREATED)
    public Contract contract(@PathVariable UUID projectId, @Valid @RequestBody TopologyRequests.CreateContractRequest request) { return topology.addContract(projectId, request); }
    @GetMapping("/contracts")
    public java.util.List<Contract> listContracts(@PathVariable UUID projectId) { projects.require(projectId); return contractRepository.findByProjectId(projectId); }
    @PutMapping("/contracts/{contractId}")
    public Contract updateContract(@PathVariable UUID projectId, @PathVariable UUID contractId, @Valid @RequestBody ContractUpdateRequest request) { return contracts.update(projectId, contractId, request); }
    @PostMapping("/connections") @ResponseStatus(HttpStatus.CREATED)
    public TopologyConnection connection(@PathVariable UUID projectId, @Valid @RequestBody TopologyRequests.CreateConnectionRequest request) { return topology.addConnection(projectId, request); }
}
