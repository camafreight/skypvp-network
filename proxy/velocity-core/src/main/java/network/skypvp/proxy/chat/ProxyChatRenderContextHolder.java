package network.skypvp.proxy.chat;

import com.velocitypowered.api.proxy.Player;
import java.util.function.Supplier;
import network.skypvp.shared.chat.ChatRenderContext;

/** Thread-local render context for proxy-side chat formatting. */
final class ProxyChatRenderContextHolder {
    private static final ThreadLocal<RenderState> CURRENT = new ThreadLocal<>();

    private ProxyChatRenderContextHolder() {
    }

    record RenderState(
            ChatRenderContext context,
            Player sender,
            String rankKeyFallback,
            ProxyChatFormatService formats
    ) {
    }

    static <T> T callWith(RenderState state, Supplier<T> action) {
        RenderState previous = CURRENT.get();
        CURRENT.set(state);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    static RenderState current() {
        return CURRENT.get();
    }
}
