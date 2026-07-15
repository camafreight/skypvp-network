package network.skypvp.extraction.ai.raider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Detects nearby gunners, forms squads, assigns roles, and shares last-known target intel.
 */
public final class RaiderGroupRegistry {

    private static final double GROUP_RADIUS_SQ = 40.0D * 40.0D;
    private static final double COMBAT_GROUP_RADIUS_SQ = 48.0D * 48.0D;
    private static final double COHORT_GROUP_RADIUS_SQ = 72.0D * 72.0D;
    private static final int MIN_SQUAD_SIZE = 2;
    public static final int MAX_SQUAD_SIZE = 4;

    private final Map<UUID, RaiderGroupSnapshot> snapshots = new ConcurrentHashMap<>();

    public RaiderGroupSnapshot snapshot(UUID entityId) {
        if (entityId == null) {
            return RaiderGroupSnapshot.solo();
        }
        return snapshots.getOrDefault(entityId, RaiderGroupSnapshot.solo());
    }

    public void clearWorld(World world) {
        if (world == null) {
            return;
        }
        snapshots.entrySet().removeIf(entry -> {
            org.bukkit.entity.Entity entity = org.bukkit.Bukkit.getEntity(entry.getKey());
            return entity == null || world.equals(entity.getWorld());
        });
    }

    public void remove(UUID entityId) {
        if (entityId != null) {
            snapshots.remove(entityId);
        }
    }

    public void rebuild(List<MemberRef> members) {
        snapshots.clear();
        if (members == null || members.isEmpty()) {
            return;
        }
        Map<World, List<MemberRef>> byWorld = new HashMap<>();
        for (MemberRef member : members) {
            if (member == null || member.entityId() == null || member.context() == null) {
                continue;
            }
            if (member.context().leavingSquad) {
                snapshots.put(member.entityId(), RaiderGroupSnapshot.solo());
                continue;
            }
            if (!member.context().cachedLocationValid || member.context().cachedWorld == null) {
                continue;
            }
            byWorld.computeIfAbsent(member.context().cachedWorld, ignored -> new ArrayList<>()).add(member);
        }
        for (List<MemberRef> worldMembers : byWorld.values()) {
            buildWorldSquads(worldMembers);
        }
        for (MemberRef member : members) {
            if (member == null || member.entityId() == null) {
                continue;
            }
            snapshots.putIfAbsent(member.entityId(), RaiderGroupSnapshot.solo());
        }
    }

    public void applyToContext(RaiderAgentContext ctx) {
        if (ctx == null || ctx.entity == null) {
            return;
        }
        RaiderGroupSnapshot snapshot = snapshot(ctx.entity.getUniqueId());
        RaiderGroupRole previousRole = ctx.groupRole;
        UUID previousGroupId = ctx.groupId;
        ctx.groupId = snapshot.groupId();
        ctx.groupSize = snapshot.size();
        ctx.groupRole = snapshot.role();
        ctx.groupAnchorId = snapshot.anchorId();
        ctx.squadAnchorLocation = snapshot.anchorLocation() == null ? null : snapshot.anchorLocation().clone();
        ctx.squadPatrolGoal = snapshot.squadPatrolGoal() == null ? null : snapshot.squadPatrolGoal().clone();
        ctx.squadPatrolFacingYaw = snapshot.squadPatrolFacingYaw();
        ctx.squadPatrolFacingValid = snapshot.squadPatrolFacingValid();
        ctx.squadMaxMemberSpreadSq = snapshot.squadMaxMemberSpreadSq();
        if (ctx.leavingSquad) {
            ctx.groupId = null;
            ctx.groupSize = 1;
            ctx.groupRole = RaiderGroupRole.SOLO;
            ctx.groupAnchorId = null;
            ctx.groupTacticPoint = null;
            ctx.groupTacticAssignedTick = 0L;
            ctx.squadAnchorLocation = null;
            ctx.squadPatrolGoal = null;
            ctx.squadPatrolFacingValid = false;
            ctx.squadMaxMemberSpreadSq = 0.0D;
            ctx.patrolScoutPoint = null;
            ctx.formationFacingValid = false;
            ctx.formationFacingThreat = null;
            ctx.strafeSide = 0;
        } else {
            applyStickyTacticPoint(ctx, snapshot, previousRole, previousGroupId);
            refreshFormationFacing(ctx, snapshot);
        }
        RaiderGroupTactics.mergeIntel(ctx, snapshot.sharedLastKnown(), snapshot.sharedLastSeenTick());
        if (ctx.mob.getTarget() == null
                && snapshot.sharedTargetId() != null
                && ctx.playerTargetGate != null) {
            org.bukkit.entity.Player shared = org.bukkit.Bukkit.getPlayer(snapshot.sharedTargetId());
            if (shared != null) {
                RaiderCombatTargets.assign(ctx.mob, shared, ctx.playerTargetGate);
            }
        }
    }

