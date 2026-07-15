ALTER TABLE extraction_player_profiles
    ADD COLUMN IF NOT EXISTS material_stash_tier INT NOT NULL DEFAULT 1;
