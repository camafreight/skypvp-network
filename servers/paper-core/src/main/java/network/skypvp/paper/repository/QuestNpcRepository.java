package network.skypvp.paper.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import network.skypvp.paper.database.AsyncDbExecutor;

/**
 * Persists quest NPC configuration ({@code /quest …}) network-wide: POI pool, NPC profiles, and
 * the shared day clock. Servers boot from ephemeral Docker images, so this data must live in
 * Postgres — local files are wiped on every restart.
 *
 * <p>Rows store the Gson document for each entity ({@code payload JSONB}); (de)serialization
 * stays with the quest model classes. NPCs and POIs are keyed by {@code (id, server_id)} so lobby
 * and extraction can each own the same logical id without colliding.
 */
public final class QuestNpcRepository {

    private final AsyncDbExecutor asyncDbExecutor;
    private volatile boolean ready;

    public QuestNpcRepository(AsyncDbExecutor asyncDbExecutor) {
        this.asyncDbExecutor = Objects.requireNonNull(asyncDbExecutor, "asyncDbExecutor");
    }

    public CompletableFuture<Void> ensureSchema() {
        return asyncDbExecutor.method_244("questNpc.ensureSchema", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    """
                    CREATE TABLE IF NOT EXISTS network_quest_pois (
                        name VARCHAR(64) NOT NULL,
                        server_id VARCHAR(32) NOT NULL DEFAULT 'lobby',
                        payload JSONB NOT NULL,
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                        PRIMARY KEY (name, server_id)
                    )
                    """
            )) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    """
                    CREATE TABLE IF NOT EXISTS network_quest_npcs (
                        id VARCHAR(64) NOT NULL,
                        server_id VARCHAR(32) NOT NULL DEFAULT 'lobby',
                        payload JSONB NOT NULL,
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                        PRIMARY KEY (id, server_id)
                    )
                    """
            )) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    """
                    CREATE TABLE IF NOT EXISTS network_quest_clock (
                        id SMALLINT PRIMARY KEY CHECK (id = 1),
                        payload JSONB NOT NULL,
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                    )
                    """
            )) {
                ps.executeUpdate();
            }
            migrateLegacyPrimaryKeys(connection);
            ready = true;
        });
    }

    /**
     * Older installs used {@code PRIMARY KEY (id)} / {@code (name)} only. Add {@code server_id}
     * and rebuild the composite keys when needed.
     */
    private static void migrateLegacyPrimaryKeys(java.sql.Connection connection) throws Exception {
        ensureServerIdColumn(connection, "network_quest_npcs");
        ensureServerIdColumn(connection, "network_quest_pois");
        rebuildCompositePk(connection, "network_quest_npcs", "id");
        rebuildCompositePk(connection, "network_quest_pois", "name");
    }

    private static void ensureServerIdColumn(java.sql.Connection connection, String table) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS server_id VARCHAR(32)"
        )) {
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE " + table + " SET server_id = 'lobby' WHERE server_id IS NULL OR btrim(server_id) = ''"
        )) {
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "ALTER TABLE " + table + " ALTER COLUMN server_id SET DEFAULT 'lobby'"
        )) {
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "ALTER TABLE " + table + " ALTER COLUMN server_id SET NOT NULL"
        )) {
            ps.executeUpdate();
        }
    }

    private static void rebuildCompositePk(java.sql.Connection connection, String table, String idColumn)
            throws Exception {
        String pkName;
        try (PreparedStatement ps = connection.prepareStatement(
                """
                SELECT constraint_name FROM information_schema.table_constraints
                WHERE table_schema = current_schema()
                  AND table_name = ?
                  AND constraint_type = 'PRIMARY KEY'
                LIMIT 1
                """
        )) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    try (PreparedStatement add = connection.prepareStatement(
                            "ALTER TABLE " + table + " ADD PRIMARY KEY (" + idColumn + ", server_id)"
                    )) {
                        add.executeUpdate();
                    }
                    return;
                }
                pkName = rs.getString(1);
            }
        }
        boolean alreadyComposite;
        try (PreparedStatement ps = connection.prepareStatement(
                """
                SELECT COUNT(*) FROM information_schema.key_column_usage
                WHERE table_schema = current_schema()
                  AND table_name = ?
                  AND constraint_name = ?
                """
        )) {
            ps.setString(1, table);
            ps.setString(2, pkName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                alreadyComposite = rs.getInt(1) >= 2;
            }
        }
        if (alreadyComposite) {
            return;
        }
        try (PreparedStatement drop = connection.prepareStatement(
                "ALTER TABLE " + table + " DROP CONSTRAINT " + quoteIdent(pkName)
        )) {
            drop.executeUpdate();
        }
        try (PreparedStatement add = connection.prepareStatement(
                "ALTER TABLE " + table + " ADD PRIMARY KEY (" + idColumn + ", server_id)"
        )) {
            add.executeUpdate();
        }
    }

    private static String quoteIdent(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    public boolean isReady() {
        return ready;
    }

    // --- POIs ------------------------------------------------------------------------------

    /** POI rows for one decoration scope, name → payload json. */
    public CompletableFuture<Map<String, String>> loadPois(String scope) {
        return loadScoped(
                "questNpc.loadPois",
                "SELECT name, payload FROM network_quest_pois WHERE server_id = ? ORDER BY name",
                "name",
                normalizeScope(scope)
        );
    }

    public CompletableFuture<Void> upsertPoi(String name, String scope, String payload) {
        return upsertScoped(
                "questNpc.upsertPoi",
                "network_quest_pois",
                "name",
                name,
                normalizeScope(scope),
                payload
        );
    }

    public CompletableFuture<Void> deletePoi(String name, String scope) {
        return deleteScoped(
                "questNpc.deletePoi",
                "DELETE FROM network_quest_pois WHERE name = ? AND server_id = ?",
                name,
                normalizeScope(scope)
        );
    }

    // --- NPC profiles ------------------------------------------------------------------------

    /** NPC rows for one decoration scope, id → payload json. */
    public CompletableFuture<Map<String, String>> loadNpcs(String scope) {
        return loadScoped(
                "questNpc.loadNpcs",
                "SELECT id, payload FROM network_quest_npcs WHERE server_id = ? ORDER BY id",
                "id",
                normalizeScope(scope)
        );
    }

    public CompletableFuture<Void> upsertNpc(String id, String scope, String payload) {
        return upsertScoped(
                "questNpc.upsertNpc",
                "network_quest_npcs",
                "id",
                id,
                normalizeScope(scope),
                payload
        );
    }

    public CompletableFuture<Void> deleteNpc(String id, String scope) {
        return deleteScoped(
                "questNpc.deleteNpc",
                "DELETE FROM network_quest_npcs WHERE id = ? AND server_id = ?",
                id,
                normalizeScope(scope)
        );
    }

    /** Moves an NPC row between decoration scopes (same logical id). */
    public CompletableFuture<Boolean> moveNpcScope(String id, String fromScope, String toScope) {
        return moveScope(
                "questNpc.moveNpcScope",
                "network_quest_npcs",
                "id",
                id,
                normalizeScope(fromScope),
                normalizeScope(toScope)
        );
    }

    /** Moves a POI row between decoration scopes (same logical name). */
    public CompletableFuture<Boolean> movePoiScope(String name, String fromScope, String toScope) {
        return moveScope(
                "questNpc.movePoiScope",
                "network_quest_pois",
                "name",
                name,
                normalizeScope(fromScope),
                normalizeScope(toScope)
        );
    }

    // --- Clock -------------------------------------------------------------------------------

    public CompletableFuture<Optional<String>> loadClock() {
        if (!ready) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return asyncDbExecutor.supply("questNpc.loadClock", connection -> {
            try (
                    PreparedStatement ps = connection.prepareStatement(
                            "SELECT payload FROM network_quest_clock WHERE id = 1");
                    ResultSet rs = ps.executeQuery();
            ) {
                if (rs.next()) {
                    return Optional.ofNullable(rs.getString("payload"));
                }
            }
            return Optional.empty();
        });
    }

    public CompletableFuture<Void> saveClock(String payload) {
        if (!ready || payload == null) {
            return CompletableFuture.completedFuture(null);
        }
        return asyncDbExecutor.method_244("questNpc.saveClock", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    """
                    INSERT INTO network_quest_clock (id, payload, updated_at)
                    VALUES (1, ?::jsonb, NOW())
                    ON CONFLICT (id)
                    DO UPDATE SET payload = EXCLUDED.payload, updated_at = NOW()
                    """
            )) {
                ps.setString(1, payload);
                ps.executeUpdate();
            }
        });
    }

    // --- Shared helpers ------------------------------------------------------------------------

    private CompletableFuture<Map<String, String>> loadScoped(
            String label, String sql, String keyColumn, String scope
    ) {
        if (!ready) {
            return CompletableFuture.completedFuture(Map.of());
        }
        return asyncDbExecutor.supply(label, connection -> {
            Map<String, String> rows = new LinkedHashMap<>();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, scope);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.put(rs.getString(keyColumn), rs.getString("payload"));
                    }
                }
            }
            return rows;
        });
    }

    private CompletableFuture<Void> upsertScoped(
            String label, String table, String keyColumn, String key, String scope, String payload
    ) {
        if (!ready || key == null || payload == null || scope.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return asyncDbExecutor.method_244(label, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO " + table + " (" + keyColumn + ", server_id, payload, updated_at) "
                            + "VALUES (?, ?, ?::jsonb, NOW()) "
                            + "ON CONFLICT (" + keyColumn + ", server_id) DO UPDATE SET "
                            + "payload = EXCLUDED.payload, updated_at = NOW()"
            )) {
                ps.setString(1, key);
                ps.setString(2, scope);
                ps.setString(3, payload);
                ps.executeUpdate();
            }
        });
    }

    private CompletableFuture<Void> deleteScoped(String label, String sql, String key, String scope) {
        if (!ready || key == null || scope.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return asyncDbExecutor.method_244(label, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, key);
                ps.setString(2, scope);
                ps.executeUpdate();
            }
        });
    }

    private CompletableFuture<Boolean> moveScope(
            String label, String table, String keyColumn, String key, String fromScope, String toScope
    ) {
        if (!ready || key == null || fromScope.isEmpty() || toScope.isEmpty() || fromScope.equals(toScope)) {
            return CompletableFuture.completedFuture(false);
        }
        return asyncDbExecutor.supply(label, connection -> {
            try (PreparedStatement exists = connection.prepareStatement(
                    "SELECT 1 FROM " + table + " WHERE " + keyColumn + " = ? AND server_id = ? LIMIT 1"
            )) {
                exists.setString(1, key);
                exists.setString(2, toScope);
                try (ResultSet rs = exists.executeQuery()) {
                    if (rs.next()) {
                        return false;
                    }
                }
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + table + " SET server_id = ?, updated_at = NOW() "
                            + "WHERE " + keyColumn + " = ? AND server_id = ?"
            )) {
                update.setString(1, toScope);
                update.setString(2, key);
                update.setString(3, fromScope);
                return update.executeUpdate() > 0;
            }
        });
    }

    private static String normalizeScope(String scope) {
        String normalized = scope == null ? "" : scope.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? "lobby" : normalized;
    }
}
