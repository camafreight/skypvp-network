-- Per-player quest stage for extraction story quests (accepted / completed / etc.).
CREATE TABLE IF NOT EXISTS extraction_quest_progress (
    player_uuid UUID NOT NULL REFERENCES extraction_player_profiles(player_uuid) ON DELETE CASCADE,
    quest_id VARCHAR(64) NOT NULL,
    stage VARCHAR(64) NOT NULL,
    payload TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (player_uuid, quest_id)
);

CREATE INDEX IF NOT EXISTS idx_extraction_quest_progress_player
    ON extraction_quest_progress (player_uuid);
