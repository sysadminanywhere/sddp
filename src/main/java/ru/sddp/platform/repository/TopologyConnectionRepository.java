package ru.sddp.platform.repository;
import ru.sddp.platform.domain.TopologyConnection;
import org.springframework.data.jpa.repository.*;
import java.util.*;
public interface TopologyConnectionRepository extends JpaRepository<TopologyConnection, UUID> {
    List<TopologyConnection> findByProjectIdAndEnvironment(UUID projectId, String environment);
    List<TopologyConnection> findByProjectId(UUID projectId);
    Optional<TopologyConnection> findByIdAndProjectId(UUID id, UUID projectId);
    long countBySourceApiIdOrTargetApiId(UUID sourceApiId, UUID targetApiId);
    long countBySourceIdOrTargetId(UUID sourceId, UUID targetId);
}
