package network.skypvp.extraction.ai.raider;

import org.bukkit.Location;
import org.bukkit.util.Vector;

/** Role-based spacing so squad members do not stack on the same block. */
final class RaiderSquadFormation {

    private static final double LATERAL_SPACING = 5.0D;
    private static final double DEPTH_SPACING = 4.5D;
    private static final double SCOUT_RADIUS = 8.0D;
    private static final double SLOT_ARRIVE_SQ = 2.56D;

    private RaiderSquadFormation() {
    }

    static Location calmSlot(RaiderGroupRole role, Location anchorFeet) {
        if (anchorFeet == null) {
            return null;
        }
        return calmSlot(role, anchorFeet, anchorFeet.getYaw());
    }

    /**
     * Formation slots relative to a sticky facing yaw. Prefer engagement facing over the
     * anchor entity's live body yaw — suppressors constantly turn toward threats and that
     * used to flip FLANK_LEFT / FLANK_RIGHT every tick.
     */
    static Location calmSlot(RaiderGroupRole role, Location anchorFeet, float facingYaw) {
        return offsetSlot(role, anchorFeet, facingYaw, LATERAL_SPACING, DEPTH_SPACING);
    }

    /**
     * March formation around the squad's shared patrol goal. Each role gets a distinct
     * offset so the fireteam fans out while moving toward the same destination.
     */
    static Location patrolSlot(RaiderGroupRole role, Location goalCenter, float facingYaw) {
        return offsetSlot(role, goalCenter, facingYaw, LATERAL_SPACING, DEPTH_SPACING);
    }

    /**
     * Checkpoint fan-out: members peel off to different arcs around the patrol goal to
     * scan separate sectors before regrouping.
     */
    static Location scoutSlot(RaiderGroupRole role, Location goalCenter, float facingYaw) {
        if (goalCenter == null || goalCenter.getWorld() == null) {
            return null;
        }
        Vector forward = facingForward(facingYaw);
        Vector right = rightOf(forward);
        Vector offset = switch (role == null ? RaiderGroupRole.SOLO : role) {
            case FLANK_LEFT -> right.clone().multiply(-SCOUT_RADIUS).add(forward.clone().multiply(3.0D));
            case FLANK_RIGHT -> right.clone().multiply(SCOUT_RADIUS).add(forward.clone().multiply(3.0D));
            case BREACH -> forward.clone().multiply(SCOUT_RADIUS);
            case SUPPRESS -> forward.clone().multiply(-2.5D);
            default -> right.clone().multiply(SCOUT_RADIUS * 0.5D);
        };
        Location slot = goalCenter.clone().add(offset);
        slot.setY(goalCenter.getY());
        return slot;
    }

    static boolean atSlot(Location feet, Location slot) {
        return feet != null && slot != null && feet.distanceSquared(slot) <= SLOT_ARRIVE_SQ;
    }

    static float checkpointLookBias(RaiderGroupRole role) {
        return switch (role == null ? RaiderGroupRole.SOLO : role) {
            case FLANK_LEFT -> -55.0F;
            case FLANK_RIGHT -> 55.0F;
            case BREACH -> 0.0F;
            case SUPPRESS -> 0.0F;
            default -> 0.0F;
        };
    }

    private static Location offsetSlot(
            RaiderGroupRole role,
            Location center,
            float facingYaw,
            double lateral,
            double depth
    ) {
        if (center == null || center.getWorld() == null) {
            return null;
        }
        Vector forward = facingForward(facingYaw);
        Vector right = rightOf(forward);
        Vector offset = switch (role == null ? RaiderGroupRole.SOLO : role) {
            case FLANK_LEFT -> right.clone().multiply(-lateral);
            case FLANK_RIGHT -> right.clone().multiply(lateral);
            case BREACH -> forward.clone().multiply(depth);
            case SUPPRESS -> forward.clone().multiply(-depth * 0.35D);
            default -> right.clone().multiply(lateral * 0.65D);
        };
        Location slot = center.clone().add(offset);
        slot.setY(center.getY());
        return slot;
    }

    private static Vector facingForward(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Vector(-Math.sin(radians), 0.0D, Math.cos(radians));
    }

    private static Vector rightOf(Vector forward) {
        return new Vector(forward.getZ(), 0.0D, -forward.getX());
    }
}