    /**
     * Squad rebuild recomputes orbit points every few ticks as the threat moves/turns.
     * Overwriting the live waypoint each apply made flankers thrash left↔right mid-path.
     * Keep the accepted point until arrival, lease expiry, role/group change, or stall.
     */
    private static void applyStickyTacticPoint(
            RaiderAgentContext ctx,
            RaiderGroupSnapshot snapshot,
            RaiderGroupRole previousRole,
            UUID previousGroupId
    ) {
        Location incoming = snapshot.tacticPoint();
        if (!ctx.groupRole.coordinates()) {
            ctx.groupTacticPoint = null;
            ctx.groupTacticAssignedTick = 0L;
            return;
        }
        if (incoming == null) {
            return;
        }
        boolean groupChanged = previousGroupId == null || !previousGroupId.equals(snapshot.groupId());
        boolean roleChanged = previousRole != snapshot.role();
        if (ctx.groupTacticPoint == null || groupChanged || roleChanged) {
            ctx.groupTacticPoint = incoming.clone();
            ctx.groupTacticAssignedTick = ctx.aiTick;
            return;
        }
        if (ctx.groupTacticPoint.getWorld() == null
                || incoming.getWorld() == null
                || !ctx.groupTacticPoint.getWorld().equals(incoming.getWorld())) {
            ctx.groupTacticPoint = incoming.clone();
            ctx.groupTacticAssignedTick = ctx.aiTick;
            return;
        }
        long heldFor = ctx.aiTick - ctx.groupTacticAssignedTick;
        boolean leaseExpired = heldFor >= TACTIC_STICKY_LEASE_TICKS;
        boolean stalled = ctx.navigation.stalled;
        Location feet = ctx.cachedLocationValid ? ctx.cachedLocation() : ctx.entity.getLocation();
        boolean nearlyArrived = feet.distanceSquared(ctx.groupTacticPoint) <= TACTIC_STICKY_ARRIVE_SQ;
        // Orbit rebuilds shift the point by ~flank radius; only retarget on a real threat relocate.
        boolean threatRelocated = horizontalDistanceSq(ctx.groupTacticPoint, incoming) >= TACTIC_RELOCATE_SQ;
        if (leaseExpired && (stalled || nearlyArrived || threatRelocated)) {
            ctx.groupTacticPoint = incoming.clone();
            ctx.groupTacticAssignedTick = ctx.aiTick;
            ctx.navigation.stalled = false;
        }
        // Otherwise keep the sticky point — ignore orbit drift from rebuild.
    }

