package network.skypvp.proxy.chat;

import com.velocitypowered.api.proxy.Player;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import network.skypvp.shared.chat.ChatRenderContext;

public final class ProxyChatPlaceholderBridge {
    private static final Pattern SKYPVP_PATTERN = Pattern.compile("%skypvp_([^%]+)%", Pattern.CASE_INSENSITIVE);
    private static final Pattern SKYPVPCHAT_PATTERN = Pattern.compile("%skypvpchat_([^%]+)%", Pattern.CASE_INSENSITIVE);

    private ProxyChatPlaceholderBridge() {
    }

    public static String apply(String input, ChatRenderContext context) {
        ProxyChatRenderContextHolder.RenderState state = ProxyChatRenderContextHolder.current();
        if (state == null) {
            return applyStatic(input, context);
        }
        return apply(input, context, state.sender(), state.formats(), state.rankKeyFallback(), 0);
    }

    static String apply(
            String input,
            ChatRenderContext context,
            Player sender,
            ProxyChatFormatService formats,
            String rankKeyFallback
    ) {
        return apply(input, context, sender, formats, rankKeyFallback, 0);
    }

    static String apply(
            String input,
            ChatRenderContext context,
            Player sender,
            ProxyChatFormatService formats,
            String rankKeyFallback,
            int depth
    ) {
        if (input == null) {
            return "";
        }
        if (depth > 6) {
            return input;
        }

        String resolved = applyStatic(input, context);
        if (formats != null) {
            resolved = replacePattern(
                    resolved,
                    SKYPVP_PATTERN,
                    params -> resolveSkyPvPParam(formats, sender, rankKeyFallback, params, context, depth)
            );
            resolved = replacePattern(
                    resolved,
                    SKYPVPCHAT_PATTERN,
                    params -> ProxyChatPlaceholderResolver.resolve(formats, sender, rankKeyFallback, params, context, depth)
            );
        }
        return resolved;
    }

    private static String resolveSkyPvPParam(
            ProxyChatFormatService formats,
            Player sender,
            String rankKeyFallback,
            String params,
            ChatRenderContext context,
            int depth
    ) {
        String normalized = params == null ? "" : params.trim().toLowerCase(Locale.ROOT);
        String chatParam = chatParam(normalized);
        if (chatParam != null) {
            return ProxyChatPlaceholderResolver.resolve(formats, sender, rankKeyFallback, chatParam, context, depth);
        }
        return "";
    }

    private static String chatParam(String key) {
        if (key.startsWith("chat_")) {
            return key.substring("chat_".length());
        }
        if (key.startsWith("chat.")) {
            return key.substring("chat.".length());
        }
        return null;
    }

    private static String applyStatic(String input, ChatRenderContext context) {
        ChatRenderContext effective = context == null
                ? new ChatRenderContext("Player", "", "", "")
                : context;
        return input
                .replace("%player_name%", effective.playerName())
                .replace("%message%", effective.message())
                .replace("%channel%", effective.channelLabel())
                .replace("%target_name%", effective.targetName());
    }

    private static String replacePattern(String input, Pattern pattern, java.util.function.Function<String, String> resolver) {
        Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) {
            return input;
        }

        StringBuffer buffer = new StringBuffer();
        do {
            String replacement = resolver.apply(matcher.group(1));
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement == null ? "" : replacement));
        } while (matcher.find());
        matcher.appendTail(buffer);
        return buffer.toString();
    }
}
