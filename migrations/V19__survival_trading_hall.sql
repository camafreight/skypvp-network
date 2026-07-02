CREATE TABLE IF NOT EXISTS survival_trading_hall (
    listing_id BIGSERIAL PRIMARY KEY,
    seller_uuid UUID NOT NULL,
    item TEXT NOT NULL,
    qty INTEGER NOT NULL,
    price BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_trading_hall_seller ON survival_trading_hall(seller_uuid);
CREATE INDEX IF NOT EXISTS idx_trading_hall_item ON survival_trading_hall(item);
