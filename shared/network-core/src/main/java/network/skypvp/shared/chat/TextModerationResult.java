package network.skypvp.shared.chat;

public record TextModerationResult(
        boolean reviewRecommended,
        double category1Score,
        double category2Score,
        double category3Score,
        boolean profanityDetected,
        String rawResponse
) {
    public static TextModerationResult clean() {
        return new TextModerationResult(false, 0.0, 0.0, 0.0, false, "");
    }

    public boolean flagged(double category3Threshold, boolean honorReviewRecommended) {
        if (profanityDetected) {
            return true;
        }
        if (honorReviewRecommended && reviewRecommended) {
            return true;
        }
        return category3Score >= category3Threshold;
    }
}
