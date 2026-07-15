package network.skypvp.extraction.listener;

import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.engine.BreachInstance;
import network.skypvp.extraction.gameplay.BreachDisconnectedStandInService;
import network.skypvp.extraction.gameplay.BreachCombatDebugService;
import network.skypvp.extraction.gameplay.BreachExtractService;
import network.skypvp.extraction.gameplay.BreachFatalDamageMath;
import network.skypvp.extraction.gameplay.BreachDamageIndicatorService;
import network.skypvp.extraction.gameplay.BreachHitMarkerService;
import network.skypvp.extraction.gameplay.BreachPlayerVitality;
import network.skypvp.extraction.gameplay.BreachRuinsMobService;
import network.skypvp.extraction.gameplay.BreachSpawnSafety;
import network.skypvp.extraction.item.ExtractionCombatDefense;
import network.skypvp.extraction.item.ShieldCombatService;
import network.skypvp.extraction.model.BreachState;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.service.PlayerHealthService;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
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
import org.bukkit.util.Vector;

public final class BreachListener implements Listener {

    private static final long SUFFOCATION_RESCUE_COOLDOWN_MS = 1_500L;
    private static final int SUFFOCATION_RESCUE_MAX_SCAN = 16;

    // Small "you got shot" reaction. Deliberately far weaker than a sword hit (~0.4/0.36) so it reads as a flinch,
    // not a launch, and repeated hits are damped (existing velocity is halved) so full-auto fire can't stack it.
    private static final double SHOT_KNOCKBACK_HORIZONTAL = 0.22D;
    private static final double SHOT_KNOCKBACK_VERTICAL = 0.16D;

    private final BreachEngine engine;
    private final BreachExtractService extractService;
    private final BreachCombatDebugService combatDebug;
    private final BreachHitMarkerService hitMarker;
    private final BreachDamageIndicatorService damageIndicator;
    private final Map<UUID, Long> lastSuffocationRescue = new ConcurrentHashMap<>();

