package network.skypvp.extraction.listener;

import network.skypvp.extraction.integration.WeaponMechanicsBridge;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;

/**
 * Blocks vanilla and WeaponMechanics explosions in the extraction hub, while allowing
 * WeaponMechanics block damage during active breach raids.
 */
public final class ExtractionEnvironmentGuard implements Listener {

    public static final String BUILD_BYPASS_PERMISSION = "skypvp.build.bypass";

    private final WeaponMechanicsBridge weaponMechanicsBridge;

    public ExtractionEnvironmentGuard(WeaponMechanicsBridge weaponMechanicsBridge) {
        this.weaponMechanicsBridge = weaponMechanicsBridge;
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

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        World world = event.getBlock().getWorld();
        if (!this.isBreachWorld(world)) {
            return;
        }
        Player player = event.getPlayer();
        if (this.canBypassBuild(player)) {
            return;
        }
        if (this.weaponMechanicsBridge != null && this.weaponMechanicsBridge.isWeaponItem(player.getInventory().getItemInMainHand())) {
            return;
        }
        if (this.weaponMechanicsBridge != null && this.weaponMechanicsBridge.isWeaponItem(player.getInventory().getItemInOffHand())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        World world = event.getBlock().getWorld();
        if (!this.isBreachWorld(world)) {
            return;
        }
        if (!this.canBypassBuild(event.getPlayer())) {
            event.setCancelled(true);
        }
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

    private boolean canBypassBuild(Player player) {
        if (player == null) {
            return true;
        }
        GameMode mode = player.getGameMode();
        return mode == GameMode.CREATIVE
                || mode == GameMode.SPECTATOR
                || player.hasPermission(BUILD_BYPASS_PERMISSION);
    }
}
