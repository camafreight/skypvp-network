-- Suite E: Territory claims upkeep + raid-window metadata hardening

ALTER TABLE survival_claims
    ADD COLUMN IF NOT EXISTS world_name VARCHAR(64) NOT NULL DEFAULT 'world';

ALTER TABLE survival_claims
    ADD COLUMN IF NOT EXISTS upkeep_paid_until TIMESTAMPTZ;

UPDATE survival_claims
SET upkeep_paid_until = COALESCE(upkeep_paid_until, expires_at, NOW() + INTERVAL '7 days')
WHERE upkeep_paid_until IS NULL;

ALTER TABLE survival_claims
    ALTER COLUMN upkeep_paid_until SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_survival_claims_world_bounds
    ON survival_claims (world_name, x1, x2, z1, z2);

CREATE INDEX IF NOT EXISTS idx_survival_claims_upkeep
    ON survival_claims (upkeep_paid_until);
