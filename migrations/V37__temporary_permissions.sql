CREATE TABLE IF NOT EXISTS player_permissions (
    player_id UUID NOT NULL,
    permission VARCHAR(255) NOT NULL,
    expires_at TIMESTAMPTZ,
    PRIMARY KEY (player_id, permission)
);

CREATE INDEX IF NOT EXISTS idx_player_permissions_expires_at ON player_permissions(expires_at);
