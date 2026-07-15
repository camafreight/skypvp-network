package network.skypvp.extraction.ai.raider;

import java.util.function.Consumer;
import network.skypvp.paper.ai.statetree.CombatAgentStateId;
import network.skypvp.paper.ai.statetree.StateTreeEngine;
import network.skypvp.paper.ai.statetree.StateTreeNode;
import network.skypvp.paper.integration.LivingEntitySprintBridge;
import network.skypvp.extraction.integration.WeaponMechanicsCombatBridge;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;

/** Builds the Ruins gunner state tree (engage, cover sprint, reload, sidearm). */
public final class RaiderGunnerStateTree {

    private static final long RELOAD_TICKS_PRIMARY = 45L;
    private static final long RELOAD_TICKS_SIDEARM = 28L;
    private static final double COVER_ARRIVE_SQ = 4.0D;
    private static final double INVESTIGATE_ARRIVE_SQ = 4.0D;
    private static final double PEEK_ABORT_RANGE_SQ = 6.5D * 6.5D;
    private static final double MELEE_DISENGAGE_SQ_MULTIPLIER = 1.6D;
    /** Aim-up delay when entering a shooting state — humans don't fire the same tick they see you. */
    private static final long AIM_REACTION_TICKS = 8L;
    private static final long STRAFE_REPICK_TICKS = 30L;
    private static final double STRAFE_ARRIVE_SQ = 1.0D;

    private RaiderGunnerStateTree() {
    }

    public static StateTreeEngine<CombatAgentStateId, RaiderAgentContext> create(
            WeaponMechanicsCombatBridge combat,
            LivingEntitySprintBridge sprintBridge
    ) {
        StateTreeEngine<CombatAgentStateId, RaiderAgentContext> engine =
                new StateTreeEngine<>(CombatAgentStateId.class, CombatAgentStateId.IDLE);

        engine.register(CombatAgentStateId.IDLE, holdNode(combat, sprintBridge));
        engine.register(CombatAgentStateId.ENGAGE, engageNode(combat, sprintBridge));
        engine.register(CombatAgentStateId.MELEE, meleeNode(combat, sprintBridge));
        engine.register(CombatAgentStateId.TAKE_COVER, coverNode(sprintBridge));
        engine.register(CombatAgentStateId.PEEK, coverBreakNode(sprintBridge));
        engine.register(CombatAgentStateId.RELOAD, reloadNode(sprintBridge));
        engine.register(CombatAgentStateId.HEAL, healNode(sprintBridge));
        engine.register(CombatAgentStateId.SECONDARY_WEAPON, sidearmNode(combat, sprintBridge));
        engine.register(CombatAgentStateId.PURSUE, investigateNode(sprintBridge));
        engine.register(CombatAgentStateId.SQUAD_TACTIC, groupTacticNode(sprintBridge));
        engine.register(CombatAgentStateId.SQUAD_LEAVE, groupAbandonNode(sprintBridge));
        engine.register(CombatAgentStateId.RETREAT, retreatNode(sprintBridge));
        engine.register(CombatAgentStateId.INSPECT, inspectBodyNode(sprintBridge));
        return engine;
    }

    private static StateTreeNode<CombatAgentStateId, RaiderAgentContext> holdNode(
            WeaponMechanicsCombatBridge combat,
            LivingEntitySprintBridge sprintBridge
    ) {
        return node(
                ctx -> {
                    stopCoverRun(ctx, sprintBridge);
                    restoreGun(ctx, combat);
                },
                ctx -> {
                    long tick = currentTick(ctx);
                    LivingEntity target = ctx.mob.getTarget();
                    if (target instanceof org.bukkit.entity.Player player
                            && RaiderThreatSupport.isStaleTarget(ctx, player, tick)) {
                        ctx.mob.setTarget(null);
                        RaiderSightSupport.expireIntel(ctx);
                        target = null;
                    }
                    CombatAgentStateId passiveThreat = RaiderThreatSupport.resolvePassiveThreat(ctx, tick);
                    if (passiveThreat != null && passiveThreat != CombatAgentStateId.IDLE) {
                        RaiderPatrolSupport.stop(ctx);
                        return passiveThreat;
                    }
                    if (target == null || target.isDead() || !target.isValid()) {
                        if (RaiderSightSupport.canInvestigate(ctx, tick)) {
                            RaiderPatrolSupport.stop(ctx);
                            return CombatAgentStateId.PURSUE;
                        }
                        RaiderPatrolSupport.tick(ctx);
                        return null;
                    }
                    RaiderPatrolSupport.stop(ctx);
                    if (!RaiderSightSupport.canSeeTarget(ctx, target)) {
                        if (maySeekCover(ctx, tick)) {
                            return seekCoverOrRetreat(ctx, target, tick);
                        }
                        if (RaiderSightSupport.canInvestigate(ctx, tick)) {
                            return RaiderGroupTactics.preferredPursuitState(ctx);
                        }
                    }
                    CombatAgentStateId stuck = RaiderStateWatchdog.resolveStuck(ctx, CombatAgentStateId.IDLE, target, tick);
                    if (stuck != null) {
                        return stuck;
                    }
                    CombatAgentStateId resume = RaiderCombatResolve.resolve(ctx, target, tick);
                    if (resume != null) {
                        return resume;
                    }
                    if (ctx.inSquad() && ctx.groupRole.coordinates() && ctx.groupTacticPoint != null
                            && !RaiderSightSupport.canSeeTarget(ctx, target)
                            && ctx.entity.getLocation().distanceSquared(ctx.groupTacticPoint) > INVESTIGATE_ARRIVE_SQ) {
                        return CombatAgentStateId.SQUAD_TACTIC;
                    }
                    if (tick - ctx.stateEnteredTick >= RaiderAgentContext.HOLD_REENGAGE_TICKS) {
                        CombatAgentStateId forced = RaiderCombatResolve.forceReengage(ctx, target, tick);
                        if (forced != null) {
                            return forced;
                        }
                    }
                    return null;
                },
                ctx -> {}
        );
    }

