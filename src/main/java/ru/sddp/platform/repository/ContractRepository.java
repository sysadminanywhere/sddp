package ru.sddp.platform.repository;
import ru.sddp.platform.domain.Contract;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ContractRepository extends JpaRepository<Contract, UUID> { Optional<Contract> findByIdAndProjectId(UUID id, UUID projectId); List<Contract> findByProjectId(UUID projectId); boolean existsByProjectIdAndNameAndVersion(UUID projectId, String name, String version); }
