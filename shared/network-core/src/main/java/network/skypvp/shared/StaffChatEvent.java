package network.skypvp.shared;

/**
 * Published to {@link NetworkChannels#STAFF_CHAT} when a staff member sends a staff chat message.
 *
 * @param senderUuid  UUID string of the sender, or {@code "CONSOLE"} for console.
 * @param senderName  Display name of the sender.
 * @param serverId    ID of the server that published the event.
 * @param message     The raw message text (unsanitized — recipients must sanitize before MiniMessage).
 */
public record StaffChatEvent(String senderUuid, String senderName, String serverId, String message) {
}
