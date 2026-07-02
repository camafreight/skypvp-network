-- Player inventory management for extraction cross-pod sync
CREATE TABLE IF NOT EXISTS extraction_player_profiles (
    player_uuid UUID PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS extraction_inventory_slots (
    player_uuid UUID NOT NULL REFERENCES extraction_player_profiles(player_uuid) ON DELETE CASCADE,
    container_type VARCHAR(32) NOT NULL,
    slot_index INT NOT NULL,
    payload_b64 TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (player_uuid, container_type, slot_index)
);

CREATE INDEX IF NOT EXISTS idx_extraction_inventory_player_container
    ON extraction_inventory_slots (player_uuid, container_type);

CREATE TABLE IF NOT EXISTS extraction_inventory_revisions (
    player_uuid UUID NOT NULL REFERENCES extraction_player_profiles(player_uuid) ON DELETE CASCADE,
    revision BIGINT NOT NULL,
    container_type VARCHAR(32) NOT NULL,
    slot_count INT NOT NULL DEFAULT 0,
    checksum VARCHAR(64) NOT NULL DEFAULT '',
    saved_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (player_uuid, revision, container_type)
);

CREATE TABLE IF NOT EXISTS extraction_breach_sessions (
    session_id UUID PRIMARY KEY,
    map_id VARCHAR(64) NOT NULL,
    pod_id VARCHAR(128) NOT NULL,
    instance_id VARCHAR(128) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ended_at TIMESTAMPTZ,
    player_count INT NOT NULL DEFAULT 0,
    state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
);

CREATE INDEX IF NOT EXISTS idx_extraction_breach_sessions_pod
    ON extraction_breach_sessions (pod_id, state);
