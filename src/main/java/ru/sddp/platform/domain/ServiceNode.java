package ru.sddp.platform.domain;

import jakarta.persistence.*;
import java.util.UUID;

@Entity @Table(name = "services", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "name"}))
public class ServiceNode {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "project_id") private Project project;
    @Column(nullable = false) private String name;
    @Column(name = "is_external", nullable = false) private boolean external;
    protected ServiceNode() { }
    public ServiceNode(Project project, String name, boolean external) { this.project = project; this.name = name; this.external = external; }
    public UUID getId() { return id; }
    public Project getProject() { return project; }
    public String getName() { return name; }
    public boolean isExternal() { return external; }
}
