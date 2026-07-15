package network.skypvp.paper.ai.statetree;

/**
 * Blackboard with a per-state clock. {@link StateTreeNodes} stamps the clock on every
 * state entry so duration-based transitions (timeouts, watchdogs) work uniformly for
 * any agent archetype built on the engine — gunners, bosses, pets.
 */
public interface StateClockContext extends StateTreeContext {

    /** Current world tick for this agent. */
    long currentWorldTick();

    /** Called by the node factory when a state is entered; store for duration checks. */
    void onStateEntered(long tick);
}
