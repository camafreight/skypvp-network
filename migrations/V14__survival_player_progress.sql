CREATE TABLE IF NOT EXISTS survival_player_progress (
    player_uuid UUID PRIMARY KEY,
    completed_quests JSONB DEFAULT '[]'::jsonb,
    updated_at TIMESTAMPTZ DEFAULT NOW()
);
