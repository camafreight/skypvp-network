CREATE TABLE IF NOT EXISTS survival_rent_payments (
    payment_id BIGSERIAL PRIMARY KEY,
    claim_id INTEGER NOT NULL,
    amount BIGINT NOT NULL,
    paid_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_rent_payments_claim ON survival_rent_payments(claim_id);
