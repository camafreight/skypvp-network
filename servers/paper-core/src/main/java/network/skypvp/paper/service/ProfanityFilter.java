package network.skypvp.paper.service;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ProfanityFilter {
    private static final Set<String> BLOCKED_WORDS = Set.of(
            "fuck", "shit", "bitch", "asshole", "bastard", "damn", "cunt", "dick", "piss", "slut", "whore", "nigger", "faggot"
    );
    private static final Pattern[] BLOCKED_PATTERNS;

    static {
        BLOCKED_PATTERNS = BLOCKED_WORDS.stream()
                .map(word -> Pattern.compile("(?i)\\b" + Pattern.quote(word) + "\\b"))
                .toArray(Pattern[]::new);
    }

    private ProfanityFilter() {
    }

    public static String filter(String message) {
        if (message == null || message.isBlank()) {
            return message;
        }
        String result = message;
        for (Pattern pattern : BLOCKED_PATTERNS) {
            Matcher matcher = pattern.matcher(result);
            StringBuffer buffer = new StringBuffer(result.length());
            while (matcher.find()) {
                matcher.appendReplacement(buffer, "*".repeat(matcher.group().length()));
            }
            matcher.appendTail(buffer);
            result = buffer.toString();
        }
        return result;
    }
}
