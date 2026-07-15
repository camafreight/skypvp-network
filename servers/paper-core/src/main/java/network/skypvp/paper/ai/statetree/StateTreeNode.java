package network.skypvp.paper.ai.statetree;

/**
 * One state in a hierarchical state tree (Unreal-style enter/tick/exit lifecycle).
 *
 * @param <S> state identifier enum
 * @param <C> shared context / blackboard
 */
public interface StateTreeNode<S extends Enum<S>, C extends StateTreeContext> {

    void enter(C context);

    /** @return next state, or {@code null} to remain in the current state */
    S tick(C context);

    void exit(C context);
}
