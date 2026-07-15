package network.skypvp.paper.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import network.skypvp.paper.database.AsyncDbExecutor;

/** Durable extraction armory progress: blueprint discovery and crafting material vault balances. */
public final class ExtractionCraftingRepository {

    private final AsyncDbExecutor asyncDbExecutor;
    private final Logger logger;
    private volatile boolean ready;

    public ExtractionCraftingRepository(AsyncDbExecutor asyncDbExecutor, Logger logger) {
        this.asyncDbExecutor = asyncDbExecutor;
        this.logger = logger;
    }

    public CompletableFuture<Void> ensureSchema() {
        return asyncDbExecutor.method_244("extraction.crafting.ensureSchema", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    """
                    CREATE TABLE IF NOT EXISTS extraction_blueprint_discovery (
                        player_uuid UUID NOT NULL REFERENCES extraction_player_profiles(player_uuid) ON DELETE CASCADE,
                        blueprint_id VARCHAR(64) NOT NULL,
                        discovered_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                        PRIMARY KEY (player_uuid, blueprint_id)
                    )
                    """
            )) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    """
                    CREATE INDEX IF NOT EXISTS idx_extraction_blueprint_discovery_player
                        ON extraction_blueprint_discovery (player_uuid)
                    """
            )) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    """
                    CREATE TABLE IF NOT EXISTS extraction_crafting_material_balances (
                        player_uuid UUID NOT NULL REFERENCES extraction_player_profiles(player_uuid) ON DELETE CASCADE,
                        material_id VARCHAR(64) NOT NULL,
                        amount INT NOT NULL DEFAULT 0 CHECK (amount >= 0),
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                        PRIMARY KEY (player_uuid, material_id)
                    )
                    """
            )) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    """
                    CREATE INDEX IF NOT EXISTS idx_extraction_crafting_materials_player
                        ON extraction_crafting_material_balances (player_uuid)
                    """
            )) {
                ps.executeUpdate();
            }
            ready = true;
        });
    }

    public boolean isReady() {
        return ready;
    }

    public Set<String> loadDiscoveredBlueprints(UUID playerId) {
        if (!ready || playerId == null) {
            return Set.of();
        }
        return supplySync("extraction.crafting.loadBlueprints", connection -> {
            ensureProfileSync(connection, playerId);
            Set<String> ids = new HashSet<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT blueprint_id FROM extraction_blueprint_discovery WHERE player_uuid = ?"
            )) {
                ps.setObject(1, playerId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getString("blueprint_id"));
                    }
                }
            }
            return ids;
        });
    }

    public void insertDiscoveredBlueprints(UUID playerId, Collection<String> blueprintIds) {
        if (!ready || playerId == null || blueprintIds == null || blueprintIds.isEmpty()) {
            return;
        }
        runSync("extraction.crafting.insertBlueprints", connection -> {
            ensureProfileSync(connection, playerId);
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO extraction_blueprint_discovery (player_uuid, blueprint_id) VALUES (?, ?) "
                            + "ON CONFLICT (player_uuid, blueprint_id) DO NOTHING"
            )) {
                for (String blueprintId : blueprintIds) {
                    if (blueprintId == null || blueprintId.isBlank()) {
                        continue;
                    }
                    ps.setObject(1, playerId);
                    ps.setString(2, blueprintId.trim());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    public void insertDiscoveredBlueprint(UUID playerId, String blueprintId) {
        if (blueprintId == null || blueprintId.isBlank()) {
            return;
        }
        insertDiscoveredBlueprints(playerId, Set.of(blueprintId.trim()));
    }

    public Map<String, Integer> loadMaterialBalances(UUID playerId) {
        if (!ready || playerId == null) {
            return Map.of();
        }
        return supplySync("extraction.crafting.loadMaterials", connection -> {
            ensureProfileSync(connection, playerId);
            Map<String, Integer> balances = new HashMap<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT material_id, amount FROM extraction_crafting_material_balances WHERE player_uuid = ?"
            )) {
                ps.setObject(1, playerId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        balances.put(rs.getString("material_id"), rs.getInt("amount"));
                    }
                }
            }
            return balances;
        });
    }

    public void grantMaterial(UUID playerId, String materialId, int amount) {
        if (!ready || playerId == null || materialId == null || materialId.isBlank() || amount <= 0) {
            return;
        }
        runSync("extraction.crafting.grantMaterial", connection -> {
            ensureProfileSync(connection, playerId);
            try (PreparedStatement ps = connection.prepareStatement(
                    """
                    INSERT INTO extraction_crafting_material_balances (player_uuid, material_id, amount, updated_at)
                    VALUES (?, ?, ?, NOW())
                    ON CONFLICT (player_uuid, material_id) DO UPDATE
                    SET amount = extraction_crafting_material_balances.amount + EXCLUDED.amount,
                        updated_at = NOW()
                    """
            )) {
                ps.setObject(1, playerId);
                ps.setString(2, materialId.trim());
                ps.setInt(3, amount);
                ps.executeUpdate();
            }
        });
    }

    public boolean trySpendMaterials(UUID playerId, Map<String, Integer> costs) {
        if (!ready || playerId == null || costs == null || costs.isEmpty()) {
            return true;
        }
        return supplySync("extraction.crafting.trySpendMaterials", connection -> {
            ensureProfileSync(connection, playerId);
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                Map<String, Integer> current = loadBalancesSync(connection, playerId);
                for (Map.Entry<String, Integer> entry : costs.entrySet()) {
                    if (current.getOrDefault(entry.getKey(), 0) < entry.getValue()) {
                        connection.rollback();
                        return false;
                    }
                }
                for (Map.Entry<String, Integer> entry : costs.entrySet()) {
                    String materialId = entry.getKey();
                    int spend = entry.getValue();
                    try (PreparedStatement ps = connection.prepareStatement(
                            """
                            UPDATE extraction_crafting_material_balances
                            SET amount = amount - ?, updated_at = NOW()
                            WHERE player_uuid = ? AND material_id = ? AND amount >= ?
                            """
                    )) {
                        ps.setInt(1, spend);
                        ps.setObject(2, playerId);
                        ps.setString(3, materialId);
                        ps.setInt(4, spend);
                        if (ps.executeUpdate() <= 0) {
                            connection.rollback();
                            return false;
                        }
                    }
                    try (PreparedStatement ps = connection.prepareStatement(
                            "DELETE FROM extraction_crafting_material_balances "
                                    + "WHERE player_uuid = ? AND material_id = ? AND amount <= 0"
                    )) {
                        ps.setObject(1, playerId);
                        ps.setString(2, materialId);
                        ps.executeUpdate();
                    }
                }
                connection.commit();
                return true;
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        });
    }

    private static Map<String, Integer> loadBalancesSync(Connection connection, UUID playerId) throws Exception {
        Map<String, Integer> balances = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT material_id, amount FROM extraction_crafting_material_balances WHERE player_uuid = ?"
        )) {
            ps.setObject(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    balances.put(rs.getString("material_id"), rs.getInt("amount"));
                }
            }
        }
        return balances;
    }

    private static void ensureProfileSync(Connection connection, UUID playerId) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO extraction_player_profiles (player_uuid, revision, updated_at) VALUES (?, 0, NOW()) "
                        + "ON CONFLICT (player_uuid) DO NOTHING"
        )) {
            ps.setObject(1, playerId);
            ps.executeUpdate();
        }
    }

    private void runSync(String label, AsyncDbExecutor.SqlConsumer consumer) {
        try {
            asyncDbExecutor.method_244(label, consumer).get();
        } catch (Exception exception) {
            logger.warning("[ExtractionCrafting] " + label + " failed: " + exception.getMessage());
            throw new RuntimeException(exception);
        }
    }

    private <T> T supplySync(String label, AsyncDbExecutor.SqlSupplier<T> supplier) {
        try {
            return asyncDbExecutor.supply(label, supplier).get();
        } catch (Exception exception) {
            logger.warning("[ExtractionCrafting] " + label + " failed: " + exception.getMessage());
            throw new RuntimeException(exception);
        }
    }
}
