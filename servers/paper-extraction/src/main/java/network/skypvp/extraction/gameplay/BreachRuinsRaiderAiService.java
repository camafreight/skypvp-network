package network.skypvp.extraction.gameplay;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.extraction.ai.raider.RaiderAgentContext;
import network.skypvp.extraction.ai.raider.RaiderCombatTargets;
import network.skypvp.extraction.ai.raider.RaiderGroupRegistry;
import network.skypvp.extraction.ai.raider.RaiderGroupSnapshot;
import network.skypvp.extraction.ai.raider.RaiderGunnerStateTree;
import network.skypvp.extraction.ai.raider.RaiderPerceptionSupport;
import network.skypvp.extraction.ai.raider.RaiderPatrolSupport;
import network.skypvp.extraction.ai.raider.RaiderWeaponProfile;
import network.skypvp.paper.ai.statetree.CombatAgentStateId;
import network.skypvp.paper.ai.statetree.StateTreeEngine;
import network.skypvp.paper.integration.LivingEntitySprintBridge;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.engine.BreachInstance;
import network.skypvp.extraction.model.BreachMapMeta;
import network.skypvp.extraction.model.BreachState;
import network.skypvp.extraction.integration.LibsDisguisesBridge;
import network.skypvp.extraction.integration.WeaponMechanicsCombatBridge;
import network.skypvp.paper.PaperCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;

/**
 * State-tree gunner AI for Ruins raiders: engage bursts, sprint to cover under fire, reload, sidearm swap.
 */
public final class BreachRuinsRaiderAiService implements Listener {

    private static final long DISGUISE_REFRESH_INTERVAL_MS = 4_000L;
    private static final long CHUNK_RECONCILE_INTERVAL_TICKS = 20L;
    private static final long ORPHAN_RECONCILE_INTERVAL_TICKS = 40L;
    private static final long GROUP_REBUILD_INTERVAL_TICKS = 10L;
    private static final long GHOST_AGENT_EVICTION_TICKS = 200L;
    private static final long INSPECT_ENGAGEMENT_TICKS = 120L;
    private static final double INSPECT_NOTIFY_RADIUS_SQ = 48.0D * 48.0D;

    private final PaperCorePlugin core;
    private final BreachEngine breachEngine;
    private final BreachMobChunkService mobChunkService;
    private final WeaponMechanicsCombatBridge combat;
    private final LivingEntitySprintBridge sprintBridge;
    private final BreachGunfireTracker gunfireTracker;
    private BreachRuinsMobNametagService nametagService;
    private final RaiderGroupRegistry groupRegistry = new RaiderGroupRegistry();
    private final Map<UUID, AgentRuntime> agents = new ConcurrentHashMap<>();
    /**
     * Global monotonic AI clock, incremented once per service tick and stamped onto every
     * agent's {@code aiTick}. Never use {@code world.getFullTime()} for AI timers: on Folia
     * it is region-local and jumps across region splits/merges, freezing cooldowns.
     */
    private volatile long serviceTickCounter;

    public BreachRuinsRaiderAiService(
            PaperCorePlugin core,
            BreachEngine breachEngine,
            BreachMobChunkService mobChunkService,
            WeaponMechanicsCombatBridge combat,
            LivingEntitySprintBridge sprintBridge,
            BreachGunfireTracker gunfireTracker
    ) {
        this.core = Objects.requireNonNull(core, "core");
        this.breachEngine = Objects.requireNonNull(breachEngine, "breachEngine");
        this.mobChunkService = Objects.requireNonNull(mobChunkService, "mobChunkService");
        this.combat = Objects.requireNonNull(combat, "combat");
        this.sprintBridge = Objects.requireNonNull(sprintBridge, "sprintBridge");
        this.gunfireTracker = gunfireTracker;
    }

    public void bindNametagService(BreachRuinsMobNametagService nametagService) {
        this.nametagService = nametagService;
    }

