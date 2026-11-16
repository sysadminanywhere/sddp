package ru.sddp.platform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_events")
public class AuditEvent {
    @Id private UUID id;
    @Column(nullable = false) private String actor;
    @Column(nullable = false, length = 16) private String method;
    @Column(nullable = false, length = 1024) private String path;
    @Column(nullable = false) private int status;
    @Column(name = "duration_ms", nullable = false) private long durationMs;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected AuditEvent() { }

    public AuditEvent(String actor, String method, String path, int status, long durationMs) {
        this.id = UUID.randomUUID(); this.actor = actor; this.method = method; this.path = path;
        this.status = status; this.durationMs = durationMs; this.createdAt = Instant.now();
    }
}
