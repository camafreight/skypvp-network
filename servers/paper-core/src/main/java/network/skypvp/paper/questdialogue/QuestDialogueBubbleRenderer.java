package network.skypvp.paper.questdialogue;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * RPG-style dialogue HUD (BetonQuest layout): solid speech panel on the left with
 * portrait + nameplate, and a separate choice panel on the right.
 *
 * <p>One action-bar line; absolute X via negative-space glyphs; vertical stacking via
 * ascent-shifted fonts / bitmaps.
 *
 * <p>All horizontal measurement goes through {@link QuestDialogueFont#width(String)} —
 * the pen cursor must track real rendered advances exactly, otherwise every element
 * placed after a text run drifts (and drifts differently each typewriter tick).
 */
public final class QuestDialogueBubbleRenderer {

    /** Max NPC speech lines inside the left panel. */
    public static final int SPEECH_LINE_COUNT = 4;
    /** Max choice rows inside the right panel. */
    public static final int CHOICE_LINE_COUNT = 4;

    private static final TextColor CHROME = TextColor.color(0xE8F4FF);
    private static final TextColor NAME = TextColor.color(0x40F0FF);
    private static final TextColor BODY = TextColor.color(0xF2F6FA);
    private static final TextColor CHOICE = TextColor.color(0xFFB84D);
    private static final TextColor CHOICE_DIM = TextColor.color(0xA8B4C4);
    private static final TextColor HINT = TextColor.color(0x9AA8B8);

    private static final String ELLIPSIS = "...";

    private QuestDialogueBubbleRenderer() {
    }

    public static Component compose(QuestDialogueSession session, long tickMillis, boolean showSilhouette) {
        Pen pen = new Pen();

        // --- LEFT: speech panel + nameplate + portrait ---------------------------------
        pen.moveTo(QuestDialogueFont.SPEECH_X);
        pen.glyph(QuestDialogueFont.SPEECH_PANEL, CHROME, QuestDialogueFont.SPEECH_ADVANCE);

        pen.moveTo(QuestDialogueFont.NAMEPLATE_X);
        pen.glyph(QuestDialogueFont.NAMEPLATE, CHROME, QuestDialogueFont.NAMEPLATE_ADVANCE);

        pen.moveTo(QuestDialogueFont.PORTRAIT_X);
        pen.glyph(QuestDialogueFont.PORTRAIT_FRAME, CHROME, QuestDialogueFont.PORTRAIT_ADVANCE);
        if (showSilhouette) {
            pen.moveTo(QuestDialogueFont.SILHOUETTE_X);
            pen.glyph(QuestDialogueFont.SILHOUETTE, CHROME, QuestDialogueFont.SILHOUETTE_ADVANCE);
        }

        String speaker = session.npcDisplayName() == null ? "NPC" : session.npcDisplayName();
        String name = truncateToWidth(speaker, QuestDialogueFont.NAME_TEXT_WIDTH);
        pen.moveTo(QuestDialogueFont.NAME_TEXT_X);
        pen.text(plain(name), QuestDialogueFont.TEXT_NAME, NAME, QuestDialogueFont.width(name));

        List<String> speech = buildSpeechLines(session, tickMillis);
        int speechRows = Math.min(SPEECH_LINE_COUNT, speech.size());
        for (int i = 0; i < speechRows; i++) {
            String line = speech.get(i);
            pen.moveTo(QuestDialogueFont.SPEECH_TEXT_X);
            pen.text(plain(line), QuestDialogueFont.TEXT_SPEECH[i], BODY, QuestDialogueFont.width(line));
        }

        // --- RIGHT: choice panel (only when choosing) ----------------------------------
        if (session.awaitingChoice()) {
            pen.moveTo(QuestDialogueFont.CHOICE_X);
            pen.glyph(QuestDialogueFont.CHOICE_PANEL, CHROME, QuestDialogueFont.CHOICE_ADVANCE);

            List<ChoiceRow> choices = buildChoiceRows(session, tickMillis);
            int choiceRows = Math.min(CHOICE_LINE_COUNT, choices.size());
            for (int row = 0; row < choiceRows; row++) {
                ChoiceRow choice = choices.get(row);
                char cursor = QuestDialogueFont.CHOICE_CURSORS[Math.min(row, QuestDialogueFont.CHOICE_CURSORS.length - 1)];
                if (choice.selected()) {
                    pen.moveTo(QuestDialogueFont.CHOICE_CURSOR_X);
                    pen.glyph(cursor, CHOICE, QuestDialogueFont.CURSOR_ADVANCE);
                }
                pen.moveTo(QuestDialogueFont.CHOICE_TEXT_X);
                pen.text(
                        plain(choice.text()),
                        QuestDialogueFont.TEXT_CHOICE[row],
                        choice.selected() ? CHOICE : CHOICE_DIM,
                        QuestDialogueFont.width(choice.text())
                );
            }
        }

        appendControls(pen, session, tickMillis);
        return pen.finish();
    }

    /**
     * Control hints occupy two FIXED slots under the speech panel so they never move
     * while text is typewriting or when the session state flips:
     * left slot = contextual action (skip / continue / choose), right slot = leave.
     */
    private static void appendControls(Pen pen, QuestDialogueSession session, long tickMillis) {
        int hintX = QuestDialogueFont.SPEECH_X + 8;
        if (session.awaitingChoice()) {
            pen.moveTo(hintX);
            pen.glyph(QuestDialogueFont.ICON_CHOOSE, CHROME, QuestDialogueFont.ICON_ADVANCE);
            pen.moveTo(hintX + QuestDialogueFont.ICON_ADVANCE + 2);
            String label = "W/S pick, Shift ok";
            pen.text(plain(label), QuestDialogueFont.TEXT_CONTROLS, HINT, QuestDialogueFont.width(label));
        } else if (!session.isCurrentLineFullyRevealed(tickMillis)) {
            pen.moveTo(hintX);
            pen.glyph(QuestDialogueFont.ICON_CONTINUE, CHROME, QuestDialogueFont.ICON_ADVANCE);
            pen.moveTo(hintX + QuestDialogueFont.ICON_ADVANCE + 2);
            String label = "Shift skip";
            pen.text(plain(label), QuestDialogueFont.TEXT_CONTROLS, HINT, QuestDialogueFont.width(label));
        } else if (session.awaitingAdvance()) {
            pen.moveTo(hintX);
            pen.glyph(QuestDialogueFont.ICON_CONTINUE, CHROME, QuestDialogueFont.ICON_ADVANCE);
            pen.moveTo(hintX + QuestDialogueFont.ICON_ADVANCE + 2);
            String label = "Shift continue";
            pen.text(plain(label), QuestDialogueFont.TEXT_CONTROLS, HINT, QuestDialogueFont.width(label));
        }

        String leave = "leave";
        int leaveX = QuestDialogueFont.SPEECH_X + QuestDialogueFont.SPEECH_WIDTH - 8
                - (QuestDialogueFont.ICON_ADVANCE + 2 + QuestDialogueFont.width(leave));
        pen.moveTo(leaveX);
        pen.glyph(QuestDialogueFont.ICON_LEAVE, CHROME, QuestDialogueFont.ICON_ADVANCE);
        pen.moveTo(leaveX + QuestDialogueFont.ICON_ADVANCE + 2);
        pen.text(plain(leave), QuestDialogueFont.TEXT_CONTROLS, HINT, QuestDialogueFont.width(leave));
    }

    /**
     * Wraps every line to its FINAL layout first, then reveals characters across the
     * stable wrapped rows — words never jump between rows mid-typewriter.
     */
    private static List<String> buildSpeechLines(QuestDialogueSession session, long tickMillis) {
        List<String> lines = new ArrayList<>(8);
        List<String> nodeLines = session.currentNode().lines();
        int currentLine = session.lineIndex();
        boolean revealAll = session.awaitingChoice();
        for (int index = 0; index < nodeLines.size(); index++) {
            if (!revealAll && index > currentLine) {
                break;
            }
            String full = plainString(nodeLines.get(index));
            List<String> wrapped = wrapToWidth(full, QuestDialogueFont.SPEECH_TEXT_WIDTH);
            if (revealAll || index < currentLine) {
                lines.addAll(wrapped);
                continue;
            }
            long elapsed = Math.max(0L, tickMillis - session.lineRevealMillis());
            int budget = (int) Math.min(full.length(), elapsed / QuestDialogueSession.MS_PER_CHAR);
            for (String row : wrapped) {
                if (budget <= 0) {
                    break;
                }
                if (budget >= row.length()) {
                    lines.add(row);
                    budget -= row.length() + 1; // wrap point consumed one space
                } else {
                    lines.add(row.substring(0, budget));
                    budget = 0;
                }
            }
        }
        if (lines.size() <= SPEECH_LINE_COUNT) {
            return lines;
        }
        boolean settled = revealAll || session.isCurrentLineFullyRevealed(tickMillis);
        int maxStart = lines.size() - SPEECH_LINE_COUNT;
        // Follow the tail while typing; respect manual scroll once the text has settled.
        int start = settled ? Math.max(0, Math.min(session.scrollOffset(), maxStart)) : maxStart;
        return lines.subList(start, start + SPEECH_LINE_COUNT);
    }

    private static List<ChoiceRow> buildChoiceRows(QuestDialogueSession session, long tickMillis) {
        List<DialogueOption> options = session.currentNode().options();
        int total = options.size();
        int offset = Math.max(0, Math.min(session.choiceScrollOffset(), Math.max(0, total - 1)));
        if (total > CHOICE_LINE_COUNT) {
            offset = Math.min(offset, total - CHOICE_LINE_COUNT);
        } else {
            offset = 0;
        }
        int end = Math.min(total, offset + CHOICE_LINE_COUNT);
        List<ChoiceRow> rows = new ArrayList<>(CHOICE_LINE_COUNT);
        for (int optionIndex = offset; optionIndex < end; optionIndex++) {
            DialogueOption option = options.get(optionIndex);
            // Truncate the FULL label first so the visible cut point is stable during reveal.
            String label = truncateToWidth(option.label(), QuestDialogueFont.CHOICE_TEXT_WIDTH);
            String typed = typewriter(label, session.optionRevealMillis(optionIndex), tickMillis);
            boolean selected = optionIndex == session.selectedOptionIndex();
            rows.add(new ChoiceRow(typed, selected));
        }
        return rows;
    }

    /** Greedy word wrap against real pixel advances. */
    private static List<String> wrapToWidth(String text, int maxWidth) {
        String source = plainString(text);
        if (source.isEmpty()) {
            return List.of();
        }
        if (QuestDialogueFont.width(source) <= maxWidth) {
            return List.of(source);
        }
        List<String> parts = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int lineWidth = 0;
        for (String word : source.split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            int wordWidth = QuestDialogueFont.width(word);
            int spaceWidth = line.isEmpty() ? 0 : QuestDialogueFont.advance(' ');
            if (!line.isEmpty() && lineWidth + spaceWidth + wordWidth > maxWidth) {
                parts.add(line.toString());
                line.setLength(0);
                lineWidth = 0;
                spaceWidth = 0;
            }
            // Hard-split a single word that alone exceeds the budget.
            while (QuestDialogueFont.width(word) > maxWidth) {
                int cut = 1;
                int cutWidth = 0;
                for (int i = 0; i < word.length(); i++) {
                    int next = cutWidth + QuestDialogueFont.advance(word.charAt(i));
                    if (i > 0 && next > maxWidth) {
                        break;
                    }
                    cutWidth = next;
                    cut = i + 1;
                }
                if (!line.isEmpty()) {
                    parts.add(line.toString());
                    line.setLength(0);
                    lineWidth = 0;
                    spaceWidth = 0;
                }
                parts.add(word.substring(0, cut));
                word = word.substring(cut);
                wordWidth = QuestDialogueFont.width(word);
            }
            if (word.isEmpty()) {
                continue;
            }
            if (!line.isEmpty()) {
                line.append(' ');
            }
            line.append(word);
            lineWidth += spaceWidth + wordWidth;
        }
        if (!line.isEmpty()) {
            parts.add(line.toString());
        }
        return parts;
    }

    private static String typewriter(String text, long revealStartMillis, long nowMillis) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        long elapsed = Math.max(0L, nowMillis - revealStartMillis);
        int chars = (int) Math.min(text.length(), elapsed / QuestDialogueSession.MS_PER_CHAR);
        return text.substring(0, chars);
    }

    private static String plainString(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replaceAll("<[^>]*>", "");
    }

    private static Component plain(String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        return Component.text(plainString(text)).decoration(TextDecoration.ITALIC, false);
    }

    /** Cuts to a pixel budget, appending {@code ...} (the atlas has no ellipsis glyph). */
    private static String truncateToWidth(String text, int maxWidth) {
        String s = plainString(text);
        if (QuestDialogueFont.width(s) <= maxWidth) {
            return s;
        }
        int budget = maxWidth - QuestDialogueFont.width(ELLIPSIS);
        int used = 0;
        int cut = 0;
        for (int i = 0; i < s.length(); i++) {
            int next = used + QuestDialogueFont.advance(s.charAt(i));
            if (next > budget) {
                break;
            }
            used = next;
            cut = i + 1;
        }
        return s.substring(0, cut).stripTrailing() + ELLIPSIS;
    }

    private record ChoiceRow(String text, boolean selected) {
    }

    private static final class Pen {
        private final TextComponent.Builder line = Component.text();
        private int cursor;

        void moveTo(int x) {
            if (x != cursor) {
                String spaces = QuestDialogueFont.offset(x - cursor);
                if (!spaces.isEmpty()) {
                    line.append(Component.text(spaces).font(QuestDialogueFont.FONT));
                }
                cursor = x;
            }
        }

        void glyph(char glyphChar, TextColor color, int advance) {
            line.append(Component.text(String.valueOf(glyphChar)).font(QuestDialogueFont.FONT).color(color));
            cursor += advance;
        }

        void text(Component content, Key font, TextColor color, int advance) {
            if (content != null) {
                line.append(content.font(font).color(color));
            }
            cursor += Math.max(0, advance);
        }

        Component finish() {
            moveTo(0);
            return line.build();
        }
    }
}
