ALTER TABLE extraction_player_profiles
    ADD COLUMN IF NOT EXISTS vault_unlocked_rows INT NOT NULL DEFAULT 5;
