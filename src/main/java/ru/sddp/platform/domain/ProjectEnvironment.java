package ru.sddp.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project_environments", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "code"}))
public class ProjectEnvironment {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "project_id") private Project project;
    @Column(nullable = false) private String code;
    @Column(nullable = false) private String name;
    @Column(nullable = false) private int position;
    @Column(name = "is_protected", nullable = false) private boolean isProtected;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

    protected ProjectEnvironment() { }

    public ProjectEnvironment(Project project, String code, String name, int position, boolean isProtected) {
        this.project = project;
        this.code = code;
        this.name = name;
        this.position = position;
        this.isProtected = isProtected;
    }

    public UUID getId() { return id; }
    public Project getProject() { return project; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public int getPosition() { return position; }
    public boolean isProtected() { return isProtected; }
    public Instant getCreatedAt() { return createdAt; }

    public void update(String code, String name, int position, boolean isProtected) {
        this.code = code;
        this.name = name;
        this.position = position;
        this.isProtected = isProtected;
    }
}
