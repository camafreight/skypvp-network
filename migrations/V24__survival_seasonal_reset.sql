CREATE TABLE IF NOT EXISTS survival_seasons (
    season_id BIGSERIAL PRIMARY KEY,
    season_num INTEGER NOT NULL UNIQUE,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL DEFAULT 'active'
);

CREATE TABLE IF NOT EXISTS survival_season_archives (
    archive_id BIGSERIAL PRIMARY KEY,
    season_num INTEGER NOT NULL,
    archived_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_seasons_active ON survival_seasons(status);
