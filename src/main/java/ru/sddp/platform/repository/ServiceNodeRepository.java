package ru.sddp.platform.repository;

import ru.sddp.platform.domain.ServiceNode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceNodeRepository extends JpaRepository<ServiceNode, UUID> {
    List<ServiceNode> findByProjectIdOrderByNameAsc(UUID projectId);
    Optional<ServiceNode> findByIdAndProjectId(UUID id, UUID projectId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ServiceNode s where s.id = :id and s.project.id = :projectId")
    Optional<ServiceNode> lockByIdAndProjectId(@Param("id") UUID id, @Param("projectId") UUID projectId);
    boolean existsByProjectIdAndName(UUID projectId, String name);
    boolean existsByProjectIdAndNameAndIdNot(UUID projectId, String name, UUID id);
}
