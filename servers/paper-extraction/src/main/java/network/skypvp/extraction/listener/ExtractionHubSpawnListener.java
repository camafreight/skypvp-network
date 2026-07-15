package network.skypvp.extraction.listener;

import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.paper.PaperCorePlugin;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Pins extraction hub joins to {@code world-templates/<presetId>/meta.json}.
 *
 * <p>Paper 1.21.9+ selects spawn during the configuration phase via
 * {@link AsyncPlayerSpawnLocationEvent}. The legacy {@code PlayerSpawnLocationEvent} no longer
 * reliably overrides persisted {@code player.dat} positions, so returning players landed at their
 * last logout spot instead of the template spawn.
 *
 * <p>Only mid-raid reconnects are exempt: disconnected raiders and eliminated spectators keep their
 * breach spawn so {@link BreachEngine#resumeDisconnectedRaider} /
 * {@link BreachEngine#rejoinEliminatedSpectator} can seat them. Everyone else joining the pod is
 * forced to the hub spawn, including players whose {@code player.dat} still points at a stale
 * {@code breach_*} world.
 */
public final class ExtractionHubSpawnListener implements Listener {

    private static final double ALREADY_AT_SPAWN_SQ = 0.25D;

    private final PaperCorePlugin core;
    private final BreachEngine engine;

    public ExtractionHubSpawnListener(PaperCorePlugin core, BreachEngine engine) {
        this.core = Objects.requireNonNull(core, "core");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAsyncSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        if (shouldResumeActiveRaid(profileId(event))) {
            return;
        }
        hubSpawn().ifPresent(event::setSpawnLocation);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (shouldResumeActiveRaid(player.getUniqueId())) {
            return;
        }
        Optional<Location> hubSpawn = hubSpawn();
        if (hubSpawn.isEmpty()) {
            return;
        }
        Location target = hubSpawn.get();
        Location current = player.getLocation();
        if (current.getWorld() != null
                && current.getWorld().equals(target.getWorld())
                && current.distanceSquared(target) <= ALREADY_AT_SPAWN_SQ
                && Math.abs(current.getYaw() - target.getYaw()) < 1.0F
                && Math.abs(current.getPitch() - target.getPitch()) < 1.0F) {
            return;
        }
        // teleportAsync loads the destination chunk on the correct Folia region thread.
        player.teleportAsync(target);
    }

    private boolean shouldResumeActiveRaid(UUID playerId) {
        return engine.isDisconnectedInRaid(playerId) || engine.isEliminatedInActiveRaid(playerId);
    }

    private static UUID profileId(AsyncPlayerSpawnLocationEvent event) {
        PlayerProfile profile = event.getConnection().getProfile();
        return profile == null ? null : profile.getId();
    }

    private Optional<Location> hubSpawn() {
        if (core.worldStateService() == null) {
            return Optional.empty();
        }
        return core.worldStateService().presetSpawnLocation();
    }
}
