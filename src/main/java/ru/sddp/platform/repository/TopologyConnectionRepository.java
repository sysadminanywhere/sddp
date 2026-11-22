package ru.sddp.platform.repository;
import jakarta.persistence.LockModeType;
import ru.sddp.platform.domain.TopologyConnection;
import org.springframework.data.jpa.repository.*;
import java.util.*;
public interface TopologyConnectionRepository extends JpaRepository<TopologyConnection, UUID> {
    List<TopologyConnection> findByProjectIdAndEnvironment(UUID projectId, String environment);
    List<TopologyConnection> findByProjectId(UUID projectId);
    Optional<TopologyConnection> findByIdAndProjectId(UUID id, UUID projectId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from TopologyConnection c where c.id = :id and c.project.id = :projectId")
    Optional<TopologyConnection> lockByIdAndProjectId(UUID id, UUID projectId);
    long countBySourceApiIdOrTargetApiId(UUID sourceApiId, UUID targetApiId);
    long countBySourceIdOrTargetId(UUID sourceId, UUID targetId);
}