    private static void refreshFormationFacing(RaiderAgentContext ctx, RaiderGroupSnapshot snapshot) {
        if (!ctx.inSquad()) {
            ctx.formationFacingValid = false;
            ctx.formationFacingThreat = null;
            return;
        }
        Location anchor = snapshot.anchorLocation() != null
                ? snapshot.anchorLocation()
                : ctx.squadAnchorLocation;
        Location threat = snapshot.sharedLastKnown();
        if (threat == null) {
            threat = ctx.trackedCombatTargetLocation;
        }
        if (threat == null) {
            threat = ctx.lastKnownTargetLocation;
        }
        if (anchor == null || threat == null || anchor.getWorld() == null || threat.getWorld() == null
                || !anchor.getWorld().equals(threat.getWorld())) {
            return;
        }
        boolean needRefresh = !ctx.formationFacingValid
                || ctx.formationFacingThreat == null
                || ctx.formationFacingThreat.getWorld() == null
                || !ctx.formationFacingThreat.getWorld().equals(threat.getWorld())
                || horizontalDistanceSq(ctx.formationFacingThreat, threat) >= FORMATION_FACING_REFRESH_SQ;
        if (!needRefresh) {
            return;
        }
        double dx = threat.getX() - anchor.getX();
        double dz = threat.getZ() - anchor.getZ();
        if (dx * dx + dz * dz < 0.01D) {
            return;
        }
        ctx.formationFacingYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        ctx.formationFacingValid = true;
        ctx.formationFacingThreat = threat.clone();
    }

    private static double horizontalDistanceSq(Location left, Location right) {
        double dx = left.getX() - right.getX();
        double dz = left.getZ() - right.getZ();
        return dx * dx + dz * dz;
    }

    private static final long TACTIC_STICKY_LEASE_TICKS = 100L;
    private static final double TACTIC_STICKY_ARRIVE_SQ = 6.25D;
    private static final double TACTIC_RELOCATE_SQ = 16.0D * 16.0D;
    private static final double FORMATION_FACING_REFRESH_SQ = 10.0D * 10.0D;

    /** Pushes live target contact from one squad member to the whole fireteam. */
    public void shareSquadIntel(UUID groupId, org.bukkit.entity.Player target, long tick) {
        if (groupId == null || target == null || !target.isValid() || target.isDead()) {
            return;
        }
        Location contact = target.getLocation().clone();
        UUID targetId = target.getUniqueId();
        for (Map.Entry<UUID, RaiderGroupSnapshot> entry : snapshots.entrySet()) {
            RaiderGroupSnapshot member = entry.getValue();
            if (member.groupId() == null || !member.groupId().equals(groupId)) {
                continue;
            }
            if (tick <= member.sharedLastSeenTick()) {
                continue;
            }
            snapshots.put(
                    entry.getKey(),
                    member.withSharedCombat(targetId, contact, tick)
            );
        }
    }

    public void alertGroup(UUID victimId, Player attacker, long tick) {
        if (victimId == null || attacker == null || !attacker.isValid()) {
            return;
        }
        RaiderGroupSnapshot victimSnapshot = snapshot(victimId);
        if (!victimSnapshot.inSquad() || victimSnapshot.groupId() == null) {
            return;
        }
        Location alertLocation = attacker.getLocation().clone();
        for (Map.Entry<UUID, RaiderGroupSnapshot> entry : snapshots.entrySet()) {
            RaiderGroupSnapshot memberSnapshot = entry.getValue();
            if (memberSnapshot.groupId() == null || !memberSnapshot.groupId().equals(victimSnapshot.groupId())) {
                continue;
            }
            snapshots.put(
                    entry.getKey(),
                    memberSnapshot.withSharedCombat(
                            attacker.getUniqueId(),
                            alertLocation,
                            tick
                    )
            );
        }
    }

    /** Clears squad snapshots that still reference an eliminated or extracted player. */
    public void purgeSharedTarget(UUID targetId) {
        if (targetId == null) {
            return;
        }
        for (Map.Entry<UUID, RaiderGroupSnapshot> entry : snapshots.entrySet()) {
            RaiderGroupSnapshot member = entry.getValue();
            if (!targetId.equals(member.sharedTargetId())) {
                continue;
            }
            snapshots.put(
                    entry.getKey(),
                    member.withSharedCombat(
                            null,
                            member.sharedLastKnown(),
                            member.sharedLastSeenTick()
                    )
            );
        }
    }

