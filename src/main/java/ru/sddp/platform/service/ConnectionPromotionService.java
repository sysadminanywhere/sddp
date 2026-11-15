package ru.sddp.platform.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sddp.platform.api.ApiModels;
import ru.sddp.platform.api.TopologyRequests;
import ru.sddp.platform.domain.ProjectEnvironment;
import ru.sddp.platform.domain.ServiceApi;
import ru.sddp.platform.domain.TopologyConnection;
import ru.sddp.platform.repository.TopologyConnectionRepository;

import java.util.*;
import java.util.stream.Collectors;

/** Copies or promotes connections from one project environment to another. */
@Service
public class ConnectionPromotionService {
    private static final Set<String> MODES = Set.of("MERGE", "REPLACE");

    private final EnvironmentService environments;
    private final TopologyConnectionRepository connections;
    private final ConnectionValidator validator;

    public ConnectionPromotionService(EnvironmentService environments, TopologyConnectionRepository connections,
                                      ConnectionValidator validator) {
        this.environments = environments;
        this.connections = connections;
        this.validator = validator;
    }

    @Transactional
    public ApiModels.ConnectionCopyResult copy(UUID projectId, TopologyRequests.CopyConnectionsRequest request,
                                               boolean confirmProtected) {
        ProjectEnvironment sourceEnvironment = environments.require(projectId, request.sourceEnvironment());
        ProjectEnvironment targetEnvironment = environments.requireWritable(projectId, request.targetEnvironment(), confirmProtected);
        if (sourceEnvironment.getCode().equals(targetEnvironment.getCode())) {
            throw new IllegalArgumentException("Source and target environments must differ");
        }
        String mode = normalizeMode(request.mode());
        List<TopologyConnection> source = connections.findByProjectIdAndEnvironment(projectId, sourceEnvironment.getCode());
        List<TopologyConnection> target = connections.findByProjectIdAndEnvironment(projectId, targetEnvironment.getCode());

        int removed = 0;
        if ("REPLACE".equals(mode)) {
            removed = target.size();
            connections.deleteAll(target);
            target = List.of();
        }

        Set<String> existing = target.stream().map(ConnectionPromotionService::identity)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        int copied = 0;
        int skipped = 0;
        for (TopologyConnection connection : source) {
            String key = identity(connection);
            if (existing.contains(key)) {
                skipped++;
                continue;
            }
            validator.validate(targetEnvironment.getCode(), connection.getTransportType(),
                connection.getConnectionSettings(), connection.getMappingRules());
            connections.save(new TopologyConnection(connection.getProject(), targetEnvironment.getCode(),
                connection.getSource(), connection.getTarget(), connection.getSourceApi(), connection.getTargetApi(),
                connection.getTransportType(), connection.getConnectionSettings(), connection.getMappingRules()));
            existing.add(key);
            copied++;
        }
        return new ApiModels.ConnectionCopyResult(sourceEnvironment.getCode(), targetEnvironment.getCode(), mode,
            copied, skipped, removed);
    }

    private String normalizeMode(String mode) {
        String value = mode == null || mode.isBlank() ? "MERGE" : mode.trim().toUpperCase(Locale.ROOT);
        if (!MODES.contains(value)) throw new IllegalArgumentException("Unsupported copy mode: " + mode);
        return value;
    }

    static String identity(TopologyConnection connection) {
        return connection.getSource().getId() + "|" + connection.getTarget().getId() + "|"
            + apiId(connection.getSourceApi()) + "|" + apiId(connection.getTargetApi()) + "|"
            + connection.getTransportType();
    }

    private static String apiId(ServiceApi api) {
        return api == null ? "-" : api.getId().toString();
    }
}
