CREATE TABLE IF NOT EXISTS survival_boss_encounters (
    encounter_id SERIAL PRIMARY KEY,
    boss_type VARCHAR(32) NOT NULL,
    participants JSONB DEFAULT '[]'::jsonb,
    winners JSONB DEFAULT '[]'::jsonb,
    loot_table JSONB DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
