package ru.sddp.platform.service;

import org.junit.jupiter.api.Test;
import ru.sddp.platform.api.TopologyRequests;
import ru.sddp.platform.domain.Project;
import ru.sddp.platform.domain.ProjectEnvironment;
import ru.sddp.platform.domain.TopologyConnection;
import ru.sddp.platform.repository.ProjectEnvironmentRepository;
import ru.sddp.platform.repository.ProjectRepository;
import ru.sddp.platform.repository.TopologyConnectionRepository;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EnvironmentServiceTest {
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final ProjectEnvironmentRepository environments = mock(ProjectEnvironmentRepository.class);
    private final TopologyConnectionRepository connections = mock(TopologyConnectionRepository.class);
    private final EnvironmentService service = new EnvironmentService(projects, environments, connections);

    @Test void seedsDefaultEnvironmentsOnce() {
        service.ensureDefaults(new Project("test"));

        verify(environments, times(3)).save(any(ProjectEnvironment.class));
    }

    @Test void protectedEnvironmentRequiresExplicitConfirmation() {
        UUID projectId = UUID.randomUUID();
        var environment = new ProjectEnvironment(new Project("test"), "PROD", "Production", 30, true);
        when(environments.findByProjectIdAndCode(projectId, "PROD")).thenReturn(Optional.of(environment));

        assertThrows(ProtectedEnvironmentException.class, () -> service.requireWritable(projectId, "PROD", false));
        assertSame(environment, service.requireWritable(projectId, "PROD", true));
    }

    @Test void createNormalizesCodeAndRejectsDuplicates() {
        UUID projectId = UUID.randomUUID();
        when(projects.findById(projectId)).thenReturn(Optional.of(new Project("test")));
        when(environments.existsByProjectIdAndCode(projectId, "QA")).thenReturn(true);

        var request = new TopologyRequests.CreateEnvironmentRequest("qa", "Quality assurance", null, null);
        var error = assertThrows(IllegalArgumentException.class, () -> service.create(projectId, request));

        assertTrue(error.getMessage().contains("already exists"));
    }

    @Test void rejectsInvalidEnvironmentCodes() {
        assertThrows(IllegalArgumentException.class, () -> EnvironmentService.normalizeCode("1-dev"));
        assertEquals("QA_2", EnvironmentService.normalizeCode("qa_2"));
    }

    @Test void renamesEnvironmentAndItsConnections() {
        UUID projectId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        Project project = new Project("test");
        ProjectEnvironment environment = new ProjectEnvironment(project, "QA", "Quality assurance", 40, false);
        TopologyConnection connection = mock(TopologyConnection.class);
        when(environments.findByIdAndProjectId(environmentId, projectId)).thenReturn(Optional.of(environment));
        when(environments.existsByProjectIdAndCode(projectId, "UAT")).thenReturn(false);
        when(connections.findByProjectIdAndEnvironment(projectId, "QA")).thenReturn(List.of(connection));
        when(connections.findByProjectIdAndEnvironment(projectId, "UAT")).thenReturn(List.of(connection));

        service.update(projectId, environmentId,
            new TopologyRequests.UpdateEnvironmentRequest("uat", "User acceptance", 50, true));

        assertEquals("UAT", environment.getCode());
        assertEquals("User acceptance", environment.getName());
        verify(connection).renameEnvironment("UAT");
    }
}
