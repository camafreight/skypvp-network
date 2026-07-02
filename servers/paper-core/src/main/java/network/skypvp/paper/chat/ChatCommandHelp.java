package network.skypvp.paper.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.chat.ChatFormatService;
import network.skypvp.paper.service.PlayerSocialSettingsService;
import network.skypvp.shared.ServerTextUtil;
import network.skypvp.shared.chat.ChatChannel;
import network.skypvp.shared.chat.ChatFormatFlagKeys;
import network.skypvp.shared.chat.ChatFormatProfile;
import network.skypvp.shared.chat.ChatPermissions;
import org.bukkit.entity.Player;

public final class ChatCommandHelp {
    public static final String PREFIX = "<#55FFFF><bold>Chat</bold><dark_gray> » <reset>";

    private ChatCommandHelp() {
    }

    public static void sendOverview(Player player, PaperCorePlugin plugin, ChatFormatService formatService, PlayerSocialSettingsService settings) {
        ChatPermissionChecks.HelpTier tier = ChatPermissionChecks.helpTier(player);
        sendHeader(player, tier, false);
        sendStatus(player, settings);
        sendChannelCycle(player);
        blank(player);
        sendCommandsForTier(player, tier, false);
        if (tier.ordinal() >= ChatPermissionChecks.HelpTier.ADMIN.ordinal()) {
            sendQuickTips(player, tier);
        }
        footer(player, tier);
    }

    public static void sendFullHelp(Player player, PaperCorePlugin plugin, ChatFormatService formatService, PlayerSocialSettingsService settings) {
        ChatPermissionChecks.HelpTier tier = ChatPermissionChecks.helpTier(player);
        sendHeader(player, tier, true);
        sendStatus(player, settings);
        sendChannelCycle(player);
        blank(player);
        sendCommandsForTier(player, tier, true);

        if (ChatPermissionChecks.has(player, ChatPermissions.FORMATS_MANAGE)) {
            blank(player);
            sendFormatsReference(player);
        }
        if (ChatPermissionChecks.has(player, ChatPermissions.PRIVATE_MANAGE)
                || ChatPermissionChecks.has(player, ChatPermissions.PARTY_MANAGE)
                || ChatPermissionChecks.has(player, ChatPermissions.STAFF_MANAGE)) {
            blank(player);
            sendSystemFormatReference(player);
        }
        if (tier == ChatPermissionChecks.HelpTier.OWNER) {
            blank(player);
            sendOwnerReference(player, plugin);
        }
        footer(player, tier);
    }

    public static void sendFormatsMenu(Player player) {
        line(player, "<gold><bold>Rank formats");
        command(player, "/chat formats add <id> <flags...>", "create a new rank format");
        command(player, "/chat formats set <id> <flags...>", "merge flags into an existing format");
        command(player, "/chat formats remove <id>", "delete a rank format");
        command(player, "/chat formats list", "list saved rank formats");
        command(player, "/chat formats show <id>", "inspect one format");
        command(player, "/chat formats flags", "list all flag names and examples");
    }

    public static void sendFlagReference(Player player) {
        line(player, "<gold><bold>Format flags");
        for (ChatFormatFlagKeys.FlagDefinition flag : ChatFormatFlagKeys.definitions()) {
            line(player, "<yellow>" + flag.key() + "<gray> — " + flag.description());
            line(player, "<dark_gray>  e.g. " + flag.example());
        }
        line(player, "<dark_gray>Values may contain spaces. Tooltips: use <white><br><dark_gray> between lines.");
    }

    public static void sendStatus(Player player, PlayerSocialSettingsService settings) {
        ChatChannel current = settings == null
                ? ChatChannel.ALL
                : settings.activeChatChannel(player.getUniqueId());
        line(player, "<gray>Active channel: <white>" + current.displayName() + "<gray> (<white>" + current.name() + "<gray>)");
        if (settings != null) {
            line(player, "<gray>Chat toggle (see player chat): <white>" + (settings.isChatEnabled(player.getUniqueId()) ? "on" : "off"));
            line(player, "<gray>Profanity filter: <white>" + (settings.isProfanityFilterEnabled(player.getUniqueId()) ? "on" : "off"));
            line(player, "<gray>Auto translate: <white>" + (settings.isAutoTranslateEnabled(player.getUniqueId()) ? "on" : "off"));
        }
    }

