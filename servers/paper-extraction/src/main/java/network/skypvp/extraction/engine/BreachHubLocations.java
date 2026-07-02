package network.skypvp.extraction.engine;

import java.util.Optional;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.service.WorldStateService;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Resolves extraction-lobby return locations for breach players.
 * Uses the saved back location from join when it is safe; otherwise falls back to
 * {@code world-templates/<presetId>/meta.json} via {@link WorldStateService#presetSpawnLocation()}.
 */
public final class BreachHubLocations {

    private BreachHubLocations() {
    }

    /** Records where the player stood in the hub when they queued for breach. */
    public static Location capture(PaperCorePlugin core, Player player) {
        if (player == null) {
            return requirePresetSpawn(core);
        }
        Location current = player.getLocation();
        if (isSafeHubReturn(core, current)) {
            return current.clone();
        }
        return requirePresetSpawn(core);
    }

    /** Returns stored back location, or preset spawn from meta.json when stored is unsafe. */
    public static Location resolve(PaperCorePlugin core, Optional<Location> stored) {
        if (stored.isPresent() && isSafeHubReturn(core, stored.get())) {
            return stored.get().clone();
        }
        return requirePresetSpawn(core);
    }

    public static void teleportToHub(PaperCorePlugin core, Player player, Optional<Location> stored) {
        if (player == null || core == null) {
            return;
        }
        Location target = resolve(core, stored);
        // teleportAsync loads the destination chunk itself and is mandatory on Folia (region threading);
        // the previous blocking getChunkAt(...).load(true) + teleport(...) throws UnsupportedOperationException.
        player.teleportAsync(target);
    }

    private static boolean isSafeHubReturn(PaperCorePlugin core, Location location) {
        if (location == null || location.getWorld() == null || core == null) {
            return false;
        }
        if (!isManagedHubWorld(core, location.getWorld())) {
            return false;
        }

        World world = location.getWorld();
        int y = location.getBlockY();
        // IMPORTANT (Folia): this is called from the *breach* region thread when teleporting a player back to
        // the *hub* world. We must NOT touch the hub world's chunks/blocks here (getChunkAt/load/getBlockAt) —
        // accessing another region's chunk off-thread throws and previously aborted the whole hub teleport,
        // stranding players in the breach. teleportAsync(target) loads the destination chunk itself, so a cheap
        // managed-world + height-bounds validation is sufficient; anything unsafe falls back to the preset spawn.
        return y >= world.getMinHeight() + 1 && y <= world.getMaxHeight() - 1;
    }

    private static boolean isManagedHubWorld(PaperCorePlugin core, World world) {
        if (world == null) {
            return false;
        }
        if (world.getName().startsWith("breach_")) {
            return false;
        }
        WorldStateService worldState = core.worldStateService();
        if (worldState != null && worldState.managedWorlds().contains(world.getName())) {
            return true;
        }
        return "world".equalsIgnoreCase(world.getName());
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
