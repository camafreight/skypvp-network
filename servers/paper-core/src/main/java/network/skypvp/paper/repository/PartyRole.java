package network.skypvp.paper.repository;

import java.util.Locale;

public enum PartyRole {
    LEADER,
    CO_LEADER,
    TRUSTED,
    MEMBER;

    public PartyRole cycleRank() {
        return switch (this) {
            case MEMBER -> TRUSTED;
            case TRUSTED -> CO_LEADER;
            case CO_LEADER, LEADER -> MEMBER;
        };
    }

    public boolean canInvite() {
        return this == LEADER || this == CO_LEADER || this == TRUSTED;
    }

    public boolean canStartBreach() {
        return this == LEADER || this == CO_LEADER;
    }

    public String displayName() {
        return switch (this) {
            case LEADER -> "Leader";
            case CO_LEADER -> "Co-Leader";
            case TRUSTED -> "Trusted";
            case MEMBER -> "Member";
        };
    }

    public static PartyRole fromDatabase(String raw, boolean leader) {
        if (leader) {
            return LEADER;
        }
        if (raw == null || raw.isBlank()) {
            return MEMBER;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "CO_LEADER", "CO-LEADER", "COLEADER" -> CO_LEADER;
            case "TRUSTED" -> TRUSTED;
            case "LEADER" -> LEADER;
            default -> MEMBER;
        };
    }

    public String databaseValue() {
        return switch (this) {
            case LEADER -> "LEADER";
            case CO_LEADER -> "CO_LEADER";
            case TRUSTED -> "TRUSTED";
            case MEMBER -> "MEMBER";
        };
    }
}
