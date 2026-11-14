package ru.sddp.platform.repository;

import ru.sddp.platform.domain.ServiceNode;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceNodeRepository extends JpaRepository<ServiceNode, UUID> {
    List<ServiceNode> findByProjectIdOrderByNameAsc(UUID projectId);
    Optional<ServiceNode> findByIdAndProjectId(UUID id, UUID projectId);
    boolean existsByProjectIdAndName(UUID projectId, String name);
    boolean existsByProjectIdAndNameAndIdNot(UUID projectId, String name, UUID id);
}
