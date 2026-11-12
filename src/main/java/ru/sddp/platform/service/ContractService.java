package ru.sddp.platform.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sddp.platform.api.ContractUpdateRequest;
import ru.sddp.platform.domain.Contract;
import ru.sddp.platform.repository.ContractRepository;
import java.util.UUID;

@Service
public class ContractService {
    private final ContractRepository contracts; private final BackwardCompatibilityEngine compatibility; private final ContractSchemaValidator validator;
    public ContractService(ContractRepository contracts, BackwardCompatibilityEngine compatibility, ContractSchemaValidator validator) { this.contracts=contracts; this.compatibility=compatibility; this.validator=validator; }
    @Transactional
    public Contract update(UUID projectId, UUID contractId, ContractUpdateRequest request) {
        Contract contract = contracts.findByIdAndProjectId(contractId, projectId).orElseThrow(() -> new ResourceNotFoundException("Contract not found"));
        validator.validate(request.requestSchema()); if (request.responseSchema() != null) validator.validate(request.responseSchema());
        compatibility.assertCompatible(contract.getResponseSchema(), request.responseSchema(), contract.getRequestSchema(), request.requestSchema());
        if (contracts.existsByProjectIdAndNameAndVersion(projectId, contract.getName(), request.version())) throw new IllegalArgumentException("Contract version already exists");
        Contract next = new Contract(contract.getProject(), contract.getName(), request.version(), request.requestSchema(), request.responseSchema());
        return contracts.save(next);
    }
}
