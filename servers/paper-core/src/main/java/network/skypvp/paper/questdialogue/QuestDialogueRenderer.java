package network.skypvp.paper.questdialogue;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import network.skypvp.paper.gamemode.api.HudProvider;
import network.skypvp.paper.hud.ScoreboardLayout;
import network.skypvp.shared.ServerTextUtil;

/** Renders dialogue onto the 15-line scoreboard with scroll + typewriter support. */
public final class QuestDialogueRenderer {

    /** Visible dialogue body lines (sidebar indices {@code 0}..{@code 13}). */
    public static final int BODY_LINE_COUNT = ScoreboardLayout.FOOTER_LINE_INDEX;
    public static final String SCROLL_HINT = "<yellow>⬇⬇ ѕᴄʀᴏʟʟ ⬇⬇";
    public static final String DIALOGUE_ICON = "💬 ";

    private QuestDialogueRenderer() {
    }

    public static HudProvider.ScoreboardFrame render(QuestDialogueSession session, long tickMillis) {
        List<Component> visibleLines = buildVisibleLines(session, tickMillis);
        int scrollOffset = Math.min(session.scrollOffset(), Math.max(0, visibleLines.size() - 1));
        boolean canScroll = visibleLines.size() > BODY_LINE_COUNT;
        int bodyCapacity = BODY_LINE_COUNT - (canScroll ? 1 : 0);
        int end = Math.min(visibleLines.size(), scrollOffset + bodyCapacity);
        if (end < scrollOffset) {
            end = scrollOffset;
        }
        List<Component> body = visibleLines.subList(scrollOffset, end);

        List<Component> sidebar = new ArrayList<>(ScoreboardLayout.SIDEBAR_LINE_COUNT);
        for (int i = 0; i < ScoreboardLayout.SIDEBAR_LINE_COUNT; i++) {
            sidebar.add(Component.empty());
        }
        for (int i = 0; i < BODY_LINE_COUNT && i < body.size(); i++) {
            sidebar.set(i, body.get(i));
        }
        if (canScroll && scrollOffset + bodyCapacity < visibleLines.size()) {
            sidebar.set(BODY_LINE_COUNT - 1, ServerTextUtil.miniMessageComponent(SCROLL_HINT));
        }
        sidebar.set(ScoreboardLayout.FOOTER_LINE_INDEX, controlsHint(session, tickMillis, canScroll));

        Component title = ServerTextUtil.miniMessageComponent(
                DIALOGUE_ICON + "<aqua>" + session.npcDisplayName()
        );
        return new HudProvider.ScoreboardFrame(title, sidebar);
    }

    private static Component controlsHint(QuestDialogueSession session, long tickMillis, boolean canScroll) {
        if (session.awaitingChoice()) {
            return ServerTextUtil.miniMessageComponent("<gray>W/S · Shift to choose");
        }
        if (!session.isCurrentLineFullyRevealed(tickMillis)) {
            return Component.empty();
        }
        if (canScroll) {
            Component scroll = ServerTextUtil.miniMessageComponent("<gray>W/S scroll");
            if (session.awaitingAdvance()) {
                return scroll.append(Component.newline()).append(
                        ServerTextUtil.miniMessageComponent("<gray>Shift to continue")
                );
            }
            return scroll;
        }
        if (session.awaitingAdvance()) {
            return ServerTextUtil.miniMessageComponent("<gray>Shift to continue");
        }
        return Component.empty();
    }

    private static List<Component> buildVisibleLines(QuestDialogueSession session, long tickMillis) {
        List<Component> lines = new ArrayList<>();
        List<String> nodeLines = session.currentNode().lines();
        int currentLine = session.lineIndex();
        for (int index = 0; index < nodeLines.size(); index++) {
            if (!session.awaitingChoice() && index > currentLine) {
                break;
            }
            String text = nodeLines.get(index);
            String visible = index < currentLine || session.awaitingChoice()
                    ? text
                    : typewriter(text, session.lineRevealMillis(), tickMillis);
            if (index == 0) {
                lines.add(ServerTextUtil.miniMessageComponent(
                        "<aqua>" + session.npcDisplayName() + ": <white>" + visible
                ));
            } else {
                lines.add(ServerTextUtil.miniMessageComponent("<white>" + visible));
            }
        }
        if (session.awaitingChoice()) {
            lines.add(Component.empty());
            for (int optionIndex = 0; optionIndex < session.currentNode().options().size(); optionIndex++) {
                DialogueOption option = session.currentNode().options().get(optionIndex);
                String typed = typewriter(option.label(), session.optionRevealMillis(optionIndex), tickMillis);
                String prefix = optionIndex == session.selectedOptionIndex() ? "<white><u>> " : "<red>  ";
                lines.add(ServerTextUtil.miniMessageComponent(prefix + (optionIndex + 1) + ". " + typed));
            }
        }
        return lines;
    }

    private static String typewriter(String text, long revealStartMillis, long nowMillis) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        long elapsed = Math.max(0L, nowMillis - revealStartMillis);
        int chars = (int) Math.min(text.length(), elapsed / QuestDialogueSession.MS_PER_CHAR);
        return text.substring(0, chars);
    }
}
