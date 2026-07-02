package network.skypvp.shared.chat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ChatFormatFlagParser {
    public record ParseResult(ChatFormatFlags flags, Set<String> keys) {
        public static ParseResult empty() {
            return new ParseResult(ChatFormatFlags.EMPTY, Set.of());
        }
    }

    private static final List<String> KEYS_BY_LENGTH = buildKeyOrder();

    private ChatFormatFlagParser() {
    }

    public static ChatFormatFlags parse(String... tokens) {
        return parseWithKeys(tokens).flags();
    }

    public static ParseResult parseWithKeys(String... tokens) {
        if (tokens == null || tokens.length == 0) {
            return ParseResult.empty();
        }
        return parseJoined(String.join(" ", tokens));
    }

    public static ParseResult parseJoined(String input) {
        if (input == null || input.isBlank()) {
            return ParseResult.empty();
        }

        String source = input.trim();
        Map<String, String> values = new HashMap<>();
        Set<String> keys = new HashSet<>();
        int pos = 0;

        while (pos < source.length()) {
            while (pos < source.length() && Character.isWhitespace(source.charAt(pos))) {
                pos++;
            }
            if (pos >= source.length()) {
                break;
            }

            String matchedKey = matchKeyAt(source, pos);
            if (matchedKey == null) {
                pos++;
                continue;
            }

            int equalsIndex = source.indexOf('=', pos);
            if (equalsIndex < 0) {
                break;
            }

            int valueStart = equalsIndex + 1;
            int valueEnd = findNextKeyBoundary(source, valueStart);
            String value = extractValue(source, valueStart, valueEnd);
            String canonical = ChatFormatFlagKeys.normalizeKey(matchedKey);
            values.put(canonical, value);
            keys.add(canonical);
            pos = valueEnd;
        }

        return new ParseResult(buildFlags(values), Set.copyOf(keys));
    }

    private static String matchKeyAt(String input, int pos) {
        String slice = input.substring(pos).toLowerCase(Locale.ROOT);
        for (String key : KEYS_BY_LENGTH) {
            if (slice.startsWith(key + "=")) {
                return key;
            }
        }
        for (Map.Entry<String, String> alias : ALIASES.entrySet()) {
            if (slice.startsWith(alias.getKey() + "=")) {
                return alias.getValue();
            }
        }
        return null;
    }

    private static int findNextKeyBoundary(String input, int valueStart) {
        int boundary = input.length();
        for (String key : KEYS_BY_LENGTH) {
            int index = findKeyEquals(input, key, valueStart + 1);
            while (index >= 0 && index < boundary) {
                if (isInsideQuotes(input, valueStart, index)) {
                    index = findKeyEquals(input, key, index + 1);
                    continue;
                }
                boundary = Math.min(boundary, index);
                break;
            }
        }
        for (Map.Entry<String, String> alias : ALIASES.entrySet()) {
            int index = findKeyEquals(input, alias.getKey(), valueStart + 1);
            while (index >= 0 && index < boundary) {
                if (isInsideQuotes(input, valueStart, index)) {
                    index = findKeyEquals(input, alias.getKey(), index + 1);
                    continue;
                }
                boundary = Math.min(boundary, index);
                break;
            }
        }
        return boundary;
    }

    private static int findKeyEquals(String input, String key, int from) {
        String lower = input.toLowerCase(Locale.ROOT);
        String needle = key.toLowerCase(Locale.ROOT) + "=";
        int index = from;
        while (index >= 0 && index < input.length()) {
            index = lower.indexOf(needle, index);
            if (index < 0) {
                return -1;
            }
            if (index == 0 || Character.isWhitespace(input.charAt(index - 1))) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private static boolean isInsideQuotes(String input, int valueStart, int candidateIndex) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = valueStart; i < candidateIndex && i < input.length(); i++) {
            char ch = input.charAt(i);
            if (ch == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (ch == '\'' && !inDouble) {
                inSingle = !inSingle;
            }
        }
        return inSingle || inDouble;
    }

    private static String extractValue(String input, int start, int end) {
        int index = start;
        while (index < end && Character.isWhitespace(input.charAt(index))) {
            index++;
        }
        if (index >= end) {
            return "";
        }

        char quote = input.charAt(index);
        if (quote == '"' || quote == '\'') {
            StringBuilder value = new StringBuilder();
            int cursor = index + 1;
            while (cursor < end) {
                char ch = input.charAt(cursor);
                if (ch == quote && (cursor == index + 1 || input.charAt(cursor - 1) != '\\')) {
                    break;
                }
                value.append(ch);
                cursor++;
            }
            return value.toString().trim();
        }

        return input.substring(index, end).trim();
    }

    private static ChatFormatFlags buildFlags(Map<String, String> values) {
        return new ChatFormatFlags(
                parseInt(values.get("priority"), 0),
                values.getOrDefault("prefix", ""),
                values.getOrDefault("name_color", ""),
                values.getOrDefault("name", ""),
                values.getOrDefault("suffix", ""),
                values.getOrDefault("chat_color", ""),
                values.getOrDefault("channel_tooltip", ""),
                values.getOrDefault("prefix_tooltip", ""),
                values.getOrDefault("name_tooltip", ""),
                values.getOrDefault("suffix_tooltip", ""),
                values.getOrDefault("prefix_click_command", ""),
                values.getOrDefault("name_click_command", ""),
                values.getOrDefault("suffix_click_command", "")
        );
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static List<String> buildKeyOrder() {
        List<String> keys = new ArrayList<>(ChatFormatFlagKeys.keys());
        keys.sort(Comparator.comparingInt(String::length).reversed());
        return List.copyOf(keys);
    }

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("namecolor", "name_color"),
            Map.entry("chatcolor", "chat_color"),
            Map.entry("channeltooltip", "channel_tooltip"),
            Map.entry("prefixtooltip", "prefix_tooltip"),
            Map.entry("nametooltip", "name_tooltip"),
            Map.entry("suffixtooltip", "suffix_tooltip"),
            Map.entry("prefixclickcommand", "prefix_click_command"),
            Map.entry("nameclickcommand", "name_click_command"),
            Map.entry("suffixclickcommand", "suffix_click_command")
    );
}
