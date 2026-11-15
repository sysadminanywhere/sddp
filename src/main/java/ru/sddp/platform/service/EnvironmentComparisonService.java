package ru.sddp.platform.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sddp.platform.api.ApiModels;
import ru.sddp.platform.domain.ProjectEnvironment;
import ru.sddp.platform.domain.ServiceApi;
import ru.sddp.platform.domain.TopologyConnection;
import ru.sddp.platform.repository.TopologyConnectionRepository;

import java.util.*;
import java.util.stream.Collectors;

/** Compares the logical connections of two project environments. */
@Service
public class EnvironmentComparisonService {
    private final EnvironmentService environments;
    private final TopologyConnectionRepository connections;

    public EnvironmentComparisonService(EnvironmentService environments, TopologyConnectionRepository connections) {
        this.environments = environments;
        this.connections = connections;
    }

    @Transactional(readOnly = true)
    public ApiModels.EnvironmentComparison compare(UUID projectId, String source, String target) {
        ProjectEnvironment sourceEnvironment = environments.require(projectId, source);
        ProjectEnvironment targetEnvironment = environments.require(projectId, target);
        List<TopologyConnection> sourceConnections =
            connections.findByProjectIdAndEnvironment(projectId, sourceEnvironment.getCode());
        List<TopologyConnection> targetConnections =
            connections.findByProjectIdAndEnvironment(projectId, targetEnvironment.getCode());
        Map<String, TopologyConnection> targetByKey = index(targetConnections);
        Map<String, TopologyConnection> sourceByKey = index(sourceConnections);

        List<ApiModels.EnvironmentDifference> differences = new ArrayList<>();
        for (TopologyConnection connection : sourceConnections) {
            TopologyConnection other = targetByKey.get(ConnectionPromotionService.identity(connection));
            if (other == null) {
                differences.add(new ApiModels.EnvironmentDifference("MISSING_IN_TARGET", connection.getId(), null,
                    label(connection), null, List.of()));
                continue;
            }
            List<String> changed = changedFields(connection, other);
            if (!changed.isEmpty()) {
                differences.add(new ApiModels.EnvironmentDifference("CHANGED", connection.getId(), other.getId(),
                    label(connection), label(other), changed));
            }
        }
        for (TopologyConnection connection : targetConnections) {
            if (!sourceByKey.containsKey(ConnectionPromotionService.identity(connection))) {
                differences.add(new ApiModels.EnvironmentDifference("MISSING_IN_SOURCE", null, connection.getId(),
                    null, label(connection), List.of()));
            }
        }
        return new ApiModels.EnvironmentComparison(projectId, sourceEnvironment.getCode(), targetEnvironment.getCode(),
            sourceConnections.size(), targetConnections.size(), differences);
    }

    private Map<String, TopologyConnection> index(List<TopologyConnection> values) {
        return values.stream().collect(Collectors.toMap(ConnectionPromotionService::identity, value -> value,
            (left, right) -> left, LinkedHashMap::new));
    }

    private List<String> changedFields(TopologyConnection source, TopologyConnection target) {
        List<String> fields = new ArrayList<>();
        if (!Objects.equals(source.getConnectionSettings(), target.getConnectionSettings())) fields.add("connection_settings");
        if (!Objects.equals(source.getMappingRules(), target.getMappingRules())) fields.add("mapping_rules");
        return fields;
    }

    private String label(TopologyConnection connection) {
        return connection.getSource().getName() + " → " + connection.getTarget().getName()
            + " (" + apiLabel(connection.getSourceApi()) + " → " + apiLabel(connection.getTargetApi())
            + ", " + connection.getTransportType() + ")";
    }

    private String apiLabel(ServiceApi api) {
        return api == null ? "без API" : api.getName() + " v" + api.getVersion();
    }
}
