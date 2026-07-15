package network.skypvp.extraction.ai.raider;

import java.util.UUID;
import network.skypvp.extraction.gameplay.MythicMobHealthUtil;
import network.skypvp.extraction.integration.WeaponMechanicsCombatBridge;
import network.skypvp.paper.ai.navigation.NavigatingMobContext;
import network.skypvp.paper.ai.navigation.NavigationTracker;
import network.skypvp.paper.ai.statetree.CombatAgentTiming;
import network.skypvp.paper.ai.statetree.StateClockContext;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

/** Per-gunner blackboard consumed by the state tree. */
public final class RaiderAgentContext implements StateClockContext, NavigatingMobContext {

    public final LivingEntity entity;
    public final Mob mob;
    public final String mobType;
    public final RaiderWeaponProfile profile;
    public final WeaponMechanicsCombatBridge combat;

    /**
     * Monotonic per-agent clock, incremented once per AI tick by the service. ALL raider
     * timers compare against this — never {@code world.getFullTime()}: on Folia that routes
     * through the CURRENT REGION's world data, and region splits/merges make it jump
     * (including backwards), which froze every cooldown ("shoot once then stare").
     * Volatile so event threads (damage marks) read a recent value.
     */
    public volatile long aiTick;

    public int ammoInMag;
    public int spareMagazines;
    public int healsRemaining;
    public boolean sidearmEquipped;
    public boolean sidearmReloaded;
    public boolean knifeEquipped;

    public long stateEnteredTick;
    public long lastDamagedTick;
    public long reloadCompleteTick;
    public long healCompleteTick;
    public long nextBurstTick;
    public long nextShotTick;
    public long nextMeleeTick;
    public long lastMeleeChaseTick;
    public int burstShotsRemaining;

    public final NavigationTracker navigation = new NavigationTracker();

    public Location coverPoint;
    public boolean coverBlocksLos;
    public long coverAssignedTick;
    public long coverLastProgressTick;
    public double coverBestDistanceSq = Double.MAX_VALUE;

    public Location lastKnownTargetLocation;
    public long lastSeenTargetTick;

    public UUID groupId;
    public UUID groupAnchorId;
    public int groupSize = 1;
    public RaiderGroupRole groupRole = RaiderGroupRole.SOLO;
    public Location groupTacticPoint;
    /** Tick when the current sticky {@link #groupTacticPoint} was accepted from squad rebuild. */
    public long groupTacticAssignedTick;
    /**
     * World yaw used for calm formation slots. Locked to the first engagement facing so
     * suppressor body-turns do not flip FLANK_LEFT/RIGHT slots every tick.
     */
    public float formationFacingYaw;
    public boolean formationFacingValid;
    public Location formationFacingThreat;
    /** Last-known suppressor anchor from squad rebuild; used for abandon distance checks. */
    public Location squadAnchorLocation;
    /** Shared idle patrol destination from squad rebuild (anchor's patrol target / leg). */
    public Location squadPatrolGoal;
    public float squadPatrolFacingYaw;
    public boolean squadPatrolFacingValid;
    /** Furthest squad member from anchor at last rebuild; anchor waits to regroup when large. */
    public double squadMaxMemberSpreadSq;
    /** Fan-out point for squad checkpoint scans before look-around. */
    public Location patrolScoutPoint;
    /** When true, squad rebuild treats this gunner as solo so another raider can fill the role. */
    public boolean leavingSquad;

    public Location retreatPoint;
    public long lastRetreatScanTick;
    public int retreatReplans;

