package network.skypvp.shared;

import java.util.List;
import java.util.UUID;

/**
 * Published to {@link NetworkChannels#PARTY_CHAT} when a player sends a party chat message.
 */
public record PartyChatEvent(
        String originServerId,
        String senderUuid,
        String senderName,
        String partyId,
        String plainMessage,
        List<String> memberUuids,
        String senderLocale
) {
    public PartyChatEvent {
        memberUuids = memberUuids == null ? List.of() : List.copyOf(memberUuids);
        senderLocale = senderLocale == null || senderLocale.isBlank()
                ? network.skypvp.shared.chat.ClientLocaleUtil.defaultMinecraftLocale()
                : senderLocale;
    }

    public UUID senderUuidAsUuid() {
        try {
            return UUID.fromString(senderUuid);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
