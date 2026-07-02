package network.skypvp.shared;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable snapshot of a single punishment record as stored in
 * {@code network_punishments}.
 */
public record PunishmentRecord(
        long id,
        UUID playerUuid,
        String playerName,
        PunishmentType type,
        String reason,
        String issuedBy,
        Instant issuedAt,
        Instant expiresAt,   // null = permanent
        boolean pardoned,
        String pardonedBy,
        Instant pardonedAt
) {
    public enum PunishmentType { BAN, MUTE, WARN }

    /** Returns true if this punishment is currently active (not pardoned, not expired). */
    public boolean isActive() {
        if (pardoned) return false;
        if (expiresAt == null) return true;
        return Instant.now().isBefore(expiresAt);
    }

    public boolean isPermanent() {
        return expiresAt == null;
    }
}
