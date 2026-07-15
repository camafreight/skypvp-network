package network.skypvp.extraction.gameplay;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import network.skypvp.paper.repository.SocialGraphRepository;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class BreachPartyJoin {

    private BreachPartyJoin() {
    }

    /**
     * @param members           party members online on this JVM at snapshot time (a convenience view)
     * @param expectedMemberIds every party member id from the DB snapshot (regardless of where they are), so the
     *                          engine can wait for members still in transit between servers to arrive locally
     */
    public record PartyContext(UUID partyId, UUID leaderId, List<Player> members, List<UUID> expectedMemberIds) {
        public boolean hasParty() {
            return partyId != null;
        }
    }

    public static PartyContext solo(Player player) {
        Objects.requireNonNull(player, "player");
        return new PartyContext(null, player.getUniqueId(), List.of(player), List.of(player.getUniqueId()));
    }

    public static PartyContext fromSnapshot(SocialGraphRepository.PartySnapshot snapshot, Player initiator) {
        return fromSnapshot(snapshot, initiator, null);
    }

    /**
     * Builds a deploy context, optionally restricted to a leader-selected {@code roster} (the breach squad). The
     * initiator is always kept regardless of the roster. When {@code roster} is null every party member is eligible
     * (used for parties small enough that no picking is needed).
     */
    public static PartyContext fromSnapshot(SocialGraphRepository.PartySnapshot snapshot, Player initiator, java.util.Set<UUID> roster) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(initiator, "initiator");
        List<Player> members = new ArrayList<>();
        List<UUID> expected = new ArrayList<>();
        for (SocialGraphRepository.PartyMember member : snapshot.members()) {
            UUID memberId = member.playerId();
            if (roster != null && !roster.contains(memberId) && !memberId.equals(initiator.getUniqueId())) {
                continue;
            }
            expected.add(memberId);
            Player online = Bukkit.getPlayer(memberId);
            if (online != null && online.isOnline()) {
                members.add(online);
            }
        }
        if (members.stream().noneMatch(player -> player.getUniqueId().equals(initiator.getUniqueId()))) {
            members.add(0, initiator);
        }
        if (!expected.contains(initiator.getUniqueId())) {
            expected.add(initiator.getUniqueId());
        }
        return new PartyContext(snapshot.partyId(), snapshot.leaderId(), List.copyOf(members), List.copyOf(expected));
    }

    public static boolean initiatorCanStartBreach(SocialGraphRepository.PartySnapshot snapshot, UUID initiatorId) {
        if (snapshot == null || initiatorId == null) {
            return true;
        }
        if (snapshot.leaderId().equals(initiatorId)) {
            return true;
        }
        return snapshot.members().stream()
                .anyMatch(member -> member.playerId().equals(initiatorId) && member.role().canStartBreach());
    }

    /**
     * Whether the given member may cancel the whole party's pending breach join. Only trusted ranks (leader,
     * co-leader, trusted) qualify — a plain member can only cancel their own entry, not abort it for everyone.
     */
    public static boolean initiatorCanCancelBreach(SocialGraphRepository.PartySnapshot snapshot, UUID initiatorId) {
        if (snapshot == null || initiatorId == null) {
            return true;
        }
        if (snapshot.leaderId().equals(initiatorId)) {
            return true;
        }
        return snapshot.members().stream()
                .anyMatch(member -> member.playerId().equals(initiatorId) && member.role().canCancelBreach());
    }
}
