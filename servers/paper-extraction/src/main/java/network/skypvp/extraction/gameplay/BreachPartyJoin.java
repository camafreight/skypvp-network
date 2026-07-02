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

    public record PartyContext(UUID partyId, UUID leaderId, List<Player> members) {
        public boolean hasParty() {
            return partyId != null;
        }
    }

    public static PartyContext solo(Player player) {
        Objects.requireNonNull(player, "player");
        return new PartyContext(null, player.getUniqueId(), List.of(player));
    }

    public static PartyContext fromSnapshot(SocialGraphRepository.PartySnapshot snapshot, Player initiator) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(initiator, "initiator");
        List<Player> members = new ArrayList<>();
        for (SocialGraphRepository.PartyMember member : snapshot.members()) {
            Player online = Bukkit.getPlayer(member.playerId());
            if (online != null && online.isOnline()) {
                members.add(online);
            }
        }
        if (members.stream().noneMatch(player -> player.getUniqueId().equals(initiator.getUniqueId()))) {
            members.add(0, initiator);
        }
        return new PartyContext(snapshot.partyId(), snapshot.leaderId(), List.copyOf(members));
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
}
