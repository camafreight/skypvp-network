package network.skypvp.paper.questdialogue;

import java.util.ArrayList;
import java.util.List;

/** Dialogue line formatting for the action-bar speech bubble interior. */
public final class DialogueText {

    /** Fits the left speech panel interior (right of portrait). */
    public static final int MAX_LINE_LENGTH = 28;

    private DialogueText() {
    }

    public static String clamp(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.length() <= MAX_LINE_LENGTH) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_LINE_LENGTH);
    }

    public static List<String> wrapLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        List<String> wrapped = new ArrayList<>();
        for (String line : lines) {
            wrapped.addAll(wrapLine(line));
        }
        return List.copyOf(wrapped);
    }

    private static List<String> wrapLine(String line) {
        if (line == null || line.isBlank()) {
            return List.of();
        }
        String source = line.trim();
        if (source.length() <= MAX_LINE_LENGTH) {
            return List.of(source);
        }
        List<String> parts = new ArrayList<>();
        int index = 0;
        while (index < source.length()) {
            int end = Math.min(index + MAX_LINE_LENGTH, source.length());
            if (end < source.length()) {
                int breakAt = source.lastIndexOf(' ', end);
                if (breakAt > index + 8) {
                    end = breakAt;
                }
            }
            parts.add(source.substring(index, end).trim());
            index = end;
            while (index < source.length() && source.charAt(index) == ' ') {
                index++;
            }
        }
        return parts.isEmpty() ? List.of(clamp(source)) : parts;
    }
}
