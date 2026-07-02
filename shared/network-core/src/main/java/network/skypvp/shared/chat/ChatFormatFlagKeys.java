package network.skypvp.shared.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ChatFormatFlagKeys {
    public record FlagDefinition(String key, String description, String example) {
    }

    private static final List<FlagDefinition> DEFINITIONS = List.of(
            new FlagDefinition("priority", "Higher wins when a player has multiple rank formats.", "priority=100"),
            new FlagDefinition("prefix", "Text before the name (MiniMessage).", "prefix=<gold><bold>[VIP]</bold><reset> "),
            new FlagDefinition("name_color", "Color/tag wrapping the name.", "name_color=gold"),
            new FlagDefinition("name", "Name segment (%player_name%, PlaceholderAPI).", "name=%player_name%"),
            new FlagDefinition("suffix", "Text after the name.", "suffix=<gray>:</gray>"),
            new FlagDefinition("chat_color", "Message body color.", "chat_color=gray"),
            new FlagDefinition("channel_tooltip", "Hover lines for the channel segment (<br> separated).", "channel_tooltip=Global chat<br>Click to switch"),
            new FlagDefinition("prefix_tooltip", "Hover lines for prefix.", "prefix_tooltip=VIP rank"),
            new FlagDefinition("name_tooltip", "Hover lines for name.", "name_tooltip=Click to message"),
            new FlagDefinition("suffix_tooltip", "Hover lines for suffix.", "suffix_tooltip="),
            new FlagDefinition("prefix_click_command", "Click runs this command.", "prefix_click_command=/msg %player_name%"),
            new FlagDefinition("name_click_command", "Click on name.", "name_click_command=/msg %player_name%"),
            new FlagDefinition("suffix_click_command", "Click on suffix.", "suffix_click_command=")
    );

    private ChatFormatFlagKeys() {
    }

    public static List<FlagDefinition> definitions() {
        return DEFINITIONS;
    }

    public static List<String> keys() {
        return DEFINITIONS.stream().map(FlagDefinition::key).toList();
    }

    public static Set<String> usedKeys(String... tokens) {
        return ChatFormatFlagParser.parseWithKeys(tokens).keys();
    }

    public static List<String> tabSuggestions(String partialToken, Set<String> usedKeys) {
        String partial = partialToken == null ? "" : partialToken.trim().toLowerCase(Locale.ROOT);
        if (partial.contains("=")) {
            return List.of(partialToken);
        }
        List<String> matches = new ArrayList<>();
        for (FlagDefinition definition : DEFINITIONS) {
            String key = definition.key();
            if (usedKeys.contains(key)) {
                continue;
            }
            if (partial.isEmpty() || key.startsWith(partial) || key.replace('_', ' ').startsWith(partial)) {
                matches.add(key + "=");
            }
        }
        return matches;
    }

    static String normalizeKey(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT)
                .replace("namecolor", "name_color")
                .replace("chatcolor", "chat_color")
                .replace("channeltooltip", "channel_tooltip")
                .replace("prefixtooltip", "prefix_tooltip")
                .replace("nametooltip", "name_tooltip")
                .replace("suffixtooltip", "suffix_tooltip")
                .replace("prefixclickcommand", "prefix_click_command")
                .replace("nameclickcommand", "name_click_command")
                .replace("suffixclickcommand", "suffix_click_command");
    }
}
