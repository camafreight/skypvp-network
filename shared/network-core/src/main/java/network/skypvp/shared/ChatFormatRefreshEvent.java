package network.skypvp.shared;

/**
 * Published to {@link NetworkChannels#CHAT_FORMAT_REFRESH} when a chat format profile is
 * created, updated, or removed on a paper server.
 *
 * <p>Subscribers reload formats from the shared database so every server applies the same
 * styling without a restart. The {@link #originServerId()} lets the publishing server skip
 * its own broadcast (it already updated in-memory caches locally).
 *
 * @param originServerId Unique id of the server that published the event.
 * @param action         Mutation kind: {@code upsert}, {@code remove}, or {@code reload}.
 * @param formatId       Affected format id, or {@code null} for full reload hints.
 * @param timestamp      Epoch milliseconds when the event was published.
 */
public record ChatFormatRefreshEvent(String originServerId, String action, String formatId, long timestamp) {

    public static final String ACTION_UPSERT = "upsert";
    public static final String ACTION_REMOVE = "remove";
    public static final String ACTION_RELOAD = "reload";
}