    private static StateTreeNode<CombatAgentStateId, RaiderAgentContext> engageNode(
            WeaponMechanicsCombatBridge combat,
            LivingEntitySprintBridge sprintBridge
    ) {
        return node(
                ctx -> {
                    stopCoverRun(ctx, sprintBridge);
                    ctx.burstShotsRemaining = 0;
                    // Human-like aim-up pause before the first burst instead of instant lock-fire.
                    ctx.nextBurstTick = currentTick(ctx) + AIM_REACTION_TICKS;
                    ctx.nextShotTick = 0L;
                    ctx.strafePoint = null;
                    restoreGun(ctx, combat);
                    equipActive(ctx, combat);
                },
                ctx -> {
                    long tick = currentTick(ctx);
                    LivingEntity target = ctx.mob.getTarget();
                    if (target == null || target.isDead() || !target.isValid()) {
                        return CombatAgentStateId.IDLE;
                    }
                    // Face the target before any sight checks so the awareness cone tracks
                    // the engagement; "lost sight" below then means a genuinely blocked ray.
                    faceTarget(ctx, bodyAimPoint(target));
                    CombatAgentStateId stuck = RaiderStateWatchdog.resolveStuck(ctx, CombatAgentStateId.ENGAGE, target, tick);
                    if (stuck != null) {
                        return stuck;
                    }
                    if (RaiderCombatResolve.shouldEnterMelee(ctx, target)) {
                        return CombatAgentStateId.MELEE;
                    }
                    double distanceSq = ctx.entity.getLocation().distanceSquared(target.getLocation());
                    if (distanceSq > RaiderCombatResolve.engageDisengageRangeSq(ctx)) {
                        if (ctx.profile.prefersCloseCombat() && !ctx.inSquad()) {
                            return CombatAgentStateId.PURSUE;
                        }
                        return CombatAgentStateId.IDLE;
                    }
                    // Too close for preferred gun spacing: keep shooting if visible; only knife with LOS.
                    if (RaiderSightSupport.tooCloseForGun(ctx, target)) {
                        if (RaiderSightSupport.canInitiateMelee(ctx, target)) {
                            return CombatAgentStateId.MELEE;
                        }
                        // Fall through and hip-fire when the ray is clear.
                    }
                    CombatAgentStateId lostSight = resolveLostSight(ctx, target, tick);
                    if (lostSight != null) {
                        return lostSight;
                    }
                    boolean maintainGunfight = RaiderCombatResolve.shouldMaintainGunfight(ctx, target, tick);
                    if (maySeekCover(ctx, tick) && !maintainGunfight && !ctx.isSuppressAnchor()) {
                        return seekCoverOrRetreat(ctx, target, tick);
                    }
                    if (ctx.needsReload()) {
                        if (maintainGunfight) {
                            CombatAgentStateId reload = RaiderCombatResolve.reloadDuringGunfight(ctx, tick);
                            if (reload != null) {
                                return reload;
                            }
                        }
                        if (ctx.shouldSwapSidearm(tick)) {
                            return CombatAgentStateId.SECONDARY_WEAPON;
                        }
                        if (ctx.canReloadPrimary()) {
                            return seekCoverOrRetreat(ctx, target, tick);
                        }
                        if (!ctx.isPistolPrimary()) {
                            return CombatAgentStateId.SECONDARY_WEAPON;
                        }
                        if (ctx.spareMagazines > 0) {
                            return seekCoverOrRetreat(ctx, target, tick);
                        }
                        CombatAgentStateId resume = RaiderCombatResolve.forceReengage(ctx, target, tick);
                        return resume != null ? resume : CombatAgentStateId.MELEE;
                    }
                    if (tick < ctx.nextBurstTick) {
                        tickCombatStrafe(ctx, target, tick);
                        return null;
                    }
                    if (ctx.burstShotsRemaining <= 0) {
                        endStrafe(ctx);
                        ctx.burstShotsRemaining = ctx.profile.burstShots();
                        ctx.nextShotTick = tick;
                    }
                    if (tick >= ctx.nextShotTick && ctx.burstShotsRemaining > 0) {
                        if (RaiderSightSupport.canSeeTarget(ctx, target)) {
                            int shotIndex = Math.max(0, ctx.profile.burstShots() - ctx.burstShotsRemaining);
                            if (combat.shootAt(
                                    ctx.entity,
                                    ctx.activeWeapon(),
                                    target,
                                    ctx.activeSpread(),
                                    shotIndex,
                                    ctx.profile.style())) {
                                ctx.ammoInMag = Math.max(0, ctx.ammoInMag - 1);
                                ctx.burstShotsRemaining--;
                            }
                        }
                        if (ctx.burstShotsRemaining > 0) {
                            ctx.nextShotTick = tick + ctx.profile.ticksBetweenBurstShots();
                        } else {
                            ctx.nextBurstTick = tick + ctx.profile.ticksBetweenBursts();
                        }
                    }
                    return null;
                },
                ctx -> {
                    endStrafe(ctx);
                    stopCoverRun(ctx, sprintBridge);
                }
        );
    }

    private static StateTreeNode<CombatAgentStateId, RaiderAgentContext> meleeNode(
            WeaponMechanicsCombatBridge combat,
            LivingEntitySprintBridge sprintBridge
    ) {
        return node(
                ctx -> {
                    stopCoverRun(ctx, sprintBridge);
                    ctx.knifeEquipped = true;
                    ctx.burstShotsRemaining = 0;
                    ctx.nextMeleeTick = 0L;
                    combat.equipWeapon(ctx.entity, ctx.profile.meleeWeapon());
                    if (sprintBridge != null) {
                        sprintBridge.setSprinting(ctx.entity, true);
                    }
                },
                ctx -> {
                    long tick = currentTick(ctx);
                    LivingEntity target = ctx.mob.getTarget();
                    if (target == null || target.isDead() || !target.isValid()) {
                        return CombatAgentStateId.IDLE;
                    }
                    // Face first so FOV/awareness and knife LOS use the current aim, not last frame's
                    // body yaw. Standing still used to freeze melee because canSeeTarget failed FOV
                    // before faceTarget ran, then chase pathfinding "arrived" and stopped.
                    faceTarget(ctx, bodyAimPoint(target));

                    CombatAgentStateId stuck = RaiderStateWatchdog.resolveStuck(ctx, CombatAgentStateId.MELEE, target, tick);
                    if (stuck != null) {
                        restoreGun(ctx, combat);
                        return stuck;
                    }
                    if (!RaiderCombatResolve.shouldEnterMelee(ctx, target)
                            && RaiderSightSupport.isSeparatedVertically(ctx, target)) {
                        restoreGun(ctx, combat);
                        if (RaiderSightSupport.hasFreshLastKnown(ctx, tick)) {
                            return CombatAgentStateId.PURSUE;
                        }
                        ctx.mob.setTarget(null);
                        RaiderSightSupport.expireIntel(ctx);
                        return CombatAgentStateId.IDLE;
                    }
                    // Wall-blocked knife: require a clear melee ray, not FOV awareness.
                    if (!RaiderSightSupport.hasMeleeLos(ctx, target)
                            && !RaiderSightSupport.withinStrikeRange(ctx, target)) {
                        restoreGun(ctx, combat);
                        if (RaiderSightSupport.hasFreshLastKnown(ctx, tick)) {
                            return CombatAgentStateId.PURSUE;
                        }
                        return CombatAgentStateId.IDLE;
                    }
                    double distanceSq = ctx.entity.getLocation().distanceSquared(target.getLocation());
                    double meleeHoldSq = ctx.profile.prefersCloseCombat()
                            ? ctx.profile.preferredCloseRangeSq() * MELEE_DISENGAGE_SQ_MULTIPLIER
                            : ctx.profile.meleeRangeSq() * MELEE_DISENGAGE_SQ_MULTIPLIER;
                    if (distanceSq > meleeHoldSq && !RaiderCombatResolve.shouldEnterMelee(ctx, target)) {
                        if (ctx.profile.isKnifeRusher()) {
                            return CombatAgentStateId.PURSUE;
                        }
                        restoreGun(ctx, combat);
                        if (RaiderSightSupport.canSeeTarget(ctx, target)) {
                            return CombatAgentStateId.ENGAGE;
                        }
                        if (RaiderSightSupport.hasFreshLastKnown(ctx, tick)) {
                            return CombatAgentStateId.PURSUE;
                        }
                        return CombatAgentStateId.IDLE;
                    }
                    if (RaiderSightSupport.canStrikeTarget(ctx, target)) {
                        RaiderCombatSupport.stopMeleeChase(ctx);
                        RaiderCombatSupport.strike(ctx, target, combat);
                        if (sprintBridge != null) {
                            sprintBridge.setSprinting(ctx.entity, false);
                        }
                    } else if (!ctx.profile.isKnifeRusher()
                            && RaiderSightSupport.canSeeTarget(ctx, target)
                            && !RaiderSightSupport.withinMeleeRange(ctx, target)) {
                        // Close but not knife-ready — keep pressure with the gun instead of freezing.
                        restoreGun(ctx, combat);
                        return CombatAgentStateId.ENGAGE;
                    } else {
                        RaiderCombatSupport.tickMeleeChase(ctx, target);
                    }
                    if (ctx.underFire(tick) && ctx.canReloadPrimary()
                            && !RaiderSightSupport.withinMeleeRange(ctx, target)
                            && maySeekCover(ctx, tick)) {
                        restoreGun(ctx, combat);
                        return seekCoverOrRetreat(ctx, target, tick);
                    }
                    return null;
                },
                ctx -> {
                    RaiderCombatSupport.stopMeleeChase(ctx);
                    if (sprintBridge != null) {
                        sprintBridge.setSprinting(ctx.entity, false);
                    }
                    ctx.knifeEquipped = false;
                }
        );
    }

