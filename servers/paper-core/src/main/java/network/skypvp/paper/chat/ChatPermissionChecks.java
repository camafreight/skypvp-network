package network.skypvp.paper.chat;

import network.skypvp.shared.chat.ChatPermissions;
import org.bukkit.permissions.Permissible;

public final class ChatPermissionChecks {
    public enum HelpTier {
        PLAYER,
        STAFF,
        ADMIN,
        OWNER
    }

    private ChatPermissionChecks() {
    }

    public static boolean hasWildcard(Permissible permissible) {
        return permissible != null && permissible.hasPermission("*");
    }

    public static boolean hasAdmin(Permissible permissible) {
        return permissible != null && permissible.hasPermission(ChatPermissions.ADMIN);
    }

    /** True when the player should be treated as having every chat permission (LP * or skypvp.chat.admin). */
    public static boolean hasFullChatAccess(Permissible permissible) {
        return hasWildcard(permissible) || hasAdmin(permissible);
    }

    public static boolean has(Permissible permissible, String permission) {
        if (permissible == null) {
            return false;
        }
        if (hasFullChatAccess(permissible)) {
            return true;
        }
        return permissible.hasPermission(permission);
    }

    public static HelpTier helpTier(Permissible permissible) {
        if (hasFullChatAccess(permissible)) {
            return HelpTier.OWNER;
        }
        if (has(permissible, ChatPermissions.FORMATS_MANAGE)
                || has(permissible, ChatPermissions.PRIVATE_MANAGE)
                || has(permissible, ChatPermissions.PARTY_MANAGE)
                || has(permissible, ChatPermissions.STAFF_MANAGE)
                || has(permissible, ChatPermissions.HELP_ADMIN)) {
            return HelpTier.ADMIN;
        }
        if (has(permissible, ChatPermissions.CLEAR) || has(permissible, ChatPermissions.CHANNEL_STAFF)) {
            return HelpTier.STAFF;
        }
        return HelpTier.PLAYER;
    }

    public static boolean canUseTabComplete(Permissible permissible) {
        if (permissible == null) {
            return false;
        }
        if (hasFullChatAccess(permissible)) {
            return true;
        }
        return permissible.hasPermission(ChatPermissions.TAB_COMPLETE)
                || permissible.hasPermission(ChatPermissions.FORMATS_MANAGE)
                || permissible.hasPermission(ChatPermissions.PRIVATE_MANAGE)
                || permissible.hasPermission(ChatPermissions.PARTY_MANAGE)
                || permissible.hasPermission(ChatPermissions.STAFF_MANAGE)
                || permissible.hasPermission(ChatPermissions.HELP_ADMIN)
                || permissible.hasPermission(ChatPermissions.CLEAR);
    }

    public static boolean hasFormatPermission(Permissible permissible, String formatId) {
        if (permissible == null || formatId == null || formatId.isBlank()) {
            return false;
        }
        if (hasFullChatAccess(permissible)) {
            return true;
        }
        if (permissible.hasPermission(ChatPermissions.FORMAT_ALL)) {
            return true;
        }
        return permissible.hasPermission(ChatPermissions.formatPermission(formatId));
    }

    /** Empty when the player already has full access; otherwise a short permission hint. */
    public static String permissionHint(Permissible permissible, String permission) {
        if (hasFullChatAccess(permissible) || has(permissible, permission)) {
            return "";
        }
        return " <dark_gray>(needs <white>" + permission + "<dark_gray>)";
    }
}