    public BreachListener(
            BreachEngine engine,
            BreachExtractService extractService,
            BreachCombatDebugService combatDebug,
            BreachHitMarkerService hitMarker,
            BreachDamageIndicatorService damageIndicator
    ) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.extractService = Objects.requireNonNull(extractService, "extractService");
        this.combatDebug = combatDebug;
        this.hitMarker = hitMarker;
        this.damageIndicator = damageIndicator;
    }

    /**
     * Folia rejects a reconnecting client at the end of the configuration phase ("You logged in from another
     * location") while the hung AFK body still occupies the uuid in the player list — it removed vanilla's
     * kick-the-old-player duplicate handling. This event is the LAST plugin-blockable configuration task, running
     * after registry sync / resource packs and immediately before spawn preparation: the pipeline waits for handlers
     * to return, so evicting here (blocking until the despawn completed) frees the uuid right before the duplicate
     * check while keeping the body in the world for all but the final ~1 network round-trip of the reconnect.
     */
    @EventHandler
    public void onConnectionConfigure(AsyncPlayerConnectionConfigureEvent event) {
        BreachDisconnectedStandInService standIn = this.engine.disconnectedStandIns();
        if (standIn == null) {
            return;
        }
        PlayerProfile profile = event.getConnection().getProfile();
        UUID playerId = profile == null ? null : profile.getId();
        if (playerId == null) {
            return;
        }
        if (this.engine.isEliminatedInActiveRaid(playerId)) {
            standIn.remove(playerId);
            return;
        }
        if (standIn.hasStandIn(playerId)) {
            standIn.evictForReconnect(playerId);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        this.applyHealthOnJoin(player);
        this.engine.instanceFor(player).ifPresent(instance -> {
            if (instance.state() != BreachState.ACTIVE) {
                return;
            }
            UUID playerId = player.getUniqueId();
            if (instance.isEliminated(playerId)) {
                this.engine.rejoinEliminatedSpectator(player, instance);
            } else if (instance.isDisconnected(playerId)) {
                this.engine.resumeDisconnectedRaider(player, instance);
            }
        });
        if (this.engine.spectatorService() != null) {
            this.engine.spectatorService().hideActiveSpectatorsFrom(player);
        }
        if (this.engine.gameplayCoordinator() != null) {
            this.engine.gameplayCoordinator().refreshTabVisibility();
        }
    }

    /** A killable hung disconnected body was killed → eliminate its owner and drop their escrow as loot. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onHeadlessStandInDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        network.skypvp.extraction.gameplay.BreachDisconnectedStandInService standInService = this.engine.disconnectedStandIns();
        if (standInService == null || !standInService.isHeadlessBody(dead)) {
            return;
        }
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        Player killer = dead.getKiller();
        String killerName = killer != null ? killer.getName() : null;
        standInService.byOwner(dead.getUniqueId()).ifPresent(standIn -> {
            java.util.UUID killerId = killer != null ? killer.getUniqueId() : null;
            this.engine.instanceById(standIn.instanceId()).ifPresent(instance ->
                    instance.recordSessionDeath(standIn.ownerId(), killerId));
            if (this.hitMarker != null && killer != null && !killer.getUniqueId().equals(standIn.ownerId())) {
                this.hitMarker.playElimination(killer);
            }
        });
        this.engine.eliminateDisconnectedRaiderByHeadlessBody(dead, killerName);
    }

    /**
     * Health safety on login: re-apply the pool + heart scaling for a raider reconnecting mid-raid, otherwise reset
     * to vanilla so a crashed player is never stuck at the 40-health max in the extraction lobby.
     */
    private void applyHealthOnJoin(Player player) {
        PlayerHealthService healthService = this.healthService();
        if (healthService == null) {
            return;
        }
        Optional<BreachInstance> instanceOptional = this.resolveInstance(player);
        if (instanceOptional.isPresent() && this.isLiveBreachRaider(instanceOptional.get(), player)) {
            healthService.reapply(player, BreachPlayerVitality.RAID_MAX_HEALTH);
        } else {
            healthService.unenroll(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        PaperCorePlugin core = this.engine.gameplayCoordinator() == null
                ? null
                : this.engine.gameplayCoordinator().core();
        if (core != null
                && core.headlessPlayerService() != null
                && core.headlessPlayerService().isHeadless(player.getUniqueId())) {
            return;
        }
        boolean wasSpectating = this.engine.isSpectating(player);
        Optional<BreachInstance> instanceOptional = this.engine.instanceFor(player);
        if (instanceOptional.isPresent()) {
            BreachInstance instance = instanceOptional.get();
            if (instance.hasExtracted(player.getUniqueId())) {
                this.extractService.clearPlayer(player);
                instance.leave(player);
                this.engine.worldPool().releaseIfIdle(instance);
                instance.clearPlayerLocations(player.getUniqueId());
            } else if (wasSpectating || instance.isEliminated(player.getUniqueId())) {
                this.engine.releaseSpectatorSession(player, instance, true);
            } else {
                this.extractService.clearPlayer(player);
                if (instance.state() == BreachState.ACTIVE) {
                    // Reconnect-into-raid: hold the slot, escrow gear, and spawn a killable AFK stand-in. Do NOT leave.
                    this.engine.handleRaiderDisconnect(player, instance);
                } else {
                    this.engine.handleDisconnect(player);
                    instance.leave(player);
                    this.engine.worldPool().releaseIfIdle(instance);
                    instance.clearPlayerLocations(player.getUniqueId());
                }
            }
        } else if (wasSpectating) {
            this.engine.clearReconnectHints(player.getUniqueId());
            if (this.engine.spectatorService() != null) {
                this.engine.spectatorService().handleQuit(player);
            }
        }
        if (this.engine.gameplayCoordinator() != null) {
            this.engine.gameplayCoordinator().refreshTabVisibility();
        }
        if (this.combatDebug != null) {
            this.combatDebug.clearPlayer(player.getUniqueId());
        }
        if (this.hitMarker != null) {
            this.hitMarker.clear(player.getUniqueId());
        }
        this.lastSuffocationRescue.remove(player.getUniqueId());
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
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInfuseDefense(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Optional<BreachInstance> instanceOptional = this.resolveInstance(victim);
        if (instanceOptional.isEmpty()) {
            return;
        }
        if (!this.isLiveBreachRaider(instanceOptional.get(), victim)) {
            return;
        }
        if (this.cancelPartyFriendlyFire(event, victim, instanceOptional.get())) {
            return;
        }
        PaperCorePlugin core = this.corePlugin();
        ExtractionCombatDefense.DamageAdjustment adjustment = ExtractionCombatDefense.applyToDamage(core, victim, event);
        ShieldCombatService.ShieldOutcome shield = ShieldCombatService.absorb(core, victim, event);
        if (this.combatDebug != null) {
            this.combatDebug.stageAdjustment(victim, adjustment, shield);
        }
        this.playAttackerHitMarker(core, victim, event, shield);
        this.scheduleVitalsRefresh(core, victim);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRuinsMobHitMarker(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim) || victim instanceof Player) {
            return;
        }
        if (this.hitMarker == null || this.damageIndicator == null) {
            return;
        }
        if (this.engine.instanceForWorld(victim.getWorld()).isEmpty()) {
            return;
        }
        if (!BreachRuinsMobService.isRuinsGunnerEntity(victim)) {
            return;
        }
        Player attacker = this.resolvePlayerDamager(event.getDamager());
        if (attacker == null || this.engine.isSpectating(attacker)) {
            return;
        }
        double healthDamage = Math.max(0.0D, event.getFinalDamage());
        if (healthDamage <= 0.0D) {
            return;
        }
        BreachHitMarkerService.HitType type = this.hitMarker.resolveAndPlay(attacker, victim, event, healthDamage, 0.0D);
        if (type != null) {
            this.damageIndicator.show(attacker, victim, healthDamage, type);
        }
    }

    /**
     * Pushes an action-bar refresh one tick after damage lands so the health count + shield bar track hits live
     * (the standard HUD refresh is only every {@code ActionBarService.REFRESH_TICKS}). Vanilla hearts already
     * update instantly since they are the real health bar.
     */
    private void scheduleVitalsRefresh(PaperCorePlugin core, Player victim) {
        if (core == null || core.actionBarService() == null) {
            return;
        }
        this.engine.scheduler().runOnPlayerLater(victim, () -> {
            if (victim.isOnline() && core.actionBarService() != null) {
                core.actionBarService().refreshPlayer(victim);
            }
        }, 1L);
    }

    /**
     * Plays the attacker's per-shot hit-marker sound. Runs at LOW (after defense + shield) so the shield-vs-health
     * split is known; party members hitting each other are skipped to match {@link #onPartyFriendlyFire}.
     */
    private void playAttackerHitMarker(
            PaperCorePlugin core,
            LivingEntity victim,
            EntityDamageEvent event,
            ShieldCombatService.ShieldOutcome shield
    ) {
        if (this.hitMarker == null || !(event instanceof EntityDamageByEntityEvent byEntity)) {
            return;
        }
        if (!(victim instanceof Player playerVictim)) {
            return;
        }
        Player attacker = this.resolvePlayerDamager(byEntity.getDamager());
        if (attacker == null || attacker.getUniqueId().equals(playerVictim.getUniqueId()) || this.engine.isSpectating(attacker)) {
            return;
        }
        if (core != null && core.partyGraphRepository() != null
                && core.partyGraphRepository().inSameParty(attacker.getUniqueId(), playerVictim.getUniqueId())) {
            return;
        }
        // A confirmed enemy hit: give the victim a small physical reaction so getting shot feels impactful.
        this.applyShotKnockback(playerVictim, attacker, event);
        double shieldAbsorbed = shield == null ? 0.0D : shield.absorbed();
        double healthDamage = Math.max(0.0D, event.getFinalDamage());
        BreachHitMarkerService.HitType type = this.hitMarker.resolveAndPlay(attacker, playerVictim, event, healthDamage, shieldAbsorbed);
        if (this.damageIndicator != null && type != null) {
            this.damageIndicator.show(attacker, playerVictim, healthDamage + shieldAbsorbed, type);
        }
    }

    /**
     * Applies a light knockback to a shot victim, pushing them away from the shooter (horizontally, with a small
     * upward pop). Vanilla melee is skipped because it already knocks back. Existing velocity is halved before adding
     * the impulse so sustained fire nudges rather than launches. Runs on the victim's region thread (this is their
     * damage event), so {@code setVelocity} is safe here.
     */
    private void applyShotKnockback(Player victim, Player attacker, EntityDamageEvent event) {
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || cause == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
            return;
        }
        Location victimLoc = victim.getLocation();
        Location attackerLoc = attacker.getLocation();
        if (victimLoc.getWorld() == null || attackerLoc.getWorld() == null
                || !victimLoc.getWorld().equals(attackerLoc.getWorld())) {
            return;
        }
        Vector push = victimLoc.toVector().subtract(attackerLoc.toVector());
        push.setY(0.0D);
        if (push.lengthSquared() < 1.0E-6) {
            // Shooter is in the same column (directly above/below); fall back to their facing direction.
            push = attackerLoc.getDirection().setY(0.0D);
            if (push.lengthSquared() < 1.0E-6) {
                return;
            }
        }
        push.normalize().multiply(SHOT_KNOCKBACK_HORIZONTAL);
        Vector current = victim.getVelocity();
        victim.setVelocity(new Vector(
                current.getX() * 0.5D + push.getX(),
                Math.max(current.getY() * 0.5D, 0.0D) + SHOT_KNOCKBACK_VERTICAL,
                current.getZ() * 0.5D + push.getZ()
        ));
    }

    /**
     * Extraction disables passive (food/idle) regeneration so the 40-health pool only recovers through items. Potion
     * and custom heals (Stim, etc.) still apply, but are capped so a single heal restores a fixed intake instead of
     * scaling with the larger raid pool — a "heal 20" adds 20, it does not top a half-full bar all the way to 40.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onManagedRegen(org.bukkit.event.entity.EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        PlayerHealthService healthService = this.healthService();
        if (healthService == null || !healthService.isManaged(player.getUniqueId())) {
            return;
        }
        org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason reason = event.getRegainReason();
        if (reason == org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason.SATIATED
                || reason == org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason.REGEN) {
            event.setCancelled(true);
            return;
        }
        double cap = this.engine.configService() != null
                ? this.engine.configService().maxHealPerEvent()
                : 20.0D;
        if (cap > 0.0D && event.getAmount() > cap) {
            event.setAmount(cap);
        }
    }

    private PlayerHealthService healthService() {
        PaperCorePlugin core = this.corePlugin();
        return core == null ? null : core.playerHealthService();
    }

    /**
     * Suffocation rescue. A raider who gets buried in blocks (map geometry, a glitch, or shoving into a wall) would
     * otherwise take {@code SUFFOCATION} damage every tick and be eliminated by {@link #onFatalDamage}. This runs at
     * {@code LOWEST} (before the defense/fatal handlers, which are {@code ignoreCancelled = true}) and:
     * <ol>
     *   <li>confirms the head is genuinely encased in a solid block ({@link BreachSpawnSafety#isHeadEncased}) so
     *       glitchers can't fake the state to earn a free teleport;</li>
     *   <li>cancels the damage tick so a wall can never eliminate a live raider;</li>
     *   <li>lifts them (rate-limited) to the nearest standable surface directly above.</li>
     * </ol>
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSuffocationRescue(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.SUFFOCATION) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Optional<BreachInstance> instanceOptional = this.resolveInstance(player);
        if (instanceOptional.isEmpty() || !this.isLiveBreachRaider(instanceOptional.get(), player)) {
            return;
        }
        if (!BreachSpawnSafety.isHeadEncased(player)) {
            return;
        }
        // Cancel every suffocating tick regardless of the teleport cooldown so the wall can never eliminate them.
        event.setCancelled(true);
        long now = System.currentTimeMillis();
        Long last = this.lastSuffocationRescue.get(player.getUniqueId());
        if (last != null && now - last < SUFFOCATION_RESCUE_COOLDOWN_MS) {
            return;
        }
        this.lastSuffocationRescue.put(player.getUniqueId(), now);
        Location target = BreachSpawnSafety
                .findStandableSurfaceAbove(player.getLocation(), SUFFOCATION_RESCUE_MAX_SCAN)
                .orElse(null);
        if (target == null) {
            // No surface found within range; damage is already cancelled so they stay alive until they can move out.
            return;
        }
        this.engine.scheduler().runOnPlayer(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            player.teleportAsync(target);
            player.sendMessage(network.skypvp.extraction.text.ExtractionTexts.miniMessage(
                    player,
                    "extraction.breach.suffocation.rescued"
            ));
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFatalDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Optional<BreachInstance> instanceOptional = this.resolveInstance(player);
        if (instanceOptional.isEmpty()) {
            return;
        }
        BreachInstance instance = instanceOptional.get();
        if (!this.isLiveBreachRaider(instance, player)) {
            return;
        }
        if (!BreachFatalDamageMath.wouldEliminate(player, event)) {
            return;
        }
        BreachCombatDebugService.PendingCapture pending = this.combatDebug != null && this.combatDebug.shouldTrack(player)
                ? this.combatDebug.consumePending(player.getUniqueId())
                : null;
        Player killer = event instanceof EntityDamageByEntityEvent byEntity
                ? this.resolveBreachKiller(instance, byEntity)
                : null;
        if (this.combatDebug != null && this.combatDebug.shouldTrack(player)) {
            this.combatDebug.recordElimination(
                    player,
                    instance,
                    event,
                    killer != null ? killer.getName() : null,
                    pending
            );
        } else if (this.combatDebug != null) {
            this.combatDebug.discardPending(player.getUniqueId());
        }
        event.setCancelled(true);
        this.extractService.clearCombat(player);
        if (killer != null) {
            instance.recordSessionDeath(player.getUniqueId(), killer.getUniqueId());
        } else {
            instance.recordSessionDeath(player.getUniqueId(), null);
        }
        if (this.hitMarker != null && killer != null && !killer.getUniqueId().equals(player.getUniqueId())) {
            this.hitMarker.playElimination(killer);
        }
        this.engine.eliminateOnFatalDamage(player, instance, killer);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSuppressVanillaDeathMessage(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        Optional<BreachInstance> instanceOptional = this.resolveInstance(player);
        if (instanceOptional.isEmpty()) {
            return;
        }
        event.deathMessage(null);
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

    private boolean isLiveBreachRaider(BreachInstance instance, Player player) {
        java.util.UUID id = player.getUniqueId();
        return instance.state() == BreachState.ACTIVE
                && instance.containsPlayer(id)
                && !instance.hasExtracted(id)
                && !instance.isEliminated(id)
                && !instance.isPendingJoin(id)
                && !this.engine.isSpectating(player);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpectatorDealDamage(EntityDamageByEntityEvent event) {
        Player attacker = resolvePlayerDamager(event.getDamager());
        if (attacker != null && this.engine.isSpectating(attacker)) {
            event.setCancelled(true);
            return;
        }
        if (event.getEntity() instanceof Player victim
                && this.engine.isSpectating(victim)
                && !(event.getDamager() instanceof Player)) {
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

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPartyFriendlyFire(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Optional<BreachInstance> instanceOptional = this.resolveInstance(victim);
        if (instanceOptional.isEmpty()) {
            return;
        }
        this.cancelPartyFriendlyFire(event, victim, instanceOptional.get());
    }

    /**
     * Party members cannot damage each other during an active breach. Must run before shield absorption (LOW) so
     * friendly fire never drains a squad-mate's shield.
     */
    private boolean cancelPartyFriendlyFire(EntityDamageEvent event, Player victim, BreachInstance instance) {
        if (!(event instanceof EntityDamageByEntityEvent byEntity) || instance.state() != BreachState.ACTIVE) {
            return false;
        }
        Player attacker = resolvePlayerDamager(byEntity.getDamager());
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) {
            return false;
        }
        PaperCorePlugin core = this.corePlugin();
        if (core == null || core.partyGraphRepository() == null) {
            return false;
        }
        if (!core.partyGraphRepository().inSameParty(attacker.getUniqueId(), victim.getUniqueId())) {
            return false;
        }
        event.setCancelled(true);
        return true;
    }

    /**
     * Records debug combat lines and always discards staged defense captures so cancelled hits (party FF, spectator
     * blocks, etc.) cannot leak pending state. Runs on the entity region thread — no async offload.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCombatDebugFinalize(EntityDamageEvent event) {
        if (this.combatDebug == null || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        java.util.UUID victimId = victim.getUniqueId();
        if (!this.combatDebug.shouldTrack(victim)) {
            this.combatDebug.discardPending(victimId);
            return;
        }
        if (event.isCancelled()) {
            this.combatDebug.discardPending(victimId);
            return;
        }
        Optional<BreachInstance> instanceOptional = this.resolveInstance(victim);
        if (instanceOptional.isEmpty() || !this.isLiveBreachRaider(instanceOptional.get(), victim)) {
            this.combatDebug.discardPending(victimId);
            return;
        }
        // Consume the staged capture first: a shot fully absorbed by the shield leaves finalDamage at 0, but the
        // shield outcome (absorbed points) still makes it a real hit that must be logged. Only discard when neither
        // health nor shield took anything.
        BreachCombatDebugService.PendingCapture pending = this.combatDebug.consumePending(victimId);
        double damage = event.getFinalDamage();
        double shieldAbsorbed = pending != null && pending.shieldOutcome() != null
                ? pending.shieldOutcome().absorbed()
                : 0.0D;
        if (damage <= 0.0D && shieldAbsorbed <= 0.0D) {
            return;
        }
        String attackerName = null;
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Player attacker = this.resolvePlayerDamager(byEntity.getDamager());
            if (attacker != null) {
                attackerName = attacker.getName();
            }
        }
        this.combatDebug.recordDamage(victim, instanceOptional.get(), event, attackerName, pending);
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

    private Player resolveBreachKiller(BreachInstance instance, EntityDamageByEntityEvent event) {
        Player killer = resolvePlayerDamager(event.getDamager());
        if (killer == null || instance == null) {
            return null;
        }
        java.util.UUID killerId = killer.getUniqueId();
        if (!instance.containsPlayer(killerId)
                || instance.isEliminated(killerId)
                || instance.hasExtracted(killerId)
                || instance.isPendingJoin(killerId)
                || this.engine.isSpectating(killer)) {
            return null;
        }
        return killer;
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
