package network.skypvp.shared.chat;

public record ChatModerationVerdict(
        ChatModerationAction action,
        String reason,
        int offenseCount,
        int warnCount
) {
    public static ChatModerationVerdict allow() {
        return new ChatModerationVerdict(ChatModerationAction.NONE, "", 0, 0);
    }
}
