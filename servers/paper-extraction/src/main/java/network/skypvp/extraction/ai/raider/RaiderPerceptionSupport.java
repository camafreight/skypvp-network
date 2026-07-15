package network.skypvp.extraction.ai.raider;

import java.util.UUID;
import network.skypvp.extraction.gameplay.BreachGunfireTracker;
import network.skypvp.paper.ai.navigation.MobNavigationSupport;
import network.skypvp.paper.ai.statetree.CombatAgentStateId;
import org.bukkit.Location;

/** Public entry points for raider threat perception from gameplay services. */
public final class RaiderPerceptionSupport {

    private RaiderPerceptionSupport() {
    }

    public static void recordDamageThreat(RaiderAgentContext ctx, Location attackerLocation, long tick) {
        RaiderSightSupport.observeThreatAt(ctx, attackerLocation, tick);
    }

    public static void scanHearing(RaiderAgentContext ctx, BreachGunfireTracker tracker) {
        RaiderHearingSupport.scan(ctx, tracker);
    }

    public static Location snapDestination(Location destination) {
        return MobNavigationSupport.prepareDestination(destination);
    }

    public static boolean canSeeTarget(RaiderAgentContext ctx, org.bukkit.entity.LivingEntity target) {
        return RaiderSightSupport.canSeeTarget(ctx, target);
    }

    public static void expireIntel(RaiderAgentContext ctx) {
        RaiderSightSupport.expireIntel(ctx);
    }

    /** Drops active combat target, intel, and movement goals for this gunner. */
    public static void clearCombatFocus(RaiderAgentContext ctx) {
        if (ctx == null || ctx.mob == null) {
            return;
        }
        ctx.mob.setTarget(null);
        ctx.lastCombatPlayerId = null;
        ctx.lastCombatPlayerTick = 0L;
        RaiderSightSupport.expireIntel(ctx);
        ctx.coverPoint = null;
        ctx.groupTacticPoint = null;
        ctx.groupTacticAssignedTick = 0L;
        ctx.retreatPoint = null;
        ctx.inspectPoint = null;
        ctx.strafePoint = null;
        ctx.strafeSide = 0;
        ctx.navigation.clearRoute();
        ctx.navigation.climbingLadder = false;
        ctx.navigation.climbingStairs = false;
        MobNavigationSupport.resetProgress(ctx);
        ctx.trackedCombatTargetId = null;
        ctx.trackedCombatTargetLocation = null;
    }

    public static boolean isFocusedOn(RaiderAgentContext ctx, UUID playerId) {
        if (ctx == null || playerId == null) {
            return false;
        }
        if (playerId.equals(ctx.lastCombatPlayerId)) {
            return true;
        }
        if (playerId.equals(ctx.trackedCombatTargetId)) {
            return true;
        }
        return ctx.mob.getTarget() instanceof org.bukkit.entity.Player player
                && player.getUniqueId().equals(playerId);
    }

    public static boolean withinStrikeRange(RaiderAgentContext ctx, org.bukkit.entity.LivingEntity target) {
        return RaiderSightSupport.withinStrikeRange(ctx, target);
    }

    public static void refreshTargetPriority(RaiderAgentContext ctx, long tick) {
        RaiderThreatSupport.refreshTargetPriority(ctx, tick);
    }

    public static boolean isStaleTarget(RaiderAgentContext ctx, org.bukkit.entity.LivingEntity target, long tick) {
        return RaiderThreatSupport.isStaleTarget(ctx, target, tick);
    }

    public static CombatAgentStateId resolvePassiveThreat(RaiderAgentContext ctx, long tick) {
        return RaiderThreatSupport.resolvePassiveThreat(ctx, tick);
    }
}