    public UUID spawnCohortId;
    public Location patrolOrigin;
    /** Far patrol destination (up to PATROL max radius from origin); reached via legs. */
    public Location patrolTarget;
    /** Current navigation LEG toward {@link #patrolTarget} — vanilla A* can't path 200-block goals. */
    public Location patrolWaypoint;
    /** Consecutive failed legs on the current target; too many = the point is unreachable, skip it. */
    public int patrolLegFailures;
    public long nextPatrolRefreshTick;
    public int patrolPointsCompleted;
    public boolean returningToSpawn;
    public long patrolCheckpointUntilTick;
    public float patrolLookBaseYaw;
    public int patrolLookStep;
    public long patrolLookNextStepTick;
    public long investigateCooldownUntilTick;
    /** Consecutive stall-clears granted while pursuing; escalates to an unstick attempt, then give-up. */
    public int pursueStallStrikes;
    /** While {@code aiTick < this}, combat resolution must not pick PURSUE/SQUAD_TACTIC (movement failed). */
    public long pursuitBlockedUntilTick;
    public long lastHeardGunfireMs;
    public UUID lastCombatPlayerId;
    public long lastCombatPlayerTick;

    /**
     * Per-tick line-of-sight memo: canSeeTarget runs a block raytrace and is consulted
     * several times per tick (target sync, priority refresh, state nodes, squad intel).
     * Facing and positions are stable within one AI tick, so one raytrace per target per
     * tick is enough. Only ever touched on the owning region thread.
     */
    public long losCacheTick = Long.MIN_VALUE;
    public UUID losCacheTargetId;
    public boolean losCacheResult;

    /** Cached on the owning entity thread for squad rebuild without cross-region entity reads. */
    public UUID trackedCombatTargetId;
    public Location trackedCombatTargetLocation;
    public UUID trackedChunkWorldId;
    public int trackedChunkX;
    public int trackedChunkZ;
    public boolean trackedChunkValid;

    public RaiderCombatTargets.PlayerTargetGate playerTargetGate;
    public network.skypvp.paper.platform.ServerPlatform combatPlatform;

    public Location inspectPoint;

    /** Sidestep waypoint used between bursts so engaged gunners reposition instead of standing. */
    public Location strafePoint;
    public long nextStrafeTick;
    /** Sticky lateral sign for combat strafe (−1 / +1); 0 means unassigned. */
    public int strafeSide;

    /**
     * Facing the AI last commanded via setRotation, with the tick it was issued.
     * The vision-cone check prefers this over the entity's body yaw, which NMS body
     * rotation control can leave stale on stationary mobs.
     */
    public float aiFacingYaw;
    public long aiFacingTick;

    public org.bukkit.World cachedWorld;
    public double cachedX;
    public double cachedY;
    public double cachedZ;
    public boolean cachedLocationValid;

    public void captureLocation(LivingEntity entity) {
        if (entity == null || !entity.isValid()) {
            cachedLocationValid = false;
            return;
        }
        Location location = entity.getLocation();
        cachedWorld = entity.getWorld();
        cachedX = location.getX();
        cachedY = location.getY();
        cachedZ = location.getZ();
        cachedLocationValid = true;
    }

    public Location cachedLocation() {
        if (!cachedLocationValid || cachedWorld == null) {
            return entity.getLocation();
        }
        return new Location(cachedWorld, cachedX, cachedY, cachedZ);
    }

    public void captureChunkAnchor(LivingEntity entity) {
        if (entity == null || !entity.isValid() || entity.getWorld() == null) {
            trackedChunkValid = false;
            return;
        }
        Location location = entity.getLocation();
        trackedChunkWorldId = entity.getWorld().getUID();
        trackedChunkX = location.getBlockX() >> 4;
        trackedChunkZ = location.getBlockZ() >> 4;
        trackedChunkValid = true;
    }