    public static void sendChannelCycle(Player player) {
        List<ChatChannel> allowed = ChatChannelAccess.allowedChannels(player);
        if (allowed.size() <= 1) {
            line(player, "<dark_gray>You can only use the default chat toggle cycle channel.");
            return;
        }
        String cycle = allowed.stream()
                .map(ChatChannel::displayName)
                .collect(Collectors.joining("<gray> → <white>"));
        line(player, "<gray>Toggle cycle: <white>" + cycle);
    }

    public static String formatIdList(ChatFormatService formatService) {
        return formatService.rankFormats().stream()
                .map(ChatFormatProfile::id)
                .collect(Collectors.joining("<gray>, <white>"));
    }

    public static String[] visibleSubcommands(Player player) {
        List<String> commands = new ArrayList<>();
        commands.add("toggle");
        commands.add("status");
        commands.add("channels");
        if (ChatPermissionChecks.has(player, ChatPermissions.CLEAR)) {
            commands.add("clear");
        }
        if (ChatPermissionChecks.has(player, ChatPermissions.FORMATS_MANAGE)) {
            commands.addAll(List.of("formats", "format"));
        }
        if (ChatPermissionChecks.has(player, ChatPermissions.PRIVATE_MANAGE)
                || ChatPermissionChecks.has(player, ChatPermissions.PARTY_MANAGE)
                || ChatPermissionChecks.has(player, ChatPermissions.STAFF_MANAGE)) {
            commands.add("channel");
        }
        if (ChatPermissionChecks.has(player, ChatPermissions.PRIVATE_MANAGE)) {
            commands.add("private");
        }
        if (ChatPermissionChecks.has(player, ChatPermissions.PARTY_MANAGE)) {
            commands.add("party");
        }
        if (ChatPermissionChecks.has(player, ChatPermissions.STAFF_MANAGE)) {
            commands.add("staff");
        }
        commands.add("help");
        return commands.toArray(String[]::new);
    }

    private static void sendHeader(Player player, ChatPermissionChecks.HelpTier tier, boolean full) {
        String mode = full ? "Help" : "Commands";
        String badge = switch (tier) {
            case OWNER -> "<#FFD700><bold>Owner";
            case ADMIN -> "<#FFAA00><bold>Admin";
            case STAFF -> "<#55FF55><bold>Staff";
            case PLAYER -> "<#AAAAAA><bold>Player";
        };
        line(player, badge + " <reset>" + mode);
    }

    private static void sendCommandsForTier(Player player, ChatPermissionChecks.HelpTier tier, boolean verbose) {
        line(player, "<gold><bold>Your commands");
        command(player, "/chat toggle", "switch where your messages are sent");
        command(player, "/chat status", "show your current chat settings");
        command(player, "/chat channels", "list channels you can use");
        command(player, "/chat help", "detailed help for your access level");

        if (ChatPermissionChecks.has(player, ChatPermissions.CLEAR)) {
            command(player, "/chat clear", "clear chat for everyone on this server");
        }

        if (tier.ordinal() >= ChatPermissionChecks.HelpTier.STAFF.ordinal()) {
            if (ChatPermissionChecks.has(player, ChatPermissions.CHANNEL_STAFF)) {
                line(player, "<gray>Staff channel: toggle to <white>Staff<gray>, then chat normally.");
            }
        }

        if (ChatPermissionChecks.has(player, ChatPermissions.FORMATS_MANAGE)) {
            blank(player);
            line(player, "<gold><bold>Format management");
            if (verbose) {
                sendFormatsMenu(player);
            } else {
                command(player, "/chat formats …", "add, set, remove, list, show, flags");
            }
        }

        if (ChatPermissionChecks.has(player, ChatPermissions.PRIVATE_MANAGE)) {
            command(player, "/chat channel formatting private <flags...>", "network private message styling for /msg");
        }
        if (ChatPermissionChecks.has(player, ChatPermissions.PARTY_MANAGE)) {
            command(player, "/chat channel formatting party <flags...>", "party chat styling");
        }
        if (ChatPermissionChecks.has(player, ChatPermissions.STAFF_MANAGE)) {
            command(player, "/chat channel formatting staff <flags...>", "staff chat styling");
        }
    }

