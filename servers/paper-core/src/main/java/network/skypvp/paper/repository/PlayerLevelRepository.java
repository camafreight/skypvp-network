package network.skypvp.paper.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import network.skypvp.paper.database.AsyncDbExecutor;

/**
 * Persistence for the network player level system (XP, prestige). Servers boot from ephemeral
 * Docker images, so progression must live in Postgres — never in plugin folders.
 */
public final class PlayerLevelRepository {

    /** Row snapshot: lifetime XP within the current prestige, and completed prestige count. */
    public record LevelRow(long xp, int prestige) {
    }

    private final AsyncDbExecutor asyncDbExecutor;

    public PlayerLevelRepository(AsyncDbExecutor asyncDbExecutor) {
        this.asyncDbExecutor = Objects.requireNonNull(asyncDbExecutor, "asyncDbExecutor");
    }

    public CompletableFuture<Void> ensureSchema() {
        return this.asyncDbExecutor.method_244(
                "levels.ensureSchema",
                connection -> {
                    try (PreparedStatement ps = connection.prepareStatement(
                            "CREATE TABLE IF NOT EXISTS player_levels ("
                                    + "player_uuid UUID PRIMARY KEY, "
                                    + "xp BIGINT NOT NULL DEFAULT 0, "
                                    + "prestige INT NOT NULL DEFAULT 0, "
                                    + "updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW())"
                    )) {
                        ps.executeUpdate();
                    }
                }
        );
    }

    public CompletableFuture<LevelRow> load(UUID playerId) {
        return this.asyncDbExecutor.supply(
                "levels.load",
                connection -> {
                    try (PreparedStatement ps = connection.prepareStatement(
                            "SELECT xp, prestige FROM player_levels WHERE player_uuid = ?"
                    )) {
                        ps.setObject(1, playerId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                return new LevelRow(Math.max(0L, rs.getLong("xp")), Math.max(0, rs.getInt("prestige")));
                            }
                        }
                    }
                    return new LevelRow(0L, 0);
                }
        );
    }

    public CompletableFuture<Void> save(UUID playerId, long xp, int prestige) {
        return this.asyncDbExecutor.method_244(
                "levels.save",
                connection -> {
                    try (PreparedStatement ps = connection.prepareStatement(
                            "INSERT INTO player_levels (player_uuid, xp, prestige, updated_at) VALUES (?, ?, ?, NOW()) "
                                    + "ON CONFLICT (player_uuid) DO UPDATE SET "
                                    + "xp = EXCLUDED.xp, prestige = EXCLUDED.prestige, updated_at = NOW()"
                    )) {
                        ps.setObject(1, playerId);
                        ps.setLong(2, Math.max(0L, xp));
                        ps.setInt(3, Math.max(0, prestige));
                        ps.executeUpdate();
                    }
                }
        );
    }
}
