package ru.sddp.platform.web;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.JsonNode;
import ru.sddp.platform.api.ApiModels;
import ru.sddp.platform.service.ProjectService;
import java.util.UUID;

@RestController @RequestMapping("/api/v1/projects")
public class ProjectController {
    private final ProjectService projects; private final ru.sddp.platform.service.SnapshotImportService snapshots;
    public ProjectController(ProjectService projects, ru.sddp.platform.service.SnapshotImportService snapshots) { this.projects = projects; this.snapshots = snapshots; }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ApiModels.ProjectResponse create(@Valid @RequestBody ApiModels.CreateProjectRequest request) { return projects.create(request); }
    @PostMapping("/import") @ResponseStatus(HttpStatus.CREATED)
    public ApiModels.ProjectResponse importSnapshot(@RequestBody JsonNode snapshot) { return snapshots.importSnapshot(snapshot); }
}
