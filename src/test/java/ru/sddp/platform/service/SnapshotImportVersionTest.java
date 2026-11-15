package ru.sddp.platform.service;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.sddp.platform.domain.Project;
import ru.sddp.platform.repository.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SnapshotImportVersionTest {
    @Test void importsCurrentShapeAsVersionOneAndRejectsVersionTwo() throws Exception {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var projects = mock(ProjectRepository.class);
        when(projects.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var service = new SnapshotImportService(projects, mock(ServiceNodeRepository.class),
            mock(ServiceApiRepository.class), mock(TopologyConnectionRepository.class),
            mock(ApiSchemaValidator.class), mock(ConnectionValidator.class), mock(ProjectEnvironmentRepository.class));
        var snapshot = mapper.readTree("""
            {"formatVersion":1,"projectName":"test","environments":[{"code":"DEV","name":"Dev","position":1,"isProtected":false}],"services":[],"apis":[],"connections":[]}
            """);
        assertEquals("test", service.importSnapshot(snapshot).name());
        var versionTwo = ((tools.jackson.databind.node.ObjectNode) snapshot).put("formatVersion", 2);
        assertThrows(IllegalArgumentException.class, () -> service.importSnapshot(versionTwo));
        verify(projects, times(1)).save(any(Project.class));
    }
}
