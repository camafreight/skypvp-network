package network.skypvp.paper.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import network.skypvp.paper.database.AsyncDbExecutor;
import network.skypvp.shared.PunishmentRecord;
import network.skypvp.shared.chat.ChatModerationEscalation;

public final class PaperPunishmentRepository {
    private final AsyncDbExecutor asyncDbExecutor;

    public PaperPunishmentRepository(AsyncDbExecutor asyncDbExecutor) {
        this.asyncDbExecutor = Objects.requireNonNull(asyncDbExecutor, "asyncDbExecutor");
    }

    public CompletableFuture<Optional<PunishmentRecord>> findActiveMute(UUID playerId) {
        return findActive(playerId, PunishmentRecord.PunishmentType.MUTE);
    }

    public CompletableFuture<Optional<PunishmentRecord>> findActive(UUID playerId, PunishmentRecord.PunishmentType type) {
        if (playerId == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return this.asyncDbExecutor.supply("punishments.findActive", connection -> {
            String sql = """
                    SELECT id, player_uuid, player_name, type, reason, issued_by, issued_at, expires_at, pardoned, pardoned_by, pardoned_at
                    FROM network_punishments
                    WHERE player_uuid = ? AND type = ? AND pardoned = FALSE
                      AND (expires_at IS NULL OR expires_at > NOW())
                    ORDER BY issued_at DESC LIMIT 1
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setObject(1, playerId);
                ps.setString(2, type.name());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(mapRow(rs));
                }
            }
        });
    }

    public CompletableFuture<Void> issue(
            UUID playerUuid,
            String playerName,
            PunishmentRecord.PunishmentType type,
            String reason,
            String issuedBy,
            Instant expiresAt
    ) {
        return this.asyncDbExecutor.method_244("punishments.issue", connection -> {
            String sql = """
                    INSERT INTO network_punishments (player_uuid, player_name, type, reason, issued_by, issued_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, NOW(), ?)
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setObject(1, playerUuid);
                ps.setString(2, playerName);
                ps.setString(3, type.name());
                ps.setString(4, reason);
                ps.setString(5, issuedBy);
                ps.setTimestamp(6, expiresAt == null ? null : Timestamp.from(expiresAt));
                ps.executeUpdate();
            }
        });
    }

    public CompletableFuture<Integer> countActiveWarnings(UUID playerId, String issuedBy) {
        if (playerId == null) {
            return CompletableFuture.completedFuture(0);
        }
        String issuer = issuedBy == null || issuedBy.isBlank() ? ChatModerationEscalation.ISSUER : issuedBy;
        return this.asyncDbExecutor.supply("punishments.countActiveWarnings", connection -> {
            String sql = """
                    SELECT COUNT(*)
                    FROM network_punishments
                    WHERE player_uuid = ? AND type = 'WARN' AND issued_by = ?
                      AND pardoned = FALSE AND (expires_at IS NULL OR expires_at > NOW())
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setObject(1, playerId);
                ps.setString(2, issuer);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    private static PunishmentRecord mapRow(ResultSet rs) throws java.sql.SQLException {
        return new PunishmentRecord(
                rs.getLong("id"),
                rs.getObject("player_uuid", UUID.class),
                rs.getString("player_name"),
                PunishmentRecord.PunishmentType.valueOf(rs.getString("type")),
                rs.getString("reason"),
                rs.getString("issued_by"),
                rs.getTimestamp("issued_at").toInstant(),
                rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant(),
                rs.getBoolean("pardoned"),
                rs.getString("pardoned_by"),
                rs.getTimestamp("pardoned_at") == null ? null : rs.getTimestamp("pardoned_at").toInstant()
        );
    }
}
