CREATE TABLE IF NOT EXISTS survival_teams (
    team_id SERIAL PRIMARY KEY,
    team_name VARCHAR(64) NOT NULL,
    leader_uuid UUID NOT NULL,
    members JSONB DEFAULT '[]'::jsonb,
    wealth BIGINT DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_survival_teams_leader ON survival_teams(leader_uuid);
