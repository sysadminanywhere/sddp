package ru.sddp.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.util.UUID;

public final class TopologyRequests {
    private TopologyRequests() { }
    public record CreateServiceRequest(@NotBlank String name, boolean external) { }
    public record CreateContractRequest(@NotBlank String name, @NotBlank String version, @NotNull JsonNode requestSchema, JsonNode responseSchema) { }
    public record CreateConnectionRequest(@NotBlank String environment, @NotNull UUID sourceServiceId, @NotNull UUID targetServiceId, UUID contractId, @NotBlank String transportType, @NotNull JsonNode connectionSettings, JsonNode mappingRules) { }
}
