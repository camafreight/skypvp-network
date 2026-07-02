-- Suite C: Weekly Server Event Framework
-- Two tables:
--   smp_events           - one row per event instance (lifecycle tracking)
--   smp_event_scores     - one row per participating player per event

CREATE TABLE IF NOT EXISTS smp_events (
    event_id        TEXT         PRIMARY KEY,
    event_type      TEXT         NOT NULL,
    display_name    TEXT         NOT NULL,
    state           TEXT         NOT NULL DEFAULT 'SCHEDULED',
    started_at      TIMESTAMPTZ,
    ended_at        TIMESTAMPTZ,
    budget_cap      BIGINT       NOT NULL DEFAULT 0,
    payout_json     JSONB,
    cancelled_by    TEXT,
    cancelled_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS smp_event_scores (
    event_id        TEXT         NOT NULL REFERENCES smp_events(event_id),
    player_uuid     UUID         NOT NULL,
    player_name     TEXT         NOT NULL,
    score           BIGINT       NOT NULL DEFAULT 0,
    session_seconds INT          NOT NULL DEFAULT 0,
    rewarded_coins  BIGINT,
    placement       INT,
    PRIMARY KEY (event_id, player_uuid)
);

CREATE INDEX IF NOT EXISTS idx_ses_event_score
    ON smp_event_scores (event_id, score DESC)
    WHERE rewarded_coins IS NULL;
