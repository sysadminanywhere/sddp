package ru.sddp.platform.web;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.sddp.platform.api.OpenApiModels;
import ru.sddp.platform.service.OpenApiImportService;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/services/{serviceId}/apis/openapi")
public class OpenApiImportController {
    private final OpenApiImportService importer;

    public OpenApiImportController(OpenApiImportService importer) {
        this.importer = importer;
    }

    @PostMapping("/preview")
    public OpenApiModels.Preview preview(@PathVariable UUID projectId, @PathVariable UUID serviceId,
                                         @Valid @RequestBody OpenApiModels.PreviewRequest request) {
        return importer.preview(projectId, serviceId, request.document());
    }

    @PostMapping("/import")
    public OpenApiModels.ImportResult importSelected(@PathVariable UUID projectId, @PathVariable UUID serviceId,
                                                      @Valid @RequestBody OpenApiModels.ImportRequest request) {
        return importer.importSelected(projectId, serviceId, request);
    }
}
