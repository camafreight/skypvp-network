package network.skypvp.paper.chat;

import java.util.Locale;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.service.PlayerSocialSettingsService;
import network.skypvp.shared.chat.ChatChannel;
import network.skypvp.shared.chat.ChatFormatFlags;
import network.skypvp.shared.chat.ChatRenderContext;
import org.bukkit.entity.Player;

public final class ChatPlaceholderResolver {
    private ChatPlaceholderResolver() {
    }

    public static String resolve(PaperCorePlugin plugin, Player player, String params) {
        if (params == null || params.isBlank() || player == null) {
            return "";
        }

        String key = params.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        ChatRenderContext renderContext = ChatRenderContextHolder.current();
        ChatFormatFlags flags = formatFlags(plugin, player);

        return switch (key) {
            case "channel", "channel_id" -> activeChannel(plugin, player).name();
            case "channel_name", "channel_label" -> activeChannel(plugin, player).displayName();
            case "global_enabled", "chat_enabled" -> bool(plugin, player, "global_enabled");
            case "profanity_filter", "profanity_filter_enabled" -> bool(plugin, player, "profanity_filter");
            case "message" -> renderContext == null ? "" : renderContext.message();
            case "target_name", "target" -> renderContext == null ? "" : renderContext.targetName();
            case "format_priority" -> Integer.toString(flags.priority());
            case "format_prefix" -> resolveFormatField(player, flags.prefix(), renderContext);
            case "format_suffix" -> resolveFormatField(player, flags.suffix(), renderContext);
            case "format_name" -> resolveFormatField(player, flags.name(), renderContext);
            case "format_name_color" -> resolveFormatColor(player, flags.nameColor(), renderContext);
            case "format_chat_color" -> resolveFormatColor(player, flags.chatColor(), renderContext);
            case "format_prefix_tooltip" -> resolveFormatField(player, flags.prefixTooltip(), renderContext);
            case "format_name_tooltip" -> resolveFormatField(player, flags.nameTooltip(), renderContext);
            case "format_suffix_tooltip" -> resolveFormatField(player, flags.suffixTooltip(), renderContext);
            default -> "";
        };
    }

    private static ChatChannel activeChannel(PaperCorePlugin plugin, Player player) {
        PlayerSocialSettingsService settings = plugin.playerSocialSettingsService();
        if (settings == null) {
            return ChatChannel.ALL;
        }
        return settings.activeChatChannel(player.getUniqueId());
    }

    private static ChatFormatFlags formatFlags(PaperCorePlugin plugin, Player player) {
        ChatFormatService formats = plugin.chatFormatService();
        if (formats == null) {
            return ChatFormatFlags.EMPTY;
        }
        return formats.resolveRankFormat(player);
    }

    private static String resolveFormatField(Player player, String template, ChatRenderContext renderContext, int depth) {
        if (template == null || template.isBlank() || depth > 6) {
            return "";
        }
        ChatRenderContext context = effectiveContext(player, renderContext);
        return ChatPlaceholderBridge.apply(player, template, context, depth + 1);
    }

    private static String resolveFormatField(Player player, String template, ChatRenderContext renderContext) {
        return resolveFormatField(player, template, renderContext, 0);
    }

    private static String resolveFormatColor(Player player, String template, ChatRenderContext renderContext) {
        String resolved = resolveFormatField(player, template, renderContext).trim();
        if (resolved.isBlank()) {
            return "white";
        }
        if (resolved.startsWith("<") && resolved.endsWith(">")) {
            resolved = resolved.substring(1, resolved.length() - 1).trim();
        }
        return resolved.isBlank() ? "white" : resolved;
    }

    private static ChatRenderContext effectiveContext(Player player, ChatRenderContext renderContext) {
        if (renderContext != null) {
            return renderContext;
        }
        return new ChatRenderContext(
                player.getName(),
                "",
                ChatChannel.ALL.displayName()
        );
    }

    private static String bool(PaperCorePlugin plugin, Player player, String type) {
        PlayerSocialSettingsService settings = plugin.playerSocialSettingsService();
        if (settings == null) {
            return "true";
        }
        boolean value = switch (type) {
            case "profanity_filter" -> settings.isProfanityFilterEnabled(player.getUniqueId());
            default -> settings.isChatEnabled(player.getUniqueId());
        };
        return Boolean.toString(value);
    }
}
