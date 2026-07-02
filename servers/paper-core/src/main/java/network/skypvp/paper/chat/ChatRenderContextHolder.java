package network.skypvp.paper.chat;

import java.util.function.Supplier;
import network.skypvp.shared.chat.ChatRenderContext;

public final class ChatRenderContextHolder {
    private static final ThreadLocal<ChatRenderContext> CURRENT = new ThreadLocal<>();

    private ChatRenderContextHolder() {
    }

    public static <T> T callWith(ChatRenderContext context, Supplier<T> action) {
        ChatRenderContext previous = CURRENT.get();
        CURRENT.set(context);
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

    public static ChatRenderContext current() {
        return CURRENT.get();
    }
}
