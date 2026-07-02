package network.skypvp.paper.integration;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.chat.ChatPlaceholderResolver;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * PlaceholderAPI expansion for the chat core system.
 * <p>
 * Examples: {@code %skypvpchat_channel_name%}, {@code %skypvpchat_format_prefix%}, {@code %skypvpchat_message%}
 */
public final class SkyPvPChatPlaceholderExpansion extends PlaceholderExpansion {
    private final PaperCorePlugin plugin;

    public SkyPvPChatPlaceholderExpansion(PaperCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "skypvpchat";
    }

    @Override
    public @NotNull String getAuthor() {
        return "SkyPvP";
    }

    @Override
    public @NotNull String getVersion() {
        return this.plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer offlinePlayer, @NotNull String params) {
        if (!(offlinePlayer instanceof Player player) || !player.isOnline()) {
            return "";
        }
        return ChatPlaceholderResolver.resolve(this.plugin, player, params);
    }
}
