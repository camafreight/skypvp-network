-- V29: Network-wide objectives (gems currency) + per-gamemode isolation for SMP objectives
-- ──────────────────────────────────────────────────────────────────────────────────────────
-- Part 1: Extend smp_daily_objective_progress with a gamemode scope column so that
--         multiple game-mode plugins can use the same table without key collisions.
--         The existing primary key is (player_uuid, objective_date, objective_id).
--         We add gamemode with DEFAULT 'SURVIVAL' so existing rows remain valid,
--         then migrate the PK to include it.

ALTER TABLE smp_daily_objective_progress
    ADD COLUMN IF NOT EXISTS gamemode TEXT NOT NULL DEFAULT 'SURVIVAL';

-- Drop the old PK and replace with one that includes gamemode.
-- This is safe: existing rows all get 'SURVIVAL', and the combination
-- (player_uuid, objective_date, objective_id, 'SURVIVAL') is still unique.
ALTER TABLE smp_daily_objective_progress
    DROP CONSTRAINT IF EXISTS smp_daily_objective_progress_pkey;

ALTER TABLE smp_daily_objective_progress
    ADD PRIMARY KEY (player_uuid, objective_date, objective_id, gamemode);

-- Also update the analytics index to cover gamemode.
DROP INDEX IF EXISTS idx_sdop_date_obj;
CREATE INDEX IF NOT EXISTS idx_sdop_date_obj_gm
    ON smp_daily_objective_progress (objective_date, objective_id, gamemode)
    WHERE completed = TRUE;

-- ──────────────────────────────────────────────────────────────────────────────────────────
-- Part 2: Network-wide objective progress (daily / weekly / monthly).
--         Key: (player_uuid, objective_id, period_key).
--         period_key is a string like "2026-05-18", "2026-W20", or "2026-05".
--         cadence stores the enum name for audit queries.
--         Progress is capped at the target on the application side; completed/claimed_at
--         are set atomically by markCompletedAsync.

CREATE TABLE IF NOT EXISTS network_objective_progress (
    player_uuid   UUID         NOT NULL,
    objective_id  TEXT         NOT NULL,
    period_key    TEXT         NOT NULL,
    cadence       TEXT         NOT NULL,
    progress      INT          NOT NULL DEFAULT 0,
    completed     BOOLEAN      NOT NULL DEFAULT FALSE,
    claimed_at    TIMESTAMPTZ,
    PRIMARY KEY (player_uuid, objective_id, period_key)
);

-- Leaderboard / analytics: completions per objective per period.
CREATE INDEX IF NOT EXISTS idx_nop_period_obj_completed
    ON network_objective_progress (period_key, objective_id)
    WHERE completed = TRUE;

-- ──────────────────────────────────────────────────────────────────────────────────────────
-- Part 3: Gems currency.
--         Gems are earned exclusively through network-wide objectives.
--         Each balance mutation appends an audit row to network_gem_transactions.

CREATE TABLE IF NOT EXISTS network_player_gems (
    player_uuid  UUID         NOT NULL PRIMARY KEY,
    player_name  TEXT         NOT NULL,
    balance      BIGINT       NOT NULL DEFAULT 0 CHECK (balance >= 0),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS network_gem_transactions (
    id            BIGSERIAL    PRIMARY KEY,
    player_uuid   UUID         NOT NULL,
    delta         BIGINT       NOT NULL,          -- positive = credit, negative = debit
    balance_after BIGINT       NOT NULL,
    reason        TEXT         NOT NULL,
    occurred_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ngt_player_time
    ON network_gem_transactions (player_uuid, occurred_at DESC);
