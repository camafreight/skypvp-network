package network.skypvp.extraction.listener;

import java.util.Objects;
import java.util.Optional;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.engine.BreachInstance;
import network.skypvp.extraction.gameplay.BreachExtractService;
import network.skypvp.extraction.gameplay.BreachCombatFeedback;
import network.skypvp.extraction.model.BreachState;
import network.skypvp.paper.PaperCorePlugin;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

public final class BreachListener implements Listener {

    private final BreachEngine engine;
    private final BreachExtractService extractService;

    public BreachListener(BreachEngine engine, BreachExtractService extractService) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.extractService = Objects.requireNonNull(extractService, "extractService");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // TAB is global, so a newly-connected player would otherwise see in-progress spectators in their list.
        if (this.engine.spectatorService() != null) {
            this.engine.spectatorService().hideActiveSpectatorsFrom(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        this.extractService.clearPlayer(player);
        boolean wasSpectating = this.engine.isSpectating(player);
        if (this.engine.spectatorService() != null) {
            this.engine.spectatorService().handleQuit(player);
        }
        this.engine.instanceFor(player).ifPresent(instance -> {
            if (instance.hasExtracted(player.getUniqueId())) {
                instance.leave(player);
                this.engine.worldPool().releaseIfIdle(instance);
                instance.clearPlayerLocations(player.getUniqueId());
                return;
            }
            if (wasSpectating || instance.isEliminated(player.getUniqueId())) {
                if (this.engine.gameplayCoordinator() != null) {
                    this.engine.gameplayCoordinator().inventoryBridge().onSpectatorExitRaid(player);
                }
                instance.leave(player);
                this.engine.worldPool().releaseIfIdle(instance);
                instance.clearPlayerLocations(player.getUniqueId());
                return;
            }
            this.engine.handleDisconnect(player);
            instance.leave(player);
            this.engine.worldPool().releaseIfIdle(instance);
            instance.clearPlayerLocations(player.getUniqueId());
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeathCaptureLoot(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        Optional<BreachInstance> instanceOptional = this.resolveInstance(player);
        if (instanceOptional.isEmpty() || instanceOptional.get().state() != BreachState.ACTIVE) {
            return;
        }
        if (this.engine.gameplayCoordinator() == null || this.engine.gameplayCoordinator().core() == null) {
            return;
        }
        var core = this.engine.gameplayCoordinator().core();
        if (core.playerInventoryManager() == null) {
            return;
        }
        this.engine.gameplayCoordinator().corpseService().rememberDeathLoot(
                player,
                core.playerInventoryManager(),
                core.coreHotbarService()
        );
    }

    /**
     * No-death-screen elimination: intercept the killing blow before it lands. Cancelling the fatal damage means the
     * raider never sees the vanilla death screen and never goes through {@link PlayerRespawnEvent} (which was sending
     * them to the hub spawn). Instead {@link BreachEngine#eliminateOnFatalDamage} drops their corpse where they fell
     * and puts them straight into soft-spectator mode. Runs before the (now effectively fallback) death handlers.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFatalDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Optional<BreachInstance> instanceOptional = this.resolveInstance(player);
        if (instanceOptional.isEmpty()) {
            return;
        }
        BreachInstance instance = instanceOptional.get();
        java.util.UUID id = player.getUniqueId();
        if (instance.state() != BreachState.ACTIVE
                || !instance.containsPlayer(id)
                || instance.hasExtracted(id)
                || instance.isEliminated(id)
                || instance.isPendingJoin(id)
                || this.engine.isSpectating(player)) {
            return;
        }
        double effectiveHealth = player.getHealth() + player.getAbsorptionAmount();
        if (event.getFinalDamage() < effectiveHealth) {
            return;
        }
        event.setCancelled(true);
        this.extractService.clearCombat(player);
        Player killer = event instanceof EntityDamageByEntityEvent byEntity
                ? this.resolvePlayerDamager(byEntity.getDamager())
                : null;
        if (killer != null) {
            instance.recordSessionDeath(player.getUniqueId(), killer.getUniqueId());
        } else {
            instance.recordSessionDeath(player.getUniqueId(), null);
        }
        this.engine.eliminateOnFatalDamage(player, instance, killer);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        Optional<BreachInstance> instanceOptional = this.resolveInstance(player);
        if (instanceOptional.isEmpty()) {
            return;
        }
        BreachInstance instance = instanceOptional.get();
        if (!instance.handlePlayerDeath(player, event)) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setKeepInventory(false);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Optional<BreachInstance> instanceOptional = this.resolveInstance(player);
        if (instanceOptional.isEmpty()) {
            return;
        }
        BreachInstance instance = instanceOptional.get();
        if (!instance.isEliminated(player.getUniqueId())) {
            return;
        }
        Location respawn = instance.breachAnchor(player.getUniqueId())
                .or(instance::eliminatedRespawnLocation)
                .orElseGet(() -> event.getRespawnLocation());
        event.setRespawnLocation(respawn);
        this.engine.scheduler().runOnPlayer(player, () -> {
            instance.finishEliminatedRespawn(player);
            if (this.engine.gameplayCoordinator() != null) {
                this.engine.gameplayCoordinator().corpseService().showCorpsesInWorld(player);
            }
        });
    }

    private PaperCorePlugin corePlugin() {
        return this.engine.gameplayCoordinator() == null ? null : this.engine.gameplayCoordinator().core();
    }

    private Optional<BreachInstance> resolveInstance(Player player) {
        Optional<BreachInstance> byPlayer = this.engine.instanceFor(player);
        if (byPlayer.isPresent()) {
            return byPlayer;
        }
        return this.engine.instanceForWorld(player.getWorld());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpectatorDealDamage(EntityDamageByEntityEvent event) {
        Player attacker = resolvePlayerDamager(event.getDamager());
        if (attacker != null && this.engine.isSpectating(attacker)) {
            event.setCancelled(true);
        }
    }

    /**
     * Lock soft-spectators out of every world/item interaction. The leave/menu hotbar items still work because
     * {@code CoreHotbarListener} dispatches their actions at NORMAL priority (before these HIGHEST handlers run)
     * and cancels the event itself - {@code ignoreCancelled = true} then skips this block for those items.
     * Corpse looting is additionally guarded at its single choke point ({@code openLoot}).
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpectatorDropItem(PlayerDropItemEvent event) {
        if (this.engine.isSpectating(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpectatorInteract(PlayerInteractEvent event) {
        if (this.engine.isSpectating(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpectatorInteractEntity(PlayerInteractEntityEvent event) {
        if (this.engine.isSpectating(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPartyFriendlyFire(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = resolvePlayerDamager(event.getDamager());
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }
        Optional<BreachInstance> instanceOptional = this.resolveInstance(victim);
        if (instanceOptional.isEmpty() || instanceOptional.get().state() != BreachState.ACTIVE) {
            return;
        }
        PaperCorePlugin core = this.corePlugin();
        if (core == null || core.partyGraphRepository() == null) {
            return;
        }
        if (!core.partyGraphRepository().inSameParty(attacker.getUniqueId(), victim.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCombatDamageFeedback(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Optional<BreachInstance> instanceOptional = this.resolveInstance(victim);
        if (instanceOptional.isEmpty()) {
            return;
        }
        BreachInstance instance = instanceOptional.get();
        java.util.UUID victimId = victim.getUniqueId();
        if (instance.state() != BreachState.ACTIVE
                || !instance.containsPlayer(victimId)
                || instance.hasExtracted(victimId)
                || instance.isEliminated(victimId)
                || instance.isPendingJoin(victimId)
                || this.engine.isSpectating(victim)) {
            return;
        }
        double damage = event.getFinalDamage();
        if (damage <= 0.0D) {
            return;
        }
        BreachCombatFeedback.showDamageTaken(victim, damage, this.corePlugin());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Player attacker = resolvePlayerDamager(event.getDamager());
        if (attacker != null) {
            engine.instanceFor(attacker).ifPresent(instance -> tagIfActive(attacker, instance));
        }
        if (event.getEntity() instanceof Player victim) {
            engine.instanceFor(victim).ifPresent(instance -> tagIfActive(victim, instance));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) {
            return;
        }
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        engine.instanceFor(event.getPlayer()).ifPresent(instance -> handleExtractZone(event.getPlayer(), instance, event));
    }

    private void tagIfActive(Player player, BreachInstance instance) {
        if (instance.state() == BreachState.ACTIVE
                && !instance.hasExtracted(player.getUniqueId())
                && !instance.isEliminated(player.getUniqueId())) {
            extractService.tagCombat(player);
        }
    }

    private Player resolvePlayerDamager(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    private void handleExtractZone(Player player, BreachInstance instance, PlayerMoveEvent event) {
        if (instance.state() != BreachState.ACTIVE
                || instance.hasExtracted(player.getUniqueId())
                || instance.isEliminated(player.getUniqueId())) {
            extractService.clearPlayer(player);
            return;
        }

        if (instance.isInExtractZone(event.getTo())) {
            extractService.onMovedInExtractZone(player, instance);
        } else {
            extractService.onLeftExtractZone(player);
        }
    }
}
