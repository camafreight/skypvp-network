package network.skypvp.paper.ai.navigation;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;

/** Applies manual upward movement when vanilla pathfinding stalls on ladders. */
public final class MobClimbSupport {

    private static final double CLIMB_VELOCITY = 0.22D;

    private MobClimbSupport() {
    }

    /**
     * Boosts a mob upward only while it is physically inside a climbable column.
     * Approaching ladders is the pathfinder's / {@link MobLadderSupport}'s job and stair
     * ascent is {@link MobStairSupport}'s, so this never applies lift near plain walls.
     */
    public static void assistClimb(NavigatingMobContext ctx, Location goal) {
        if (ctx == null || goal == null) {
            return;
        }
        LivingEntity entity = ctx.agentEntity();
        if (entity == null || !entity.isValid() || entity.isDead()) {
            return;
        }
        if (goal.getY() <= entity.getLocation().getY() + 1.1D) {
            return;
        }
        Block current = entity.getLocation().getBlock();
        if (!MobTerrainSupport.isClimbableMaterial(current.getType())
                && !MobTerrainSupport.isClimbableMaterial(current.getRelative(0, 1, 0).getType())) {
            return;
        }
        Vector velocity = entity.getVelocity();
        velocity.setY(Math.max(velocity.getY(), CLIMB_VELOCITY));
        entity.setVelocity(velocity);
    }
}
