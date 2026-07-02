CREATE TABLE IF NOT EXISTS network_player_social_settings (
    player_uuid UUID PRIMARY KEY REFERENCES network_players(player_id) ON DELETE CASCADE,
    chat_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    block_friend_requests BOOLEAN NOT NULL DEFAULT FALSE,
    block_party_requests BOOLEAN NOT NULL DEFAULT FALSE,
    profanity_filter_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
