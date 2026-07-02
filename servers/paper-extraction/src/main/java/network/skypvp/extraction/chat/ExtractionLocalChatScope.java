package network.skypvp.extraction.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Objects;
import network.skypvp.paper.gamemode.api.LocalChatScope;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Breach/extraction local chat: global messages stay in the sender's world and do not fan out
 * over Redis. Party, staff, and private channels still use the network chat core routing.
 */
public final class ExtractionLocalChatScope implements LocalChatScope {

    @Override
    public void restrictGlobalAudience(AsyncChatEvent event) {
        Objects.requireNonNull(event, "event");
        Player sender = event.getPlayer();
        World world = sender.getWorld();
        event.viewers().clear();
        for (Player viewer : world.getPlayers()) {
            if (viewer.isOnline()) {
                event.viewers().add(viewer);
            }
        }
    }

    @Override
    public boolean skipGlobalRedisBroadcast() {
        return true;
    }
}
