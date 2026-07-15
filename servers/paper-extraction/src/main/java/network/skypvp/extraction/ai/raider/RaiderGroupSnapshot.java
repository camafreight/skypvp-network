package network.skypvp.extraction.ai.raider;

import java.util.UUID;
import org.bukkit.Location;

/** Per-member squad assignment rebuilt periodically by {@link RaiderGroupRegistry}. */
public record RaiderGroupSnapshot(
        UUID groupId,
        int size,
        RaiderGroupRole role,
        UUID anchorId,
        Location anchorLocation,
        Location tacticPoint,
        UUID sharedTargetId,
        Location sharedLastKnown,
        long sharedLastSeenTick,
        Location squadPatrolGoal,
        float squadPatrolFacingYaw,
        boolean squadPatrolFacingValid,
        double squadMaxMemberSpreadSq
) {
    public static RaiderGroupSnapshot solo() {
        return new RaiderGroupSnapshot(
                null, 1, RaiderGroupRole.SOLO, null, null, null, null, null, 0L,
                null, 0.0F, false, 0.0D
        );
    }

    public boolean inSquad() {
        return size >= 2 && role != RaiderGroupRole.SOLO;
    }

    public RaiderGroupSnapshot withSharedCombat(
            UUID targetId,
            Location lastKnown,
            long seenTick
    ) {
        return new RaiderGroupSnapshot(
                groupId,
                size,
                role,
                anchorId,
                anchorLocation,
                tacticPoint,
                targetId,
                lastKnown,
                seenTick,
                squadPatrolGoal,
                squadPatrolFacingYaw,
                squadPatrolFacingValid,
                squadMaxMemberSpreadSq
        );
    }
}
