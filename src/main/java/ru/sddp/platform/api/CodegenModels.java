package ru.sddp.platform.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

public final class CodegenModels {
    private CodegenModels() { }
    public record GenerateRequest(UUID projectId, @NotBlank String environment, @NotBlank String packageName, @NotBlank String clientName, PluginDescriptor plugin) { }
    public record GenerateResponse(String language, String fileName, String source, java.util.Map<String,String> files) { }
    public record ArtifactRequest(@NotNull UUID projectId, @NotBlank String environment,
                                  @NotBlank String packageName, @NotBlank String clientName,
                                  @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._+-]{0,63}") String version) { }
    public record PluginDescriptor(String language, String deliveryType, String executableTarget) { }
    public record PluginExecutionResult(int exitCode, String stdout, String stderr, java.util.Map<String,String> files) { }
}
