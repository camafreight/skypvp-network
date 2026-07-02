CREATE TABLE IF NOT EXISTS survival_global_leaderboard (
    snapshot_id BIGSERIAL PRIMARY KEY,
    server_name TEXT NOT NULL,
    snapshot_data JSONB NOT NULL,
    timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_global_leaderboard_server ON survival_global_leaderboard(server_name, timestamp DESC);
