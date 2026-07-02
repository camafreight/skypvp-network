CREATE TABLE IF NOT EXISTS survival_claims (
    claim_id SERIAL PRIMARY KEY,
    player_uuid UUID NOT NULL,
    claim_name VARCHAR(64) NOT NULL,
    x1 INTEGER NOT NULL,
    z1 INTEGER NOT NULL,
    x2 INTEGER NOT NULL,
    z2 INTEGER NOT NULL,
    members JSONB DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    expires_at TIMESTAMPTZ DEFAULT NOW() + INTERVAL '90 days'
);
CREATE INDEX IF NOT EXISTS idx_survival_claims_player ON survival_claims(player_uuid);
CREATE INDEX IF NOT EXISTS idx_survival_claims_expire ON survival_claims(expires_at);
