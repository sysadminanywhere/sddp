package ru.sddp.platform.service;

import ru.sddp.platform.api.ApiModels;
import ru.sddp.platform.domain.Project;
import ru.sddp.platform.repository.ProjectRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

@Service
public class ProjectService {
    private final ProjectRepository projects;
    private final EnvironmentService environments;
    public ProjectService(ProjectRepository projects, EnvironmentService environments) {
        this.projects = projects;
        this.environments = environments;
    }

    @Transactional
    public ApiModels.ProjectResponse create(ApiModels.CreateProjectRequest request) {
        Project project = projects.save(new Project(request.name()));
        environments.ensureDefaults(project);
        return response(project);
    }

    @Transactional(readOnly = true)
    public List<ApiModels.ProjectResponse> list() {
        return projects.findAll(Sort.by(Sort.Direction.ASC, "name")).stream().map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public ApiModels.ProjectResponse get(UUID id) { return response(require(id)); }

    @Transactional
    public void delete(UUID id) {
        projects.delete(require(id));
    }

    @Transactional(readOnly = true)
    public Project require(UUID id) { return projects.findById(id).orElseThrow(() -> new ResourceNotFoundException("Project not found: " + id)); }

    private ApiModels.ProjectResponse response(Project project) {
        return new ApiModels.ProjectResponse(project.getId(), project.getName(), project.getCreatedAt());
    }
}
