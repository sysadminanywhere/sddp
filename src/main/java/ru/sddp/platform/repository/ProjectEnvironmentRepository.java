package ru.sddp.platform.repository;

import ru.sddp.platform.domain.ProjectEnvironment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectEnvironmentRepository extends JpaRepository<ProjectEnvironment, UUID> {
    List<ProjectEnvironment> findByProjectIdOrderByPositionAscCodeAsc(UUID projectId);
    Optional<ProjectEnvironment> findByProjectIdAndCode(UUID projectId, String code);
    Optional<ProjectEnvironment> findByIdAndProjectId(UUID id, UUID projectId);
    boolean existsByProjectIdAndCode(UUID projectId, String code);
    long countByProjectId(UUID projectId);
}
