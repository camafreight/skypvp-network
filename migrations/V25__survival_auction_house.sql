CREATE TABLE IF NOT EXISTS survival_auction_house_listings (
    listing_id            BIGSERIAL PRIMARY KEY,
    seller_uuid           UUID        NOT NULL,
    seller_name           TEXT        NOT NULL,
    buyer_uuid            UUID,
    buyer_name            TEXT,
    material_key          TEXT        NOT NULL,
    item_amount           INTEGER     NOT NULL CHECK (item_amount > 0),
    item_name             TEXT        NOT NULL,
    serialized_item       TEXT        NOT NULL,
    item_fingerprint      TEXT        NOT NULL,
    unit_price            BIGINT      NOT NULL CHECK (unit_price > 0),
    total_price           BIGINT      NOT NULL CHECK (total_price > 0),
    deposit_paid          BIGINT      NOT NULL DEFAULT 0 CHECK (deposit_paid >= 0),
    sale_tax_paid         BIGINT      NOT NULL DEFAULT 0 CHECK (sale_tax_paid >= 0),
    duration_seconds      INTEGER     NOT NULL CHECK (duration_seconds > 0),
    state                 TEXT        NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at            TIMESTAMPTZ NOT NULL,
    completed_at          TIMESTAMPTZ,
    seller_claimed_at     TIMESTAMPTZ,
    buyer_claimed_at      TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_auction_active_created
    ON survival_auction_house_listings (state, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_auction_seller_state
    ON survival_auction_house_listings (seller_uuid, state, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_auction_material_state
    ON survival_auction_house_listings (material_key, state, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_auction_expires
    ON survival_auction_house_listings (state, expires_at);

CREATE TABLE IF NOT EXISTS survival_auction_house_claims (
    claim_id              BIGSERIAL PRIMARY KEY,
    listing_id            BIGINT      REFERENCES survival_auction_house_listings(listing_id) ON DELETE SET NULL,
    owner_uuid            UUID        NOT NULL,
    owner_name            TEXT        NOT NULL,
    claim_type            TEXT        NOT NULL,
    serialized_item       TEXT        NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    claimed_at            TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_auction_claim_owner
    ON survival_auction_house_claims (owner_uuid, claimed_at, created_at DESC);