    private void buildWorldSquads(List<MemberRef> members) {
        if (members.size() < MIN_SQUAD_SIZE) {
            return;
        }
        members.sort(Comparator.comparing(member -> member.entityId()));
        int[] parent = new int[members.size()];
        for (int index = 0; index < parent.length; index++) {
            parent[index] = index;
        }
        for (int left = 0; left < members.size(); left++) {
            for (int right = left + 1; right < members.size(); right++) {
                if (shouldGroup(members.get(left), members.get(right))) {
                    union(parent, left, right);
                }
            }
        }
        Map<Integer, List<MemberRef>> clusters = new HashMap<>();
        for (int index = 0; index < members.size(); index++) {
            int root = find(parent, index);
            clusters.computeIfAbsent(root, ignored -> new ArrayList<>()).add(members.get(index));
        }
        for (List<MemberRef> cluster : clusters.values()) {
            assignSquadsFromCluster(cluster);
        }
    }

    private void assignSquadsFromCluster(List<MemberRef> cluster) {
        if (cluster.size() < MIN_SQUAD_SIZE) {
            return;
        }
        cluster.sort(Comparator.comparing(MemberRef::entityId));
        for (int start = 0; start < cluster.size(); start += MAX_SQUAD_SIZE) {
            int end = Math.min(start + MAX_SQUAD_SIZE, cluster.size());
            List<MemberRef> squad = cluster.subList(start, end);
            if (squad.size() >= MIN_SQUAD_SIZE) {
                assignSquad(new ArrayList<>(squad));
            }
        }
    }

    private static boolean shouldGroup(MemberRef left, MemberRef right) {
        Location leftLocation = left.context().cachedLocation();
        Location rightLocation = right.context().cachedLocation();
        if (leftLocation.getWorld() == null
                || rightLocation.getWorld() == null
                || !leftLocation.getWorld().equals(rightLocation.getWorld())) {
            return false;
        }
        if (leftLocation.distanceSquared(rightLocation) <= GROUP_RADIUS_SQ) {
            return true;
        }
        UUID leftCohort = left.context().spawnCohortId;
        UUID rightCohort = right.context().spawnCohortId;
        if (leftCohort != null
                && leftCohort.equals(rightCohort)
                && leftLocation.distanceSquared(rightLocation) <= COHORT_GROUP_RADIUS_SQ) {
            return true;
        }
        UUID leftTarget = left.context().trackedCombatTargetId;
        UUID rightTarget = right.context().trackedCombatTargetId;
        return leftTarget != null
                && leftTarget.equals(rightTarget)
                && leftLocation.distanceSquared(rightLocation) <= COMBAT_GROUP_RADIUS_SQ;
    }

