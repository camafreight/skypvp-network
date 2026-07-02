package network.skypvp.paper.library.packet;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import network.skypvp.paper.platform.ServerPlatform;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Server-side click detection for client-only packet entities (fake players / NPCs sent via PacketEvents).
 *
 * <p>Because those entities do not exist on the server, Bukkit's {@code PlayerInteractEntityEvent} never fires for
 * them — historically we spawned a separate {@code Interaction} entity to catch clicks, which left a second visible
 * hitbox next to the model. This service instead listens for the inbound {@code Interact Entity} packet and routes it
 * to a handler registered against the fake entity's id, so the model itself is the only clickable target.
 */
public final class PacketEntityInteractionService {

    private static final long INTERACT_DEBOUNCE_MILLIS = 200L;

    private final Plugin plugin;
    private final ServerPlatform scheduler;
    private final Map<Integer, Consumer<Player>> handlers = new ConcurrentHashMap<>();
    private final Map<String, Long> lastInteractMillis = new ConcurrentHashMap<>();
    private PacketListenerAbstract listener;

    public PacketEntityInteractionService(Plugin plugin, ServerPlatform scheduler) {
        this.plugin = plugin;
        this.scheduler = scheduler;
    }

    public void start() {
        if (this.listener != null || !PacketEventsBridge.isAvailable()) {
            return;
        }
        this.listener = new InteractListener();
        PacketEvents.getAPI().getEventManager().registerListener(this.listener);
    }

    public void shutdown() {
        if (this.listener != null) {
            try {
                PacketEvents.getAPI().getEventManager().unregisterListener(this.listener);
            } catch (RuntimeException ignored) {
                // PacketEvents may already be torn down during shutdown.
            }
            this.listener = null;
        }
        this.handlers.clear();
        this.lastInteractMillis.clear();
    }

    /** Registers a click handler for a fake entity id. The handler runs on the clicking player's region thread. */
    public void register(int entityId, Consumer<Player> onInteract) {
        if (onInteract != null) {
            this.handlers.put(entityId, onInteract);
        }
    }

    public void unregister(int entityId) {
        this.handlers.remove(entityId);
    }

    private final class InteractListener extends PacketListenerAbstract {

        InteractListener() {
            super(PacketListenerPriority.NORMAL);
        }

        @Override
        public void onPacketReceive(PacketReceiveEvent event) {
            if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) {
                return;
            }
            WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
            // A right-click sends both INTERACT and INTERACT_AT for the same entity; handle INTERACT (and ATTACK for
            // left-click) only so each click triggers the handler exactly once.
            if (packet.getAction() == WrapperPlayClientInteractEntity.InteractAction.INTERACT_AT) {
                return;
            }
            Consumer<Player> handler = PacketEntityInteractionService.this.handlers.get(packet.getEntityId());
            if (handler == null) {
                return;
            }
            UUID uuid = event.getUser() == null ? null : event.getUser().getUUID();
            if (uuid == null) {
                return;
            }
            String key = uuid + ":" + packet.getEntityId();
            long now = System.currentTimeMillis();
            Long last = PacketEntityInteractionService.this.lastInteractMillis.get(key);
            if (last != null && now - last < INTERACT_DEBOUNCE_MILLIS) {
                return;
            }
            PacketEntityInteractionService.this.lastInteractMillis.put(key, now);
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                return;
            }
            PacketEntityInteractionService.this.scheduler.runOnPlayer(player, () -> handler.accept(player));
        }
    }
}
