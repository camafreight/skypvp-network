CREATE TABLE IF NOT EXISTS extraction_quest_dialogue_choices (
    player_uuid UUID NOT NULL REFERENCES extraction_player_profiles(player_uuid) ON DELETE CASCADE,
    dialogue_id VARCHAR(64) NOT NULL,
    option_id VARCHAR(64) NOT NULL,
    value VARCHAR(128) NOT NULL DEFAULT 'selected',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (player_uuid, dialogue_id, option_id)
);

CREATE INDEX IF NOT EXISTS idx_extraction_quest_dialogue_choices_player
    ON extraction_quest_dialogue_choices (player_uuid);

ALTER TABLE extraction_player_profiles
    ADD COLUMN IF NOT EXISTS scrapper_tier INT NOT NULL DEFAULT 1;
