package ru.sddp.platform.web;

import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.sddp.platform.api.CodegenModels;
import ru.sddp.platform.service.JavaArtifactService;

@RestController
@RequestMapping("/api/v1/codegen")
public class CodegenController {
    private final ru.sddp.platform.service.CodeGenerationService codegen;
    private final JavaArtifactService artifacts;
    private final ru.sddp.platform.repository.CodegenPluginRepository plugins;
    public CodegenController(ru.sddp.platform.service.CodeGenerationService codegen,
                             ru.sddp.platform.repository.CodegenPluginRepository plugins, JavaArtifactService artifacts) {
        this.codegen = codegen;
        this.plugins = plugins;
        this.artifacts = artifacts;
    }
    @GetMapping("/plugins")
    public java.util.List<ru.sddp.platform.domain.CodegenPlugin> plugins() { return plugins.findByActiveTrue(); }
    @PostMapping("/generate")
    public CodegenModels.GenerateResponse generate(@Valid @RequestBody CodegenModels.GenerateRequest request) { return codegen.generate(request); }
    @PostMapping(value = "/artifact", produces = "application/zip")
    public ResponseEntity<byte[]> artifact(@Valid @RequestBody CodegenModels.ArtifactRequest request) {
        byte[] archive = artifacts.build(request);
        String filename = request.clientName() + "-" + request.version() + ".zip";
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
            .body(archive);
    }
}
