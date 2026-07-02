package network.skypvp.paper.listener;

import java.util.Objects;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gamemode.api.CoreBehaviorKeys;
import network.skypvp.paper.library.WorldGroundItemCleanup;
import network.skypvp.paper.service.BuildProtectionSupport;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;

public final class WorldEnvironmentListener implements Listener {

    public static final String BUILD_BYPASS_PERMISSION = "skypvp.build.bypass";

    private final PaperCorePlugin plugin;

    public WorldEnvironmentListener(PaperCorePlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        if (!this.shouldCleanupGroundItems(event.getWorld())) {
            return;
        }
        WorldGroundItemCleanup.clearGroundItems(
                event.getWorld(),
                this.plugin.getLogger(),
                "world load"
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!this.shouldCleanupGroundItems(event.getWorld())) {
            return;
        }
        Chunk chunk = event.getChunk();
        World world = chunk.getWorld();
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();
        // Defer one tick so entities restored from chunk data are present before we scan.
        this.plugin.platformScheduler().runAtChunkLater(world, chunkX, chunkZ, () -> {
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                return;
            }
            int removed = WorldGroundItemCleanup.clearGroundItemsInChunk(world.getChunkAt(chunkX, chunkZ));
            if (removed > 0) {
                this.plugin.getLogger().fine(
                        "[WorldCleanup] Removed " + removed + " ground item(s) in '"
                                + world.getName() + "' chunk (" + chunkX + "," + chunkZ + ") (chunk load)."
                );
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!this.appliesBuildProtection(event.getBlock().getWorld()) || this.canModifyBlocks(event.getPlayer())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!this.appliesBuildProtection(event.getBlock().getWorld()) || this.canModifyBlocks(event.getPlayer())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (!this.appliesBuildProtection(event.getBlock().getWorld())) {
            return;
        }
        if (event.getEntity() instanceof Player player && this.canModifyBlocks(player)) {
            return;
        }
        if (BuildProtectionSupport.isProtectedLandscapeBlock(event.getBlock())
                || BuildProtectionSupport.isProtectedLandscapeBlock(event.getTo())) {
            event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        if (!this.appliesBuildProtection(event.getBlock().getWorld())) {
            return;
        }
        if (BuildProtectionSupport.isProtectedLandscapeBlock(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockFade(BlockFadeEvent event) {
        if (!this.appliesBuildProtection(event.getBlock().getWorld())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        if (!this.appliesBuildProtection(event.getBlock().getWorld())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        if (!this.appliesBuildProtection(event.getBlock().getWorld())) {
            return;
        }
        event.setCancelled(true);
    }

    private boolean buildProtectionEnabled() {
        return this.plugin.gameModeBehaviorService().booleanValue(CoreBehaviorKeys.BUILD_PROTECTION_ENABLED, false);
    }

    private boolean appliesBuildProtection(World world) {
        return this.buildProtectionEnabled() && (world == null || !world.getName().startsWith("breach_"));
    }

    private boolean shouldCleanupGroundItems(World world) {
        if (world == null || !this.clearGroundItemsOnLoadEnabled()) {
            return false;
        }
        return !world.getName().startsWith("breach_");
    }

    private boolean clearGroundItemsOnLoadEnabled() {
        return this.plugin.gameModeBehaviorService().booleanValue(CoreBehaviorKeys.CLEAR_GROUND_ITEMS_ON_LOAD_ENABLED, false);
    }

    private boolean canModifyBlocks(Player player) {
        if (player == null) {
            return true;
        }
        GameMode mode = player.getGameMode();
        return mode == GameMode.CREATIVE
                || mode == GameMode.SPECTATOR
                || player.hasPermission(BUILD_BYPASS_PERMISSION);
    }
}
