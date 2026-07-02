package network.skypvp.paper.library.packet;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import network.skypvp.paper.platform.Platforms;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Death corpse: a fake player rendered lying flat (prone) at the exact location the player died. The body uses the
 * SWIMMING pose so it lies on the ground where they fell — no bed is involved, so the corpse is never displaced.
 */
public final class PacketCorpseDisplay {

    private final Plugin plugin;
    private final PacketFakePlayer body;

    public PacketCorpseDisplay(
        Plugin plugin,
        String name,
        String textureValue,
        String textureSignature,
        Location bodyLocation
    ) {
        this.plugin = plugin;

        Location corpseBody = bodyLocation == null ? null : bodyLocation.clone();
        if (corpseBody != null) {
            corpseBody.setPitch(0.0F);
        }

        this.body = new PacketFakePlayer(
            plugin,
            corpseProfileName(name),
            textureValue,
            textureSignature,
            corpseBody,
            false,
            true
        );
    }

    /**
     * Builds a per-corpse profile name that can never collide with the dead player's real name. Scoreboard teams
     * identify players by <em>name</em>, so when the corpse reused the owner's exact name, the corpse's
     * hide-nametag team also captured the real player — every corpse spawn/resync/destroy near them yanked the
     * living player in and out of that team, flickering their nametag and TAB state. A unique
     * {@code <owner>_body<hex>} profile keeps the corpse's team (and tab entry) fully isolated; the visible
     * "<player>'s body" label is rendered separately, so this internal name is never shown to clients.
     */
    private static String corpseProfileName(String ownerName) {
        String base = ownerName == null ? "" : ownerName.replaceAll("[^a-zA-Z0-9_]", "");
        if (base.length() > 6) {
            base = base.substring(0, 6);
        }
        if (base.isEmpty()) {
            base = "corpse";
        }
        String suffix = Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000)).toLowerCase(Locale.ROOT);
        return base + "_body" + suffix;
    }

    /** Client-side entity id of the corpse body, used to route inbound interaction packets back to this corpse. */
    public int bodyEntityId() {
        return this.body.getEntityId();
    }

    public void showTo(Player viewer) {
        if (viewer == null) {
            return;
        }
        Platforms.get(this.plugin).runOnPlayerLater(viewer, () -> {
            this.body.showTo(viewer);
            Platforms.get(this.plugin).runOnPlayerLater(viewer, () -> this.body.resyncCorpseMetadata(viewer), 2L);
        }, 2L);
    }

    public void destroy(Player viewer) {
        if (viewer == null) {
            return;
        }
        this.body.destroy(viewer);
    }

    public void resync(Player viewer) {
        if (viewer == null) {
            return;
        }
        this.destroy(viewer);
        this.showTo(viewer);
    }

    public void destroyAll(Set<UUID> viewerIds, Function<UUID, Player> playerLookup) {
        for (UUID viewerId : viewerIds) {
            Player viewer = playerLookup.apply(viewerId);
            if (viewer != null && viewer.isOnline()) {
                this.destroy(viewer);
            }
        }
    }

    public Set<UUID> trackedViewers() {
        return this.body.getViewersSnapshot();
    }
}
