package network.skypvp.paper.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import network.skypvp.paper.hud.ScoreboardLayout;
import network.skypvp.shared.ServerTextUtil;

/** Shared party sidebar line layout (extraction + lobby). */
public final class PartyScoreboardLines {

    public static final int MAX_MEMBERS = 12;
    /** Matches {@link ScoreboardLayout#SIDEBAR_LINE_COUNT}. */
    public static final int SIDEBAR_LINE_COUNT = ScoreboardLayout.SIDEBAR_LINE_COUNT;
    public static final int FOOTER_LINE_INDEX = ScoreboardLayout.FOOTER_LINE_INDEX;
    /** Max visible characters before an ellipsis is appended on the party sidebar. */
    public static final int MAX_MEMBER_NAME_CHARS = 11;
    /** Fallback grace when no breach config is available (lobby). */
    public static final long DEFAULT_DISCONNECTED_GRACE_MILLIS = 5L * 60L * 1000L;

    private PartyScoreboardLines() {
    }

    public static String compactHeader(int onlineCount, String date) {
        return "<dark_gray>" + onlineCount + "/" + MAX_MEMBERS + " <gray>" + (date == null ? "" : date);
    }

    /** Places body lines at the top and pins {@code footer} to the bottom sidebar slot. */
    public static List<Component> buildSidebar(List<Component> bodyLines, Component footer) {
        List<Component> lines = new ArrayList<>(Collections.nCopies(SIDEBAR_LINE_COUNT, Component.empty()));
        if (bodyLines != null) {
            int limit = Math.min(bodyLines.size(), FOOTER_LINE_INDEX);
            for (int index = 0; index < limit; index++) {
                lines.set(index, bodyLines.get(index));
            }
        }
        lines.set(FOOTER_LINE_INDEX, footer == null ? Component.empty() : footer);
        return lines;
    }

    public static Component memberLine(PartyScoreboardData.MemberView member, long graceMillis, long nowEpochMillis) {
        Component namePart = ServerTextUtil.miniMessageComponent(namePrefix(member));
        if (member.presence() != PartyScoreboardData.Presence.DISCONNECTED || member.disconnectedSinceEpochMillis() <= 0L) {
            return namePart;
        }
        long safeGrace = graceMillis > 0L ? graceMillis : DEFAULT_DISCONNECTED_GRACE_MILLIS;
        long remainingMs = member.disconnectedSinceEpochMillis() + safeGrace - nowEpochMillis;
        String timer = formatGraceRemaining(remainingMs);
        String timerColor = remainingMs <= 60_000L ? "<red>" : "<yellow>";
        Component timerPart = ServerTextUtil.miniMessageComponent(timerColor + timer);
        int lineWidth = ServerTextUtil.SCOREBOARD_LINE_WIDTH_PIXELS;
        int timerWidth = ServerTextUtil.componentVisibleWidth(timerPart);
        int maxNameWidth = Math.max(24, lineWidth - timerWidth - 4);
        if (ServerTextUtil.componentVisibleWidth(namePart) > maxNameWidth) {
            namePart = ServerTextUtil.padToWidth(namePart, maxNameWidth, ServerTextUtil.HAlign.LEFT);
        }
        return ServerTextUtil.layoutThreeZone(namePart, Component.empty(), timerPart, lineWidth);
    }

    private static String namePrefix(PartyScoreboardData.MemberView member) {
        String dot = switch (member.presence()) {
            case ONLINE -> "<green>\u25CF";
            case DISCONNECTED -> "<gold>\u25CF";
            default -> "<dark_gray>\u25CF";
        };
        String nameColor = member.leader() ? "<gold>" : "<white>";
        String name = formatMemberName(member.name());
        return dot + " " + nameColor + name;
    }

    /** Plain name line without rank prefix (fallback when chat formats are unavailable). */
    public static String memberLinePlainName(PartyScoreboardData.MemberView member) {
        return namePrefix(member);
    }

    private static String formatMemberName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "?";
        }
        if (raw.length() <= MAX_MEMBER_NAME_CHARS) {
            return escape(raw);
        }
        return escape(raw.substring(0, MAX_MEMBER_NAME_CHARS)) + "...";
    }

    public static String formatGraceRemaining(long remainingMs) {
        if (remainingMs <= 0L) {
            return "0:00";
        }
        long totalSeconds = (remainingMs + 999L) / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return minutes + ":" + String.format(Locale.ROOT, "%02d", seconds);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("<", "\\<").replace(">", "\\>");
    }
}
