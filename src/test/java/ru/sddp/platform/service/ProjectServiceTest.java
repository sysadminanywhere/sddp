package ru.sddp.platform.service;

import org.junit.jupiter.api.Test;
import ru.sddp.platform.domain.Project;
import ru.sddp.platform.repository.ProjectRepository;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProjectServiceTest {
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final ProjectService service = new ProjectService(projects, mock(EnvironmentService.class));

    @Test void deleteRemovesProject() {
        UUID id = UUID.randomUUID();
        var project = new Project("billing");
        when(projects.findById(id)).thenReturn(Optional.of(project));

        service.delete(id);

        verify(projects).delete(project);
    }

    @Test void deleteRejectsUnknownProject() {
        UUID id = UUID.randomUUID();
        when(projects.findById(id)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.delete(id));
        verify(projects, never()).delete(any());
    }
}
