package network.skypvp.shared;

public final class NetworkChannels {

    public static final String GLOBAL_EVENTS = "SkyPvP:network:global";
    public static final String PLAYER_SESSIONS = "SkyPvP:network:players";
    public static final String SERVER_HEARTBEATS = "SkyPvP:network:heartbeats";
    public static final String RANK_UPDATES = "SkyPvP:network:ranks";
    public static final String QUEUE_EVENTS = "SkyPvP:network:queues";
    public static final String PLAYER_REPORTS = "SkyPvP:network:reports";
    public static final String PROXY_ROUTE_REQUEST = "SkyPvP:route";
    public static final String PROXY_SOCIAL_REQUEST = "skypvp:social";
    public static final String PAPER_MENU_REQUEST = "SkyPvP:menu";
    /** Cross-server global chat broadcast. Paper publishes; proxy fans out to remote servers. */
    public static final String GLOBAL_CHAT = "SkyPvP:network:chat";
    /** Vanish state changes: paper publishes; proxy updates network-level vanish registry. */
    public static final String STAFF_VANISH = "SkyPvP:network:vanish";
    /** Cross-server staff chat: paper publishes; all paper servers receive and display to local staff. */
    public static final String STAFF_CHAT = "SkyPvP:network:staffchat";
    /** Cross-server party chat: paper publishes; proxy fans out to party members. */
    public static final String PARTY_CHAT = "SkyPvP:network:partychat";
    /** Server draining: paper publishes; proxy intercepts and shuffles players to fallbacks. */
    public static final String SERVER_DRAINING = "SkyPvP:network:draining";
    /**
     * Decoration refresh: paper publishes after an NPC/hologram is created, edited, or deleted.
     * Other paper servers in the <em>same decoration scope</em> reload the affected decorations from
     * the database and re-apply them live, so all replicas of a role stay in sync without a restart.
     */
    public static final String DECORATION_REFRESH = "SkyPvP:network:decorations";
    /**
     * Chat format refresh: paper publishes after {@code /chat formats} or system format commands;
     * other paper servers reload {@code network_chat_formats} from the database.
     */
    public static final String CHAT_FORMAT_REFRESH = "SkyPvP:network:chatformats";
    /**
     * Social settings refresh: paper publishes after menu toggles or {@code /chat} changes;
     * proxy and other paper servers reload the player's settings from the database.
     */
    public static final String SOCIAL_SETTINGS_REFRESH = "SkyPvP:network:socialsettings";
    /**
     * Private messages: proxy publishes after {@code /msg}; skypvp-web publishes to deliver
     * styled PMs to online players on the network.
     */
    public static final String PRIVATE_MESSAGE = "SkyPvP:network:privatemessage";
    /**
     * Breach disconnected presence: extraction publishes when a disconnected raider's stand-in body is hung or cleared.
     * The proxy tracks this to route reconnects straight back to the hosting extraction pod.
     */
    public static final String BREACH_DISCONNECTED_PRESENCE = "SkyPvP:network:breach-disconnected";
    /** @deprecated legacy channel name; proxy still subscribes during rolling deploys */
    public static final String BREACH_AWAY_PRESENCE = "SkyPvP:network:breach-away";
    /** Proxy → extraction: party queue admission to a specific breach instance before members connect. */
    public static final String BREACH_PARTY_QUEUE_DEPLOY = "SkyPvP:network:breach-party-deploy";
    /** Proxy → extraction: cancel a pending party deploy (disconnect / leave during join). */
    public static final String BREACH_PARTY_DEPLOY_CANCEL = "SkyPvP:network:breach-party-deploy-cancel";
    /**
     * Offline eliminated raider still tied to an active breach: proxy routes reconnects to the hosting extraction pod
     * as spectators instead of the lobby.
     */
    public static final String BREACH_SPECTATOR_PRESENCE = "SkyPvP:network:breach-spectator";

    private NetworkChannels() {
    }
}
