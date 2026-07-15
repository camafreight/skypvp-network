-- NPC waypoint navigator toggle: right-click starts/clears a WaypointNavigator to the NPC.
ALTER TABLE network_npcs ADD COLUMN IF NOT EXISTS navigator BOOLEAN NOT NULL DEFAULT false;
