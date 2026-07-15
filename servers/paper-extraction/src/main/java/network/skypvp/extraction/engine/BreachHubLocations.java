package network.skypvp.extraction.engine;

import java.util.Optional;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.service.WorldStateService;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Resolves extraction-lobby return locations for breach players.
 *
 * <p>Hub returns always use {@code world-templates/<presetId>/meta.json} via
 * {@link WorldStateService#presetSpawnLocation()}. Players joining the pod spawn at that point
 * unless they are reconnecting into an active raid (handled separately by
 * {@link BreachEngine#resumeDisconnectedRaider}).
 */
public final class BreachHubLocations {

    private BreachHubLocations() {
    }

    /**
     * @deprecated Hub returns always use the preset spawn; kept so call sites that still record a
     *     back-location compile without behavior change.
     */
    @Deprecated
    public static Location capture(PaperCorePlugin core, Player player) {
        return requirePresetSpawn(core);
    }

    /** Always returns the configured hub spawn from meta.json. */
    public static Location resolve(PaperCorePlugin core, Optional<Location> stored) {
        return requirePresetSpawn(core);
    }

    public static void teleportToHub(PaperCorePlugin core, Player player, Optional<Location> stored) {
        if (player == null || core == null) {
            return;
        }
        Location target = resolve(core, stored);
        // teleportAsync loads the destination chunk itself and is mandatory on Folia (region threading).
        player.teleportAsync(target);
    }

    private static Location requirePresetSpawn(PaperCorePlugin core) {
        WorldStateService worldState = core == null ? null : core.worldStateService();
        if (worldState != null) {
            Optional<Location> preset = worldState.presetSpawnLocation();
            if (preset.isPresent()) {
                return preset.get().clone();
            }
        }
        throw new IllegalStateException("No hub spawn configured in world-templates meta.json");
    }
}
