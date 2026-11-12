package ru.sddp.platform.api;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
public record ContractUpdateRequest(@NotBlank String version, @NotNull JsonNode requestSchema, JsonNode responseSchema) { }
