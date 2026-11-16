package ru.sddp.platform.domain;

import tools.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "topology_connections")
public class TopologyConnection {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "project_id") private Project project;
    @Column(nullable = false) private String environment;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "source_service_id") private ServiceNode source;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "target_service_id") private ServiceNode target;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "source_api_id") private ServiceApi sourceApi;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "target_api_id") private ServiceApi targetApi;
    @Column(name = "transport_type", nullable = false) private String transportType;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "connection_settings", nullable = false, columnDefinition = "jsonb") private JsonNode connectionSettings;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "mapping_rules", columnDefinition = "jsonb") private JsonNode mappingRules;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    protected TopologyConnection() { }

    public TopologyConnection(Project project, String environment, ServiceNode source, ServiceNode target,
                              ServiceApi sourceApi, ServiceApi targetApi, String transportType,
                              JsonNode connectionSettings, JsonNode mappingRules) {
        this.project = project;
        this.environment = environment;
        this.source = source;
        this.target = target;
        this.sourceApi = sourceApi;
        this.targetApi = targetApi;
        this.transportType = transportType;
        this.connectionSettings = connectionSettings;
        this.mappingRules = mappingRules;
    }

    public UUID getId() { return id; }
    public Project getProject() { return project; }
    public String getEnvironment() { return environment; }
    public void renameEnvironment(String environment) { this.environment = environment; this.updatedAt = Instant.now(); }
    public ServiceNode getSource() { return source; }
    public ServiceNode getTarget() { return target; }
    public ServiceApi getSourceApi() { return sourceApi; }
    public ServiceApi getTargetApi() { return targetApi; }
    public String getTransportType() { return transportType; }
    public JsonNode getConnectionSettings() { return connectionSettings; }
    public JsonNode getMappingRules() { return mappingRules; }

    public void update(String environment, ServiceNode source, ServiceNode target, ServiceApi sourceApi, ServiceApi targetApi,
                       String transportType, JsonNode connectionSettings, JsonNode mappingRules) {
        this.environment = environment;
        this.source = source;
        this.target = target;
        this.sourceApi = sourceApi;
        this.targetApi = targetApi;
        this.transportType = transportType;
        this.connectionSettings = connectionSettings;
        this.mappingRules = mappingRules;
        this.updatedAt = Instant.now();
    }

    public void updateMapping(JsonNode mapping) {
        this.mappingRules = mapping;
        this.updatedAt = Instant.now();
    }
}
