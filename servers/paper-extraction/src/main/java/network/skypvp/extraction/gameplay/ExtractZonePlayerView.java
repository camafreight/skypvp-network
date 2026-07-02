package network.skypvp.extraction.gameplay;

public record ExtractZonePlayerView(
        boolean extracted,
        boolean inExtractZone,
        boolean aliveInMatch
) {
    public static ExtractZonePlayerView defaults() {
        return new ExtractZonePlayerView(false, false, true);
    }
}
