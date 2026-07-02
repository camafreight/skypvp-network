package network.skypvp.shared;

/**
 * Published to {@link NetworkChannels#DECORATION_REFRESH} when an NPC or hologram is created,
 * edited, or deleted on a paper server.
 *
 * <p>Subscribers reload the affected decorations from the database and re-apply them live, but only
 * when {@link #scope()} matches their own decoration scope. The {@link #originServerId()} lets the
 * publishing server skip its own broadcast (it already applied the change locally).
 *
 * @param scope          Decoration scope the change belongs to (role name for stateless roles such as
 *                       {@code "lobby"}, or a stable per-server id such as {@code "survival-1"}).
 * @param kind           Decoration kind that changed: {@code "npc"} or {@code "hologram"}.
 * @param originServerId Unique id of the server that published the event (its pod/server id).
 * @param timestamp      Epoch milliseconds when the event was published.
 */
public record DecorationRefreshEvent(String scope, String kind, String originServerId, long timestamp) {

    /** Decoration kind constant for NPCs. */
    public static final String KIND_NPC = "npc";

    /** Decoration kind constant for holograms. */
    public static final String KIND_HOLOGRAM = "hologram";
}
