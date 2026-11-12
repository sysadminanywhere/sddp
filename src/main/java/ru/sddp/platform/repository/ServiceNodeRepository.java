package ru.sddp.platform.repository;
import ru.sddp.platform.domain.ServiceNode;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ServiceNodeRepository extends JpaRepository<ServiceNode, UUID> { List<ServiceNode> findByProjectId(UUID projectId); Optional<ServiceNode> findByIdAndProjectId(UUID id, UUID projectId); }
