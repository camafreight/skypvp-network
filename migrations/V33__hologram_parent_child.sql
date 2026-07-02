-- Add parent-child hierarchy support to network_holograms
ALTER TABLE network_holograms 
    ADD COLUMN parent_id VARCHAR(64),
    ADD COLUMN offset_x DOUBLE PRECISION DEFAULT 0.0,
    ADD COLUMN offset_y DOUBLE PRECISION DEFAULT 0.0,
    ADD COLUMN offset_z DOUBLE PRECISION DEFAULT 0.0;

ALTER TABLE network_holograms ADD CONSTRAINT fk_hologram_parent FOREIGN KEY (parent_id, server_id) REFERENCES network_holograms(id, server_id);