    public void captureCombatTarget(Mob mob) {
        if (mob == null) {
            trackedCombatTargetId = null;
            trackedCombatTargetLocation = null;
            return;
        }
        if (mob.getTarget() instanceof Player player && player.isValid() && !player.isDead()) {
            if (playerTargetGate != null && !playerTargetGate.allows(player)) {
                trackedCombatTargetId = null;
                trackedCombatTargetLocation = null;
                return;
            }
            trackedCombatTargetId = player.getUniqueId();
            trackedCombatTargetLocation = player.getLocation().clone();
            return;
        }
        trackedCombatTargetId = null;
        trackedCombatTargetLocation = null;
    }
    public static final long LAST_KNOWN_TTL_TICKS = CombatAgentTiming.LAST_KNOWN_TTL_TICKS;
    public static final long INVESTIGATE_COOLDOWN_TICKS = CombatAgentTiming.PURSUE_COOLDOWN_TICKS;
    public static final long INVESTIGATE_TIMEOUT_TICKS = CombatAgentTiming.PURSUE_TIMEOUT_TICKS;
    /** Horizontal radius for "arrived at last known" during blind pursuit (~1 block). */
    public static final double PURSUE_ARRIVE_SQ = 1.0D;
    /** Stall-clears granted to a pursuing raider before it tries to physically unstick itself. */
    public static final int PURSUE_STALL_MAX_STRIKES = 3;
    /** Pursuit hard-block after failed physical progress; prevents instant PURSUE re-entry loops. */
    public static final long PURSUIT_BLOCK_TICKS = 200L;
    public static final long GROUP_TACTIC_TIMEOUT_TICKS = CombatAgentTiming.SQUAD_TACTIC_TIMEOUT_TICKS;
    public static final long HOLD_REENGAGE_TICKS = CombatAgentTiming.IDLE_REENGAGE_TICKS;
    public static final long COVER_PROGRESS_STALL_TICKS = CombatAgentTiming.COVER_PROGRESS_STALL_TICKS;
    public static final long COVER_MAX_DURATION_TICKS = CombatAgentTiming.COVER_MAX_DURATION_TICKS;
    public static final long COVER_REASSIGN_COOLDOWN_TICKS = CombatAgentTiming.COVER_REASSIGN_COOLDOWN_TICKS;
    public static final long COVER_FAKE_TIMEOUT_TICKS = CombatAgentTiming.COVER_FAKE_TIMEOUT_TICKS;
    public static final long RETREAT_RESCAN_TICKS = CombatAgentTiming.RETREAT_RESCAN_TICKS;
    public static final long RETREAT_WAYPOINT_STALL_TICKS = CombatAgentTiming.RETREAT_WAYPOINT_STALL_TICKS;
    public static final long RETREAT_MAX_DURATION_TICKS = CombatAgentTiming.RETREAT_MAX_DURATION_TICKS;
    public static final int RETREAT_MAX_REPLANS = CombatAgentTiming.RETREAT_MAX_REPLANS;
    public static final long GROUP_ABANDON_TIMEOUT_TICKS = CombatAgentTiming.SQUAD_LEAVE_TIMEOUT_TICKS;
    public static final long HEAL_GRACE_TICKS = CombatAgentTiming.HEAL_GRACE_TICKS;
    public static final long UNDER_FIRE_WINDOW_TICKS = CombatAgentTiming.UNDER_FIRE_WINDOW_TICKS;
    public static final long RETURN_FIRE_DELAY_TICKS = CombatAgentTiming.RETURN_FIRE_DELAY_TICKS;
    public static final long COVER_CACHE_TTL_TICKS = CombatAgentTiming.COVER_CACHE_TTL_TICKS;
    public static final long INSPECT_BODY_TICKS = CombatAgentTiming.INSPECT_TICKS;
    public static final long INSPECT_TIMEOUT_TICKS = CombatAgentTiming.INSPECT_TIMEOUT_TICKS;

    public long healGraceUntilTick;
    public Location cachedRealCover;
    public long cachedRealCoverTick;

    public RaiderAgentContext(
            LivingEntity entity,
            Mob mob,
            String mobType,
            RaiderWeaponProfile profile,
            WeaponMechanicsCombatBridge combat,
            Location home
    ) {
        this.entity = entity;
        this.mob = mob;
        this.mobType = mobType;
        this.profile = profile;
        this.combat = combat;
        this.ammoInMag = profile.magazineSize();
        this.spareMagazines = profile.spareMagazines();
        this.healsRemaining = profile.healCharges();
        this.sidearmEquipped = false;
        this.sidearmReloaded = false;
        this.knifeEquipped = false;
        this.lastDamagedTick = 0L;
    }

