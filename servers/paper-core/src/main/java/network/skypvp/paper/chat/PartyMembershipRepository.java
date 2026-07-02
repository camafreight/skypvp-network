package network.skypvp.paper.chat;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import network.skypvp.paper.database.AsyncDbExecutor;

public final class PartyMembershipRepository {
    public record PartySnapshot(UUID partyId, List<UUID> memberIds) {
    }

    private final AsyncDbExecutor asyncDbExecutor;

    public PartyMembershipRepository(AsyncDbExecutor asyncDbExecutor) {
        this.asyncDbExecutor = Objects.requireNonNull(asyncDbExecutor, "asyncDbExecutor");
    }

    public CompletableFuture<Optional<PartySnapshot>> findParty(UUID playerId) {
        if (playerId == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return this.asyncDbExecutor.supply("partyMembership.find", connection -> {
            String findPartySql = "SELECT party_id FROM network_party_members WHERE member_id = ? LIMIT 1";
            UUID partyId;
            try (PreparedStatement ps = connection.prepareStatement(findPartySql)) {
                ps.setObject(1, playerId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    partyId = rs.getObject("party_id", UUID.class);
                }
            }
            if (partyId == null) {
                return Optional.empty();
            }
            List<UUID> members = new ArrayList<>();
            String membersSql = "SELECT member_id FROM network_party_members WHERE party_id = ?";
            try (PreparedStatement ps = connection.prepareStatement(membersSql)) {
                ps.setObject(1, partyId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID memberId = rs.getObject("member_id", UUID.class);
                        if (memberId != null) {
                            members.add(memberId);
                        }
                    }
                }
            }
            return Optional.of(new PartySnapshot(partyId, members));
        });
    }
}
