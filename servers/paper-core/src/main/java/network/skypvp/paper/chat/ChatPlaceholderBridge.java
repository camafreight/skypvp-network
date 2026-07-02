package network.skypvp.paper.chat;

import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.integration.SkyPvPPlaceholderSupport;
import network.skypvp.shared.chat.ChatRenderContext;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class ChatPlaceholderBridge {
    private ChatPlaceholderBridge() {
    }

    public static String apply(Player player, String input) {
        return apply(player, input, ChatRenderContextHolder.current());
    }

    public static String apply(Player player, String input, ChatRenderContext context) {
        return apply(player, input, context, 0);
    }

    public static String apply(Player player, String input, ChatRenderContext context, int depth) {
        if (input == null) {
            return "";
        }
        if (depth > 6) {
            return input;
        }
        String resolved = applyStatic(input, context, player == null ? null : player.getName());
        PaperCorePlugin plugin = paperPlugin();
        if (plugin != null && player != null) {
            resolved = SkyPvPPlaceholderSupport.replacePlaceholders(plugin, player, resolved);
        }
        if (player != null && Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            resolved = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, resolved);
            if (plugin != null) {
                resolved = SkyPvPPlaceholderSupport.replacePlaceholders(plugin, player, resolved);
            }
        }
        return resolved;
    }

    public static String apply(String input, ChatRenderContext context) {
        return applyStatic(input, context, context == null ? null : context.playerName());
    }

    private static PaperCorePlugin paperPlugin() {
        var plugin = Bukkit.getPluginManager().getPlugin("SkyPvPCore");
        return plugin instanceof PaperCorePlugin paper ? paper : null;
    }

    private static String applyStatic(String input, ChatRenderContext context, String playerName) {
        ChatRenderContext effective = context == null
                ? new ChatRenderContext(playerName == null ? "Player" : playerName, "", "", "")
                : context;
        String name = playerName == null ? effective.playerName() : playerName;
        return input
                .replace("%player_name%", name)
                .replace("%player_uuid%", "")
                .replace("%message%", effective.message())
                .replace("%channel%", effective.channelLabel())
                .replace("%target_name%", effective.targetName());
    }
}
