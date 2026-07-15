package network.skypvp.extraction.gameplay;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import network.skypvp.extraction.engine.BreachInstance;
import network.skypvp.paper.platform.ServerPlatform;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Cross-server rendezvous for party breaches.
 *
 * <p>When a leader starts a breach, only the party members already online on this extraction JVM can be teleported in
 * immediately. The rest are typically still in transit — the proxy is moving them onto this server a beat later. Before
 * this coordinator existed those late members were silently dropped from the raid (they arrived to find their party
 * gone). This holds a reserved instance slot for each expected-but-not-yet-local member for a short window and admits
 * them the moment they connect, so an entire online party lands in the <em>same</em> breach instance regardless of the
 * staggered cross-server arrival order.</p>
 *
 * <p>Reservations are self-healing: any that are not claimed before {@link #arrivalWindowMillis} elapses (e.g. the
 * member was actually offline, or their transfer was cancelled) are released back to the instance during {@link #sweep()}.</p>
 */
public final class BreachArrivalCoordinator {

    /** How long a slot is held open for a party member who is expected to arrive from another server. */
    private static final long ARRIVAL_WINDOW_MILLIS = 45_000L;

    private final ServerPlatform scheduler;
    private final Logger logger;
    private final Map<UUID, PendingArrival> pending = new ConcurrentHashMap<>();

    public BreachArrivalCoordinator(ServerPlatform scheduler, Logger logger) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public long arrivalWindowMillis() {
        return ARRIVAL_WINDOW_MILLIS;
    }

    /**
     * Holds an instance slot for each expected member who is not currently online on this JVM. Members already local
     * are ignored (they are admitted directly by the caller). A slot is only held when the instance can still fit it;
     * once the instance is full further expected members are left to join normally if room frees up.
     *
     * @param instance   the instance the party is being seated into
     * @param partyId    the party id (used so late arrivals are grouped/friendly-fire-protected correctly)
     * @param memberIds  every expected party member id (local members are filtered out here)
     */
    public void expectArrivals(BreachInstance instance, UUID partyId, Collection<UUID> memberIds) {
        expectArrivals(instance, partyId, memberIds, true);
    }

    /**
     * Same as {@link #expectArrivals(BreachInstance, UUID, Collection)} but when {@code reserveSlots} is false the
     * caller already reserved capacity (e.g. proxy queue deploy batch reserve) and this only tracks pending arrivals.
     */
    public void expectArrivals(BreachInstance instance, UUID partyId, Collection<UUID> memberIds, boolean reserveSlots) {
        if (instance == null || memberIds == null || memberIds.isEmpty()) {
            return;
        }
        for (UUID memberId : memberIds) {
            if (memberId == null) {
                continue;
            }
            Player local = Bukkit.getPlayer(memberId);
            if (local != null && local.isOnline()) {
                continue;
            }
            if (pending.containsKey(memberId)) {
                continue;
            }
            if (reserveSlots && !instance.reserveSlots(1)) {
                // Instance is full — stop holding slots for further in-transit members.
                break;
            }
            pending.put(memberId, new PendingArrival(instance, partyId, System.currentTimeMillis() + ARRIVAL_WINDOW_MILLIS));
            logger.fine("[Breach] Holding a slot in " + instance.instanceId() + " for in-transit party member " + memberId);
        }
    }

    /** Cancels every pending arrival for {@code partyId} (and releases held reservations). */
    public void cancelParty(UUID partyId) {
        if (partyId == null || pending.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, PendingArrival>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PendingArrival> entry = it.next();
            PendingArrival arrival = entry.getValue();
            if (arrival.partyId() == null || !partyId.equals(arrival.partyId())) {
                continue;
            }
            it.remove();
            arrival.instance().releaseReservation(1);
            logger.fine("[Breach] Cancelled held slot in " + arrival.instance().instanceId()
                    + " for party " + partyId + " member " + entry.getKey());
        }
    }

    /** Cancels pending arrivals for the given members (and releases held reservations). */
    public void cancelMembers(Collection<UUID> memberIds) {
        if (memberIds == null || memberIds.isEmpty()) {
            return;
        }
        for (UUID memberId : memberIds) {
            cancel(memberId);
        }
    }

    /**
     * Called when any player connects to this extraction server. If they have a slot held from a party breach start,
     * they are teleported straight into that instance (consuming the reservation).
     *
     * @return true if the player was claimed and admission was scheduled
     */
    public boolean onPlayerArrive(Player player) {
        if (player == null) {
            return false;
        }
        PendingArrival arrival = pending.remove(player.getUniqueId());
        if (arrival == null) {
            return false;
        }
        BreachInstance instance = arrival.instance();
        if (System.currentTimeMillis() > arrival.deadlineMillis()) {
            instance.releaseReservation(1);
            return false;
        }
        UUID memberId = player.getUniqueId();
        UUID partyId = arrival.partyId();
        scheduler.runOnPlayer(player, () -> {
            Player online = Bukkit.getPlayer(memberId);
            if (online == null || !online.isOnline()) {
                instance.releaseReservation(1);
                return;
            }
            if (!instance.joinReserved(online, partyId)) {
                logger.warning("[Breach] Late-arrival joinReserved() failed for " + online.getName()
                        + " (instance " + instance.instanceId() + ", state=" + instance.state() + ")");
            }
        });
        return true;
    }

    /** Releases any reservations whose arrival window has elapsed. Called from the engine's 1s tick. */
    public void sweep() {
        if (pending.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, PendingArrival>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PendingArrival> entry = it.next();
            PendingArrival arrival = entry.getValue();
            if (now <= arrival.deadlineMillis()) {
                continue;
            }
            it.remove();
            arrival.instance().releaseReservation(1);
            logger.fine("[Breach] Released held slot in " + arrival.instance().instanceId()
                    + " — party member " + entry.getKey() + " never arrived.");
        }
    }

    /** Returns the party id held for a pending arrival, if any. */
    public Optional<UUID> pendingPartyId(UUID memberId) {
        if (memberId == null) {
            return Optional.empty();
        }
        PendingArrival arrival = pending.get(memberId);
        return arrival == null || arrival.partyId() == null ? Optional.empty() : Optional.of(arrival.partyId());
    }

    /** Drops a held slot for a member (e.g. they disconnected from the network before arriving). */
    public void cancel(UUID memberId) {
        if (memberId == null) {
            return;
        }
        PendingArrival arrival = pending.remove(memberId);
        if (arrival != null) {
            arrival.instance().releaseReservation(1);
        }
    }

    public void clear() {
        for (PendingArrival arrival : pending.values()) {
            arrival.instance().releaseReservation(1);
        }
        pending.clear();
    }

    private record PendingArrival(BreachInstance instance, UUID partyId, long deadlineMillis) {
    }
}
