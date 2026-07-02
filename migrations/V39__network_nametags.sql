-- V39: Network-wide player nametag layouts (one row per decoration scope bucket)
CREATE TABLE IF NOT EXISTS network_nametags (
    scope              TEXT PRIMARY KEY,
    enabled            BOOLEAN NOT NULL DEFAULT true,
    apply_scopes       TEXT NOT NULL DEFAULT 'global',
    lines              TEXT NOT NULL DEFAULT '',
    base_height        DOUBLE PRECISION NOT NULL DEFAULT 0.32,
    line_spacing       DOUBLE PRECISION NOT NULL DEFAULT 0.27,
    scale              REAL NOT NULL DEFAULT 1.0,
    refresh_ticks      INTEGER NOT NULL DEFAULT 20,
    hide_vanilla_name  BOOLEAN NOT NULL DEFAULT true,
    visible_to_self    BOOLEAN NOT NULL DEFAULT false,
    background         BOOLEAN NOT NULL DEFAULT true,
    created_by         TEXT NOT NULL DEFAULT 'system',
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
