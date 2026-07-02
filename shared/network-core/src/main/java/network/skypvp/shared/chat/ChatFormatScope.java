package network.skypvp.shared.chat;

public enum ChatFormatScope {
    RANK,
    PRIVATE,
    PARTY,
    STAFF;

    public static ChatFormatScope fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return RANK;
        }
        return valueOf(raw.trim().toUpperCase());
    }
}
