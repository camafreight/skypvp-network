package network.skypvp.shared.chat;

import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import network.skypvp.shared.ServerTextUtil;

/**
 * Renders formatted chat lines from {@link ChatFormatFlags} and resolved placeholder text.
 */
public final class ChatMessageRenderer {
    private ChatMessageRenderer() {
    }

    public static Component render(
            ChatFormatFlags flags,
            ChatRenderContext context,
            Function<String, String> placeholderResolver
    ) {
        if (flags == null) {
            flags = ChatFormatFlags.EMPTY;
        }
        if (context == null) {
            context = new ChatRenderContext("Player", "", "");
        }
        Function<String, String> resolver = placeholderResolver == null ? Function.identity() : placeholderResolver;

        String prefix = resolve(flags.prefix(), context, resolver);
        String name = resolve(flags.name().isBlank() ? context.playerName() : flags.name(), context, resolver);
        String suffix = resolve(flags.suffix(), context, resolver);
        String message = resolve(context.message(), context, resolver);
        String nameColor = resolveColor(flags.nameColor(), "white", context, resolver);
        String chatColor = resolveColor(flags.chatColor(), "white", context, resolver);

        Component line = Component.empty();
        if (!prefix.isBlank()) {
            line = line.append(segment(prefix, flags.prefixTooltip(), flags.prefixClickCommand(), resolver, context));
        }
        line = line.append(segment("<" + nameColor + ">" + name, flags.nameTooltip(), flags.nameClickCommand(), resolver, context));
        if (!suffix.isBlank()) {
            line = line.append(segment(suffix, flags.suffixTooltip(), flags.suffixClickCommand(), resolver, context));
        }
        line = line.append(ServerTextUtil.miniMessageComponent("<gray>: <" + chatColor + ">" + escapeMiniMessage(message)));
        if (!flags.channelTooltip().isBlank()) {
            line = line.hoverEvent(HoverEvent.showText(tooltipComponent(flags.channelTooltip(), resolver, context)));
        }
        return line;
    }

    /**
     * Renders prefix, name, and suffix for tab list / identity display (no message body).
     */
    public static Component renderIdentity(
            ChatFormatFlags flags,
            ChatRenderContext context,
            Function<String, String> placeholderResolver
    ) {
        if (flags == null) {
            flags = ChatFormatFlags.EMPTY;
        }
        if (context == null) {
            context = new ChatRenderContext("Player", "", "");
        }
        Function<String, String> resolver = placeholderResolver == null ? Function.identity() : placeholderResolver;

        String prefix = resolve(flags.prefix(), context, resolver);
        String name = resolve(flags.name().isBlank() ? context.playerName() : flags.name(), context, resolver);
        String suffix = resolve(flags.suffix(), context, resolver);
        String nameColor = resolveColor(flags.nameColor(), "white", context, resolver);

        Component line = Component.empty();
        if (!prefix.isBlank()) {
            line = line.append(segment(prefix, flags.prefixTooltip(), flags.prefixClickCommand(), resolver, context));
        }
        line = line.append(segment("<" + nameColor + ">" + name, flags.nameTooltip(), flags.nameClickCommand(), resolver, context));
        if (!suffix.isBlank()) {
            line = line.append(segment(suffix, flags.suffixTooltip(), flags.suffixClickCommand(), resolver, context));
        }
        return line;
    }

    private static Component segment(
            String miniMessage,
            String tooltip,
            String clickCommand,
            Function<String, String> resolver,
            ChatRenderContext context
    ) {
        Component component = ServerTextUtil.miniMessageComponent(resolve(miniMessage, context, resolver));
        if (!tooltip.isBlank()) {
            component = component.hoverEvent(HoverEvent.showText(tooltipComponent(tooltip, resolver, context)));
        }
        if (!clickCommand.isBlank()) {
            String command = resolve(clickCommand, context, resolver);
            component = component.clickEvent(ClickEvent.runCommand(command.startsWith("/") ? command : "/" + command));
        }
        return component;
    }

    private static Component tooltipComponent(String tooltip, Function<String, String> resolver, ChatRenderContext context) {
        String[] lines = tooltip.split("(?i)<br\\s*/?>");
        Component result = Component.empty();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                result = result.append(Component.newline());
            }
            result = result.append(ServerTextUtil.miniMessageComponent(resolve(lines[i], context, resolver)));
        }
        return result;
    }

    private static String resolve(String template, ChatRenderContext context, Function<String, String> resolver) {
        if (template == null) {
            return "";
        }
        String replaced = template
                .replace("%player_name%", context.playerName())
                .replace("%message%", context.message())
                .replace("%channel%", context.channelLabel())
                .replace("%target_name%", context.targetName());
        return resolver.apply(replaced);
    }

    /**
     * Resolves a MiniMessage color token (named color, hex, or placeholder) for use inside
     * {@code <color>text} wrappers. Unresolved placeholders fall back to {@code fallback}.
     */
    private static String resolveColor(
            String raw,
            String fallback,
            ChatRenderContext context,
            Function<String, String> resolver
    ) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String resolved = resolve(raw, context, resolver).trim();
        if (resolved.isBlank() || resolved.indexOf('%') >= 0) {
            return fallback;
        }
        if (resolved.startsWith("<") && resolved.endsWith(">")) {
            resolved = resolved.substring(1, resolved.length() - 1).trim();
        }
        return resolved.isBlank() ? fallback : resolved;
    }

    private static String escapeMiniMessage(String input) {
        return input.replace("<", "\\<");
    }
}
