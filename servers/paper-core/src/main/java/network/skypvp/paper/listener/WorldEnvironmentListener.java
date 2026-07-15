package network.skypvp.paper.listener;

import java.util.Objects;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gamemode.api.CoreBehaviorKeys;
import network.skypvp.paper.library.WorldGroundItemCleanup;
import network.skypvp.paper.service.BuildProtectionSupport;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;

/**
 * Hub-world environment locks when {@link CoreBehaviorKeys#BUILD_PROTECTION_ENABLED} is on.
 *
 * <p>Block break/place/interact are denied for every player — creative, spectator, and
 * {@code skypvp.build.bypass} do not override. Breach worlds are owned by extraction guards.
 */
public final class WorldEnvironmentListener implements Listener {

    /** @deprecated Bypass is intentionally unused; kept for callers that still reference the constant. */
    @Deprecated
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
        if (!this.appliesBuildProtection(event.getBlock().getWorld())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!this.appliesBuildProtection(event.getBlock().getWorld())) {
            return;
        }
        event.setCancelled(true);
    }

    /**
     * Denies doors/chests/buttons/etc. without cancelling the whole interact, so item-use
     * listeners (hotbar, consumables, guns) still run. Gameplay that needs a block click
     * (e.g. breach loot chests) must open its own UI after reading the clicked block.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!this.appliesBuildProtection(event.getPlayer().getWorld())) {
            return;
        }
        Action action = event.getAction();
        if (action == Action.PHYSICAL) {
            event.setCancelled(true);
            return;
        }
        if ((action == Action.RIGHT_CLICK_BLOCK || action == Action.LEFT_CLICK_BLOCK)
                && event.getClickedBlock() != null) {
            event.setUseInteractedBlock(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (!this.appliesBuildProtection(event.getBlock().getWorld())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (!this.appliesBuildProtection(event.getBlock().getWorld())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        if (!this.appliesBuildProtection(event.getBlock().getWorld())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (!this.appliesBuildProtection(event.getBlock().getWorld())) {
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
}
