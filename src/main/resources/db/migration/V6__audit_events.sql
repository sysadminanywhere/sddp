CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    actor VARCHAR(255) NOT NULL,
    method VARCHAR(16) NOT NULL,
    path VARCHAR(1024) NOT NULL,
    status INTEGER NOT NULL,
    duration_ms BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_audit_events_created_at ON audit_events (created_at);
