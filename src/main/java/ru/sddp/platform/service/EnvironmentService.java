package ru.sddp.platform.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sddp.platform.api.ApiModels;
import ru.sddp.platform.api.TopologyRequests;
import ru.sddp.platform.domain.Project;
import ru.sddp.platform.domain.ProjectEnvironment;
import ru.sddp.platform.domain.TopologyConnection;
import ru.sddp.platform.repository.ProjectEnvironmentRepository;
import ru.sddp.platform.repository.ProjectRepository;
import ru.sddp.platform.repository.TopologyConnectionRepository;

import java.util.*;
import java.util.regex.Pattern;

@Service
public class EnvironmentService {
    public static final String CONFIRM_HEADER = "X-SDDP-Confirm-Protected";
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_-]{0,49}$");
    private static final List<DefaultEnvironment> DEFAULTS = List.of(
        new DefaultEnvironment("DEV", "Development", 10, false),
        new DefaultEnvironment("STAGE", "Staging", 20, false),
        new DefaultEnvironment("PROD", "Production", 30, true)
    );

    private final ProjectRepository projects;
    private final ProjectEnvironmentRepository environments;
    private final TopologyConnectionRepository connections;

    public EnvironmentService(ProjectRepository projects, ProjectEnvironmentRepository environments,
                              TopologyConnectionRepository connections) {
        this.projects = projects;
        this.environments = environments;
        this.connections = connections;
    }

    /** Creates the default DEV/STAGE/PROD set for a project that does not have environments yet. */
    @Transactional
    public void ensureDefaults(Project project) {
        if (environments.countByProjectId(project.getId()) > 0) return;
        for (DefaultEnvironment value : DEFAULTS) {
            environments.save(new ProjectEnvironment(project, value.code(), value.name(), value.position(), value.protectedFlag()));
        }
    }

    /** Creates a missing environment while importing a snapshot. */
    @Transactional
    public ProjectEnvironment ensure(Project project, String code) {
        String normalized = normalizeCode(code);
        return environments.findByProjectIdAndCode(project.getId(), normalized).orElseGet(() ->
            environments.save(new ProjectEnvironment(project, normalized, normalized, nextPosition(project.getId()), false)));
    }

    @Transactional(readOnly = true)
    public List<ApiModels.EnvironmentResponse> list(UUID projectId) {
        requireProject(projectId);
        Map<String, Long> counts = new HashMap<>();
        for (TopologyConnection connection : connections.findByProjectId(projectId)) {
            counts.merge(connection.getEnvironment(), 1L, Long::sum);
        }
        return environments.findByProjectIdOrderByPositionAscCodeAsc(projectId).stream()
            .map(environment -> response(environment, counts.getOrDefault(environment.getCode(), 0L)))
            .toList();
    }

    @Transactional
    public ApiModels.EnvironmentResponse create(UUID projectId, TopologyRequests.CreateEnvironmentRequest request) {
        Project project = requireProject(projectId);
        String code = normalizeCode(request.code());
        if (environments.existsByProjectIdAndCode(projectId, code)) {
            throw new IllegalArgumentException("Environment already exists: " + code);
        }
        String name = request.name() == null || request.name().isBlank() ? code : request.name().trim();
        int position = request.position() == null ? nextPosition(projectId) : request.position();
        ProjectEnvironment environment = environments.save(new ProjectEnvironment(project, code, name, position,
            Boolean.TRUE.equals(request.isProtected())));
        return response(environment, 0L);
    }

    @Transactional
    public ApiModels.EnvironmentResponse update(UUID projectId, UUID environmentId,
                                                TopologyRequests.UpdateEnvironmentRequest request) {
        ProjectEnvironment environment = requireEnvironment(projectId, environmentId);
        String name = request.name() == null || request.name().isBlank() ? environment.getCode() : request.name().trim();
        int position = request.position() == null ? environment.getPosition() : request.position();
        boolean isProtected = request.isProtected() == null ? environment.isProtected() : request.isProtected();
        environment.update(name, position, isProtected);
        return response(environment, connections.findByProjectIdAndEnvironment(projectId, environment.getCode()).size());
    }

    @Transactional
    public void delete(UUID projectId, UUID environmentId, boolean confirmProtected) {
        ProjectEnvironment environment = requireEnvironment(projectId, environmentId);
        if (environment.isProtected() && !confirmProtected) {
            throw protectedError(environment.getCode());
        }
        long usage = connections.findByProjectIdAndEnvironment(projectId, environment.getCode()).size();
        if (usage > 0) {
            throw new ResourceInUseException("Environment " + environment.getCode() + " has " + usage
                + " connection(s); delete or move them first");
        }
        if (environments.countByProjectId(projectId) <= 1) {
            throw new IllegalArgumentException("A project must keep at least one environment");
        }
        environments.delete(environment);
    }

    @Transactional(readOnly = true)
    public String defaultCode(UUID projectId) {
        requireProject(projectId);
        return environments.findByProjectIdOrderByPositionAscCodeAsc(projectId).stream()
            .findFirst().map(ProjectEnvironment::getCode)
            .orElseThrow(() -> new IllegalArgumentException("Project has no environments: " + projectId));
    }

    @Transactional(readOnly = true)
    public ProjectEnvironment require(UUID projectId, String code) {
        String value = code == null ? "" : code.trim();
        Optional<ProjectEnvironment> exact = environments.findByProjectIdAndCode(projectId, value);
        if (exact.isPresent()) return exact.get();
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!normalized.equals(value)) {
            Optional<ProjectEnvironment> upper = environments.findByProjectIdAndCode(projectId, normalized);
            if (upper.isPresent()) return upper.get();
        }
        throw new IllegalArgumentException("Unknown environment: " + code);
    }

    /** Returns the canonical environment after checking the protected-environment confirmation. */
    @Transactional(readOnly = true)
    public ProjectEnvironment requireWritable(UUID projectId, String code, boolean confirmProtected) {
        ProjectEnvironment environment = require(projectId, code);
        if (environment.isProtected() && !confirmProtected) {
            throw protectedError(environment.getCode());
        }
        return environment;
    }

    public static String normalizeCode(String value) {
        String code = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!CODE_PATTERN.matcher(code).matches()) {
            throw new IllegalArgumentException("Invalid environment code: " + value
                + ". Use letters, digits, underscore or dash, starting with a letter");
        }
        return code;
    }

    private ProtectedEnvironmentException protectedError(String code) {
        return new ProtectedEnvironmentException("Environment " + code + " is protected. Repeat the request with header "
            + CONFIRM_HEADER + ": true");
    }

    private Project requireProject(UUID projectId) {
        return projects.findById(projectId).orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
    }

    private ProjectEnvironment requireEnvironment(UUID projectId, UUID environmentId) {
        return environments.findByIdAndProjectId(environmentId, projectId)
            .orElseThrow(() -> new ResourceNotFoundException("Environment not found: " + environmentId));
    }

    private int nextPosition(UUID projectId) {
        return environments.findByProjectIdOrderByPositionAscCodeAsc(projectId).stream()
            .mapToInt(ProjectEnvironment::getPosition).max().orElse(0) + 10;
    }

    private ApiModels.EnvironmentResponse response(ProjectEnvironment environment, long connectionCount) {
        return new ApiModels.EnvironmentResponse(environment.getId(), environment.getProject().getId(),
            environment.getCode(), environment.getName(), environment.getPosition(),
            environment.isProtected(), connectionCount);
    }

    private record DefaultEnvironment(String code, String name, int position, boolean protectedFlag) { }
}
