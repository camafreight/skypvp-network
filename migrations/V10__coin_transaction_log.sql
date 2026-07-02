-- V10: Coin transaction log
-- Stores an append-only ledger of every coin mutation for auditing and player history.

CREATE TABLE IF NOT EXISTS network_coin_transactions (
    id            BIGSERIAL PRIMARY KEY,
    player_uuid   UUID        NOT NULL,
    player_name   TEXT        NOT NULL,
    delta         BIGINT      NOT NULL,   -- positive = credit, negative = debit
    balance_after BIGINT      NOT NULL,
    reason        TEXT        NOT NULL,
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_coin_tx_player ON network_coin_transactions (player_uuid, occurred_at DESC);
