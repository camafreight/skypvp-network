package network.skypvp.paper.gamemode.api;

import io.papermc.paper.event.player.AsyncChatEvent;

/**
 * Restricts global chat to a local audience (e.g. same world) on gamemode servers.
 *
 * <p>Party, staff, and private channels are routed normally by the chat core. Only the
 * default global ({@code ALL}) channel uses these hooks.
 */
public interface LocalChatScope {

    /**
     * Restricts who receives a global chat message on this server.
     *
     * <p>Called only when the sender's active channel is {@code ALL}. Implementations may
     * replace {@link AsyncChatEvent#viewers()} with a local audience.
     */
    default void restrictGlobalAudience(AsyncChatEvent event) {
    }

    /**
     * @return {@code true} if global chat should not publish to {@link network.skypvp.shared.NetworkChannels#GLOBAL_CHAT}
     */
    default boolean skipGlobalRedisBroadcast() {
        return false;
    }
}
