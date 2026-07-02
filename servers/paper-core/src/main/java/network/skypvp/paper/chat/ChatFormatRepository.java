package network.skypvp.paper.chat;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import network.skypvp.paper.database.AsyncDbExecutor;
import network.skypvp.shared.chat.ChatFormatFlags;
import network.skypvp.shared.chat.ChatFormatJsonCodec;
import network.skypvp.shared.chat.ChatFormatProfile;
import network.skypvp.shared.chat.ChatFormatScope;

public final class ChatFormatRepository {
    private static final Gson GSON = new Gson();
    private final AsyncDbExecutor asyncDbExecutor;

    public ChatFormatRepository(AsyncDbExecutor asyncDbExecutor) {
        this.asyncDbExecutor = Objects.requireNonNull(asyncDbExecutor, "asyncDbExecutor");
    }

    public CompletableFuture<List<ChatFormatProfile>> loadAll() {
        return this.asyncDbExecutor.supply("chatFormats.loadAll", connection -> {
            List<ChatFormatProfile> profiles = new ArrayList<>();
            String sql = "SELECT format_id, scope, priority, flags_json FROM network_chat_formats ORDER BY priority DESC, format_id ASC";
            try (PreparedStatement ps = connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    profiles.add(mapRow(rs));
                }
            }
            return profiles;
        });
    }

    public CompletableFuture<Void> upsert(ChatFormatProfile profile) {
        return this.asyncDbExecutor.method_244("chatFormats.upsert", connection -> {
            String sql = """
                    INSERT INTO network_chat_formats (format_id, scope, priority, flags_json, updated_at)
                    VALUES (?, ?, ?, ?::jsonb, NOW())
                    ON CONFLICT (format_id) DO UPDATE SET
                        scope = EXCLUDED.scope,
                        priority = EXCLUDED.priority,
                        flags_json = EXCLUDED.flags_json,
                        updated_at = NOW()
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, profile.id().toLowerCase());
                ps.setString(2, profile.scope().name());
                ps.setInt(3, profile.flags().priority());
                ps.setString(4, GSON.toJson(flagsToJson(profile.flags())));
                ps.executeUpdate();
            }
        });
    }

    public CompletableFuture<Boolean> remove(String formatId) {
        return this.asyncDbExecutor.supply("chatFormats.remove", connection -> {
            String sql = "DELETE FROM network_chat_formats WHERE format_id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, formatId.toLowerCase());
                return ps.executeUpdate() > 0;
            }
        });
    }

    private static ChatFormatProfile mapRow(ResultSet rs) throws java.sql.SQLException {
        String id = rs.getString("format_id");
        ChatFormatScope scope = ChatFormatScope.fromString(rs.getString("scope"));
        int priority = rs.getInt("priority");
        String flagsJson = rs.getString("flags_json");
        ChatFormatFlags flags = ChatFormatJsonCodec.jsonToFlags(flagsJson);
        if (priority != 0) {
            flags = new ChatFormatFlags(
                    priority,
                    flags.prefix(),
                    flags.nameColor(),
                    flags.name(),
                    flags.suffix(),
                    flags.chatColor(),
                    flags.channelTooltip(),
                    flags.prefixTooltip(),
                    flags.nameTooltip(),
                    flags.suffixTooltip(),
                    flags.prefixClickCommand(),
                    flags.nameClickCommand(),
                    flags.suffixClickCommand()
            );
        }
        return new ChatFormatProfile(id, scope, flags);
    }

    static JsonObject flagsToJson(ChatFormatFlags flags) {
        JsonObject json = new JsonObject();
        json.addProperty("priority", flags.priority());
        json.addProperty("prefix", flags.prefix());
        json.addProperty("name_color", flags.nameColor());
        json.addProperty("name", flags.name());
        json.addProperty("suffix", flags.suffix());
        json.addProperty("chat_color", flags.chatColor());
        json.addProperty("channel_tooltip", flags.channelTooltip());
        json.addProperty("prefix_tooltip", flags.prefixTooltip());
        json.addProperty("name_tooltip", flags.nameTooltip());
        json.addProperty("suffix_tooltip", flags.suffixTooltip());
        json.addProperty("prefix_click_command", flags.prefixClickCommand());
        json.addProperty("name_click_command", flags.nameClickCommand());
        json.addProperty("suffix_click_command", flags.suffixClickCommand());
        return json;
    }
}
