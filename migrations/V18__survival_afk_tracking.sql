CREATE TABLE IF NOT EXISTS survival_afk_tracking (
    player_uuid UUID PRIMARY KEY,
    last_activity TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS survival_afk_sessions (
    session_id BIGSERIAL PRIMARY KEY,
    player_uuid UUID NOT NULL,
    duration_ms BIGINT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_afk_tracking_player ON survival_afk_tracking(player_uuid);
