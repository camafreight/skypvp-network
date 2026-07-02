-- Suite B: Daily SMP Objective Rotation
-- Tracks per-player progress toward the 3 daily objectives (one per track).
-- Reset is logical: rows are keyed by objective_date so history is preserved for telemetry.

CREATE TABLE IF NOT EXISTS smp_daily_objective_progress (
    player_uuid    UUID         NOT NULL,
    objective_date DATE         NOT NULL,
    objective_id   TEXT         NOT NULL,
    progress       INT          NOT NULL DEFAULT 0,
    completed      BOOLEAN      NOT NULL DEFAULT FALSE,
    claimed_at     TIMESTAMPTZ,
    PRIMARY KEY (player_uuid, objective_date, objective_id)
);

-- Distribution query: how many completions per objective per day
CREATE INDEX IF NOT EXISTS idx_sdop_date_obj
    ON smp_daily_objective_progress (objective_date, objective_id)
    WHERE completed = TRUE;
