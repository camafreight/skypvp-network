package network.skypvp.paper.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.platform.PlatformTask;
import network.skypvp.paper.platform.ServerPlatform;
import network.skypvp.paper.repository.SocialGraphRepository;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Periodically caches each online player's party membership + per-member presence for the party sidebar so the
 * per-tick scoreboard render never performs a blocking DB read. Presence is resolved from: (1) network-wide open
 * sessions (cross-server), (2) local {@link Bukkit#getPlayer}, and (3) an optional "disconnected" predicate that flags a
 * member who disconnected mid-raid (their slot is held by a killable stand-in).
 */
public final class PartyScoreboardData {

    public static final PartyView EMPTY = new PartyView(false, "", 0, 0, List.of());

    public enum Presence {
        ONLINE,
        DISCONNECTED,
        OFFLINE
    }

    public record MemberView(UUID playerId, String name, boolean leader, Presence presence, long disconnectedSinceEpochMillis) {
    }

    public record PartyView(boolean inParty, String leaderName, int size, int onlineCount, List<MemberView> members) {
    }

    private final PaperCorePlugin core;
    private final ServerPlatform scheduler;
    private final Map<UUID, PartyView> cache = new ConcurrentHashMap<>();
    private volatile Predicate<UUID> disconnectedPredicate;
    private volatile Function<UUID, Long> disconnectedSinceSupplier;
    private PlatformTask task;

    public PartyScoreboardData(PaperCorePlugin core) {
        this.core = core;
        this.scheduler = core.platformScheduler();
    }

    /** Marks which member ids should render as disconnected (mid-raid disconnect). Null = never disconnected. */
    public void setDisconnectedPredicate(Predicate<UUID> disconnectedPredicate) {
        this.disconnectedPredicate = disconnectedPredicate;
    }

    /** Supplies epoch millis when a member disconnected mid-raid ({@code 0} if unknown). */
    public void setDisconnectedSinceSupplier(Function<UUID, Long> disconnectedSinceSupplier) {
        this.disconnectedSinceSupplier = disconnectedSinceSupplier;
    }

    public void start() {
        if (this.task == null) {
            this.task = this.scheduler.runGlobalTimer(this::refreshAll, 20L, 20L);
        }
    }

    public void stop() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
        this.cache.clear();
    }

    public PartyView get(UUID playerId) {
        return this.cache.getOrDefault(playerId, EMPTY);
    }

    private void refreshAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            this.scheduler.runAsync(() -> this.refreshPlayer(id));
        }
        this.cache.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    private void refreshPlayer(UUID viewerId) {
        SocialGraphRepository social = this.core.socialGraphRepository();
        if (social == null) {
            this.cache.put(viewerId, EMPTY);
            return;
        }
        try {
            Optional<SocialGraphRepository.PartySnapshot> snapshotOptional = social.partyForMember(viewerId).join();
            if (snapshotOptional.isEmpty()) {
                this.cache.put(viewerId, EMPTY);
                return;
            }
            SocialGraphRepository.PartySnapshot snapshot = snapshotOptional.get();
            List<UUID> memberIds = new ArrayList<>();
            for (SocialGraphRepository.PartyMember member : snapshot.members()) {
                memberIds.add(member.playerId());
            }
            Set<UUID> onlineSet = social.onlineMembers(memberIds).join();
            Predicate<UUID> disconnected = this.disconnectedPredicate;
            Function<UUID, Long> disconnectedSince = this.disconnectedSinceSupplier;

            String leaderName = "";
            int onlineCount = 0;
            List<MemberView> views = new ArrayList<>();
            for (SocialGraphRepository.PartyMember member : snapshot.members()) {
                UUID id = member.playerId();
                boolean isDisconnected = disconnected != null && disconnected.test(id);
                boolean isOnline = onlineSet.contains(id) || Bukkit.getPlayer(id) != null;
                Presence presence = isDisconnected ? Presence.DISCONNECTED : (isOnline ? Presence.ONLINE : Presence.OFFLINE);
                if (presence == Presence.ONLINE) {
                    onlineCount++;
                }
                if (member.leader()) {
                    leaderName = member.username();
                }
                long disconnectedSinceEpochMillis = 0L;
                if (isDisconnected && disconnectedSince != null) {
                    Long resolved = disconnectedSince.apply(id);
                    if (resolved != null && resolved > 0L) {
                        disconnectedSinceEpochMillis = resolved;
                    }
                }
                views.add(new MemberView(id, member.username(), member.leader(), presence, disconnectedSinceEpochMillis));
            }
            views.sort(Comparator
                    .comparing(MemberView::leader).reversed()
                    .thenComparing(view -> view.presence().ordinal())
                    .thenComparing(view -> view.name() == null ? "" : view.name().toLowerCase()));
            this.cache.put(viewerId, new PartyView(true, leaderName, snapshot.members().size(), onlineCount, views));
        } catch (RuntimeException exception) {
            this.cache.put(viewerId, EMPTY);
        }
    }
}