    private static StateTreeNode<CombatAgentStateId, RaiderAgentContext> coverNode(LivingEntitySprintBridge sprintBridge) {
        return node(
                ctx -> {
                    if (ctx.coverPoint == null && ctx.mob.getTarget() != null) {
                        assignCover(ctx, ctx.mob.getTarget());
                    } else if (ctx.mob.getTarget() != null) {
                        long tick = currentTick(ctx);
                        markCoverAssignment(ctx, tick);
                    }
                    RaiderMovementSupport.beginCoverRun(ctx, sprintBridge);
                },
                ctx -> {
                    long tick = currentTick(ctx);
                    LivingEntity target = ctx.mob.getTarget();
                    RaiderCoverSupport.updateProgress(ctx, tick);

                    CombatAgentStateId pressure = resolveCoverPressure(ctx, target, tick, sprintBridge);
                    if (pressure != null) {
                        return pressure;
                    }
                    if (target != null && !ctx.canFightBack(tick) && !ctx.coverBlocksLos) {
                        Location realCover = RaiderGroupTactics.findRealCoverCached(ctx, target);
                        if (realCover == null) {
                            stopCoverRun(ctx, sprintBridge);
                            planRetreat(ctx, target, tick);
                            return CombatAgentStateId.RETREAT;
                        }
                    }

                    Location cover = ctx.coverPoint;
                    if (cover == null && target != null) {
                        assignCover(ctx, target);
                        cover = ctx.coverPoint;
                    } else if (cover != null && target != null
                            && !RaiderCoverSupport.stillProvidesCover(target, cover)
                            && tick - ctx.coverAssignedTick >= RaiderAgentContext.COVER_REASSIGN_COOLDOWN_TICKS
                            && RaiderCoverSupport.ticksWithoutCoverProgress(ctx, tick)
                                    >= RaiderAgentContext.COVER_PROGRESS_STALL_TICKS / 2L) {
                        assignCover(ctx, target);
                        cover = ctx.coverPoint;
                    }
                    RaiderMovementSupport.tickCoverRun(ctx);
                    if (ctx.shouldSwapSidearm(tick)) {
                        return CombatAgentStateId.SECONDARY_WEAPON;
                    }
                    if (cover != null && hasReachedCover(ctx, target)) {
                        stopCoverRun(ctx, sprintBridge);
                        return transitionAfterCoverArrival(ctx, target, tick);
                    }
                    if (tick - ctx.stateEnteredTick > 60L && !ctx.needsReload() && !ctx.needsHeal()) {
                        if (target != null && target.isValid() && !target.isDead()) {
                            CombatAgentStateId resume = RaiderCombatResolve.resolve(ctx, target, tick);
                            if (resume != null) {
                                stopCoverRun(ctx, sprintBridge);
                                return resume;
                            }
                        }
                        if (!ctx.underFire(tick)) {
                            stopCoverRun(ctx, sprintBridge);
                            return CombatAgentStateId.IDLE;
                        }
                    }
                    CombatAgentStateId watchdog = RaiderStateWatchdog.resolveStuck(ctx, CombatAgentStateId.TAKE_COVER, target, tick);
                    if (watchdog != null) {
                        stopCoverRun(ctx, sprintBridge);
                        return watchdog;
                    }
                    return null;
                },
                ctx -> stopCoverRun(ctx, sprintBridge)
        );
    }

    private static StateTreeNode<CombatAgentStateId, RaiderAgentContext> coverBreakNode(LivingEntitySprintBridge sprintBridge) {
        return node(
                ctx -> {
                    stopCoverRun(ctx, sprintBridge);
                    faceAwayFromThreat(ctx);
                },
                ctx -> {
                    long tick = currentTick(ctx);
                    LivingEntity target = ctx.mob.getTarget();
                    faceAwayFromThreat(ctx);

                    CombatAgentStateId stuck = RaiderStateWatchdog.resolveStuck(ctx, CombatAgentStateId.PEEK, target, tick);
                    if (stuck != null) {
                        return stuck;
                    }

                    CombatAgentStateId pressure = resolvePeekPressure(ctx, target, tick);
                    if (pressure != null) {
                        return pressure;
                    }

                    if (ctx.needsHeal(tick)) {
                        return CombatAgentStateId.HEAL;
                    }
                    if (ctx.needsReload() && (ctx.canReloadPrimary() || ctx.canReloadSidearm())
                            && !RaiderSightSupport.withinMeleeRange(ctx, target)
                            && !RaiderSightSupport.tooCloseForGun(ctx, target)) {
                        if (target != null && RaiderCombatResolve.shouldMaintainGunfight(ctx, target, tick)) {
                            CombatAgentStateId reload = RaiderCombatResolve.reloadDuringGunfight(ctx, tick);
                            if (reload != null) {
                                return reload;
                            }
                        }
                        return CombatAgentStateId.RELOAD;
                    }
                    if (ctx.shouldSeekCover(tick) && !ctx.shouldReturnFire(tick)) {
                        if (ctx.coverBreakFinished(tick) && target != null && target.isValid() && !target.isDead()) {
                            return seekCoverOrRetreat(ctx, target, tick);
                        }
                        return null;
                    }
                    if (!ctx.coverBreakFinished(tick)) {
                        return null;
                    }
                    if (target != null && target.isValid() && !target.isDead()) {
                        CombatAgentStateId resume = RaiderCombatResolve.resolve(ctx, target, tick);
                        if (resume != null) {
                            return resume;
                        }
                        CombatAgentStateId forced = RaiderCombatResolve.forceReengage(ctx, target, tick);
                        return forced != null ? forced : CombatAgentStateId.IDLE;
                    }
                    return CombatAgentStateId.IDLE;
                },
                ctx -> {}
        );
    }

    private static StateTreeNode<CombatAgentStateId, RaiderAgentContext> healNode(LivingEntitySprintBridge sprintBridge) {
        return node(
                ctx -> {
                    stopCoverRun(ctx, sprintBridge);
                    RaiderCombatSupport.beginHeal(ctx);
                },
                ctx -> {
                    long tick = currentTick(ctx);
                    LivingEntity target = ctx.mob.getTarget();
                    faceAwayFromThreat(ctx);

                    CombatAgentStateId pressure = resolvePeekPressure(ctx, target, tick);
                    if (pressure != null) {
                        return pressure;
                    }

                    CombatAgentStateId stuck = RaiderStateWatchdog.resolveStuck(ctx, CombatAgentStateId.HEAL, target, tick);
                    if (stuck != null) {
                        if (tick >= ctx.healCompleteTick) {
                            RaiderCombatSupport.completeHeal(ctx);
                        }
                        return stuck;
                    }

                    if (tick >= ctx.healCompleteTick) {
                        RaiderCombatSupport.completeHeal(ctx);
                        return resolvePostHealTransition(ctx, target, tick);
                    }
                    return null;
                },
                ctx -> {}
        );
    }

    private static StateTreeNode<CombatAgentStateId, RaiderAgentContext> reloadNode(LivingEntitySprintBridge sprintBridge) {
        return node(
                ctx -> {
                    stopCoverRun(ctx, sprintBridge);
                    ctx.reloadCompleteTick = currentTick(ctx)
                            + (ctx.sidearmEquipped ? RELOAD_TICKS_SIDEARM : RELOAD_TICKS_PRIMARY);
                    ctx.combat.tryReload(ctx.entity);
                },
                ctx -> {
                    long tick = currentTick(ctx);
                    faceAwayFromThreat(ctx);
                    LivingEntity target = ctx.mob.getTarget();
                    CombatAgentStateId pressure = resolvePeekPressure(ctx, target, tick);
                    if (pressure != null) {
                        return pressure;
                    }
                    CombatAgentStateId stuck = RaiderStateWatchdog.resolveStuck(ctx, CombatAgentStateId.RELOAD, target, tick);
                    if (stuck != null) {
                        completeReload(ctx);
                        return stuck;
                    }
                    if (tick >= ctx.reloadCompleteTick) {
                        completeReload(ctx);
                        if (target != null && target.isValid() && !target.isDead()) {
                            if (RaiderCombatResolve.shouldMaintainGunfight(ctx, target, tick)) {
                                CombatAgentStateId resume = RaiderCombatResolve.resolve(ctx, target, tick);
                                if (resume != null) {
                                    return resume;
                                }
                                return CombatAgentStateId.ENGAGE;
                            }
                            if (maySeekCover(ctx, tick)) {
                                return seekCoverOrRetreat(ctx, target, tick);
                            }
                            CombatAgentStateId resume = RaiderCombatResolve.resolve(ctx, target, tick);
                            if (resume != null) {
                                return resume;
                            }
                        }
                        return CombatAgentStateId.IDLE;
                    }
                    return null;
                },
                ctx -> {
                    if (ctx.needsReload()) {
                        completeReload(ctx);
                    }
                }
        );
    }

