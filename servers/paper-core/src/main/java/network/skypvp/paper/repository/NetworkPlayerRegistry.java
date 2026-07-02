package network.skypvp.paper.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

public final class NetworkPlayerRegistry {

    private static final String ENSURE_PLAYER = """
            INSERT INTO network_players (player_id, last_username, first_seen_at, last_seen_at)
            VALUES (?, ?, NOW(), NOW())
            ON CONFLICT (player_id) DO UPDATE SET
                last_username = EXCLUDED.last_username,
                last_seen_at = NOW()
            """;

    private NetworkPlayerRegistry() {
    }

    public static void ensurePlayerExists(Connection connection, UUID playerId, String username) throws SQLException {
        if (connection == null || playerId == null) {
            return;
        }
        String safeUsername = sanitizeUsername(username, playerId);
        try (PreparedStatement statement = connection.prepareStatement(ENSURE_PLAYER)) {
            statement.setObject(1, playerId);
            statement.setString(2, safeUsername);
            statement.executeUpdate();
        }
    }

    private static String sanitizeUsername(String username, UUID playerId) {
        if (username != null && !username.isBlank()) {
            String trimmed = username.trim();
            return trimmed.length() > 16 ? trimmed.substring(0, 16) : trimmed;
        }
        String fallback = playerId.toString().replace("-", "");
        return fallback.length() > 16 ? fallback.substring(0, 16) : fallback;
    }
}
