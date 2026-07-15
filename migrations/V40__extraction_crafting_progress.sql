-- Extraction armory: blueprint discovery + virtual crafting material stash (cross-pod durable)
CREATE TABLE IF NOT EXISTS extraction_blueprint_discovery (
    player_uuid UUID NOT NULL REFERENCES extraction_player_profiles(player_uuid) ON DELETE CASCADE,
    blueprint_id VARCHAR(64) NOT NULL,
    discovered_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (player_uuid, blueprint_id)
);

CREATE INDEX IF NOT EXISTS idx_extraction_blueprint_discovery_player
    ON extraction_blueprint_discovery (player_uuid);

CREATE TABLE IF NOT EXISTS extraction_crafting_material_balances (
    player_uuid UUID NOT NULL REFERENCES extraction_player_profiles(player_uuid) ON DELETE CASCADE,
    material_id VARCHAR(64) NOT NULL,
    amount INT NOT NULL DEFAULT 0 CHECK (amount >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (player_uuid, material_id)
);

CREATE INDEX IF NOT EXISTS idx_extraction_crafting_materials_player
    ON extraction_crafting_material_balances (player_uuid);
