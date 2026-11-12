package ru.sddp.platform.web;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import ru.sddp.platform.api.CodegenModels;

@RestController
@RequestMapping("/api/v1/codegen")
public class CodegenController {
    private final ru.sddp.platform.service.CodeGenerationService codegen;
    public CodegenController(ru.sddp.platform.service.CodeGenerationService codegen) { this.codegen = codegen; }
    @PostMapping("/generate")
    public CodegenModels.GenerateResponse generate(@Valid @RequestBody CodegenModels.GenerateRequest request) { return codegen.generate(request); }
}
