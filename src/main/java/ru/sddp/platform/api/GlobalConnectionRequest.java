package ru.sddp.platform.api;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import jakarta.validation.Valid;
import java.util.UUID;
public record GlobalConnectionRequest(@NotNull UUID projectId, @Valid TopologyRequests.CreateConnectionRequest connection) { }
