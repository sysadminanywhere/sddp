package ru.sddp.platform.web;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import ru.sddp.platform.api.ApiModels;
import ru.sddp.platform.service.ProjectService;
import ru.sddp.platform.service.ServiceCatalogService;
import java.util.List;
import java.util.UUID;

@RestController @RequestMapping("/api/v1/projects")
public class ProjectController {
    private final ProjectService projects;
    private final ServiceCatalogService catalog;
    private final ru.sddp.platform.service.SnapshotImportService snapshots;

    public ProjectController(ProjectService projects, ServiceCatalogService catalog, ru.sddp.platform.service.SnapshotImportService snapshots) {
        this.projects = projects;
        this.catalog = catalog;
        this.snapshots = snapshots;
    }

    @GetMapping
    public List<ApiModels.ProjectResponse> list() { return projects.list(); }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ApiModels.ProjectResponse create(@Valid @RequestBody ApiModels.CreateProjectRequest request) { return projects.create(request); }

    @GetMapping("/{projectId}")
    public ApiModels.ProjectResponse get(@PathVariable UUID projectId) { return projects.get(projectId); }

    @DeleteMapping("/{projectId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID projectId) { projects.delete(projectId); }

    @GetMapping("/{projectId}/apis")
    public List<ApiModels.ServiceApiResponse> apis(@PathVariable UUID projectId) { return catalog.listProjectApis(projectId); }

    @PostMapping("/import") @ResponseStatus(HttpStatus.CREATED)
    public ApiModels.ProjectResponse importSnapshot(@RequestBody JsonNode snapshot) { return snapshots.importSnapshot(snapshot); }
}
