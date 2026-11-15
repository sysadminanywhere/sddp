-- Services become typed topology nodes with human-readable metadata.
ALTER TABLE services ADD COLUMN service_type VARCHAR(20);
ALTER TABLE services ADD COLUMN description TEXT;
ALTER TABLE services ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

UPDATE services
SET service_type = CASE WHEN is_external THEN 'EXTERNAL' ELSE 'CORE' END;

ALTER TABLE services ALTER COLUMN service_type SET NOT NULL;
ALTER TABLE services ADD CONSTRAINT ck_service_type CHECK (service_type IN ('CORE', 'EXTERNAL', 'ADAPTER'));
ALTER TABLE services DROP COLUMN is_external;

-- API descriptions belong to a service. A service may expose several named API operations.
CREATE TABLE service_apis (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id UUID NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    version VARCHAR(50) NOT NULL,
    description TEXT,
    pattern VARCHAR(20) NOT NULL DEFAULT 'REQUEST_RESPONSE',
    request_schema JSONB NOT NULL,
    response_schema JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_service_apis_service_name_version UNIQUE(service_id, name, version),
    CONSTRAINT ck_service_api_pattern CHECK (pattern IN ('REQUEST_RESPONSE', 'PUB_SUB'))
);
CREATE INDEX ix_service_apis_service ON service_apis (service_id);

-- Migrate every project-scoped contract to the service that consumes it.
-- When a contract is shared by several target services, each service gets its own API copy.
CREATE TEMP TABLE contract_targets ON COMMIT DROP AS
SELECT DISTINCT contract_id, target_service_id
FROM topology_connections
WHERE contract_id IS NOT NULL;

CREATE TEMP TABLE contract_api_map ON COMMIT DROP AS
SELECT contract_id,
       target_service_id,
       CASE WHEN row_number() OVER (PARTITION BY contract_id ORDER BY target_service_id) = 1
            THEN contract_id
            ELSE gen_random_uuid() END AS api_id
FROM contract_targets;

INSERT INTO service_apis (id, service_id, name, version, description, pattern, request_schema, response_schema, created_at)
SELECT m.api_id,
       m.target_service_id,
       c.name,
       c.version,
       'Migrated from a project contract',
       CASE WHEN EXISTS (
           SELECT 1
           FROM topology_connections tc
           WHERE tc.contract_id = c.id
             AND tc.target_service_id = m.target_service_id
             AND tc.transport_type IN ('KAFKA', 'RABBITMQ')
       ) THEN 'PUB_SUB' ELSE 'REQUEST_RESPONSE' END,
       c.request_schema,
       c.response_schema,
       c.created_at
FROM contract_api_map m
JOIN contracts c ON c.id = m.contract_id;

-- A connection points at concrete API operations on both sides.
ALTER TABLE topology_connections ADD COLUMN source_api_id UUID;
ALTER TABLE topology_connections ADD COLUMN target_api_id UUID;

UPDATE topology_connections tc
SET target_api_id = m.api_id
FROM contract_api_map m
WHERE tc.contract_id = m.contract_id
  AND tc.target_service_id = m.target_service_id;

ALTER TABLE topology_connections DROP CONSTRAINT fk_connection_contract_project;
ALTER TABLE topology_connections DROP COLUMN contract_id;

ALTER TABLE topology_connections
    ADD CONSTRAINT fk_connection_source_api FOREIGN KEY (source_api_id) REFERENCES service_apis(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_connection_target_api FOREIGN KEY (target_api_id) REFERENCES service_apis(id) ON DELETE SET NULL;

CREATE INDEX ix_topology_source_api ON topology_connections (source_api_id);
CREATE INDEX ix_topology_target_api ON topology_connections (target_api_id);

DROP TABLE contracts;
