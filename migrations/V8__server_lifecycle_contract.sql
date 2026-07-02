-- Suite D foundation: explicit backend lifecycle contract for orchestration-aware routing.
--
-- Why:
--   Heartbeats alone only describe liveness. Production routing needs an explicit
--   lifecycle state machine so orchestrators can drain/reset/hold servers safely.
--
-- Notes:
--   - lifecycle_state = current effective runtime state
--   - desired_lifecycle_state = target state requested by orchestrator/operator
--   - orchestration_generation supports monotonic state transitions by external controllers

ALTER TABLE network_server_registry
    ADD COLUMN IF NOT EXISTS lifecycle_state varchar(32) NOT NULL DEFAULT 'READY',
    ADD COLUMN IF NOT EXISTS desired_lifecycle_state varchar(32) NULL,
    ADD COLUMN IF NOT EXISTS lifecycle_reason varchar(255) NULL,
    ADD COLUMN IF NOT EXISTS orchestrator_source varchar(64) NULL,
    ADD COLUMN IF NOT EXISTS orchestration_generation bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS lifecycle_updated_at timestamptz NOT NULL DEFAULT now();

CREATE INDEX IF NOT EXISTS idx_network_server_registry_lifecycle
    ON network_server_registry (lifecycle_state);

CREATE INDEX IF NOT EXISTS idx_network_server_registry_desired_lifecycle
    ON network_server_registry (desired_lifecycle_state);

CREATE TABLE IF NOT EXISTS network_server_lifecycle_audit (
    audit_id bigserial PRIMARY KEY,
    server_id varchar(64) NOT NULL,
    previous_state varchar(32) NULL,
    new_state varchar(32) NOT NULL,
    desired_state varchar(32) NULL,
    reason varchar(255) NULL,
    source varchar(64) NULL,
    generation bigint NOT NULL DEFAULT 0,
    changed_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_network_server_lifecycle_audit_server_time
    ON network_server_lifecycle_audit (server_id, changed_at DESC);