    private static StateTreeNode<CombatAgentStateId, RaiderAgentContext> sidearmNode(
            WeaponMechanicsCombatBridge combat,
            LivingEntitySprintBridge sprintBridge
    ) {
        return node(
                ctx -> {
                    stopCoverRun(ctx, sprintBridge);
                    ctx.sidearmEquipped = true;
                    ctx.ammoInMag = ctx.profile.sidearmMagazineSize();
                    ctx.sidearmReloaded = false;
                    ctx.burstShotsRemaining = 0;
                    // Swap animation beat: brief pause before the sidearm opens up.
                    ctx.nextBurstTick = currentTick(ctx) + AIM_REACTION_TICKS / 2L;
                    equipActive(ctx, combat);
                    if (ctx.underFire(currentTick(ctx)) && ctx.mob.getTarget() != null) {
                        assignCover(ctx, ctx.mob.getTarget());
                    }
                },
                ctx -> {
                    long tick = currentTick(ctx);
                    LivingEntity target = ctx.mob.getTarget();
                    if (target == null || target.isDead() || !target.isValid()) {
                        return CombatAgentStateId.IDLE;
                    }
                    faceTarget(ctx, bodyAimPoint(target));
                    CombatAgentStateId stuck = RaiderStateWatchdog.resolveStuck(ctx, CombatAgentStateId.SECONDARY_WEAPON, target, tick);
                    if (stuck != null) {
                        return stuck;
                    }
                    if (RaiderCombatResolve.shouldEnterMelee(ctx, target)) {
                        return CombatAgentStateId.MELEE;
                    }
                    if (maySeekCover(ctx, tick) && ctx.coverPoint != null
                            && ctx.entity.getLocation().distanceSquared(ctx.coverPoint) > COVER_ARRIVE_SQ
                            && !RaiderCombatResolve.shouldMaintainGunfight(ctx, target, tick)) {
                        return CombatAgentStateId.TAKE_COVER;
                    }
                    if (ctx.needsReload()) {
                        if (RaiderCombatResolve.shouldMaintainGunfight(ctx, target, tick)) {
                            CombatAgentStateId reload = RaiderCombatResolve.reloadDuringGunfight(ctx, tick);
                            if (reload != null) {
                                return reload;
                            }
                        }
                        if (ctx.canReloadSidearm()) {
                            return CombatAgentStateId.RELOAD;
                        }
                        restoreGun(ctx, combat);
                        CombatAgentStateId resume = RaiderCombatResolve.resolve(ctx, target, tick);
                        return resume != null ? resume : CombatAgentStateId.MELEE;
                    }
                    CombatAgentStateId lostSight = resolveLostSight(ctx, target, tick);
                    if (lostSight != null) {
                        return lostSight;
                    }
                    if (tick < ctx.nextBurstTick) {
                        return null;
                    }
                    if (ctx.burstShotsRemaining <= 0) {
                        ctx.burstShotsRemaining = Math.min(2, ctx.profile.burstShots());
                        ctx.nextShotTick = tick;
                    }
                    if (tick >= ctx.nextShotTick && ctx.burstShotsRemaining > 0) {
                        if (RaiderSightSupport.canSeeTarget(ctx, target)) {
                            int shotIndex = Math.max(0, Math.min(2, ctx.profile.burstShots()) - ctx.burstShotsRemaining);
                            if (combat.shootAt(
                                    ctx.entity,
                                    ctx.activeWeapon(),
                                    target,
                                    ctx.activeSpread(),
                                    shotIndex,
                                    ctx.profile.style())) {
                                ctx.ammoInMag = Math.max(0, ctx.ammoInMag - 1);
                                ctx.burstShotsRemaining--;
                            }
                        }
                        ctx.nextShotTick = tick + ctx.profile.ticksBetweenBurstShots();
                        if (ctx.burstShotsRemaining <= 0) {
                            ctx.nextBurstTick = tick + ctx.profile.ticksBetweenBursts();
                        }
                    }
                    return null;
                },
                ctx -> stopCoverRun(ctx, sprintBridge)
        );
    }

    private static StateTreeNode<CombatAgentStateId, RaiderAgentContext> investigateNode(
            LivingEntitySprintBridge sprintBridge
    ) {
        return node(
                ctx -> {
                    stopCoverRun(ctx, sprintBridge);
                    RaiderMovementSupport.beginInvestigate(ctx, sprintBridge);
                },
                ctx -> {
                    long tick = currentTick(ctx);
                    org.bukkit.entity.Player adjacent = RaiderThreatSupport.findAdjacentThreat(ctx);
                    if (adjacent != null) {
                        RaiderCombatTargets.assign(ctx.mob, adjacent, ctx.playerTargetGate);
                        if (RaiderSightSupport.canSeeTarget(ctx, adjacent)) {
                            if (RaiderCombatResolve.shouldEnterMelee(ctx, adjacent)) {
                                return CombatAgentStateId.MELEE;
                            }
                            if (ctx.profile.isKnifeRusher()) {
                                return CombatAgentStateId.PURSUE;
                            }
                            if (RaiderSightSupport.withinEngageRange(ctx, adjacent)) {
                                return CombatAgentStateId.ENGAGE;
                            }
                        }
                    }
                    CombatAgentStateId passiveThreat = RaiderThreatSupport.resolvePassiveThreat(ctx, tick);
                    if (passiveThreat != null && passiveThreat != CombatAgentStateId.PURSUE) {
                        return passiveThreat;
                    }
                    LivingEntity target = ctx.mob.getTarget();
                    if (target instanceof org.bukkit.entity.Player player
                            && RaiderThreatSupport.isStaleTarget(ctx, player, tick)) {
                        ctx.mob.setTarget(null);
                        RaiderSightSupport.expireIntel(ctx);
                        target = null;
                    }
                    if (target != null && target.isValid() && !target.isDead()) {
                        if (RaiderSightSupport.canSeeTarget(ctx, target)) {
                            if (RaiderCombatResolve.shouldEnterMelee(ctx, target)) {
                                return CombatAgentStateId.MELEE;
                            }
                            if (ctx.profile.isKnifeRusher()) {
                                return CombatAgentStateId.PURSUE;
                            }
                            if (RaiderSightSupport.withinEngageRange(ctx, target)) {
                                return CombatAgentStateId.ENGAGE;
                            }
                            if (ctx.profile.prefersCloseCombat()) {
                                return CombatAgentStateId.PURSUE;
                            }
                        }
                    }
                    if (ctx.underFire(tick) && RaiderThreatSupport.intelMoved(ctx)) {
                        RaiderMovementSupport.refreshInvestigateNavigation(ctx);
                    }
                    if (!RaiderSightSupport.hasFreshLastKnown(ctx, tick)
                            || tick - ctx.stateEnteredTick >= RaiderAgentContext.INVESTIGATE_TIMEOUT_TICKS) {
                        if (target != null && target.isValid() && !target.isDead()) {
                            CombatAgentStateId resume = RaiderCombatResolve.resolve(ctx, target, tick);
                            if (resume != null
                                    && resume != CombatAgentStateId.PURSUE
                                    && resume != CombatAgentStateId.SQUAD_TACTIC) {
                                return resume;
                            }
                            if (!shouldAbandonPursuit(ctx, target)) {
                                return null;
                            }
                        }
                        ctx.mob.setTarget(null);
                        RaiderSightSupport.beginInvestigateCooldown(ctx, tick);
                        return RaiderGroupTactics.maybeSquadAbandon(ctx);
                    }
                    Location lastKnown = ctx.lastKnownTargetLocation;
                    if (lastKnown != null && hasReachedInvestigateGoal(ctx, lastKnown)) {
                        if (target != null && target.isValid() && !target.isDead()) {
                            CombatAgentStateId resume = RaiderCombatResolve.resolve(ctx, target, tick);
                            if (resume != null
                                    && resume != CombatAgentStateId.PURSUE
                                    && resume != CombatAgentStateId.SQUAD_TACTIC) {
                                return resume;
                            }
                            if (!shouldAbandonPursuit(ctx, target)) {
                                return null;
                            }
                        }
                        ctx.mob.setTarget(null);
                        RaiderSightSupport.beginInvestigateCooldown(ctx, tick);
                        return RaiderGroupTactics.maybeSquadAbandon(ctx);
                    }
                    RaiderMovementSupport.tickInvestigate(ctx);
                    if (ctx.lastKnownTargetLocation == null) {
                        RaiderSightSupport.beginInvestigateCooldown(ctx, tick);
                        return RaiderGroupTactics.maybeSquadAbandon(ctx);
                    }
                    CombatAgentStateId stalled = RaiderStateWatchdog.resolveStuck(ctx, CombatAgentStateId.PURSUE, target, tick);
                    if (stalled != null) {
                        return stalled;
                    }
                    return null;
                },
                ctx -> RaiderMovementSupport.endNavigation(ctx, sprintBridge)
        );
    }

