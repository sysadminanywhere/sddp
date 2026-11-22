package ru.sddp.platform.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public final class AsyncApiModels {
    private AsyncApiModels() { }

    public record ImportRequest(@NotNull JsonNode document, @NotEmpty @Size(max = 200) List<@NotBlank String> operations) { }
    public record ImportResult(List<UUID> created, List<UUID> existing) { }
}
