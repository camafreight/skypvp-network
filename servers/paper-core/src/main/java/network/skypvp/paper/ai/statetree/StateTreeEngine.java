package network.skypvp.paper.ai.statetree;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Minimal state-tree runtime: register nodes, tick transitions with enter/exit hooks. */
public final class StateTreeEngine<S extends Enum<S>, C extends StateTreeContext> {

    private final Class<S> stateType;
    private final Map<S, StateTreeNode<S, C>> nodes;
    private S current;

    public StateTreeEngine(Class<S> stateType, S initialState) {
        this.stateType = Objects.requireNonNull(stateType, "stateType");
        this.current = Objects.requireNonNull(initialState, "initialState");
        this.nodes = new EnumMap<>(stateType);
    }

    public StateTreeEngine<S, C> register(S state, StateTreeNode<S, C> node) {
        nodes.put(state, node);
        return this;
    }

    public S currentState() {
        return current;
    }

    public void forceState(S next, C context) {
        if (next == null || next == current) {
            return;
        }
        StateTreeNode<S, C> leaving = nodes.get(current);
        if (leaving != null) {
            leaving.exit(context);
        }
        current = next;
        StateTreeNode<S, C> entering = nodes.get(current);
        if (entering != null) {
            entering.enter(context);
        }
    }

    public void exitCurrent(C context) {
        StateTreeNode<S, C> leaving = nodes.get(current);
        if (leaving != null) {
            leaving.exit(context);
        }
    }

    /**
     * A tick result equal to the current state is a no-op: no exit/enter runs and node
     * timers keep their original values. Nodes that want to restart their own state must
     * reset their context timers directly instead of returning the current state.
     */
    public void tick(C context) {
        StateTreeNode<S, C> node = nodes.get(current);
        if (node == null) {
            return;
        }
        S next = node.tick(context);
        if (next != null && next != current) {
            forceState(next, context);
        }
    }

    public void reset(S initialState, C context) {
        forceState(initialState, context);
    }

    /** Runs the initial state's enter hook after all nodes are registered. */
    public void start(C context) {
        StateTreeNode<S, C> node = nodes.get(current);
        if (node != null) {
            node.enter(context);
        }
    }
}