    private static StateTreeNode<CombatAgentStateId, RaiderAgentContext> groupTacticNode(
            LivingEntitySprintBridge sprintBridge
    ) {
        return node(
                ctx -> {
                    stopCoverRun(ctx, sprintBridge);
                    RaiderMovementSupport.beginGroupTactic(ctx, sprintBridge);
                },
                ctx -> {
                    long tick = currentTick(ctx);
                    LivingEntity target = ctx.mob.getTarget();
                    if (target != null && target.isValid() && !target.isDead()) {
                        if (RaiderSightSupport.canSeeTarget(ctx, target)) {
                            if (RaiderCombatResolve.shouldEnterMelee(ctx, target)) {
                                return CombatAgentStateId.MELEE;
                            }
                            if (ctx.profile.isKnifeRusher()) {
                                return CombatAgentStateId.PURSUE;
                            }
                            if (RaiderSightSupport.withinEngageRange(ctx, target)
                                    || RaiderSightSupport.tooCloseForGun(ctx, target)) {
                                return CombatAgentStateId.ENGAGE;
                            }
                        }
                    }
                    Location tacticPoint = ctx.groupTacticPoint;
                    if (tacticPoint == null) {
                        if (target != null && target.isValid() && !target.isDead()) {
                            CombatAgentStateId resume = RaiderCombatResolve.forceReengage(ctx, target, tick);
                            if (resume != null) {
                                return resume;
                            }
                            if (RaiderSightSupport.canInvestigate(ctx, tick)) {
                                return RaiderGroupTactics.preferredPursuitState(ctx);
                            }
                        }
                        return CombatAgentStateId.IDLE;
                    }
                    if (ctx.entity.getLocation().distanceSquared(tacticPoint) <= INVESTIGATE_ARRIVE_SQ) {
                        // Point consumed. If the flank spot has no line of sight (behind cover),
                        // push toward last-known contact — never resolve back into SQUAD_TACTIC,
                        // which the engine ignores as a self-transition and would camp us here.
                        ctx.groupTacticPoint = null;
                        ctx.groupTacticAssignedTick = 0L;
                        if (target != null && target.isValid() && !target.isDead()) {
                            CombatAgentStateId resume = RaiderCombatResolve.forceReengage(ctx, target, tick);
                            if (resume != null && resume != CombatAgentStateId.SQUAD_TACTIC) {
                                return resume;
                            }
                            if (RaiderSightSupport.canInvestigate(ctx, tick)) {
                                return CombatAgentStateId.PURSUE;
                            }
                        }
                        return CombatAgentStateId.IDLE;
                    }
                    if (tick - ctx.stateEnteredTick >= RaiderAgentContext.GROUP_TACTIC_TIMEOUT_TICKS) {
                        if (target != null && target.isValid() && !target.isDead()) {
                            CombatAgentStateId resume = RaiderCombatResolve.forceReengage(ctx, target, tick);
                            if (resume != null && resume != CombatAgentStateId.SQUAD_TACTIC) {
                                return resume;
                            }
                        }
                        return CombatAgentStateId.IDLE;
                    }
                    RaiderMovementSupport.tickGroupTactic(ctx);
                    if (ctx.groupTacticPoint == null) {
                        return CombatAgentStateId.IDLE;
                    }
                    CombatAgentStateId stalled = RaiderStateWatchdog.resolveStuck(ctx, CombatAgentStateId.SQUAD_TACTIC, target, tick);
                    if (stalled != null) {
                        return stalled;
                    }
                    return null;
                },
                ctx -> RaiderMovementSupport.endNavigation(ctx, sprintBridge)
        );
    }

    private static StateTreeNode<CombatAgentStateId, RaiderAgentContext> inspectBodyNode(
            LivingEntitySprintBridge sprintBridge
    ) {
        return node(
                ctx -> {
                    stopCoverRun(ctx, sprintBridge);
                    ctx.knifeEquipped = false;
                    RaiderMovementSupport.beginInspect(ctx, sprintBridge);
                },
                ctx -> {
                    long tick = currentTick(ctx);
                    CombatAgentStateId threat = resolveInspectThreat(ctx, tick);
                    if (threat != null) {
                        return threat;
                    }
                    Location inspectPoint = ctx.inspectPoint;
                    if (inspectPoint == null) {
                        return CombatAgentStateId.IDLE;
                    }
                    Location feet = ctx.entity.getLocation();
                    if (feet.distanceSquared(inspectPoint) <= INVESTIGATE_ARRIVE_SQ) {
                        ctx.mob.getPathfinder().stopPathfinding();
                        float yaw = yawToward(feet, inspectPoint);
                        ctx.entity.setRotation(yaw, 28.0F);
                        recordFacing(ctx, yaw);
                        if (tick - ctx.stateEnteredTick >= RaiderAgentContext.INSPECT_BODY_TICKS) {
                            ctx.inspectPoint = null;
                            return CombatAgentStateId.IDLE;
                        }
                        return null;
                    }
                    if (tick - ctx.stateEnteredTick >= RaiderAgentContext.INSPECT_TIMEOUT_TICKS) {
                        ctx.inspectPoint = null;
                        return CombatAgentStateId.IDLE;
                    }
                    RaiderMovementSupport.tickInspect(ctx);
                    if (ctx.inspectPoint == null) {
                        return CombatAgentStateId.IDLE;
                    }
                    CombatAgentStateId stalled = RaiderStateWatchdog.resolveStuck(ctx, CombatAgentStateId.INSPECT, null, tick);
                    if (stalled != null) {
                        return stalled;
                    }
                    return null;
                },
                ctx -> {
                    RaiderMovementSupport.endNavigation(ctx, sprintBridge);
                    ctx.inspectPoint = null;
                }
        );
    }

