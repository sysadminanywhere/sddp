package ru.sddp.platform.service;

import ru.sddp.platform.api.ApiModels;
import ru.sddp.platform.domain.Project;
import ru.sddp.platform.repository.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class ProjectService {
    private final ProjectRepository projects;
    public ProjectService(ProjectRepository projects) { this.projects = projects; }
    @Transactional
    public ApiModels.ProjectResponse create(ApiModels.CreateProjectRequest request) {
        Project project = projects.save(new Project(request.name()));
        return new ApiModels.ProjectResponse(project.getId(), project.getName(), project.getCreatedAt());
    }
    @Transactional(readOnly = true)
    public Project require(UUID id) { return projects.findById(id).orElseThrow(() -> new ResourceNotFoundException("Project not found: " + id)); }
}
