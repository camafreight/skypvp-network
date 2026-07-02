CREATE TABLE IF NOT EXISTS network_chat_formats (
    format_id   VARCHAR(64) PRIMARY KEY,
    scope       VARCHAR(16) NOT NULL DEFAULT 'RANK',
    priority    INTEGER     NOT NULL DEFAULT 0,
    flags_json  JSONB       NOT NULL DEFAULT '{}'::jsonb,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS network_chat_moderation_offenses (
    player_uuid     UUID PRIMARY KEY REFERENCES network_players(player_id) ON DELETE CASCADE,
    offense_count   INTEGER NOT NULL DEFAULT 0,
    warn_count      INTEGER NOT NULL DEFAULT 0,
    last_offense_at TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE network_player_social_settings
    ADD COLUMN IF NOT EXISTS active_chat_channel VARCHAR(16) NOT NULL DEFAULT 'ALL';
