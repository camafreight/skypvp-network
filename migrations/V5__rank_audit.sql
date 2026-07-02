CREATE TABLE IF NOT EXISTS network_rank_audit (
    audit_id      BIGSERIAL PRIMARY KEY,
    player_id     UUID NOT NULL REFERENCES network_players(player_id) ON DELETE CASCADE,
    actor         VARCHAR(64) NOT NULL,
    action        VARCHAR(32) NOT NULL,
    old_rank_key  VARCHAR(32),
    new_rank_key  VARCHAR(32),
    notes         VARCHAR(255) NOT NULL DEFAULT '',
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_rank_audit_player_time
    ON network_rank_audit(player_id, occurred_at DESC);
