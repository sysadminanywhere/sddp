package ru.sddp.platform.api;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

public final class CodegenModels {
    private CodegenModels() { }
    public record GenerateRequest(UUID projectId, @NotBlank String environment, @NotBlank String packageName, @NotBlank String clientName, PluginDescriptor plugin) { }
    public record GenerateResponse(String language, String fileName, String source, java.util.Map<String,String> files) { }
    public record PluginDescriptor(String language, String deliveryType, String executableTarget) { }
    public record PluginExecutionResult(int exitCode, String stdout, String stderr, java.util.Map<String,String> files) { }
}