    /**
     * Global heartbeat: squad/chunk bookkeeping, then dispatch every agent onto its
     * <strong>cached chunk's region thread</strong>. EntityScheduler fixed-rate AI stalls
     * during Folia region handoffs when gunners/players cross chunk borders — freezing
     * pathfinding and return fire. Global → RegionScheduler keeps combat ticks flowing;
     * if the entity already left the cached chunk we hop once via {@code runAtEntity}.
     */
    public void tick() {
        long tickCounter = ++serviceTickCounter;
        if (tickCounter % GROUP_REBUILD_INTERVAL_TICKS == 0L) {
            try {
                rebuildGroups();
            } catch (RuntimeException ex) {
                // Never let a squad rebuild abort agent ticking for the whole service.
                core.getLogger().log(java.util.logging.Level.FINE, "[Breach] Squad rebuild failed", ex);
            }
        }
        if (tickCounter % CHUNK_RECONCILE_INTERVAL_TICKS == 0L) {
            reconcileTrackedChunksFromContext();
        }
        if (tickCounter % ORPHAN_RECONCILE_INTERVAL_TICKS == 0L) {
            reconcileOrphanAgents();
        }
        for (Map.Entry<UUID, AgentRuntime> entry : agents.entrySet()) {
            dispatchAgentTick(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Schedules one AI tick on the region that last owned the gunner. Supersedes any still-queued
     * dispatch from a prior global tick so border hops cannot pile up deferred work.
     */
    private void dispatchAgentTick(UUID entityId, AgentRuntime runtime) {
        if (core.platform() == null) {
            tickAgentOnRegionThread(entityId, runtime);
            return;
        }
        int generation = runtime.dispatchGeneration.incrementAndGet();
        RaiderAgentContext context = runtime.context;
        Runnable body = () -> {
            if (runtime.dispatchGeneration.get() != generation) {
                return;
            }
            tickAgentOnRegionThread(entityId, runtime);
        };
        if (context.trackedChunkValid && context.trackedChunkWorldId != null) {
            World world = Bukkit.getWorld(context.trackedChunkWorldId);
            if (world != null) {
                core.platform().runAtChunk(world, context.trackedChunkX, context.trackedChunkZ, () -> {
                    if (runtime.dispatchGeneration.get() != generation) {
                        return;
                    }
                    LivingEntity entity = living(entityId);
                    if (entity != null && entity.isValid() && !core.platform().isOwnedByCurrentRegion(entity)) {
                        core.platform().runAtEntity(entity, body);
                        return;
                    }
                    body.run();
                });
                return;
            }
        }
        LivingEntity entity = living(entityId);
        if (entity != null && entity.isValid()) {
            core.platform().runAtEntity(entity, body);
            return;
        }
        body.run();
    }

    private void tickAgentOnRegionThread(UUID entityId, AgentRuntime runtime) {
        LivingEntity entity = living(entityId);
        if (entity != null && entity.isDead()) {
            removeAgent(entityId);
            return;
        }
        if (entity == null || !entity.isValid()) {
            long tick = resolveWorldTick(runtime);
            if (runtime.unloadedSinceTick <= 0L) {
                runtime.unloadedSinceTick = tick;
            } else if (tick - runtime.unloadedSinceTick >= GHOST_AGENT_EVICTION_TICKS) {
                removeAgent(entityId);
            }
            return;
        }
        runtime.unloadedSinceTick = 0L;
        if (!isInActiveBreach(entity)) {
            removeAgent(entityId);
            return;
        }
        if (runtime.context.entity != entity) {
            retrackReloadedGunner(entityId, entity);
            return;
        }
        // Out-of-combat agents (IDLE covers patrol; no target, not under fire) tick at half
        // rate, staggered by entity id so the load spreads evenly across ticks. Combat and
        // navigation-heavy states keep full rate for responsiveness.
        CombatAgentStateId state = runtime.engine.currentState();
        boolean passive = state == CombatAgentStateId.IDLE
                && runtime.context.mob.getTarget() == null
                && !runtime.context.underFire(serviceTickCounter);
        if (passive && ((serviceTickCounter + (entityId.hashCode() & 1L)) & 1L) != 0L) {
            return;
        }
        tickAgent(entity, runtime);
    }

    private void rebuildGroups() {
        List<RaiderGroupRegistry.MemberRef> members = new ArrayList<>(agents.size());
        for (AgentRuntime runtime : agents.values()) {
            RaiderAgentContext context = runtime.context;
            if (!context.cachedLocationValid || context.cachedWorld == null) {
                continue;
            }
            members.add(new RaiderGroupRegistry.MemberRef(context.entity.getUniqueId(), context));
        }
        groupRegistry.rebuild(members);
    }

    public void track(LivingEntity entity, String mobType) {
        track(entity, mobType, 1);
    }

    public void track(LivingEntity entity, String mobType, int level) {
        if (entity == null || mobType == null || !(entity instanceof Mob mob)) {
            return;
        }
        if (agents.containsKey(entity.getUniqueId())) {
            return;
        }
        String normalizedType = mobType.toLowerCase(Locale.ROOT);
        RaiderWeaponProfile profile = RaiderWeaponProfile.forMobType(normalizedType, level);
        RaiderAgentContext context = new RaiderAgentContext(
                entity,
                mob,
                normalizedType,
                profile,
                combat,
                entity.getLocation()
        );
        context.captureLocation(entity);
        bindPatrolCohort(context, entity);
        tagGunner(entity, mobType);
        tagGunnerLevel(entity, level);
        StateTreeEngine<CombatAgentStateId, RaiderAgentContext> engine =
                RaiderGunnerStateTree.create(combat, sprintBridge);
        engine.start(context);
        mob.setTarget(null);
        org.bukkit.Bukkit.getMobGoals().removeAllGoals(mob);
        entity.setRemoveWhenFarAway(false);
        entity.setPersistent(true);
        if (combat.isAvailable()) {
            if (profile.isKnifeRusher()) {
                context.knifeEquipped = true;
                combat.equipWeapon(entity, profile.meleeWeapon());
            } else {
                combat.equipWeapon(entity, profile.primaryWeapon());
            }
        }
        AttributeInstance speed = entity.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) {
            speed.setBaseValue(profile.moveSpeed());
        }
        mobChunkService.retainAroundEntity(entity);
        AgentRuntime runtime = new AgentRuntime(engine, context, normalizedType, System.currentTimeMillis());
        context.captureLocation(entity);
        context.captureChunkAnchor(entity);
        agents.put(entity.getUniqueId(), runtime);
        // AI ticks come from the global heartbeat → region dispatch (not EntityScheduler timers).
        ensureDisguise(entity, agents.get(entity.getUniqueId()), true);
        if (nametagService != null) {
            nametagService.attachIfAbsent(entity, mobType, displayName(entity));
        }
    }

    private void bindPatrolCohort(RaiderAgentContext context, LivingEntity entity) {
        if (context == null || entity == null) {
            return;
        }
        BreachMapMeta mapMeta = breachEngine.instanceForWorld(entity.getWorld())
                .map(BreachInstance::mapMeta)
                .orElse(null);
        RaiderPatrolSupport.bindPatrolCohort(context, mapMeta);
    }

    private static String displayName(LivingEntity entity) {
        String raw = entity.getCustomName();
        if (raw == null || raw.isBlank()) {
            return "Raider";
        }
        return raw.replaceAll("(?i)§[0-9a-fk-or]", "").trim();
    }

    /** Re-bootstrap gunners, disguises, and nametags when players re-enter a breach world. */
    public void reconcileWorld(World world) {
        if (world == null || breachEngine.instanceForWorld(world).isEmpty()) {
            return;
        }
        for (AgentRuntime runtime : agents.values()) {
            LivingEntity entity = living(runtime.context.entity.getUniqueId());
            if (entity == null) {
                continue;
            }
            Runnable refresh = () -> {
                if (!entity.isValid() || entity.isDead() || !world.equals(entity.getWorld())) {
                    return;
                }
                bootstrapLoadedGunner(entity);
            };
            if (core.platform() != null) {
                core.platform().runAtEntity(entity, refresh);
            } else {
                refresh.run();
            }
        }
        reconcileOrphanAgents(world);
    }

    public void syncViewer(Player viewer, double radiusSq) {
        if (viewer == null || !viewer.isOnline()) {
            return;
        }
        World world = viewer.getWorld();
        for (AgentRuntime runtime : agents.values()) {
            LivingEntity entity = living(runtime.context.entity.getUniqueId());
            if (entity == null) {
                continue;
            }
            Runnable sync = () -> {
                if (!entity.isValid() || entity.isDead() || !world.equals(entity.getWorld())) {
                    return;
                }
                if (!viewer.isOnline()
                        || viewer.getLocation().distanceSquared(entity.getLocation()) > radiusSq) {
                    return;
                }
                ensureDisguise(entity, runtime, true);
                LibsDisguisesBridge.refreshDisguiseForPlayer(entity, viewer);
            };
            if (core.platform() != null) {
                core.platform().runAtEntity(entity, sync);
            } else {
                sync.run();
            }
        }
    }

    public void clearWorld(World world) {
        if (world == null) {
            return;
        }
        agents.entrySet().removeIf(entry -> {
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (entity != null && !world.equals(entity.getWorld())) {
                return false;
            }
            disposeRuntime(entry.getValue());
            return true;
        });
        groupRegistry.clearWorld(world);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamaged(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent byEntity
                && byEntity.getEntity() instanceof Player playerVictim
                && isInActiveBreach(playerVictim)) {
            LivingEntity damagerEntity = resolveDamagerEntity(byEntity.getDamager());
            if (damagerEntity != null) {
                AgentRuntime attackerRuntime = agents.get(damagerEntity.getUniqueId());
                if (attackerRuntime != null) {
                    Runnable markAttacker = () -> markPlayerCombat(attackerRuntime, playerVictim);
                    if (core.platform() != null) {
                        core.platform().runOwned(damagerEntity, markAttacker);
                    } else {
                        markAttacker.run();
                    }
                }
            }
        }
        if (!(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        if (!isInActiveBreach(victim)) {
            return;
        }
        AgentRuntime runtime = agents.get(victim.getUniqueId());
        if (runtime == null) {
            return;
        }
        Player attacker = event instanceof EntityDamageByEntityEvent byEntity
                ? resolveAttacker(byEntity.getDamager())
                : null;
        Runnable mark = () -> {
            long tick = runtime.context.aiTick;
            runtime.context.lastDamagedTick = tick;
            if (attacker == null) {
                return;
            }
            runtime.context.lastCombatPlayerId = attacker.getUniqueId();
            runtime.context.lastCombatPlayerTick = tick;
            RaiderPerceptionSupport.recordDamageThreat(runtime.context, attacker.getLocation(), tick);
            if (victim instanceof Mob mob) {
                assignCombatTarget(mob, attacker);
            }
            propagateCombatContact(attacker, tick);
            groupRegistry.alertGroup(victim.getUniqueId(), attacker, tick);
            propagateSquadContact(victim.getUniqueId(), tick);
            interruptPassiveState(runtime, tick);
        };
        if (core.platform() != null) {
            core.platform().runOwned(victim, mark);
        } else {
            mark.run();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        World world = event.getWorld();
        if (breachEngine.instanceForWorld(world).isEmpty()) {
            return;
        }
        for (Entity entity : event.getChunk().getEntities()) {
            if (entity instanceof LivingEntity living && living.isValid() && !living.isDead()) {
                bootstrapLoadedGunner(living);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        if (victim == null || !isInActiveBreach(victim)) {
            return;
        }
        onPlayerRemovedFromRaid(victim.getUniqueId(), victim.getWorld(), victim.getLocation().clone(), true);
    }

    /**
     * Called when a raid participant is eliminated or extracts. Clears combat focus immediately instead of waiting
     * for the next AI tick.
     */
    public void onPlayerRemovedFromRaid(UUID playerId, World world, Location lastLocation, boolean inspectBodies) {
        if (playerId == null || world == null) {
            return;
        }
        groupRegistry.purgeSharedTarget(playerId);
        Location focus = lastLocation != null ? lastLocation.clone() : null;
        for (AgentRuntime runtime : agents.values()) {
            LivingEntity entity = living(runtime.context.entity.getUniqueId());
            if (entity == null) {
                continue;
            }
            // Validity/world/focus checks read entity state — run them on the owning thread.
            Runnable release = () -> {
                if (!entity.isValid() || entity.isDead() || !world.equals(entity.getWorld())) {
                    return;
                }
                if (!RaiderPerceptionSupport.isFocusedOn(runtime.context, playerId)) {
                    return;
                }
                releaseAgentFocus(runtime, playerId, focus, inspectBodies);
            };
            if (core.platform() != null) {
                core.platform().runAtEntity(entity, release);
            } else {
                release.run();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityRemoved(EntityRemoveEvent event) {
        Entity entity = event.getEntity();
        if (entity == null) {
            return;
        }
        removeAgent(entity.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity != null) {
            removeAgent(entity.getUniqueId());
        }
    }

    private void reconcileOrphanAgents() {
        for (World world : Bukkit.getWorlds()) {
            reconcileOrphanAgents(world);
        }
    }

    private void reconcileOrphanAgents(World world) {
        if (world == null || breachEngine.instanceForWorld(world).isEmpty()) {
            return;
        }
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof LivingEntity living)) {
                continue;
            }
            if (agents.containsKey(living.getUniqueId())) {
                continue;
            }
            // Folia: isValid/isDead/PDC/track all require the entity's owning region thread;
            // only the UUID/type filters above are safe from the global tick.
            if (core.platform() != null) {
                core.platform().runAtEntity(living, () -> bootstrapLoadedGunner(living));
            } else {
                bootstrapLoadedGunner(living);
            }
        }
    }

    private void bootstrapLoadedGunner(LivingEntity entity) {
        if (entity == null || !entity.isValid() || entity.isDead() || !isInActiveBreach(entity)) {
            return;
        }
        Optional<String> mobType = BreachRuinsMobService.resolveMobType(entity);
        if (mobType.isEmpty() || !BreachRuinsMobService.isRuinsGunnerType(mobType.get())) {
            return;
        }
        tagGunner(entity, mobType.get());
        if (!agents.containsKey(entity.getUniqueId())) {
            track(entity, mobType.get());
            return;
        }
        AgentRuntime runtime = agents.get(entity.getUniqueId());
        if (runtime == null) {
            return;
        }
        if (runtime.context.entity != entity) {
            retrackReloadedGunner(entity.getUniqueId(), entity);
            return;
        }
        runtime.context.captureLocation(entity);
        ensureDisguise(entity, runtime, true);
        if (nametagService != null) {
            nametagService.attachIfAbsent(entity, mobType.get(), displayName(entity));
        }
    }

    private void reconcileTrackedChunksFromContext() {
        Map<World, List<BreachMobChunkService.PositionAnchor>> byWorld = new java.util.HashMap<>();
        for (AgentRuntime runtime : agents.values()) {
            RaiderAgentContext context = runtime.context;
            if (!context.trackedChunkValid || context.cachedWorld == null) {
                continue;
            }
            double x = (context.trackedChunkX << 4) + 8.0D;
            double z = (context.trackedChunkZ << 4) + 8.0D;
            byWorld.computeIfAbsent(context.cachedWorld, ignored -> new ArrayList<>())
                    .add(new BreachMobChunkService.PositionAnchor(x, z));
        }
        for (Map.Entry<World, List<BreachMobChunkService.PositionAnchor>> entry : byWorld.entrySet()) {
            mobChunkService.updateTrackedPositions(entry.getKey(), entry.getValue());
        }
    }

    private static void tagGunner(LivingEntity entity, String mobType) {
        if (entity == null || mobType == null || RaiderGunnerKeys.mobTypeKey() == null) {
            return;
        }
        entity.getPersistentDataContainer().set(
                RaiderGunnerKeys.mobTypeKey(),
                PersistentDataType.STRING,
                mobType.toLowerCase(Locale.ROOT)
        );
    }

    private static void tagGunnerLevel(LivingEntity entity, int level) {
        if (entity == null || RaiderGunnerKeys.levelKey() == null) {
            return;
        }
        entity.getPersistentDataContainer().set(
                RaiderGunnerKeys.levelKey(),
                PersistentDataType.INTEGER,
                Math.max(1, level)
        );
    }

    private static int storedGunnerLevel(LivingEntity entity) {
        if (entity == null || RaiderGunnerKeys.levelKey() == null) {
            return 1;
        }
        Integer level = entity.getPersistentDataContainer().get(RaiderGunnerKeys.levelKey(), PersistentDataType.INTEGER);
        return level == null ? 1 : Math.max(1, level);
    }

    /**
     * A chunk unload/reload re-creates the gunner entity: the tracked context still drives the OLD handle (its
     * entity/mob fields are final), and the fresh husk boots with its vanilla zombie goal set restored — visibly,
     * raiders that re-target on their own and "swing" for the Mythic config's 0.1 damage while our state tree
     * no-ops against the stale instance. Re-track against the live entity (goals stripped, profile from PDC).
     */
    private void retrackReloadedGunner(UUID entityId, LivingEntity entity) {
        String mobType = BreachRuinsMobService.resolveMobType(entity)
                .filter(BreachRuinsMobService::isRuinsGunnerType)
                .orElse(null);
        try {
            removeAgent(entityId);
        } catch (RuntimeException staleCleanup) {
            // Old handle may refuse cleanup calls (removed entity / foreign region); drop tracking directly.
            agents.remove(entityId);
            groupRegistry.remove(entityId);
        }
        if (mobType != null) {
            track(entity, mobType, storedGunnerLevel(entity));
        }
    }

    private void tickAgent(LivingEntity entity, AgentRuntime runtime) {
        if (!(entity instanceof Mob)) {
            removeAgent(entity.getUniqueId());
            return;
        }
        runtime.context.aiTick = serviceTickCounter;
        runtime.context.captureLocation(entity);
        if (runtime.context.spawnCohortId == null) {
            bindPatrolCohort(runtime.context, entity);
        }
        runtime.context.playerTargetGate = this::isValidPlayerTarget;
        runtime.context.combatPlatform = core.platform();
        ensureDisguise(entity, runtime, false);
        groupRegistry.applyToContext(runtime.context);
        if (gunfireTracker != null) {
            RaiderPerceptionSupport.scanHearing(runtime.context, gunfireTracker);
        }
        syncSquadTarget(entity, runtime);
        sanitizeCombatTarget(runtime);
        RaiderPerceptionSupport.refreshTargetPriority(runtime.context, runtime.context.aiTick);
        if (entity instanceof Mob mob) {
            runtime.context.captureCombatTarget(mob);
            if (runtime.context.inSquad()
                    && runtime.context.groupId != null
                    && mob.getTarget() instanceof Player visibleTarget
                    && RaiderPerceptionSupport.canSeeTarget(runtime.context, visibleTarget)) {
                groupRegistry.shareSquadIntel(
                        runtime.context.groupId,
                        visibleTarget,
                        runtime.context.aiTick
                );
            }
        }
        runtime.context.captureChunkAnchor(entity);
        if (runtime.engine.currentState() == CombatAgentStateId.TAKE_COVER
                || runtime.engine.currentState() == CombatAgentStateId.MELEE
                || runtime.engine.currentState() == CombatAgentStateId.PURSUE
                || runtime.engine.currentState() == CombatAgentStateId.SQUAD_TACTIC
                || runtime.engine.currentState() == CombatAgentStateId.RETREAT
                || (runtime.context.underFire(runtime.context.aiTick)
                        && runtime.engine.currentState() == CombatAgentStateId.IDLE)) {
            sprintBridge.setSprinting(entity, true);
        }
        runtime.engine.tick(runtime.context);
        if (nametagService != null) {
            nametagService.updateAiState(
                    entity,
                    runtime.engine.currentState(),
                    runtime.context.groupRole,
                    runtime.context.groupSize,
                    runtime.context.groupId
            );
        }
    }

    private void syncSquadTarget(LivingEntity entity, AgentRuntime runtime) {
        if (!(entity instanceof Mob mob) || !runtime.context.inSquad() || runtime.context.groupId == null) {
            return;
        }
        long tick = runtime.context.aiTick;
        RaiderGroupSnapshot squadSnapshot = groupRegistry.snapshot(entity.getUniqueId());
        if (mob.getTarget() == null && squadSnapshot.sharedTargetId() != null) {
            org.bukkit.entity.Player shared = entity.getServer().getPlayer(squadSnapshot.sharedTargetId());
            if (shared != null && assignCombatTarget(mob, shared)) {
                return;
            }
        }
        if (mob.getTarget() instanceof Player player && isValidPlayerTarget(player)) {
            if (!RaiderPerceptionSupport.isStaleTarget(runtime.context, player, tick)) {
                if (!runtime.context.underFire(tick)) {
                    return;
                }
                if (player.getUniqueId().equals(runtime.context.lastCombatPlayerId)
                        || RaiderPerceptionSupport.canSeeTarget(runtime.context, player)
                        || RaiderPerceptionSupport.withinStrikeRange(runtime.context, player)) {
                    return;
                }
            }
            mob.setTarget(null);
        }
        for (AgentRuntime other : agents.values()) {
            if (other.context.groupId == null || !other.context.groupId.equals(runtime.context.groupId)) {
                continue;
            }
            // Never read another member's live mob target from this agent's thread (Folia
            // cross-region violation) — use the target the member cached on its own thread.
            UUID candidateId = other.context.trackedCombatTargetId;
            if (candidateId == null) {
                continue;
            }
            Player squadTarget = entity.getServer().getPlayer(candidateId);
            if (squadTarget != null && assignCombatTarget(mob, squadTarget)) {
                return;
            }
        }
    }

    private boolean assignCombatTarget(Mob mob, Player player) {
        return RaiderCombatTargets.assign(mob, player, this::isValidPlayerTarget);
    }

    private void markPlayerCombat(AgentRuntime runtime, Player player) {
        if (runtime == null || player == null) {
            return;
        }
        long tick = runtime.context.aiTick;
        runtime.context.lastCombatPlayerId = player.getUniqueId();
        runtime.context.lastCombatPlayerTick = tick;
        assignCombatTarget(runtime.context.mob, player);
    }

    private static LivingEntity resolveDamagerEntity(Entity damager) {
        if (damager instanceof LivingEntity living) {
            return living;
        }
        if (damager instanceof org.bukkit.entity.Projectile projectile
                && projectile.getShooter() instanceof LivingEntity shooter) {
            return shooter;
        }
        return null;
    }

    private void sanitizeCombatTarget(AgentRuntime runtime) {
        LivingEntity target = runtime.context.mob.getTarget();
        if (!(target instanceof Player player)) {
            return;
        }
        if (isValidPlayerTarget(player)) {
            return;
        }
        RaiderPerceptionSupport.clearCombatFocus(runtime.context);
    }

    private void releaseAgentFocus(
            AgentRuntime runtime,
            UUID playerId,
            Location lastLocation,
            boolean inspectBodies
    ) {
        boolean inspect = inspectBodies
                && lastLocation != null
                && shouldInspectKill(runtime, playerId, lastLocation)
                && canBeginInspect(runtime);
        RaiderPerceptionSupport.clearCombatFocus(runtime.context);
        if (inspect) {
            beginBodyInspection(runtime, lastLocation);
            return;
        }
        if (shouldForceIdleAfterRelease(runtime.engine.currentState())) {
            runtime.engine.forceState(CombatAgentStateId.IDLE, runtime.context);
        }
    }

    private static boolean shouldForceIdleAfterRelease(CombatAgentStateId state) {
        return switch (state) {
            case ENGAGE, MELEE, PURSUE, RELOAD, HEAL, TAKE_COVER, PEEK, RETREAT, SECONDARY_WEAPON, SQUAD_TACTIC,
                 INSPECT -> true;
            default -> false;
        };
    }

    private boolean isValidPlayerTarget(Player player) {
        if (player == null || !player.isValid() || player.isDead()) {
            return false;
        }
        if (breachEngine.isSpectating(player)) {
            return false;
        }
        return breachEngine.instanceFor(player)
                .map(instance -> instance.state() == BreachState.ACTIVE
                        && instance.containsPlayer(player.getUniqueId())
                        && !instance.isEliminated(player.getUniqueId())
                        && !instance.hasExtracted(player.getUniqueId()))
                .orElse(false);
    }

    private boolean shouldInspectKill(AgentRuntime runtime, UUID victimId, Location deathLocation) {
        LivingEntity entity = runtime.context.entity;
        if (entity == null || deathLocation == null) {
            return false;
        }
        if (entity.getLocation().distanceSquared(deathLocation) > INSPECT_NOTIFY_RADIUS_SQ) {
            return false;
        }
        LivingEntity target = runtime.context.mob.getTarget();
        if (target instanceof Player player && player.getUniqueId().equals(victimId)) {
            return true;
        }
        long tick = runtime.context.aiTick;
        return victimId.equals(runtime.context.lastCombatPlayerId)
                && tick - runtime.context.lastCombatPlayerTick <= INSPECT_ENGAGEMENT_TICKS;
    }

    private boolean canBeginInspect(AgentRuntime runtime) {
        if (runtime == null || runtime.engine == null) {
            return false;
        }
        return switch (runtime.engine.currentState()) {
            case ENGAGE, MELEE, TAKE_COVER, PEEK, RETREAT, RELOAD, HEAL, SECONDARY_WEAPON -> false;
            default -> true;
        };
    }

    private void beginBodyInspection(AgentRuntime runtime, Location deathLocation) {
        Location inspectPoint = RaiderPerceptionSupport.snapDestination(deathLocation);
        runtime.context.inspectPoint = inspectPoint != null ? inspectPoint : deathLocation.clone();
        runtime.context.mob.setTarget(null);
        runtime.context.lastCombatPlayerId = null;
        runtime.context.lastCombatPlayerTick = 0L;
        runtime.engine.forceState(CombatAgentStateId.INSPECT, runtime.context);
    }

    private void interruptPassiveState(AgentRuntime runtime, long tick) {
        if (runtime == null || runtime.engine == null) {
            return;
        }
        CombatAgentStateId current = runtime.engine.currentState();
        if (current != CombatAgentStateId.IDLE && current != CombatAgentStateId.PURSUE) {
            return;
        }
        CombatAgentStateId next = RaiderPerceptionSupport.resolvePassiveThreat(runtime.context, tick);
        if (next != null && next != current) {
            runtime.engine.forceState(next, runtime.context);
        }
    }

    private void propagateCombatContact(Player attacker, long tick) {
        if (attacker == null || !isValidPlayerTarget(attacker)) {
            return;
        }
        Location attackerLocation = attacker.getLocation();
        World attackerWorld = attacker.getWorld();
        for (AgentRuntime other : agents.values()) {
            LivingEntity entity = living(other.context.entity.getUniqueId());
            if (entity == null) {
                continue;
            }
            // Entity state (validity, world, position, LOS) must be read on the owner thread.
            Runnable contact = () -> {
                if (!entity.isValid() || entity.isDead() || !entity.getWorld().equals(attackerWorld)) {
                    return;
                }
                if (entity.getLocation().distanceSquared(attackerLocation) > 48.0D * 48.0D) {
                    return;
                }
                if (!attacker.isValid() || !RaiderPerceptionSupport.canSeeTarget(other.context, attacker)) {
                    return;
                }
                if (entity instanceof Mob mob) {
                    assignCombatTarget(mob, attacker);
                }
                other.context.lastKnownTargetLocation = attackerLocation.clone();
                other.context.lastSeenTargetTick = tick;
            };
            if (core.platform() != null) {
                core.platform().runAtEntity(entity, contact);
            } else {
                contact.run();
            }
        }
    }

    private void propagateSquadContact(UUID victimId, long tick) {
        RaiderGroupSnapshot snap = groupRegistry.snapshot(victimId);
        if (!snap.inSquad() || snap.groupId() == null) {
            return;
        }
        for (AgentRuntime other : agents.values()) {
            RaiderGroupSnapshot otherSnap = groupRegistry.snapshot(other.context.entity.getUniqueId());
            if (!snap.groupId().equals(otherSnap.groupId())) {
                continue;
            }
            other.context.lastDamagedTick = tick;
            Location shared = snap.sharedLastKnown();
            if (shared != null && snap.sharedLastSeenTick() > other.context.lastSeenTargetTick) {
                other.context.lastKnownTargetLocation = shared.clone();
                other.context.lastSeenTargetTick = snap.sharedLastSeenTick();
            }
            if (snap.sharedTargetId() != null && other.context.mob.getTarget() == null) {
                org.bukkit.entity.Player sharedTarget = other.context.entity.getServer().getPlayer(snap.sharedTargetId());
                if (sharedTarget != null) {
                    assignCombatTarget(other.context.mob, sharedTarget);
                }
            }
        }
    }

    private void ensureDisguise(LivingEntity entity, AgentRuntime runtime, boolean force) {
        long now = System.currentTimeMillis();
        boolean disguised = LibsDisguisesBridge.isPlayerDisguised(entity);
        if (!disguised) {
            force = true;
        }
        if (!force && now - runtime.lastDisguiseAttemptAt < 2_000L) {
            return;
        }
        if (!force && disguised && now - runtime.lastDisguiseRefreshAt < DISGUISE_REFRESH_INTERVAL_MS) {
            return;
        }
        runtime.lastDisguiseAttemptAt = now;
        String skin = LibsDisguisesBridge.skinForMobType(runtime.mobType);
        if (!LibsDisguisesBridge.isPlayerDisguised(entity)) {
            LibsDisguisesBridge.applyPlayerDisguise(entity, "Raider", skin);
        } else if (force || now - runtime.lastDisguiseRefreshAt >= DISGUISE_REFRESH_INTERVAL_MS) {
            LibsDisguisesBridge.refreshDisguise(entity);
            runtime.lastDisguiseRefreshAt = now;
        }
    }

    private static Player resolveAttacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof org.bukkit.entity.Projectile projectile
                && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    private void removeAgent(UUID entityId) {
        AgentRuntime runtime = agents.remove(entityId);
        if (runtime == null) {
            groupRegistry.remove(entityId);
            return;
        }
        disposeRuntime(runtime);
        groupRegistry.remove(entityId);
    }

    private void disposeRuntime(AgentRuntime runtime) {
        if (runtime == null) {
            return;
        }
        runtime.dispatchGeneration.incrementAndGet();
        runtime.context.trackedChunkValid = false;
        runtime.engine.exitCurrent(runtime.context);
        runtime.context.mob.setTarget(null);
        runtime.context.inspectPoint = null;
        runtime.context.playerTargetGate = null;
    }

    private long resolveWorldTick(AgentRuntime runtime) {
        return serviceTickCounter;
    }

    private LivingEntity living(UUID entityId) {
        Entity entity = Bukkit.getEntity(entityId);
        return entity instanceof LivingEntity living ? living : null;
    }

    private boolean isInActiveBreach(Entity entity) {
        if (entity == null || entity.getWorld() == null) {
            return false;
        }
        if (entity instanceof Player player) {
            return breachEngine.instanceFor(player).isPresent();
        }
        return breachEngine.instanceForWorld(entity.getWorld()).isPresent();
    }

    private static final class AgentRuntime {
        private final StateTreeEngine<CombatAgentStateId, RaiderAgentContext> engine;
        private final RaiderAgentContext context;
        private final String mobType;
        private long lastDisguiseAttemptAt;
        private long lastDisguiseRefreshAt;
        private long unloadedSinceTick;
        /**
         * Bumped each global dispatch and on dispose so stale region tasks from a prior tick
         * (or after region hop) no-op instead of double-ticking.
         */
        private final java.util.concurrent.atomic.AtomicInteger dispatchGeneration =
                new java.util.concurrent.atomic.AtomicInteger();

        private AgentRuntime(
                StateTreeEngine<CombatAgentStateId, RaiderAgentContext> engine,
                RaiderAgentContext context,
                String mobType,
                long spawnedAt
        ) {
            this.engine = engine;
            this.context = context;
            this.mobType = mobType;
            this.lastDisguiseAttemptAt = spawnedAt;
            this.lastDisguiseRefreshAt = spawnedAt;
        }
    }
}
