package ru.sddp.platform.domain;

import com.fasterxml.jackson.databind.JsonNode;
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
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "contract_id") private Contract contract;
    @Column(name = "transport_type", nullable = false) private String transportType;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "connection_settings", nullable = false, columnDefinition = "jsonb") private JsonNode connectionSettings;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "mapping_rules", columnDefinition = "jsonb") private JsonNode mappingRules;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
    protected TopologyConnection() { }
    public TopologyConnection(Project p, String env, ServiceNode source, ServiceNode target, Contract contract, String transport, JsonNode settings, JsonNode mapping) {
        this.project=p; this.environment=env; this.source=source; this.target=target; this.contract=contract; this.transportType=transport; this.connectionSettings=settings; this.mappingRules=mapping;
    }
    public UUID getId() { return id; } public String getEnvironment() { return environment; } public ServiceNode getSource() { return source; }
    public ServiceNode getTarget() { return target; } public Contract getContract() { return contract; } public String getTransportType() { return transportType; }
    public JsonNode getConnectionSettings() { return connectionSettings; } public JsonNode getMappingRules() { return mappingRules; }
}
