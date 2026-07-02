package network.skypvp.paper.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import network.skypvp.paper.database.AsyncDbExecutor;
import network.skypvp.paper.model.PlayerSocialSettings;
import network.skypvp.shared.chat.ChatChannel;

public final class PlayerSocialSettingsRepository {
    private final AsyncDbExecutor asyncDbExecutor;

    public PlayerSocialSettingsRepository(AsyncDbExecutor asyncDbExecutor) {
        this.asyncDbExecutor = Objects.requireNonNull(asyncDbExecutor, "asyncDbExecutor");
    }

    public CompletableFuture<PlayerSocialSettings> load(UUID playerId) {
        if (playerId == null) {
            return CompletableFuture.completedFuture(PlayerSocialSettings.defaults(UUID.randomUUID()));
        }
        return this.asyncDbExecutor.supply(
                "socialSettings.load",
                connection -> {
                    String sql = """
                            SELECT chat_enabled, block_friend_requests, block_party_requests,
                                   profanity_filter_enabled, auto_translate_enabled, active_chat_channel
                            FROM network_player_social_settings
                            WHERE player_uuid = ?
                            """;
                    try (PreparedStatement ps = connection.prepareStatement(sql)) {
                        ps.setObject(1, playerId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                return new PlayerSocialSettings(
                                        playerId,
                                        rs.getBoolean("chat_enabled"),
                                        rs.getBoolean("block_friend_requests"),
                                        rs.getBoolean("block_party_requests"),
                                        rs.getBoolean("profanity_filter_enabled"),
                                        rs.getBoolean("auto_translate_enabled"),
                                        ChatChannel.fromString(rs.getString("active_chat_channel"))
                                );
                            }
                        }
                    }
                    return PlayerSocialSettings.defaults(playerId);
                }
        );
    }

    public CompletableFuture<Void> save(PlayerSocialSettings settings, String username) {
        if (settings == null || settings.playerId() == null) {
            return CompletableFuture.completedFuture(null);
        }
        return this.asyncDbExecutor.method_244(
                "socialSettings.save",
                connection -> {
                    NetworkPlayerRegistry.ensurePlayerExists(connection, settings.playerId(), username);
                    String sql = """
                            INSERT INTO network_player_social_settings (
                                player_uuid, chat_enabled, block_friend_requests, block_party_requests,
                                profanity_filter_enabled, auto_translate_enabled, active_chat_channel, updated_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, NOW())
                            ON CONFLICT (player_uuid) DO UPDATE SET
                                chat_enabled = EXCLUDED.chat_enabled,
                                block_friend_requests = EXCLUDED.block_friend_requests,
                                block_party_requests = EXCLUDED.block_party_requests,
                                profanity_filter_enabled = EXCLUDED.profanity_filter_enabled,
                                auto_translate_enabled = EXCLUDED.auto_translate_enabled,
                                active_chat_channel = EXCLUDED.active_chat_channel,
                                updated_at = NOW()
                            """;
                    try (PreparedStatement ps = connection.prepareStatement(sql)) {
                        ps.setObject(1, settings.playerId());
                        ps.setBoolean(2, settings.chatEnabled());
                        ps.setBoolean(3, settings.blockFriendRequests());
                        ps.setBoolean(4, settings.blockPartyRequests());
                        ps.setBoolean(5, settings.profanityFilterEnabled());
                        ps.setBoolean(6, settings.autoTranslateEnabled());
                        ps.setString(7, settings.activeChatChannel().name());
                        ps.executeUpdate();
                    }
                }
        );
    }
}