    private static void sendQuickTips(Player player, ChatPermissionChecks.HelpTier tier) {
        blank(player);
        line(player, "<gold><bold>Tips");
        line(player, "<dark_gray>• Tab-complete works on flags after the format id.");
        line(player, "<dark_gray>• Use <white>/chat formats flags<dark_gray> for every flag name and example.");
        if (tier == ChatPermissionChecks.HelpTier.OWNER) {
            line(player, "<dark_gray>• Wildcard <white>*<dark_gray> or <white>" + ChatPermissions.ADMIN + "<dark_gray> grants full chat access.");
        }
    }

    private static void sendFormatsReference(Player player) {
        line(player, "<gold><bold>Format permissions");
        line(player, "<gray>Players need <white>" + ChatPermissions.formatPermission("<id>") + "<gray> per format.");
        line(player, "<gray>Or grant <white>" + ChatPermissions.FORMAT_ALL + "<gray> to apply every rank format.");
        line(player, "<dark_gray>Example: /chat formats add vip priority=10 prefix=<gold>[VIP] </gold> name_color=gold");
    }

    private static void sendSystemFormatReference(Player player) {
        line(player, "<gold><bold>System formats");
        line(player, "<gray>Unified: <white>/chat channel formatting <party|staff|private> <flags...>");
        if (ChatPermissionChecks.has(player, ChatPermissions.PRIVATE_MANAGE)) {
            line(player, "<dark_gray>Private uses <white>%player_name%<dark_gray> and <white>%target_name%<dark_gray> for /msg lines.");
        }
    }

    private static void sendOwnerReference(Player player, PaperCorePlugin plugin) {
        line(player, "<gold><bold>Owner reference");
        line(player, "<gray>Placeholders: <white>%player_name% %channel% %target_name%");
        line(player, "<gray>PAPI: <white>%skypvp_server_id% %skypvp_chat_channel% %skypvpchat_channel_name% %skypvpchat_format_prefix%");
        boolean moderation = plugin.chatModerationEnabled();
        line(player, "<gray>Azure moderation: <white>" + (moderation ? "enabled" : "disabled")
                + "<gray> <dark_gray>(env SPVP_CHAT_MODERATION_* or chat.moderation in SkyPvPCore)");
        boolean translation = plugin.chatTranslationEnabled();
        line(player, "<gray>Auto-translate: <white>" + (translation ? "enabled" : "disabled")
                + " <gray>(provider: <white>azure<gray>)");
        line(player, "<dark_gray>Chat auto-translate uses Azure Cognitive Services; SPVP_CHAT_TRANSLATION_DEBUG=true for logs");
        line(player, "<dark_gray>Key nodes: " + ChatPermissions.ADMIN + ", " + ChatPermissions.FORMAT_ALL);
    }

    private static void footer(Player player, ChatPermissionChecks.HelpTier tier) {
        blank(player);
        if (tier == ChatPermissionChecks.HelpTier.PLAYER) {
            line(player, "<dark_gray>Tip: Social menu also has Chat Toggle Cycle.");
        } else {
            line(player, "<dark_gray>Social menu → Chat Toggle Cycle does the same as /chat toggle.");
        }
    }

    private static void command(Player player, String command, String description) {
        line(player, "<gray>" + command + " <dark_gray>— " + description);
    }

    private static void line(Player player, String miniMessage) {
        player.sendMessage(ServerTextUtil.miniMessageComponent(PREFIX + miniMessage));
    }

    private static void blank(Player player) {
        player.sendMessage(ServerTextUtil.miniMessageComponent(" "));
    }
}
