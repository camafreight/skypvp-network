package network.skypvp.extraction.listener;

import network.skypvp.extraction.integration.WeaponMechanicsBridge;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * Blocks vanilla and WeaponMechanics explosions in the extraction hub, while allowing
 * WeaponMechanics block damage during active breach raids.
 *
 * <p>Breach worlds also lock player block break/place/interact for every gamemode and permission.
 * Hub worlds rely on paper-core {@code WorldEnvironmentListener} for the same lock.
 */
public final class ExtractionEnvironmentGuard implements Listener {

    /** @deprecated Bypass is intentionally unused; kept for callers that still reference the constant. */
    @Deprecated
    public static final String BUILD_BYPASS_PERMISSION = "skypvp.build.bypass";

    public ExtractionEnvironmentGuard() {
    }

    /** @param weaponMechanicsBridge unused; retained for call-site compatibility */
    public ExtractionEnvironmentGuard(WeaponMechanicsBridge weaponMechanicsBridge) {
        this();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        World world = event.getLocation().getWorld();
        if (world == null) {
            return;
        }
        if (this.isHubWorld(world)) {
            this.cancelExplosion(event);
            return;
        }
        if (this.isBreachWorld(world) && !this.isWeaponMechanicsExplosion(event.getEntity())) {
            this.cancelExplosion(event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        World world = event.getBlock().getWorld();
        if (world == null) {
            return;
        }
        if (this.isHubWorld(world) || this.isBreachWorld(world)) {
            this.cancelExplosion(event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplosionPrime(ExplosionPrimeEvent event) {
        Entity entity = event.getEntity();
        if (entity == null) {
            return;
        }
        World world = entity.getWorld();
        if (this.isHubWorld(world)) {
            event.setCancelled(true);
            return;
        }
        if (this.isBreachWorld(world) && !(entity instanceof Player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!this.isBreachWorld(event.getBlock().getWorld())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!this.isBreachWorld(event.getBlock().getWorld())) {
            return;
        }
        event.setCancelled(true);
    }

    /**
     * Denies doors/chests/buttons/etc. Item-use still fires so guns/consumables work.
     * Breach loot chests open via their own listener reading the clicked block.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!this.isBreachWorld(event.getPlayer().getWorld())) {
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
        if (!this.isBreachWorld(event.getBlock().getWorld())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (!this.isBreachWorld(event.getBlock().getWorld())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        if (!this.isBreachWorld(event.getBlock().getWorld())) {
            return;
        }
        event.setCancelled(true);
    }

    private boolean isHubWorld(World world) {
        return world != null && !this.isBreachWorld(world);
    }

    private boolean isBreachWorld(World world) {
        return world != null && world.getName().startsWith("breach_");
    }

    private boolean isWeaponMechanicsExplosion(Entity entity) {
        return entity instanceof Player;
    }

    private void cancelExplosion(EntityExplodeEvent event) {
        event.setCancelled(true);
        event.blockList().clear();
    }

    private void cancelExplosion(BlockExplodeEvent event) {
        event.setCancelled(true);
        event.blockList().clear();
    }
}
