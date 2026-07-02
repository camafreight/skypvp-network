package network.skypvp.paper.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import network.skypvp.shared.chat.ChatChannel;
import org.bukkit.entity.Player;

public final class ChatChannelAccess {
    private ChatChannelAccess() {
    }

    public static List<ChatChannel> allowedChannels(Player player) {
        List<ChatChannel> channels = new ArrayList<>();
        channels.add(ChatChannel.ALL);
        for (ChatChannel channel : ChatChannel.values()) {
            if (channel == ChatChannel.ALL) {
                continue;
            }
            if (ChatPermissionChecks.has(player, channel.permission())) {
                channels.add(channel);
            }
        }
        return channels;
    }

    public static ChatChannel nextChannel(Player player, ChatChannel current) {
        List<ChatChannel> allowed = allowedChannels(player);
        if (allowed.isEmpty()) {
            return ChatChannel.ALL;
        }
        int index = allowed.indexOf(current);
        if (index < 0) {
            return allowed.getFirst();
        }
        return allowed.get((index + 1) % allowed.size());
    }

    public static boolean canUseTabComplete(Player player) {
        return ChatPermissionChecks.canUseTabComplete(player);
    }

    public static String[] visibleSubcommands(Player player) {
        return ChatCommandHelp.visibleSubcommands(player);
    }

    public static String channelLabel(ChatChannel channel) {
        return channel == null ? ChatChannel.ALL.displayName() : channel.displayName();
    }
}
