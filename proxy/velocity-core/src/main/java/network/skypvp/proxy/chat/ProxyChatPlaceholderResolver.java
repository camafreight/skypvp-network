package network.skypvp.proxy.chat;

import com.velocitypowered.api.proxy.Player;
import java.util.Locale;
import network.skypvp.shared.chat.ChatFormatFlags;
import network.skypvp.shared.chat.ChatRenderContext;

final class ProxyChatPlaceholderResolver {
    private static final int MAX_NESTING = 6;

    private ProxyChatPlaceholderResolver() {
    }

    static String resolve(
            ProxyChatFormatService formats,
            Player sender,
            String rankKeyFallback,
            String params,
            ChatRenderContext renderContext,
            int depth
    ) {
        if (params == null || params.isBlank() || depth > MAX_NESTING) {
            return "";
        }

        String key = params.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        ChatFormatFlags flags = formats.resolveRankFormat(sender, rankKeyFallback);
        ChatRenderContext context = renderContext == null
                ? new ChatRenderContext("Player", "", "")
                : renderContext;

        return switch (key) {
            case "channel", "channel_id" -> context.channelLabel().isBlank() ? "ALL" : context.channelLabel();
            case "channel_name", "channel_label" -> context.channelLabel();
            case "message" -> context.message();
            case "target_name", "target" -> context.targetName();
            case "format_priority" -> Integer.toString(flags.priority());
            case "format_prefix" -> resolveField(formats, sender, rankKeyFallback, flags.prefix(), context, depth + 1);
            case "format_suffix" -> resolveField(formats, sender, rankKeyFallback, flags.suffix(), context, depth + 1);
            case "format_name" -> resolveField(formats, sender, rankKeyFallback, flags.name(), context, depth + 1);
            case "format_name_color" -> resolveColor(formats, sender, rankKeyFallback, flags.nameColor(), context, depth + 1);
            case "format_chat_color" -> resolveColor(formats, sender, rankKeyFallback, flags.chatColor(), context, depth + 1);
            case "format_prefix_tooltip" -> resolveField(formats, sender, rankKeyFallback, flags.prefixTooltip(), context, depth + 1);
            case "format_name_tooltip" -> resolveField(formats, sender, rankKeyFallback, flags.nameTooltip(), context, depth + 1);
            case "format_suffix_tooltip" -> resolveField(formats, sender, rankKeyFallback, flags.suffixTooltip(), context, depth + 1);
            default -> "";
        };
    }

    private static String resolveField(
            ProxyChatFormatService formats,
            Player sender,
            String rankKeyFallback,
            String template,
            ChatRenderContext context,
            int depth
    ) {
        if (template == null || template.isBlank()) {
            return "";
        }
        return ProxyChatPlaceholderBridge.apply(template, context, sender, formats, rankKeyFallback, depth);
    }

    private static String resolveColor(
            ProxyChatFormatService formats,
            Player sender,
            String rankKeyFallback,
            String template,
            ChatRenderContext context,
            int depth
    ) {
        String resolved = resolveField(formats, sender, rankKeyFallback, template, context, depth).trim();
        if (resolved.isBlank()) {
            return "white";
        }
        if (resolved.startsWith("<") && resolved.endsWith(">")) {
            resolved = resolved.substring(1, resolved.length() - 1).trim();
        }
        return resolved.isBlank() ? "white" : resolved;
    }
}