    private static UUID squadGroupId(MemberRef anchor) {
        RaiderAgentContext context = anchor.context();
        if (context.spawnCohortId != null) {
            return UUID.nameUUIDFromBytes(
                    ("raider-squad:" + context.spawnCohortId + ":" + anchor.entityId())
                            .getBytes(StandardCharsets.UTF_8)
            );
        }
        return UUID.nameUUIDFromBytes(
                ("raider-squad:" + anchor.entityId()).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static int find(int[] parent, int index) {
        if (parent[index] != index) {
            parent[index] = find(parent, parent[index]);
        }
        return parent[index];
    }

    private static void union(int[] parent, int left, int right) {
        int leftRoot = find(parent, left);
        int rightRoot = find(parent, right);
        if (leftRoot != rightRoot) {
            parent[rightRoot] = leftRoot;
        }
    }

    private void assignSquad(List<MemberRef> cluster) {
        cluster.sort(Comparator.comparing(MemberRef::entityId));
        UUID anchorId = cluster.get(0).entityId();
        UUID groupId = squadGroupId(cluster.get(0));
        Location anchorLocation = cluster.get(0).context().cachedLocation();
        Location sharedTargetLocation = resolveSharedTargetLocation(cluster);
        UUID sharedTargetId = resolveSharedTargetId(cluster, sharedTargetLocation);
        Intel intel = resolveSharedIntel(cluster);
        if (intel.location() == null && sharedTargetLocation != null) {
            intel = new Intel(sharedTargetLocation, Math.max(intel.seenTick(), 1L));
        }
        Location threatLocation = resolveThreatLocation(sharedTargetLocation, intel);
        RaiderSquadPatrolSupport.IdlePatrolPlan patrolPlan =
                RaiderSquadPatrolSupport.planIdlePatrol(cluster, anchorLocation, threatLocation);

        for (int index = 0; index < cluster.size(); index++) {
            MemberRef member = cluster.get(index);
            RaiderGroupRole role = roleForIndex(index);
            Location tacticPoint = RaiderGroupTactics.computeTacticPoint(
                    role,
                    member.context().cachedLocation(),
                    anchorLocation,
                    threatLocation
            );
            snapshots.put(
                    member.entityId(),
                    new RaiderGroupSnapshot(
                            groupId,
                            cluster.size(),
                            role,
                            anchorId,
                            anchorLocation.clone(),
                            tacticPoint,
                            sharedTargetId,
                            intel.location(),
                            intel.seenTick(),
                            patrolPlan.goal(),
                            patrolPlan.facingYaw(),
                            patrolPlan.facingValid(),
                            patrolPlan.maxMemberSpreadSq()
                    )
            );
        }
    }

    private static Location resolveThreatLocation(Location sharedTargetLocation, Intel intel) {
        if (sharedTargetLocation != null) {
            return sharedTargetLocation;
        }
        return intel.location();
    }

    private static RaiderGroupRole roleForIndex(int index) {
        return switch (index) {
            case 0 -> RaiderGroupRole.SUPPRESS;
            case 1 -> RaiderGroupRole.FLANK_LEFT;
            case 2 -> RaiderGroupRole.FLANK_RIGHT;
            case 3 -> RaiderGroupRole.BREACH;
            default -> index % 2 == 0 ? RaiderGroupRole.FLANK_LEFT : RaiderGroupRole.FLANK_RIGHT;
        };
    }

    private static Location resolveSharedTargetLocation(List<MemberRef> cluster) {
        Location bestLocation = null;
        double bestDistanceSq = Double.MAX_VALUE;
        for (MemberRef member : cluster) {
            RaiderAgentContext ctx = member.context();
            Location targetLocation = ctx.trackedCombatTargetLocation;
            if (targetLocation == null) {
                continue;
            }
            double distanceSq = ctx.cachedLocation().distanceSquared(targetLocation);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                bestLocation = targetLocation.clone();
            }
        }
        return bestLocation;
    }

    private static UUID resolveSharedTargetId(List<MemberRef> cluster, Location sharedTargetLocation) {
        UUID bestId = null;
        long bestTick = 0L;
        for (MemberRef member : cluster) {
            RaiderAgentContext ctx = member.context();
            if (ctx.trackedCombatTargetId == null) {
                continue;
            }
            if (ctx.lastSeenTargetTick >= bestTick) {
                bestTick = ctx.lastSeenTargetTick;
                bestId = ctx.trackedCombatTargetId;
            }
        }
        if (bestId != null) {
            return bestId;
        }
        if (sharedTargetLocation == null) {
            return null;
        }
        org.bukkit.World world = sharedTargetLocation.getWorld();
        if (world == null) {
            return null;
        }
        RaiderAgentContext gateContext = cluster.isEmpty() ? null : cluster.get(0).context();
        for (org.bukkit.entity.Player player : world.getPlayers()) {
            if (gateContext != null
                    && gateContext.playerTargetGate != null
                    && !gateContext.playerTargetGate.allows(player)) {
                continue;
            }
            if (player.getLocation().distanceSquared(sharedTargetLocation) <= 4.0D) {
                return player.getUniqueId();
            }
        }
        return null;
    }

    private static Intel resolveSharedIntel(List<MemberRef> cluster) {
        Location bestLocation = null;
        long bestTick = 0L;
        for (MemberRef member : cluster) {
            RaiderAgentContext ctx = member.context();
            if (ctx.lastKnownTargetLocation == null) {
                continue;
            }
            if (ctx.lastSeenTargetTick >= bestTick) {
                bestTick = ctx.lastSeenTargetTick;
                bestLocation = ctx.lastKnownTargetLocation.clone();
            }
        }
        return new Intel(bestLocation, bestTick);
    }

    public record MemberRef(UUID entityId, RaiderAgentContext context) {
    }

    private record Intel(Location location, long seenTick) {
    }
}
