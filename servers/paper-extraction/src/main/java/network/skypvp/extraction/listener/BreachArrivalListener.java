package network.skypvp.extraction.listener;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.engine.BreachInstance;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Bridges cross-server party arrivals into {@link network.skypvp.extraction.gameplay.BreachArrivalCoordinator}.
 *
 * <p>When a party member who was still in transit finally connects to this extraction server, the coordinator holds a
 * reserved instance slot for them; this listener claims it so they are teleported straight into their party's breach
 * instead of being stranded in the lobby. Runs at LOWEST so the claim happens before other join handlers react.</p>
 */
public final class BreachArrivalListener implements Listener {

    private final BreachEngine engine;

    public BreachArrivalListener(BreachEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        engine.arrivalCoordinator().onPlayerArrive(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        Optional<UUID> pendingParty = engine.arrivalCoordinator().pendingPartyId(playerId);
        Optional<BreachInstance> tracked = engine.instanceFor(event.getPlayer());

        if (pendingParty.isPresent()) {
            // Abort the whole party's in-transit reservations — reconnect must not force-admit them mid-cancel.
            engine.cancelPartyQueueDeploy(pendingParty.get(), null, List.of(playerId));
            return;
        }

        if (tracked.isPresent() && tracked.get().isPendingJoin(playerId)) {
            BreachInstance instance = tracked.get();
            UUID partyId = instance.partyIdFor(playerId);
            if (partyId != null) {
                engine.cancelPartyQueueDeploy(partyId, instance.instanceId(), List.copyOf(instance.partyMembers(partyId)));
            } else {
                engine.cancelPartyQueueDeploy(null, instance.instanceId(), List.of(playerId));
            }
            return;
        }

        // Solo / leftover held slot with no party metadata.
        engine.arrivalCoordinator().cancel(playerId);
    }
}
