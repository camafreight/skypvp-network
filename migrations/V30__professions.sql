-- V30: Profession tracks per player per season.
-- Professions are chosen once per season and grant passive XP-based bonuses.
-- Season is tracked as a string key (e.g. "2026-S1") set in config.

CREATE TABLE IF NOT EXISTS player_professions (
    player_uuid       UUID        NOT NULL,
    season            TEXT        NOT NULL,
    profession        TEXT        NOT NULL,
    xp                INT         NOT NULL DEFAULT 0 CHECK (xp >= 0),
    level             INT         NOT NULL DEFAULT 1 CHECK (level >= 1 AND level <= 50),
    selected_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_reset_at     TIMESTAMPTZ,
    PRIMARY KEY (player_uuid, season)
);

CREATE INDEX IF NOT EXISTS idx_player_professions_season ON player_professions (season);

-- Daily XP cap tracking — resets nightly, prevents AFK farming.
CREATE TABLE IF NOT EXISTS profession_daily_xp (
    player_uuid   UUID  NOT NULL,
    xp_date       DATE  NOT NULL DEFAULT CURRENT_DATE,
    xp_earned     INT   NOT NULL DEFAULT 0 CHECK (xp_earned >= 0),
    PRIMARY KEY (player_uuid, xp_date)
);
