CREATE TABLE IF NOT EXISTS survival_level (
    player_uuid UUID PRIMARY KEY,
    current_level INTEGER DEFAULT 1,
    current_xp INTEGER DEFAULT 0,
    updated_at TIMESTAMPTZ DEFAULT NOW()
);
