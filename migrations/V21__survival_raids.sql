CREATE TABLE IF NOT EXISTS survival_raids (
    raid_id BIGSERIAL PRIMARY KEY,
    team_id INTEGER NOT NULL,
    raid_type TEXT NOT NULL,
    participants JSONB NOT NULL,
    status TEXT NOT NULL DEFAULT 'active',
    boss_phase INTEGER DEFAULT 1,
    wave_count INTEGER DEFAULT 0,
    total_reward BIGINT DEFAULT 0,
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_raids_team ON survival_raids(team_id);
CREATE INDEX IF NOT EXISTS idx_raids_status ON survival_raids(status);
