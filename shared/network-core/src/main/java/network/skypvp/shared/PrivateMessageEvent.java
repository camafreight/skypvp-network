package network.skypvp.shared;

/**
 * Published to {@link NetworkChannels#PRIVATE_MESSAGE} when a private message is sent on the
 * proxy or from skypvp-web.
 *
 * <p>Proxy-originated events use {@code deliverToPlayers=false} because Velocity already delivered
 * locally; web-originated events use {@code deliverToPlayers=true} so the proxy renders and
 * delivers styled lines to online players.
 */
public record PrivateMessageEvent(
        String senderUuid,
        String senderName,
        String targetUuid,
        String targetName,
        String plainMessage,
        String origin,
        boolean deliverToPlayers,
        long timestamp
) {
    public static final String ORIGIN_PROXY = "proxy";
    public static final String ORIGIN_WEB = "web";

    public PrivateMessageEvent {
        senderName = senderName == null ? "Player" : senderName;
        targetName = targetName == null ? "" : targetName;
        plainMessage = plainMessage == null ? "" : plainMessage;
        origin = origin == null ? ORIGIN_PROXY : origin;
    }
}
