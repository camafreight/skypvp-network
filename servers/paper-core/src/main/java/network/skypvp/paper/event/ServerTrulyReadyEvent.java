package network.skypvp.paper.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired on the global scheduler when this backend is truly ready to accept routed players:
 * platform lifecycle complete, spawn chunks ready, decorations settled, and all readiness holds released.
 */
public final class ServerTrulyReadyEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String serverId;
    private final String status;

    public ServerTrulyReadyEvent(String serverId, String status) {
        this.serverId = serverId == null ? "" : serverId;
        this.status = status == null ? "" : status;
    }

    public String serverId() {
        return this.serverId;
    }

    public String status() {
        return this.status;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
