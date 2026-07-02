-- V11: Persistent NPC registry for creator-authored NPCs across all servers
CREATE TABLE IF NOT EXISTS network_npcs (
    id            TEXT        NOT NULL,
    server_id     TEXT        NOT NULL,
    display_name  TEXT        NOT NULL DEFAULT '<gold><bold>NPC</bold>',
    entity_type   TEXT        NOT NULL DEFAULT 'VILLAGER',
    world_name    TEXT        NOT NULL DEFAULT 'world',
    x             DOUBLE PRECISION NOT NULL,
    y             DOUBLE PRECISION NOT NULL,
    z             DOUBLE PRECISION NOT NULL,
    yaw           REAL        NOT NULL DEFAULT 0,
    pitch         REAL        NOT NULL DEFAULT 0,
    action_type   TEXT        NOT NULL DEFAULT 'NONE',
    action_data   TEXT        NOT NULL DEFAULT '',
    created_by    TEXT        NOT NULL DEFAULT 'system',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, server_id)
);
