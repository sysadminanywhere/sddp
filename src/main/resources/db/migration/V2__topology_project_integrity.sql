-- Preserve project boundaries even for writes that bypass the REST API.
ALTER TABLE services ADD CONSTRAINT uq_services_project_id UNIQUE (project_id, id);
ALTER TABLE contracts ADD CONSTRAINT uq_contracts_project_id UNIQUE (project_id, id);

ALTER TABLE topology_connections
    ADD CONSTRAINT fk_connection_source_project
        FOREIGN KEY (project_id, source_service_id) REFERENCES services (project_id, id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_connection_target_project
        FOREIGN KEY (project_id, target_service_id) REFERENCES services (project_id, id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_connection_contract_project
        FOREIGN KEY (project_id, contract_id) REFERENCES contracts (project_id, id),
    ADD CONSTRAINT ck_connection_environment CHECK (environment IN ('DEV', 'STAGE', 'PROD')),
    ADD CONSTRAINT ck_connection_transport CHECK (transport_type IN ('REST', 'GRPC', 'KAFKA', 'RABBITMQ'));

-- Multiple contracts may connect the same services in the same environment.
ALTER TABLE topology_connections DROP CONSTRAINT uq_topology_connection;
CREATE INDEX ix_topology_project_environment ON topology_connections (project_id, environment);
