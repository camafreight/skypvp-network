-- Quest NPCs / POIs are gamemode-scoped (lobby vs extraction), matching network_npcs.server_id.
-- Runtime ensureSchema also applies this; this migration documents the intended shape for ops DBs.

ALTER TABLE IF EXISTS network_quest_npcs
    ADD COLUMN IF NOT EXISTS server_id VARCHAR(32);

UPDATE network_quest_npcs
SET server_id = 'lobby'
WHERE server_id IS NULL OR btrim(server_id) = '';

ALTER TABLE IF EXISTS network_quest_npcs
    ALTER COLUMN server_id SET DEFAULT 'lobby';

ALTER TABLE IF EXISTS network_quest_npcs
    ALTER COLUMN server_id SET NOT NULL;

ALTER TABLE IF EXISTS network_quest_pois
    ADD COLUMN IF NOT EXISTS server_id VARCHAR(32);

UPDATE network_quest_pois
SET server_id = 'lobby'
WHERE server_id IS NULL OR btrim(server_id) = '';

ALTER TABLE IF EXISTS network_quest_pois
    ALTER COLUMN server_id SET DEFAULT 'lobby';

ALTER TABLE IF EXISTS network_quest_pois
    ALTER COLUMN server_id SET NOT NULL;

-- Rebuild primary keys to (id|name, server_id) when still single-column.
DO $$
DECLARE
    pk_name text;
    col_count int;
BEGIN
    SELECT constraint_name INTO pk_name
    FROM information_schema.table_constraints
    WHERE table_schema = current_schema()
      AND table_name = 'network_quest_npcs'
      AND constraint_type = 'PRIMARY KEY'
    LIMIT 1;

    IF pk_name IS NOT NULL THEN
        SELECT COUNT(*) INTO col_count
        FROM information_schema.key_column_usage
        WHERE table_schema = current_schema()
          AND table_name = 'network_quest_npcs'
          AND constraint_name = pk_name;
        IF col_count < 2 THEN
            EXECUTE format('ALTER TABLE network_quest_npcs DROP CONSTRAINT %I', pk_name);
            ALTER TABLE network_quest_npcs ADD PRIMARY KEY (id, server_id);
        END IF;
    END IF;

    SELECT constraint_name INTO pk_name
    FROM information_schema.table_constraints
    WHERE table_schema = current_schema()
      AND table_name = 'network_quest_pois'
      AND constraint_type = 'PRIMARY KEY'
    LIMIT 1;

    IF pk_name IS NOT NULL THEN
        SELECT COUNT(*) INTO col_count
        FROM information_schema.key_column_usage
        WHERE table_schema = current_schema()
          AND table_name = 'network_quest_pois'
          AND constraint_name = pk_name;
        IF col_count < 2 THEN
            EXECUTE format('ALTER TABLE network_quest_pois DROP CONSTRAINT %I', pk_name);
            ALTER TABLE network_quest_pois ADD PRIMARY KEY (name, server_id);
        END IF;
    END IF;
END $$;
