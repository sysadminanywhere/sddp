package ru.sddp.platform.web;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import ru.sddp.platform.api.CodegenModels;

@RestController
@RequestMapping("/api/v1/codegen")
public class CodegenController {
    private final ru.sddp.platform.service.CodeGenerationService codegen;
    private final ru.sddp.platform.repository.CodegenPluginRepository plugins;
    public CodegenController(ru.sddp.platform.service.CodeGenerationService codegen, ru.sddp.platform.repository.CodegenPluginRepository plugins) { this.codegen = codegen; this.plugins = plugins; }
    @GetMapping("/plugins")
    public java.util.List<ru.sddp.platform.domain.CodegenPlugin> plugins() { return plugins.findByActiveTrue(); }
    @PostMapping("/generate")
    public CodegenModels.GenerateResponse generate(@Valid @RequestBody CodegenModels.GenerateRequest request) { return codegen.generate(request); }
}
