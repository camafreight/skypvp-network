package network.skypvp.paper.ai.navigation;

import network.skypvp.paper.ai.statetree.StateTreeContext;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;

/** Blackboard slice required for shared pathfinder helpers. */
public interface NavigatingMobContext extends StateTreeContext {

    LivingEntity agentEntity();

    Mob agentMob();

    NavigationTracker navigation();

    /**
     * Monotonic clock for navigation timers. Defaults to world full time; agents ticked by
     * their own service should override with a per-agent counter — on Folia, world time is
     * region-local and jumps across region splits/merges, breaking duration comparisons.
     */
    default long navClock() {
        return agentEntity().getWorld().getFullTime();
    }
}
