-- Display-entity options for holograms and NPC-attached hologram lines.
ALTER TABLE network_holograms ADD COLUMN IF NOT EXISTS background BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE network_holograms ADD COLUMN IF NOT EXISTS see_through BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE network_holograms ADD COLUMN IF NOT EXISTS shadowed BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE network_holograms ADD COLUMN IF NOT EXISTS text_alignment VARCHAR(16) NOT NULL DEFAULT 'CENTER';
ALTER TABLE network_holograms ADD COLUMN IF NOT EXISTS view_range REAL NOT NULL DEFAULT 1.0;
-- "freeze" is a PostgreSQL keyword (VACUUM FREEZE / COPY FREEZE); must be quoted.
ALTER TABLE network_holograms ADD COLUMN IF NOT EXISTS "freeze" BOOLEAN NOT NULL DEFAULT true;

ALTER TABLE network_npcs ADD COLUMN IF NOT EXISTS hologram_background BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE network_npcs ADD COLUMN IF NOT EXISTS hologram_see_through BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE network_npcs ADD COLUMN IF NOT EXISTS hologram_shadowed BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE network_npcs ADD COLUMN IF NOT EXISTS hologram_alignment VARCHAR(16) NOT NULL DEFAULT 'CENTER';
ALTER TABLE network_npcs ADD COLUMN IF NOT EXISTS hologram_view_range REAL NOT NULL DEFAULT 1.0;
ALTER TABLE network_npcs ADD COLUMN IF NOT EXISTS hologram_freeze BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE network_npcs ADD COLUMN IF NOT EXISTS hologram_billboard VARCHAR(32) NOT NULL DEFAULT 'CENTER';
ALTER TABLE network_npcs ADD COLUMN IF NOT EXISTS hologram_scale DOUBLE PRECISION NOT NULL DEFAULT 1.0;
