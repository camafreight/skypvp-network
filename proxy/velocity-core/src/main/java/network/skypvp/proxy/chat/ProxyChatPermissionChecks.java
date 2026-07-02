package network.skypvp.proxy.chat;

import com.velocitypowered.api.proxy.Player;
import network.skypvp.shared.chat.ChatPermissions;

public final class ProxyChatPermissionChecks {
    private ProxyChatPermissionChecks() {
    }

    public static boolean hasFormatPermission(Player player, String formatId) {
        if (player == null || formatId == null || formatId.isBlank()) {
            return false;
        }
        if (hasFullChatAccess(player)) {
            return true;
        }
        if (player.hasPermission(ChatPermissions.FORMAT_ALL)) {
            return true;
        }
        return player.hasPermission(ChatPermissions.formatPermission(formatId));
    }

    private static boolean hasFullChatAccess(Player player) {
        return player.hasPermission("*") || player.hasPermission(ChatPermissions.ADMIN);
    }
}
