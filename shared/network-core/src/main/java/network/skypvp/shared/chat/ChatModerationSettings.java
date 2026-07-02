package network.skypvp.shared.chat;

public record ChatModerationSettings(
        boolean enabled,
        String language,
        double category3Threshold,
        boolean honorReviewRecommended,
        int flagsBeforeWarn,
        int warnsBeforeMute,
        int muteDurationSeconds,
        int contentSafetyMinSeverity
) {
    public static ChatModerationSettings defaults() {
        return new ChatModerationSettings(false, "eng", 0.75D, true, 2, 2, 3600, 2);
    }
}
