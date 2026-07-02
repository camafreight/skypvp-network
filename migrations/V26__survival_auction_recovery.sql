ALTER TABLE survival_auction_house_listings
    ADD COLUMN IF NOT EXISTS source_slot INTEGER;

ALTER TABLE survival_auction_house_claims
    ADD COLUMN IF NOT EXISTS delivery_started_at TIMESTAMPTZ;

ALTER TABLE survival_auction_house_claims
    ADD COLUMN IF NOT EXISTS delivery_slot INTEGER;

CREATE INDEX IF NOT EXISTS idx_auction_claim_owner_delivery
    ON survival_auction_house_claims (owner_uuid, claimed_at, delivery_started_at, created_at DESC);