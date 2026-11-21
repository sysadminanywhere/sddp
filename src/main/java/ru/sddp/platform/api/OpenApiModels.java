package ru.sddp.platform.api;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

public final class OpenApiModels {
    private OpenApiModels() { }

    public record PreviewRequest(@NotNull JsonNode document) { }
    public record ImportRequest(@NotNull JsonNode document, @NotEmpty @Size(max = 200) List<@NotNull String> operations) { }
    public record Preview(String version, List<Operation> operations, List<String> warnings) { }
    public record Operation(String key, String name, String method, String path, boolean exists) { }
    public record ImportResult(List<UUID> created, List<UUID> existing) { }
}
