-- Suite C foundation: world preset promotion lifecycle.
--
-- This schema allows presets to be registered, validated, promoted, and rolled
-- back with full auditability.

CREATE TABLE IF NOT EXISTS network_world_presets (
    preset_id varchar(64) PRIMARY KEY,
    version integer NOT NULL DEFAULT 1,
    status varchar(32) NOT NULL DEFAULT 'DRAFT', -- DRAFT|VALIDATED|ACTIVE|DEPRECATED
    description varchar(255) NULL,
    checksum_sha256 varchar(64) NULL,
    world_count integer NOT NULL DEFAULT 1,
    created_by varchar(64) NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_network_world_presets_status
    ON network_world_presets (status);

CREATE TABLE IF NOT EXISTS network_world_preset_promotions (
    promotion_id bigserial PRIMARY KEY,
    preset_id varchar(64) NOT NULL,
    from_status varchar(32) NULL,
    to_status varchar(32) NOT NULL,
    server_scope varchar(64) NULL, -- null=global or specific cluster/mode
    reason varchar(255) NULL,
    promoted_by varchar(64) NULL,
    promoted_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (preset_id) REFERENCES network_world_presets (preset_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_network_world_preset_promotions_preset_time
    ON network_world_preset_promotions (preset_id, promoted_at DESC);
