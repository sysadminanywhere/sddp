package ru.sddp.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "services", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "name"}))
public class ServiceNode {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "project_id") private Project project;
    @Column(nullable = false) private String name;
    @Enumerated(EnumType.STRING) @Column(name = "service_type", nullable = false) private ServiceType serviceType;
    @Column(columnDefinition = "text") private String description;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

    protected ServiceNode() { }

    public ServiceNode(Project project, String name, ServiceType serviceType, String description) {
        this.project = project;
        this.name = name;
        this.serviceType = serviceType;
        this.description = description;
    }

    public UUID getId() { return id; }
    public Project getProject() { return project; }
    public String getName() { return name; }
    public ServiceType getServiceType() { return serviceType; }
    public String getDescription() { return description; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isExternal() { return serviceType == ServiceType.EXTERNAL; }

    public void update(String name, ServiceType serviceType, String description) {
        this.name = name;
        this.serviceType = serviceType;
        this.description = description;
    }
}
