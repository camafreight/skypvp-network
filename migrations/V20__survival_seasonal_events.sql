CREATE TABLE IF NOT EXISTS survival_seasonal_events (
    event_id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    rewards JSONB
);

CREATE TABLE IF NOT EXISTS survival_event_completions (
    completion_id BIGSERIAL PRIMARY KEY,
    player_uuid UUID NOT NULL,
    event_name TEXT NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_seasonal_events_active ON survival_seasonal_events(starts_at, ends_at);
CREATE INDEX IF NOT EXISTS idx_event_completions_player ON survival_event_completions(player_uuid, event_name);
