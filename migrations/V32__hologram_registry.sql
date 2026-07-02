-- V12: Persistent Hologram registry for creator-authored Holograms across all servers
CREATE TABLE IF NOT EXISTS network_holograms (
    id            TEXT        NOT NULL,
    server_id     TEXT        NOT NULL,
    lines         TEXT        NOT NULL DEFAULT '',
    interactive   BOOLEAN     NOT NULL DEFAULT false,
    hitbox_size   INTEGER     NOT NULL DEFAULT 1,
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
