package ru.sddp.platform.domain;

import tools.jackson.databind.JsonNode;
import ru.sddp.platform.api.HttpOperation;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "service_apis", uniqueConstraints = @UniqueConstraint(columnNames = {"service_id", "name", "version"}))
public class ServiceApi {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "service_id") private ServiceNode service;
    @Column(nullable = false) private String name;
    @Column(nullable = false) private String version;
    @Column(columnDefinition = "text") private String description;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private ApiPattern pattern;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "request_schema", nullable = false, columnDefinition = "jsonb") private JsonNode requestSchema;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "response_schema", columnDefinition = "jsonb") private JsonNode responseSchema;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "http_operation", columnDefinition = "jsonb") private HttpOperation httpOperation;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

    protected ServiceApi() { }

    public ServiceApi(ServiceNode service, String name, String version, String description, ApiPattern pattern,
                      JsonNode requestSchema, JsonNode responseSchema) {
        this(service, name, version, description, pattern, requestSchema, responseSchema, null);
    }

    public ServiceApi(ServiceNode service, String name, String version, String description, ApiPattern pattern,
                      JsonNode requestSchema, JsonNode responseSchema, HttpOperation httpOperation) {
        this.httpOperation = httpOperation;
        this.service = service;
        this.name = name;
        this.version = version;
        this.description = description;
        this.pattern = pattern;
        this.requestSchema = requestSchema;
        this.responseSchema = responseSchema;
    }

    public void update(String name, String version, String description, ApiPattern pattern,
                       JsonNode requestSchema, JsonNode responseSchema) {
        this.name = name;
        this.version = version;
        this.description = description;
        this.pattern = pattern;
        this.requestSchema = requestSchema;
        this.responseSchema = responseSchema;
    }

    public UUID getId() { return id; }
    @com.fasterxml.jackson.annotation.JsonIgnore
    public ServiceNode getService() { return service; }
    public String getName() { return name; }
    public String getVersion() { return version; }
    public String getDescription() { return description; }
    public ApiPattern getPattern() { return pattern; }
    public JsonNode getRequestSchema() { return requestSchema; }
    public JsonNode getResponseSchema() { return responseSchema; }
    public HttpOperation getHttpOperation() { return httpOperation; }
    public Instant getCreatedAt() { return createdAt; }
}