    private static float yawToward(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    private static CombatAgentStateId resolveInspectThreat(RaiderAgentContext ctx, long tick) {
        if (ctx.underFire(tick)) {
            ctx.inspectPoint = null;
            return CombatAgentStateId.IDLE;
        }
        LivingEntity target = ctx.mob.getTarget();
        if (target instanceof LivingEntity living && living.isValid() && !living.isDead()) {
            if (RaiderSightSupport.canSeeTarget(ctx, living)) {
                ctx.inspectPoint = null;
                CombatAgentStateId resume = RaiderCombatResolve.resolve(ctx, living, tick);
                return resume != null ? resume : CombatAgentStateId.ENGAGE;
            }
        }
        return null;
    }

    private static StateTreeNode<CombatAgentStateId, RaiderAgentContext> groupAbandonNode(
            LivingEntitySprintBridge sprintBridge
    ) {
        return node(
                ctx -> {
                    stopCoverRun(ctx, sprintBridge);
                    releaseThreat(ctx);
                    RaiderGroupTactics.beginLeavingSquad(ctx);
                    RaiderMovementSupport.beginGroupAbandon(ctx, sprintBridge);
                },
                ctx -> {
                    long tick = currentTick(ctx);
                    if (ctx.underFire(tick)) {
                        CombatAgentStateId threat = RaiderThreatSupport.resolvePassiveThreat(ctx, tick);
                        if (threat != null && threat != CombatAgentStateId.SQUAD_LEAVE) {
                            return threat;
                        }
                    }
                    RaiderMovementSupport.tickGroupAbandon(ctx);
                    Location origin = ctx.patrolOrigin;
                    if (origin != null
                            && ctx.entity.getLocation().distanceSquared(origin) <= INVESTIGATE_ARRIVE_SQ) {
                        return CombatAgentStateId.IDLE;
                    }
                    if (tick - ctx.stateEnteredTick >= RaiderAgentContext.GROUP_ABANDON_TIMEOUT_TICKS) {
                        return CombatAgentStateId.IDLE;
                    }
                    return null;
                },
                ctx -> {
                    RaiderMovementSupport.endNavigation(ctx, sprintBridge);
                    RaiderGroupTactics.finishLeavingSquad(ctx);
                }
        );
    }

    private static void releaseThreat(RaiderAgentContext ctx) {
        ctx.mob.setTarget(null);
        ctx.lastCombatPlayerId = null;
        ctx.lastCombatPlayerTick = 0L;
        ctx.retreatPoint = null;
        ctx.retreatReplans = 0;
        ctx.coverPoint = null;
        ctx.groupTacticPoint = null;
        ctx.groupTacticAssignedTick = 0L;
        ctx.knifeEquipped = false;
        RaiderSightSupport.expireIntel(ctx);
        ctx.invalidateCoverCache();
    }

    private static StateTreeNode<CombatAgentStateId, RaiderAgentContext> retreatNode(LivingEntitySprintBridge sprintBridge) {
        return node(
                ctx -> {
                    stopCoverRun(ctx, sprintBridge);
                    LivingEntity target = ctx.mob.getTarget();
                    if (target != null && ctx.retreatPoint == null) {
                        planRetreat(ctx, target, currentTick(ctx));
                    }
                    ctx.retreatReplans = 0;
                    RaiderMovementSupport.beginRetreat(ctx, sprintBridge);
                },
                ctx -> {
                    long tick = currentTick(ctx);
                    LivingEntity target = ctx.mob.getTarget();
                    if (target == null || !target.isValid() || target.isDead()) {
                        stopCoverRun(ctx, sprintBridge);
                        return CombatAgentStateId.IDLE;
                    }
                    faceAwayFromThreat(ctx);
                    RaiderCoverSupport.updateRetreatProgress(ctx, tick);

                    if (RaiderCombatResolve.shouldEnterMelee(ctx, target)) {
                        stopCoverRun(ctx, sprintBridge);
                        return CombatAgentStateId.MELEE;
                    }

                    if (tick - ctx.lastRetreatScanTick >= RaiderAgentContext.RETREAT_RESCAN_TICKS) {
                        ctx.lastRetreatScanTick = tick;
                        Location realCover = RaiderGroupTactics.findRealCoverCached(ctx, target);
                        if (realCover != null) {
                            stopCoverRun(ctx, sprintBridge);
                            ctx.coverPoint = realCover;
                            ctx.coverBlocksLos = true;
                            markCoverAssignment(ctx, tick);
                            return CombatAgentStateId.TAKE_COVER;
                        }
                    }

                    if (ctx.canFightBack(tick) && !ctx.underFire(tick)) {
                        stopCoverRun(ctx, sprintBridge);
                        CombatAgentStateId resume = RaiderCombatResolve.forceReengage(ctx, target, tick);
                        return resume != null ? resume : CombatAgentStateId.ENGAGE;
                    }

                    Location waypoint = ctx.retreatPoint;
                    boolean arrived = waypoint != null
                            && ctx.entity.getLocation().distanceSquared(waypoint) <= INVESTIGATE_ARRIVE_SQ;
                    boolean stalled = RaiderCoverSupport.ticksWithoutCoverProgress(ctx, tick)
                            >= RaiderAgentContext.RETREAT_WAYPOINT_STALL_TICKS;
                    if (waypoint == null || arrived || stalled) {
                        ctx.retreatReplans++;
                        if (ctx.retreatReplans >= RaiderAgentContext.RETREAT_MAX_REPLANS) {
                            stopCoverRun(ctx, sprintBridge);
                            return RaiderGroupTactics.maybeSquadAbandon(ctx);
                        }
                        planRetreat(ctx, target, tick);
                        RaiderMovementSupport.beginRetreat(ctx, sprintBridge);
                    } else {
                        RaiderMovementSupport.tickRetreat(ctx);
                    }

                    if (tick - ctx.stateEnteredTick >= RaiderAgentContext.RETREAT_MAX_DURATION_TICKS) {
                        stopCoverRun(ctx, sprintBridge);
                        if (ctx.canFightBack(tick) && !ctx.underFire(tick)) {
                            CombatAgentStateId resume = RaiderCombatResolve.forceReengage(ctx, target, tick);
                            if (resume != null) {
                                return resume;
                            }
                            if (RaiderSightSupport.canInvestigate(ctx, tick)) {
                                return RaiderGroupTactics.preferredPursuitState(ctx);
                            }
                            return CombatAgentStateId.IDLE;
                        }
                        return RaiderGroupTactics.maybeSquadAbandon(ctx);
                    }
                    CombatAgentStateId watchdog = RaiderStateWatchdog.resolveStuck(ctx, CombatAgentStateId.RETREAT, target, tick);
                    if (watchdog != null) {
                        stopCoverRun(ctx, sprintBridge);
                        return watchdog;
                    }
                    return null;
                },
                ctx -> RaiderMovementSupport.endNavigation(ctx, sprintBridge)
        );
    }

    private static CombatAgentStateId transitionAfterCoverArrival(
            RaiderAgentContext ctx,
            LivingEntity target,
            long tick
    ) {
        if (target != null && target.isValid() && !target.isDead()) {
            CombatAgentStateId pressure = resolvePeekPressure(ctx, target, tick);
            if (pressure != null) {
                return pressure;
            }
        }
        if (ctx.coverBlocksLos && (ctx.needsReload() || ctx.needsHeal(tick) || maySeekCover(ctx, tick))) {
            return CombatAgentStateId.PEEK;
        }
        if (target != null && target.isValid() && !target.isDead()) {
            if (ctx.canFightBack(tick)) {
                CombatAgentStateId resume = RaiderCombatResolve.forceReengage(ctx, target, tick);
                if (resume != null) {
                    return resume;
                }
            }
            return seekCoverOrRetreat(ctx, target, tick);
        }
        return CombatAgentStateId.IDLE;
    }

    /** Resume combat after a heal finishes instead of looping back into peek/heal. */
    private static CombatAgentStateId resolvePostHealTransition(RaiderAgentContext ctx, LivingEntity target, long tick) {
        if (target != null && target.isValid() && !target.isDead()) {
            CombatAgentStateId pressure = resolvePeekPressure(ctx, target, tick);
            if (pressure != null) {
                return pressure;
            }
            if (ctx.needsReload() && (ctx.canReloadPrimary() || ctx.canReloadSidearm())
                    && !RaiderSightSupport.withinMeleeRange(ctx, target)
                    && !RaiderSightSupport.tooCloseForGun(ctx, target)) {
                return CombatAgentStateId.RELOAD;
            }
            if (ctx.canFightBack(tick)) {
                CombatAgentStateId resume = RaiderCombatResolve.forceReengage(ctx, target, tick);
                if (resume != null) {
                    return resume;
                }
            }
            if (maySeekCover(ctx, tick)) {
                return seekCoverOrRetreat(ctx, target, tick);
            }
        }
        if (ctx.needsReload() && (ctx.canReloadPrimary() || ctx.canReloadSidearm())) {
            return CombatAgentStateId.RELOAD;
        }
        return CombatAgentStateId.IDLE;
    }

    /** Break peek immediately when the threat is on top of the gunner or line-of-sight is clear to fight. */
    private static CombatAgentStateId resolvePeekPressure(RaiderAgentContext ctx, LivingEntity target, long tick) {
        if (target == null || !target.isValid() || target.isDead()) {
            return CombatAgentStateId.IDLE;
        }
        if (RaiderCombatResolve.shouldEnterMelee(ctx, target)) {
            return CombatAgentStateId.MELEE;
        }
        double distanceSq = ctx.entity.getLocation().distanceSquared(target.getLocation());
        if (distanceSq <= PEEK_ABORT_RANGE_SQ) {
            if (RaiderSightSupport.canSeeTarget(ctx, target) && ctx.canFightBack(tick)) {
                CombatAgentStateId resume = RaiderCombatResolve.forceReengage(ctx, target, tick);
                if (resume != null) {
                    return resume;
                }
                if (RaiderSightSupport.withinEngageRange(ctx, target)
                        || distanceSq <= ctx.profile.engageRangeSq()) {
                    return ctx.profile.isKnifeRusher()
                            ? CombatAgentStateId.PURSUE
                            : CombatAgentStateId.ENGAGE;
                }
            }
            if (RaiderCombatResolve.shouldEnterMelee(ctx, target)) {
                return CombatAgentStateId.MELEE;
            }
            return null;
        }
        if (RaiderSightSupport.canEngageWithGun(ctx, target) && ctx.canFightBack(tick)) {
            return ctx.profile.isKnifeRusher() ? CombatAgentStateId.PURSUE : CombatAgentStateId.ENGAGE;
        }
        if (RaiderSightSupport.canSeeTarget(ctx, target) && ctx.canFightBack(tick)) {
            if (distanceSq <= ctx.profile.engageRangeSq()) {
                return ctx.profile.isKnifeRusher() ? CombatAgentStateId.PURSUE : CombatAgentStateId.ENGAGE;
            }
        }
        return null;
    }

    /** Cover run complete when near the waypoint or when line-of-sight to the threat is broken. */
    private static boolean hasReachedCover(RaiderAgentContext ctx, LivingEntity target) {
        Location cover = ctx.coverPoint;
        if (cover != null && ctx.entity.getLocation().distanceSquared(cover) <= COVER_ARRIVE_SQ) {
            return true;
        }
        if (target == null || !target.isValid() || target.isDead()) {
            return cover != null;
        }
        Location stand = ctx.entity.getLocation();
        if (RaiderCoverSupport.stillProvidesCover(target, stand)) {
            ctx.coverBlocksLos = true;
            return true;
        }
        if (!RaiderSightSupport.canSeeTarget(ctx, target)) {
            ctx.coverBlocksLos = RaiderCoverSupport.stillProvidesCover(target, stand);
            return true;
        }
        return false;
    }

    private static void assignCover(RaiderAgentContext ctx, LivingEntity threat) {
        ctx.invalidateCoverCache();
        Location realCover = RaiderGroupTactics.findRealCoverCached(ctx, threat);
        if (realCover != null) {
            ctx.coverPoint = realCover;
            ctx.coverBlocksLos = true;
        } else {
            ctx.coverPoint = RaiderGroupTactics.findCover(ctx, threat);
            ctx.coverBlocksLos = threat != null
                    && ctx.coverPoint != null
                    && RaiderCoverSupport.stillProvidesCover(threat, ctx.coverPoint);
        }
        markCoverAssignment(ctx, currentTick(ctx));
    }

    private static CombatAgentStateId seekCoverOrRetreat(RaiderAgentContext ctx, LivingEntity threat, long tick) {
        if (threat == null || !threat.isValid() || threat.isDead()) {
            return CombatAgentStateId.IDLE;
        }
        Location realCover = RaiderGroupTactics.findRealCoverCached(ctx, threat);
        if (realCover != null) {
            ctx.coverPoint = realCover;
            ctx.coverBlocksLos = true;
            markCoverAssignment(ctx, tick);
            return CombatAgentStateId.TAKE_COVER;
        }
        if (!ctx.canFightBack(tick)) {
            planRetreat(ctx, threat, tick);
            return CombatAgentStateId.RETREAT;
        }
        ctx.coverPoint = RaiderCoverSupport.findRetreatWaypoint(
                ctx.entity.getLocation(),
                threat.getLocation(),
                false
        );
        if (ctx.coverPoint == null) {
            ctx.coverPoint = RaiderCoverSupport.findBestCover(ctx, threat);
        }
        ctx.coverBlocksLos = RaiderCoverSupport.stillProvidesCover(threat, ctx.coverPoint);
        markCoverAssignment(ctx, tick);
        return CombatAgentStateId.TAKE_COVER;
    }

    private static void planRetreat(RaiderAgentContext ctx, LivingEntity threat, long tick) {
        ctx.retreatPoint = RaiderCoverSupport.findRetreatWaypoint(
                ctx.entity.getLocation(),
                threat.getLocation(),
                true
        );
        ctx.lastRetreatScanTick = tick;
        RaiderCoverSupport.markRetreatAssignment(ctx, tick);
    }

    private static void markCoverAssignment(RaiderAgentContext ctx, long tick) {
        RaiderCoverSupport.markAssignment(ctx, tick);
    }

    /** Bail out of cover runs that stall, expose the agent, or let the player close the distance. */
    private static CombatAgentStateId resolveCoverPressure(
            RaiderAgentContext ctx,
            LivingEntity target,
            long tick,
            LivingEntitySprintBridge sprintBridge
    ) {
        if (target == null || !target.isValid() || target.isDead()) {
            stopCoverRun(ctx, sprintBridge);
            return CombatAgentStateId.IDLE;
        }
        if (RaiderCombatResolve.shouldEnterMelee(ctx, target)) {
            stopCoverRun(ctx, sprintBridge);
            return CombatAgentStateId.MELEE;
        }
        long inCover = tick - ctx.stateEnteredTick;
        long stalled = RaiderCoverSupport.ticksWithoutCoverProgress(ctx, tick);
        if (stalled >= RaiderAgentContext.COVER_PROGRESS_STALL_TICKS
                || inCover >= RaiderAgentContext.COVER_MAX_DURATION_TICKS) {
            stopCoverRun(ctx, sprintBridge);
            if (!ctx.canFightBack(tick) && RaiderGroupTactics.findRealCoverCached(ctx, target) == null) {
                planRetreat(ctx, target, tick);
                return CombatAgentStateId.RETREAT;
            }
            return abortCoverForCombat(ctx, target, tick);
        }
        if (!ctx.coverBlocksLos
                && ctx.underFire(tick)
                && inCover >= RaiderAgentContext.COVER_FAKE_TIMEOUT_TICKS) {
            stopCoverRun(ctx, sprintBridge);
            if (!ctx.canFightBack(tick)) {
                planRetreat(ctx, target, tick);
                return CombatAgentStateId.RETREAT;
            }
            return abortCoverForCombat(ctx, target, tick);
        }
        return null;
    }

    private static CombatAgentStateId abortCoverForCombat(RaiderAgentContext ctx, LivingEntity target, long tick) {
        CombatAgentStateId resume = RaiderCombatResolve.forceReengage(ctx, target, tick);
        if (resume != null) {
            return resume;
        }
        if (RaiderCombatResolve.shouldEnterMelee(ctx, target)) {
            return CombatAgentStateId.MELEE;
        }
        if (RaiderSightSupport.canInvestigate(ctx, tick)) {
            return RaiderGroupTactics.preferredPursuitState(ctx);
        }
        return CombatAgentStateId.IDLE;
    }

    /** Lost sight without being pinned down: investigate last known position instead of sprinting to fake cover. */
    private static CombatAgentStateId resolveLostSight(RaiderAgentContext ctx, LivingEntity target, long tick) {
        if (RaiderSightSupport.canSeeTarget(ctx, target)) {
            return null;
        }
        if (ctx.shouldSeekCover(tick) && !ctx.shouldReturnFire(tick)) {
            return seekCoverOrRetreat(ctx, target, tick);
        }
        if (RaiderSightSupport.canInvestigate(ctx, tick)) {
            return RaiderGroupTactics.preferredPursuitState(ctx);
        }
        return CombatAgentStateId.IDLE;
    }

    private static boolean maySeekCover(RaiderAgentContext ctx, long tick) {
        return ctx.shouldSeekCover(tick) && !ctx.shouldReturnFire(tick);
    }

    private static void completeReload(RaiderAgentContext ctx) {
        if (ctx.sidearmEquipped) {
            ctx.ammoInMag = ctx.profile.sidearmMagazineSize();
            ctx.sidearmReloaded = true;
            return;
        }
        ctx.ammoInMag = ctx.profile.magazineSize();
        ctx.spareMagazines = Math.max(0, ctx.spareMagazines - 1);
    }

    private static void restoreGun(RaiderAgentContext ctx, WeaponMechanicsCombatBridge combat) {
        if (ctx.profile.isKnifeRusher()) {
            ctx.knifeEquipped = true;
            ctx.sidearmEquipped = false;
            ctx.sidearmReloaded = false;
            combat.equipWeapon(ctx.entity, ctx.profile.meleeWeapon());
            return;
        }
        ctx.knifeEquipped = false;
        ctx.sidearmEquipped = false;
        ctx.sidearmReloaded = false;
        combat.equipWeapon(ctx.entity, ctx.profile.primaryWeapon());
    }

    private static void equipActive(RaiderAgentContext ctx, WeaponMechanicsCombatBridge combat) {
        combat.equipWeapon(ctx.entity, ctx.activeWeapon());
    }

    private static void stopCoverRun(RaiderAgentContext ctx, LivingEntitySprintBridge sprintBridge) {
        RaiderMovementSupport.endCoverRun(ctx, sprintBridge);
    }

    /**
     * Between bursts, sidestep a few blocks perpendicular to the threat so engaged gunners
     * reposition like fighters instead of standing sentry. Suppress anchors hold their pin.
     */
    private static void tickCombatStrafe(RaiderAgentContext ctx, LivingEntity target, long tick) {
        if (ctx.isSuppressAnchor() || RaiderSightSupport.tooCloseForGun(ctx, target)) {
            return;
        }
        Location feet = ctx.entity.getLocation();
        boolean needsNewPoint = ctx.strafePoint == null
                || tick >= ctx.nextStrafeTick
                || feet.distanceSquared(ctx.strafePoint) <= STRAFE_ARRIVE_SQ;
        if (!needsNewPoint) {
            return;
        }
        ctx.nextStrafeTick = tick + STRAFE_REPICK_TICKS;
        ctx.strafePoint = pickStrafePoint(ctx, target);
        if (ctx.strafePoint != null) {
            network.skypvp.paper.ai.navigation.MobNavigationSupport.navigateTo(
                    ctx,
                    ctx.strafePoint,
                    network.skypvp.paper.ai.navigation.MobNavigationSupport.PATROL_WALK_SPEED
            );
        }
    }

    private static void endStrafe(RaiderAgentContext ctx) {
        if (ctx.strafePoint != null) {
            ctx.strafePoint = null;
            ctx.mob.getPathfinder().stopPathfinding();
        }
    }

    /** Standable sidestep 2.5-4 blocks perpendicular to the threat on the gunner's own floor. */
    private static Location pickStrafePoint(RaiderAgentContext ctx, LivingEntity target) {
        Location feet = ctx.entity.getLocation();
        org.bukkit.util.Vector toThreat = target.getLocation().toVector().subtract(feet.toVector());
        toThreat.setY(0.0D);
        if (toThreat.lengthSquared() < 0.04D) {
            return null;
        }
        toThreat.normalize();
        org.bukkit.util.Vector sideDir = new org.bukkit.util.Vector(-toThreat.getZ(), 0.0D, toThreat.getX());
        int sign = stickyStrafeSide(ctx);
        double distance = 2.5D + java.util.concurrent.ThreadLocalRandom.current().nextDouble() * 1.5D;
        Location candidate = feet.clone().add(sideDir.clone().multiply(sign * distance));
        Location snapped = network.skypvp.paper.ai.navigation.MobTerrainSupport.snapToStandableNear(
                candidate, 2, feet.getY(), 2.5D
        );
        if (snapped != null) {
            return snapped;
        }
        Location opposite = feet.clone().add(sideDir.clone().multiply(-sign * distance));
        return network.skypvp.paper.ai.navigation.MobTerrainSupport.snapToStandableNear(
                opposite, 2, feet.getY(), 2.5D
        );
    }

    /**
     * Lock lateral strafe sign so gunners do not ping-pong left↔right every repick.
     * Flank roles bias toward their assigned arc; everyone else picks once at random.
     */
    private static int stickyStrafeSide(RaiderAgentContext ctx) {
        if (ctx.strafeSide == -1 || ctx.strafeSide == 1) {
            return ctx.strafeSide;
        }
        if (ctx.groupRole == RaiderGroupRole.FLANK_LEFT) {
            ctx.strafeSide = -1;
        } else if (ctx.groupRole == RaiderGroupRole.FLANK_RIGHT) {
            ctx.strafeSide = 1;
        } else {
            ctx.strafeSide = java.util.concurrent.ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
        }
        return ctx.strafeSide;
    }

    private static void faceAwayFromThreat(RaiderAgentContext ctx) {
        LivingEntity threat = ctx.mob.getTarget();
        if (threat == null) {
            return;
        }
        Location origin = ctx.entity.getLocation();
        double deltaX = origin.getX() - threat.getX();
        double deltaZ = origin.getZ() - threat.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-deltaX, deltaZ));
        ctx.entity.setRotation(yaw, 0.0F);
        recordFacing(ctx, yaw);
    }

    private static void faceTarget(RaiderAgentContext ctx, Location targetLocation) {
        // Pitch from eye height (same as shot / LOS), not feet — feet-origin pitch aimed
        // the body too low and compounded spray climb into the floor.
        Location origin = RaiderSightSupport.eyeLocation(ctx.entity);
        double deltaX = targetLocation.getX() - origin.getX();
        double deltaY = targetLocation.getY() - origin.getY();
        double deltaZ = targetLocation.getZ() - origin.getZ();
        double horizontalDistance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        float yaw = (float) Math.toDegrees(Math.atan2(-deltaX, deltaZ));
        float pitch = (float) Math.toDegrees(Math.atan2(-deltaY, Math.max(0.001D, horizontalDistance)));
        ctx.entity.setRotation(yaw, pitch);
        recordFacing(ctx, yaw);
    }

    /** Chest/torso aim — avoids staring at head-mounted nametag TextDisplays. */
    private static Location bodyAimPoint(org.bukkit.entity.LivingEntity target) {
        Location base = target.getLocation().clone();
        double height = Math.max(0.9D, target.getHeight() * 0.55D);
        return base.add(0.0D, height, 0.0D);
    }

    private static void recordFacing(RaiderAgentContext ctx, float yaw) {
        ctx.aiFacingYaw = yaw;
        ctx.aiFacingTick = currentTick(ctx);
    }

    private static long currentTick(RaiderAgentContext ctx) {
        return ctx.aiTick;
    }

    private static boolean hasReachedInvestigateGoal(RaiderAgentContext ctx, Location goal) {
        org.bukkit.entity.LivingEntity target = ctx.mob.getTarget();
        if (target != null && target.isValid() && !target.isDead()) {
            if (RaiderThreatSupport.shouldContinuePursuit(ctx, target)) {
                return false;
            }
        }
        Location feet = ctx.entity.getLocation();
        double horizontalSq = horizontalDistanceSq(feet, goal);
        if (horizontalSq > RaiderAgentContext.PURSUE_ARRIVE_SQ) {
            return false;
        }
        double verticalDelta = goal.getY() - feet.getY();
        if (verticalDelta <= 2.0D) {
            return true;
        }
        if (target != null && target.isValid() && !target.isDead()) {
            if (target.getLocation().getY() > feet.getY() + 2.0D) {
                return false;
            }
            return true;
        }
        long tick = currentTick(ctx);
        return tick - ctx.stateEnteredTick >= 80L;
    }

    private static boolean shouldAbandonPursuit(RaiderAgentContext ctx, org.bukkit.entity.LivingEntity target) {
        return !RaiderThreatSupport.shouldContinuePursuit(ctx, target);
    }

    private static double horizontalDistanceSq(Location left, Location right) {
        double dx = left.getX() - right.getX();
        double dz = left.getZ() - right.getZ();
        return dx * dx + dz * dz;
    }

    private static StateTreeNode<CombatAgentStateId, RaiderAgentContext> node(
            Consumer<RaiderAgentContext> enter,
            java.util.function.Function<RaiderAgentContext, CombatAgentStateId> tick,
            Consumer<RaiderAgentContext> exit
    ) {
        return network.skypvp.paper.ai.statetree.StateTreeNodes.of(enter, tick, exit);
    }
}
