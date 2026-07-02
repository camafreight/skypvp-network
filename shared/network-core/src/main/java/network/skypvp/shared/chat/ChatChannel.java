package network.skypvp.shared.chat;

import java.util.Locale;

public enum ChatChannel {
    ALL,
    PARTY,
    PRIVATE,
    STAFF;

    public static ChatChannel fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return ALL;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return ALL;
        }
    }

    public String permission() {
        return switch (this) {
            case ALL -> ChatPermissions.CHANNEL_ALL;
            case PARTY -> ChatPermissions.CHANNEL_PARTY;
            case PRIVATE -> ChatPermissions.CHANNEL_PRIVATE;
            case STAFF -> ChatPermissions.CHANNEL_STAFF;
        };
    }

    public String displayName() {
        return switch (this) {
            case ALL -> "Global";
            case PARTY -> "Party";
            case PRIVATE -> "Private";
            case STAFF -> "Staff";
        };
    }
}
