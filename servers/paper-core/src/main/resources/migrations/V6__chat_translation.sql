ALTER TABLE network_player_social_settings
    ADD COLUMN IF NOT EXISTS auto_translate_enabled BOOLEAN NOT NULL DEFAULT FALSE;
