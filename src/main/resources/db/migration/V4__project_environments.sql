-- Environments become project-scoped, editable entities instead of a hardcoded list.
CREATE TABLE project_environments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    code VARCHAR(50) NOT NULL,
    name VARCHAR(255) NOT NULL,
    position INTEGER NOT NULL DEFAULT 0,
    is_protected BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_project_environments_code UNIQUE(project_id, code)
);
CREATE INDEX ix_project_environments_project ON project_environments (project_id, position);

-- Every existing project keeps the familiar DEV/STAGE/PROD set. PROD is protected by default.
INSERT INTO project_environments (project_id, code, name, position, is_protected)
SELECT p.id, d.code, d.name, d.position, d.is_protected
FROM projects p
CROSS JOIN (VALUES
    ('DEV', 'Development', 10, FALSE),
    ('STAGE', 'Staging', 20, FALSE),
    ('PROD', 'Production', 30, TRUE)
) AS d(code, name, position, is_protected)
ON CONFLICT (project_id, code) DO NOTHING;

-- Preserve custom environment codes that connections already use.
INSERT INTO project_environments (project_id, code, name, position, is_protected)
SELECT DISTINCT tc.project_id, tc.environment, tc.environment, 100, FALSE
FROM topology_connections tc
ON CONFLICT (project_id, code) DO NOTHING;

ALTER TABLE topology_connections DROP CONSTRAINT IF EXISTS ck_connection_environment;

ALTER TABLE topology_connections
    ADD CONSTRAINT fk_connection_environment
        FOREIGN KEY (project_id, environment) REFERENCES project_environments (project_id, code);
