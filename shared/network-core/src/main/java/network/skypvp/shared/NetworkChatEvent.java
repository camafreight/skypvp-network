package network.skypvp.shared;

/**
 * Immutable event published on {@link NetworkChannels#GLOBAL_CHAT} when a player
 * sends a public chat message on any Paper backend.
 *
 * <p>The proxy receives this and fans out the formatted message to every player
 * on remote servers (i.e., NOT on the originating server, which already saw the
 * message from the local Paper event).
 *
 * <p>Fields are plain text to avoid serialization dependency on Adventure.
 * The proxy reconstructs the full styled Component using {@code rankKey},
 * {@code rankDisplayName}, and {@code plainMessage}.
 */
public record NetworkChatEvent(
        /** ID of the server where the message originated (e.g. "lobby-1"). */
        String originServerId,
        /** Sender's UUID as a string. */
        String senderUuid,
        /** Sender's current display name (plain). */
        String senderName,
        /** Rank key (e.g. "vip", "mvp_plus", "default"). Used to pick colors. */
        String rankKey,
        /** Human-readable rank label (e.g. "VIP", "MVP+"). */
        String rankDisplayName,
        /** The raw plain-text message content. */
        String plainMessage,
        /** Sender Minecraft client locale (e.g. {@code en_us}), used for auto-translation. */
        String senderLocale
) {
    public NetworkChatEvent {
        senderLocale = senderLocale == null || senderLocale.isBlank()
                ? network.skypvp.shared.chat.ClientLocaleUtil.defaultMinecraftLocale()
                : senderLocale;
    }
}
