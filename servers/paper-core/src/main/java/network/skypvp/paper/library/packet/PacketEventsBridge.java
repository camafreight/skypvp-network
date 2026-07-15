package network.skypvp.paper.library.packet;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Thin send helper around the PacketEvents runtime ([retrooper/packetevents](https://github.com/retrooper/packetevents)).
 */
public final class PacketEventsBridge {

    /** Missing-user warnings repeat per packet (sidebar refreshes 3x/s) — throttle to one per player per interval. */
    private static final java.util.concurrent.ConcurrentHashMap<java.util.UUID, Long> MISSING_USER_WARNED_AT =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final long MISSING_USER_WARN_INTERVAL_MS = 300_000L;

    private PacketEventsBridge() {
    }

    public static boolean isAvailable() {
        return Bukkit.getPluginManager().getPlugin("packetevents") != null
            && PacketEvents.getAPI() != null
            && PacketEvents.getAPI().isLoaded();
    }

    public static void requireAvailable(Plugin plugin) {
        if (!isAvailable()) {
            throw new IllegalStateException("PacketEvents is required for NPC visuals. Install packetevents-spigot on this server.");
        }
    }

    public static void send(Player player, PacketWrapper<?> packet, Logger logger, String context) {
        if (player == null || packet == null || !player.isOnline()) {
            return;
        }
        try {
            User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
            if (user == null) {
                long now = System.currentTimeMillis();
                if (MISSING_USER_WARNED_AT.size() > 512) {
                    MISSING_USER_WARNED_AT.clear();
                }
                Long warnedAt = MISSING_USER_WARNED_AT.get(player.getUniqueId());
                if (warnedAt == null || now - warnedAt >= MISSING_USER_WARN_INTERVAL_MS) {
                    MISSING_USER_WARNED_AT.put(player.getUniqueId(), now);
                    logger.warning("PacketEvents user missing for " + player.getName()
                        + " (" + context + "; suppressing repeats for 5m)");
                }
                return;
            }
            user.sendPacket(packet);
        } catch (Exception exception) {
            logger.warning("PacketEvents send failed (" + context + "): " + exception.getMessage());
        }
    }
}
