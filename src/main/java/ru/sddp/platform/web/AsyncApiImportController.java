package ru.sddp.platform.web;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.sddp.platform.api.AsyncApiModels;
import ru.sddp.platform.service.AsyncApiImportService;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/services/{serviceId}/apis/asyncapi")
public class AsyncApiImportController {
    private final AsyncApiImportService importer;

    public AsyncApiImportController(AsyncApiImportService importer) { this.importer = importer; }

    @PostMapping("/import")
    public AsyncApiModels.ImportResult importSelected(@PathVariable UUID projectId, @PathVariable UUID serviceId,
                                                      @Valid @RequestBody AsyncApiModels.ImportRequest request) {
        return importer.importSelected(projectId, serviceId, request);
    }
}
