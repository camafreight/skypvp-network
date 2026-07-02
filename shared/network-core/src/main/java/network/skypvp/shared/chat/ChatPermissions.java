package network.skypvp.shared.chat;

import java.util.Locale;

public final class ChatPermissions {
    public static final String USE = "skypvp.chat.use";
    public static final String ADMIN = "skypvp.chat.admin";
    public static final String CLEAR = "skypvp.chat.clear";
    public static final String FORMATS_MANAGE = "skypvp.chat.formats.manage";
    /** Grant in LuckPerms to apply every rank format without per-id nodes. */
    public static final String FORMAT_ALL = "skypvp.chat.format.*";
    public static final String PRIVATE_MANAGE = "skypvp.chat.private.manage";
    public static final String PARTY_MANAGE = "skypvp.chat.party.manage";
    public static final String STAFF_MANAGE = "skypvp.chat.staff.manage";
    public static final String MODERATION_BYPASS = "skypvp.chat.moderation.bypass";
    public static final String TAB_COMPLETE = "skypvp.chat.tab.complete";
    public static final String HELP_ADMIN = "skypvp.chat.help.admin";

    public static final String CHANNEL_ALL = "skypvp.chat.channel.all";
    public static final String CHANNEL_PARTY = "skypvp.chat.channel.party";
    public static final String CHANNEL_PRIVATE = "skypvp.chat.channel.private";
    public static final String CHANNEL_STAFF = "skypvp.chat.channel.staff";

    public static String formatPermission(String formatId) {
        return "skypvp.chat.format." + formatId.toLowerCase(Locale.ROOT);
    }

    private ChatPermissions() {
    }
}
