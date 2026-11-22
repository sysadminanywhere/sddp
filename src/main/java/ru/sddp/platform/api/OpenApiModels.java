package ru.sddp.platform.api;

import com.fasterxml.jackson.annotation.JsonInclude;
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
    public record ImportRequest(JsonNode document, String yaml, @NotEmpty @Size(max = MAX_SELECTION) List<@NotBlank String> operations,
                                @Size(max = 255) String version) {
        public ImportRequest(JsonNode document, String yaml, List<String> operations) { this(document, yaml, operations, null); }
        public ImportRequest(JsonNode document, List<String> operations) { this(document, null, operations, null); }
    }
    public record Preview(String version, List<Operation> operations, List<String> warnings, boolean selectionRequired) { }
    public record Operation(String key, String name, String method, String path, boolean exists,
                            @JsonInclude(JsonInclude.Include.NON_NULL) Comparison comparison) { }
    public record Comparison(String baselineVersion, List<Change> changes, boolean truncated) { }
    public record Change(String path, Kind kind) {
        public enum Kind { ADDED, REMOVED, CHANGED }
    }
    public record ImportResult(List<UUID> created, List<UUID> existing) { }
}
