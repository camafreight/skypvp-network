CREATE TABLE IF NOT EXISTS network_player_admin_overrides (
    player_id   UUID PRIMARY KEY REFERENCES network_players(player_id) ON DELETE CASCADE,
    granted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    granted_by  VARCHAR(64) NOT NULL DEFAULT 'console'
);
