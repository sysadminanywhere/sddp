package ru.sddp.platform.repository;
import ru.sddp.platform.domain.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface ProjectRepository extends JpaRepository<Project, UUID> { boolean existsByName(String name); }
