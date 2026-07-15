package network.skypvp.paper.ai.statetree;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Lambda-based node factory — the extension point for building new agent trees
 * (bosses, pets) without hand-rolling {@link StateTreeNode} classes.
 *
 * <p>Usage: define a state enum, a context implementing {@link StateClockContext},
 * then register nodes:
 * <pre>{@code
 * engine.register(MyState.IDLE, StateTreeNodes.of(
 *         ctx -> { ... enter ... },
 *         ctx -> { ... per-tick; return next state or null ... },
 *         ctx -> { ... exit ... }));
 * }</pre>
 *
 * <p>The factory stamps {@link StateClockContext#onStateEntered} before the enter hook
 * runs, so {@code tick - stateEnteredTick} duration guards are always correct. Note the
 * engine ignores self-transitions (returning the current state is a no-op); nodes that
 * want to restart their clock must reset it via the context instead.
 */
public final class StateTreeNodes {

    private StateTreeNodes() {
    }

    public static <S extends Enum<S>, C extends StateClockContext> StateTreeNode<S, C> of(
            Consumer<C> enter,
            Function<C, S> tick,
            Consumer<C> exit
    ) {
        Objects.requireNonNull(enter, "enter");
        Objects.requireNonNull(tick, "tick");
        Objects.requireNonNull(exit, "exit");
        return new StateTreeNode<>() {
            @Override
            public void enter(C context) {
                context.onStateEntered(context.currentWorldTick());
                enter.accept(context);
            }

            @Override
            public S tick(C context) {
                return tick.apply(context);
            }

            @Override
            public void exit(C context) {
                exit.accept(context);
            }
        };
    }
}
