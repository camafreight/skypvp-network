package network.skypvp.paper.questsignal;

import java.util.List;
import network.skypvp.paper.waypoint.Waypoint;

/**
 * What a {@link QuestSignalProvider} wants delivered to a player on hub join / refresh:
 * chat CTA, navigator waypoint, and/or a floating NPC shout board.
 *
 * @param chatMiniMessage MiniMessage chat CTA; {@code null}/blank = no chat
 * @param waypoint        navigator waypoint; {@code null} = no waypoint
 * @param npcDisplayName  speaker label for the shout board; ignored when shout lines empty
 * @param shoutLines      non-blocking floating board lines; empty = no board shout
 */
public record QuestSignalDelivery(
        String chatMiniMessage,
        Waypoint waypoint,
        String npcDisplayName,
        List<String> shoutLines
) {

    public QuestSignalDelivery {
        chatMiniMessage = chatMiniMessage == null || chatMiniMessage.isBlank() ? null : chatMiniMessage;
        npcDisplayName = npcDisplayName == null || npcDisplayName.isBlank() ? null : npcDisplayName;
        shoutLines = shoutLines == null || shoutLines.isEmpty() ? List.of() : List.copyOf(shoutLines);
        if (chatMiniMessage == null && waypoint == null && shoutLines.isEmpty()) {
            throw new IllegalArgumentException("delivery needs chat, waypoint, and/or shout lines");
        }
    }

    /** Chat + waypoint only (legacy constructor used by existing providers). */
    public QuestSignalDelivery(String chatMiniMessage, Waypoint waypoint) {
        this(chatMiniMessage, waypoint, null, List.of());
    }

    public QuestSignalDelivery withShout(String npcDisplayName, List<String> shoutLines) {
        return new QuestSignalDelivery(chatMiniMessage, waypoint, npcDisplayName, shoutLines);
    }

    public boolean hasShout() {
        return !shoutLines.isEmpty();
    }
}
