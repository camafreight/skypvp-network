package network.skypvp.shared.chat;

/**
 * Escalates Azure-flagged chat using existing {@code network_punishments} WARN/MUTE records.
 */
public final class ChatModerationEscalation {
    public static final String ISSUER = "ChatModeration";

    public enum Decision {
        WARN,
        MUTE
    }

    private ChatModerationEscalation() {
    }

    public static Decision decide(int activeWarnCount, ChatModerationSettings settings) {
        if (activeWarnCount >= settings.warnsBeforeMute()) {
            return Decision.MUTE;
        }
        return Decision.WARN;
    }
}