    public boolean isPistolPrimary() {
        return "ruinspistolgunner".equalsIgnoreCase(mobType);
    }

    public String activeWeapon() {
        if (knifeEquipped) {
            return profile.meleeWeapon();
        }
        return sidearmEquipped ? profile.sidearmWeapon() : profile.primaryWeapon();
    }

    public double activeSpread() {
        return sidearmEquipped ? profile.sidearmSpread() : profile.primarySpread();
    }

    public boolean underFire(long nowTick) {
        return lastDamagedTick > 0L && nowTick - lastDamagedTick <= UNDER_FIRE_WINDOW_TICKS;
    }

    /** True while the gunner should keep shooting instead of diving for cover after a hit. */
    public boolean shouldReturnFire(long nowTick) {
        return underFire(nowTick)
                && nowTick - lastDamagedTick <= RETURN_FIRE_DELAY_TICKS
                && canFightBack(nowTick);
    }

    /** True when recent damage should trigger a cover seek (after the return-fire window). */
    public boolean shouldSeekCover(long nowTick) {
        return underFire(nowTick) && nowTick - lastDamagedTick > RETURN_FIRE_DELAY_TICKS;
    }

    public void invalidateCoverCache() {
        cachedRealCover = null;
        cachedRealCoverTick = 0L;
    }

    public boolean needsReload() {
        return !knifeEquipped && ammoInMag <= 0;
    }

    public boolean canReloadPrimary() {
        return !sidearmEquipped && !knifeEquipped && spareMagazines > 0;
    }

    public boolean canReloadSidearm() {
        return sidearmEquipped && !sidearmReloaded;
    }

    public boolean shouldSwapSidearm(long nowTick) {
        if (isPistolPrimary() || sidearmEquipped || knifeEquipped) {
            return false;
        }
        return needsReload() && spareMagazines <= 0 && underFire(nowTick);
    }

    public boolean needsHeal() {
        return needsHeal(aiTick);
    }

    public boolean needsHeal(long nowTick) {
        if (healsRemaining <= 0) {
            return false;
        }
        if (nowTick < healGraceUntilTick) {
            return false;
        }
        MythicMobHealthUtil.HealthSnapshot health = MythicMobHealthUtil.snapshot(entity);
        return health.ratio() <= profile.hurtHealRatio();
    }

    public boolean coverBreakFinished(long nowTick) {
        return nowTick - stateEnteredTick >= profile.coverBreakTicks();
    }

    public boolean inSquad() {
        return groupSize >= 2 && groupRole != RaiderGroupRole.SOLO;
    }

    public boolean isSuppressAnchor() {
        return groupRole == RaiderGroupRole.SUPPRESS;
    }

    /** True when the gunner can shoot, swap sidearm, or knife fight right now. */
    public boolean canFightBack(long tick) {
        if (knifeEquipped) {
            return true;
        }
        if (ammoInMag > 0) {
            return true;
        }
        if (sidearmEquipped && canReloadSidearm()) {
            return true;
        }
        if (!isPistolPrimary() && !sidearmEquipped && spareMagazines > 0) {
            return true;
        }
        if (shouldSwapSidearm(tick)) {
            return true;
        }
        return false;
    }

    @Override
    public long currentWorldTick() {
        return aiTick;
    }

    @Override
    public long navClock() {
        return aiTick;
    }

    @Override
    public void onStateEntered(long tick) {
        stateEnteredTick = tick;
    }

    @Override
    public LivingEntity agentEntity() {
        return entity;
    }

    @Override
    public Mob agentMob() {
        return mob;
    }

    @Override
    public NavigationTracker navigation() {
        return navigation;
    }
}
