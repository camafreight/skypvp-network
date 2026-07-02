CREATE TABLE IF NOT EXISTS survival_daily_rewards (
    player_uuid UUID PRIMARY KEY,
    last_claim TIMESTAMPTZ,
    claim_streak INTEGER DEFAULT 0
);
