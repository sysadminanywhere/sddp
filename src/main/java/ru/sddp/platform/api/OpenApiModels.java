package ru.sddp.platform.api;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

public final class OpenApiModels {
    public static final int MAX_SELECTION = 200;
    private OpenApiModels() { }

    public record PreviewRequest(JsonNode document, String yaml, @Size(max = MAX_SELECTION) List<@NotBlank String> operations) { }
    public record ImportRequest(JsonNode document, String yaml, @NotEmpty @Size(max = MAX_SELECTION) List<@NotBlank String> operations) {
        public ImportRequest(JsonNode document, List<String> operations) { this(document, null, operations); }
    }
    public record Preview(String version, List<Operation> operations, List<String> warnings, boolean selectionRequired) { }
    public record Operation(String key, String name, String method, String path, boolean exists) { }
    public record ImportResult(List<UUID> created, List<UUID> existing) { }
}
