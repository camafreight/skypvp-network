package network.skypvp.shared;

import java.util.Locale;
import java.util.Set;

public final class SocialChatRules {
    public static final String PERMISSION_CHAT_TOGGLE_BYPASS = "skypvp.chat.toggle.bypass";

    private static final Set<String> STAFF_RANK_KEYS = Set.of("staff", "admin", "owner");

    private SocialChatRules() {
    }

    public static boolean isStaffRank(String rankKey) {
        if (rankKey == null || rankKey.isBlank()) {
            return false;
        }
        return STAFF_RANK_KEYS.contains(rankKey.trim().toLowerCase(Locale.ROOT));
    }

    public static boolean isAnnouncementMessage(String plainMessage) {
        if (plainMessage == null || plainMessage.isBlank()) {
            return false;
        }
        String trimmed = plainMessage.trim();
        return trimmed.startsWith("[") && trimmed.contains("]");
    }
}
