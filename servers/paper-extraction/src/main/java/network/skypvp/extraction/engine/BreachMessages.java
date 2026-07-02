package network.skypvp.extraction.engine;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.extraction.text.ExtractionTexts;
import network.skypvp.shared.ServerTextUtil;
import org.bukkit.entity.Player;

public final class BreachMessages {

    private BreachMessages() {
    }

    public static void errorKey(Player player, String catalogKey, Object... args) {
        sendStyledKey(player, ServerTextUtil.miniMessageComponent("<#FF5555>[!] <reset>"), catalogKey, args);
    }

    public static void successKey(Player player, String catalogKey, Object... args) {
        sendStyledKey(player, ServerTextUtil.miniMessageComponent("<#55FF55>✓ <reset>"), catalogKey, args);
    }

    public static void infoKey(Player player, String catalogKey, Object... args) {
        sendStyledKey(player, ServerTextUtil.miniMessageComponent("<#FFD700>➤ <reset>"), catalogKey, args);
    }

    public static void infoHighlightKey(
            Player player,
            String prefixKey,
            String highlight,
            String suffixKey,
            Object... suffixArgs
    ) {
        Component message = ServerTextUtil.miniMessageComponent("<#FFD700>➤ <reset>")
                .append(ExtractionTexts.miniMessage(player, prefixKey));
        if (highlight != null && !highlight.isBlank()) {
            message = message.append(Component.text(highlight, NamedTextColor.WHITE));
        }
        if (suffixKey != null && !suffixKey.isBlank()) {
            message = message.append(ExtractionTexts.miniMessage(player, suffixKey, suffixArgs));
        }
        player.sendMessage(message);
    }

    private static void sendStyledKey(Player player, Component prefix, String catalogKey, Object... args) {
        player.sendMessage(prefix.append(ExtractionTexts.miniMessage(player, catalogKey, args)));
    }
}
