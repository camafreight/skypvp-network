package network.skypvp.proxy.chat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import network.skypvp.core.database.DatabaseManager;
import network.skypvp.shared.chat.ChatFormatFlags;
import network.skypvp.shared.chat.ChatFormatJsonCodec;
import network.skypvp.shared.chat.ChatFormatProfile;
import network.skypvp.shared.chat.ChatFormatScope;
import org.slf4j.Logger;

public final class ProxyChatFormatRepository {
    private final DatabaseManager dataSource;
    private final Logger logger;

    public ProxyChatFormatRepository(DatabaseManager dataSource, Logger logger) {
        this.dataSource = dataSource;
        this.logger = logger;
    }

    public List<ChatFormatProfile> loadAll() {
        if (this.dataSource == null) {
            return List.of();
        }
        String sql = "SELECT format_id, scope, priority, flags_json FROM network_chat_formats ORDER BY priority DESC, format_id ASC";
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            List<ChatFormatProfile> profiles = new ArrayList<>();
            while (rs.next()) {
                profiles.add(mapRow(rs));
            }
            return profiles;
        } catch (SQLException ex) {
            this.logger.warn("ProxyChatFormatRepository.loadAll: {}", ex.getMessage());
            return List.of();
        }
    }

    private static ChatFormatProfile mapRow(ResultSet rs) throws SQLException {
        String id = rs.getString("format_id");
        ChatFormatScope scope = ChatFormatScope.fromString(rs.getString("scope"));
        int priority = rs.getInt("priority");
        ChatFormatFlags flags = ChatFormatJsonCodec.jsonToFlags(rs.getString("flags_json"));
        if (priority != 0 && flags.priority() == 0) {
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
}
