package network.skypvp.proxy.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.core.database.DatabaseManager;
import org.slf4j.Logger;

public final class PlayerSocialSettingsRepository {
    private final DatabaseManager dataSource;
    private final Logger logger;
    private final ConcurrentHashMap<UUID, PlayerSocialSettingsSnapshot> cache = new ConcurrentHashMap<>();

    public PlayerSocialSettingsRepository(DatabaseManager dataSource, Logger logger) {
        this.dataSource = dataSource;
        this.logger = logger;
    }

    public void preload(UUID playerId) {
        if (playerId == null || this.dataSource == null) {
            return;
        }
        CompletableFuture.runAsync(() -> this.cache.put(playerId, this.load(playerId)));
    }

    public void evict(UUID playerId) {
        if (playerId != null) {
            this.cache.remove(playerId);
        }
    }

    public void refresh(UUID playerId) {
        if (playerId == null || this.dataSource == null) {
            return;
        }
        this.cache.put(playerId, this.load(playerId));
    }

    public boolean isChatEnabled(UUID playerId) {
        return this.snapshot(playerId).chatEnabled();
    }

    public boolean blocksFriendRequests(UUID playerId) {
        return this.snapshot(playerId).blockFriendRequests();
    }

    public boolean blocksPartyRequests(UUID playerId) {
        return this.snapshot(playerId).blockPartyRequests();
    }

    public boolean isAutoTranslateEnabled(UUID playerId) {
        return this.snapshot(playerId).autoTranslateEnabled();
    }

    public boolean isLoaded(UUID playerId) {
        return playerId != null && this.cache.containsKey(playerId);
    }

    private PlayerSocialSettingsSnapshot snapshot(UUID playerId) {
        if (playerId == null) {
            return PlayerSocialSettingsSnapshot.defaults();
        }
        return this.cache.computeIfAbsent(playerId, this::load);
    }

    private PlayerSocialSettingsSnapshot load(UUID playerId) {
        if (this.dataSource == null) {
            return PlayerSocialSettingsSnapshot.defaults();
        }
        String sql = """
                SELECT chat_enabled, block_friend_requests, block_party_requests,
                       profanity_filter_enabled, auto_translate_enabled
                FROM network_player_social_settings
                WHERE player_uuid = ?
                """;
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, playerId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return new PlayerSocialSettingsSnapshot(
                            rs.getBoolean("chat_enabled"),
                            rs.getBoolean("block_friend_requests"),
                            rs.getBoolean("block_party_requests"),
                            rs.getBoolean("profanity_filter_enabled"),
                            rs.getBoolean("auto_translate_enabled")
                    );
                }
            }
        } catch (SQLException exception) {
            this.logger.warn("PlayerSocialSettingsRepository.load: {}", exception.getMessage());
        }
        return PlayerSocialSettingsSnapshot.defaults();
    }

    public record PlayerSocialSettingsSnapshot(
            boolean chatEnabled,
            boolean blockFriendRequests,
            boolean blockPartyRequests,
            boolean profanityFilterEnabled,
            boolean autoTranslateEnabled
    ) {
        public static PlayerSocialSettingsSnapshot defaults() {
            return new PlayerSocialSettingsSnapshot(true, false, false, true, false);
        }
    }
}
