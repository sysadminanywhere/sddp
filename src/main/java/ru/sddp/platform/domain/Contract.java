package ru.sddp.platform.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "contracts", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "name", "version"}))
public class Contract {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "project_id") private Project project;
    @Column(nullable = false) private String name;
    @Column(nullable = false) private String version;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "request_schema", nullable = false, columnDefinition = "jsonb") private JsonNode requestSchema;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "response_schema", columnDefinition = "jsonb") private JsonNode responseSchema;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    protected Contract() { }
    public Contract(Project project, String name, String version, JsonNode requestSchema, JsonNode responseSchema) {
        this.project = project; this.name = name; this.version = version; this.requestSchema = requestSchema; this.responseSchema = responseSchema;
    }
    public UUID getId() { return id; }
    @com.fasterxml.jackson.annotation.JsonIgnore
    public Project getProject() { return project; }
    public String getName() { return name; }
    public String getVersion() { return version; }
    public JsonNode getRequestSchema() { return requestSchema; }
    public JsonNode getResponseSchema() { return responseSchema; }
}
