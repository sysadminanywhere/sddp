package ru.sddp.platform.api;

import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import ru.sddp.platform.domain.ApiPattern;
import ru.sddp.platform.domain.ServiceType;
import java.util.UUID;

public final class TopologyRequests {
    private TopologyRequests() { }

    public record CreateServiceRequest(@NotBlank String name, @NotNull ServiceType serviceType, String description) { }
    public record UpdateServiceRequest(@NotBlank String name, @NotNull ServiceType serviceType, String description, Integer positionX, Integer positionY) { }

    public record CreateApiRequest(@NotBlank String name, @NotBlank String version, String description,
                                   @NotNull ApiPattern pattern, @NotNull JsonNode requestSchema, JsonNode responseSchema) { }
    public record UpdateApiRequest(@NotBlank String name, @NotBlank String version, String description,
                                   @NotNull ApiPattern pattern, @NotNull JsonNode requestSchema, JsonNode responseSchema) { }
    public record CreateApiVersionRequest(@NotBlank String version, String description,
                                          @NotNull ApiPattern pattern, @NotNull JsonNode requestSchema, JsonNode responseSchema) { }

    public record CreateConnectionRequest(@NotBlank String environment, @NotNull UUID sourceServiceId, @NotNull UUID targetServiceId,
                                          UUID sourceApiId, @NotNull UUID targetApiId, @NotBlank String transportType,
                                          @NotNull JsonNode connectionSettings, JsonNode mappingRules) { }
    public record UpdateConnectionMappingRequest(JsonNode mappingRules) { }

    public record CreateEnvironmentRequest(@NotBlank String code, @NotBlank String name, Integer position, Boolean isProtected) { }
    public record UpdateEnvironmentRequest(@NotBlank String name, Integer position, Boolean isProtected) { }
    public record CopyConnectionsRequest(@NotBlank String sourceEnvironment, @NotBlank String targetEnvironment, String mode) { }
}
