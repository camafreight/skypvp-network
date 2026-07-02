package network.skypvp.paper.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.chat.ChatChannelAccess;
import network.skypvp.paper.chat.ChatCommandHelp;
import network.skypvp.paper.chat.ChatFormatService;
import network.skypvp.paper.chat.ChatPermissionChecks;
import network.skypvp.paper.chat.LocalChatScopeSupport;
import network.skypvp.paper.library.NetworkSoundCue;
import network.skypvp.paper.service.PlayerSocialSettingsService;
import network.skypvp.shared.ServerTextUtil;
import network.skypvp.shared.chat.ChatChannel;
import network.skypvp.shared.chat.ChatFormatFlagKeys;
import network.skypvp.shared.chat.ChatFormatFlagParser;
import network.skypvp.shared.chat.ChatFormatFlags;
import network.skypvp.shared.chat.ChatFormatProfile;
import network.skypvp.shared.chat.ChatFormatScope;
import network.skypvp.shared.chat.ChatPermissions;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class ChatCommand implements CommandExecutor, TabCompleter {
    private static final String PREFIX = ChatCommandHelp.PREFIX;

    private final PaperCorePlugin plugin;
    private final ChatFormatService formatService;
    private final PlayerSocialSettingsService socialSettingsService;

    public ChatCommand(PaperCorePlugin plugin, ChatFormatService formatService, PlayerSocialSettingsService socialSettingsService) {
        this.plugin = plugin;
        this.formatService = formatService;
        this.socialSettingsService = socialSettingsService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (!ChatPermissionChecks.has(player, ChatPermissions.USE)) {
            deny(player, ChatPermissions.USE, "You do not have permission to use /chat.");
            return true;
        }
        if (args.length == 0) {
            ChatCommandHelp.sendOverview(player, this.plugin, this.formatService, this.socialSettingsService);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "toggle" -> handleToggle(player);
            case "status" -> {
                ChatCommandHelp.sendStatus(player, this.socialSettingsService);
                ChatCommandHelp.sendChannelCycle(player);
                yield true;
            }
            case "channels" -> handleChannels(player);
            case "clear" -> handleClear(player);
            case "formats", "format" -> handleFormats(player, Arrays.copyOfRange(args, 1, args.length));
            case "channel" -> handleChannel(player, Arrays.copyOfRange(args, 1, args.length));
            case "private" -> handleSystemFormat(player, ChatFormatScope.PRIVATE, ChatPermissions.PRIVATE_MANAGE, Arrays.copyOfRange(args, 1, args.length));
            case "party" -> handleSystemFormat(player, ChatFormatScope.PARTY, ChatPermissions.PARTY_MANAGE, Arrays.copyOfRange(args, 1, args.length));
            case "staff" -> handleSystemFormat(player, ChatFormatScope.STAFF, ChatPermissions.STAFF_MANAGE, Arrays.copyOfRange(args, 1, args.length));
            case "help" -> {
                ChatCommandHelp.sendFullHelp(player, this.plugin, this.formatService, this.socialSettingsService);
                yield true;
            }
            default -> {
                fail(player, "Unknown subcommand <white>" + args[0] + "<red>.");
                info(player, "Try <white>/chat help<gray> or: <white>" + String.join("<gray>, <white>", ChatCommandHelp.visibleSubcommands(player)));
                yield true;
            }
        };
    }

    private boolean handleChannels(Player player) {
        List<ChatChannel> allowed = ChatChannelAccess.allowedChannels(player);
        ChatChannel current = this.socialSettingsService.activeChatChannel(player.getUniqueId());
        success(player, "You can use <white>" + allowed.size() + "<green> chat channel(s).");
        for (ChatChannel channel : allowed) {
            boolean active = channel == current;
            player.sendMessage(format(
                    (active ? "<green>▸ " : "<gray>  ")
                            + "<white>" + channel.displayName()
                            + "<gray> (<white>" + channel.name() + "<gray>)"
                            + (active ? " <green>← active" : "")
            ));
        }
        if (allowed.size() > 1) {
            info(player, "Use <white>/chat toggle<gray> to switch.");
        }
        return true;
    }

    private boolean handleToggle(Player player) {
        List<ChatChannel> allowed = ChatChannelAccess.allowedChannels(player);
        if (allowed.size() <= 1) {
            info(player, "Only the default chat toggle cycle channel is available to you.");
            ChatCommandHelp.sendChannelCycle(player);
            return true;
        }
        ChatChannel current = this.socialSettingsService.activeChatChannel(player.getUniqueId());
        ChatChannel next = ChatChannelAccess.nextChannel(player, current);
        working(player, "Switching <white>" + current.displayName() + "<gray> → <white>" + next.displayName() + "<gray>...");
        this.socialSettingsService.setActiveChatChannel(player.getUniqueId(), next).thenAcceptAsync(
                saved -> this.plugin.platformScheduler().runOnPlayer(player, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    success(player, "Active channel: <white>" + saved.activeChatChannel().displayName() + "<green>.");
                    info(player, channelBehaviorHint(saved.activeChatChannel()));
                    ChatCommandHelp.sendChannelCycle(player);
                    NetworkSoundCue.UI_BUTTON_CLICK.play(player);
                }),
                runnable -> this.plugin.platformScheduler().runAsync(runnable)
        ).exceptionally(error -> {
            notifyAsyncFailure(player, "save chat channel");
            return null;
        });
        return true;
    }

    private static String channelBehaviorHint(ChatChannel channel) {
        return switch (channel) {
            case ALL -> LocalChatScopeSupport.skipGlobalRedisBroadcast()
                    ? "Your messages stay in this world (local global chat)."
                    : "Your messages appear in global chat.";
            case PARTY -> "Your messages go to party members only.";
            case PRIVATE -> "Use <white>/msg <player> <message><gray> for private messages.";
            case STAFF -> "Your messages go to staff chat network-wide.";
        };
    }

    private boolean handleClear(Player player) {
        if (!ChatPermissionChecks.has(player, ChatPermissions.CLEAR)) {
            deny(player, ChatPermissions.CLEAR, "You do not have permission to clear chat.");
            return true;
        }
        int lines = Math.max(1, this.plugin.getConfig().getInt("chat.clear-lines", 150));
        int recipients = Bukkit.getOnlinePlayers().size();
        working(player, "Clearing chat for <white>" + recipients + "<gray> player(s)...");
        var empty = ServerTextUtil.miniMessageComponent(" ");
        for (Player online : Bukkit.getOnlinePlayers()) {
            for (int i = 0; i < lines; i++) {
                online.sendMessage(empty);
            }
        }
        success(player, "Chat cleared (<white>" + lines + "<green> lines × <white>" + recipients + "<green> players).");
        Bukkit.getConsoleSender().sendMessage(ServerTextUtil.miniMessageComponent(
                PREFIX + "<gray>Cleared by <white>" + player.getName()
        ));
        NetworkSoundCue.UI_BUTTON_CLICK.play(player);
        return true;
    }

    private boolean handleFormats(Player player, String[] args) {
        if (!ChatPermissionChecks.has(player, ChatPermissions.FORMATS_MANAGE)) {
            deny(player, ChatPermissions.FORMATS_MANAGE, "You do not have permission to manage chat formats.");
            return true;
        }
        if (args.length == 0) {
            ChatCommandHelp.sendFormatsMenu(player);
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "flags" -> {
                ChatCommandHelp.sendFlagReference(player);
                yield true;
            }
            case "show" -> {
                if (args.length < 2) {
                    usage(player, "/chat formats show <id-name>");
                    suggestFormatIds(player);
                    yield true;
                }
                String id = args[1].toLowerCase(Locale.ROOT);
                this.formatService.findRankFormat(id).ifPresentOrElse(
                        profile -> {
                            success(player, "Format <white>" + profile.id() + "<green>:");
                            sendFormatPreview(player, profile);
                        },
                        () -> {
                            fail(player, "No format named <white>" + id + "<red>.");
                            suggestFormatIds(player);
                        }
                );
                yield true;
            }
            case "list" -> {
                List<ChatFormatProfile> formats = this.formatService.rankFormats();
                if (formats.isEmpty()) {
                    info(player, "No rank formats yet. <white>/chat formats add <id> <flags...>");
                    yield true;
                }
                success(player, "<white>" + formats.size() + "<green> rank format(s):");
                for (ChatFormatProfile profile : formats) {
                    player.sendMessage(format(
                            "<gray>• <white>" + profile.id() + " <dark_gray>(priority <white>" + profile.flags().priority() + "<dark_gray>)"
                    ));
                }
                yield true;
            }
            case "remove" -> {
                if (args.length < 2) {
                    usage(player, "/chat formats remove <id-name>");
                    suggestFormatIds(player);
                    yield true;
                }
                String id = args[1].toLowerCase(Locale.ROOT);
                if (this.formatService.findRankFormat(id).isEmpty()) {
                    fail(player, "No format named <white>" + id + "<red>.");
                    yield true;
                }
                working(player, "Removing <white>" + id + "<gray>...");
                this.formatService.remove(id).thenAcceptAsync(
                        removed -> this.plugin.platformScheduler().runOnPlayer(player, () -> {
                            if (!player.isOnline()) {
                                return;
                            }
                            if (removed) {
                                success(player, "Removed format <white>" + id + "<green>.");
                                info(player, "Synced network-wide.");
                                NetworkSoundCue.UI_BUTTON_CLICK.play(player);
                            } else {
                                fail(player, "Could not remove <white>" + id + "<red>.");
                                NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
                            }
                        }),
                        runnable -> this.plugin.platformScheduler().runAsync(runnable)
                ).exceptionally(error -> {
                    notifyAsyncFailure(player, "remove format " + id);
                    return null;
                });
                yield true;
            }
            case "add", "set" -> handleFormatSave(player, action, args);
            default -> {
                fail(player, "Unknown action <white>" + action + "<red>.");
                ChatCommandHelp.sendFormatsMenu(player);
                yield true;
            }
        };
    }

    private boolean handleFormatSave(Player player, String action, String[] args) {
        if (args.length < 2) {
            usage(player, "/chat formats " + action + " <id-name> <flags...>");
            return true;
        }
        if (args.length < 3) {
            usage(player, "/chat formats " + action + " <id-name> <flags...>");
            info(player, "Tab-complete flags after the id, or <white>/chat formats flags");
            return true;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        String[] flagTokens = Arrays.copyOfRange(args, 2, args.length);
        Set<String> appliedKeys = ChatFormatFlagKeys.usedKeys(flagTokens);
        if (appliedKeys.isEmpty()) {
            fail(player, "No valid flags found.");
            ChatCommandHelp.sendFlagReference(player);
            return true;
        }
        boolean exists = this.formatService.findRankFormat(id).isPresent();
        if (action.equals("add") && exists) {
            fail(player, "Format <white>" + id + "<red> already exists — use <white>set<red>.");
            return true;
        }
        if (action.equals("set") && !exists) {
            info(player, "Creating new format <white>" + id + "<gray>.");
        }
        ChatFormatFlags parsed = ChatFormatFlagParser.parse(flagTokens);
        ChatFormatFlags flags = action.equals("set")
                ? this.formatService.findRankFormat(id).map(existing -> existing.flags().merge(parsed)).orElse(parsed)
                : parsed;
        ChatFormatProfile profile = new ChatFormatProfile(id, ChatFormatScope.RANK, flags);
        final ChatFormatFlags savedFlags = flags;
        final String savedId = id;
        final boolean creating = action.equals("add");
        working(player, (creating ? "Creating" : "Updating") + " <white>" + savedId + "<gray>...");
        info(player, "Flags: <white>" + summarizeFlagKeys(appliedKeys));
        this.formatService.upsert(profile).thenRunAsync(
                () -> this.plugin.platformScheduler().runOnPlayer(player, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    success(player, (creating ? "Created" : "Updated") + " <white>" + savedId + "<green> (priority <white>" + savedFlags.priority() + "<green>).");
                    info(player, "Synced network-wide.");
                    if (!ChatPermissionChecks.hasFullChatAccess(player)) {
                        info(player, "Grant <white>" + ChatPermissions.formatPermission(savedId) + "<gray> for players to use it.");
                    }
                    NetworkSoundCue.UI_BUTTON_CLICK.play(player);
                }),
                runnable -> this.plugin.platformScheduler().runAsync(runnable)
        ).exceptionally(error -> {
            notifyAsyncFailure(player, "save format " + savedId);
            return null;
        });
        return true;
    }

    private void sendFormatPreview(Player player, ChatFormatProfile profile) {
        ChatFormatFlags flags = profile.flags();
        player.sendMessage(format("<gray>priority=<white>" + flags.priority()));
        player.sendMessage(format("<gray>prefix=<white>" + emptyDash(flags.prefix())));
        player.sendMessage(format("<gray>name=<white>" + emptyDash(flags.name()) + " <dark_gray>color=<white>" + emptyDash(flags.nameColor())));
        player.sendMessage(format("<gray>suffix=<white>" + emptyDash(flags.suffix()) + " <dark_gray>chat=<white>" + emptyDash(flags.chatColor())));
        player.sendMessage(format("<dark_gray>perm: <white>" + ChatPermissions.formatPermission(profile.id())));
    }

    private static String emptyDash(String value) {
        return value == null || value.isBlank() ? "(empty)" : value;
    }

    private boolean handleChannel(Player player, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("formatting")) {
            usage(player, "/chat channel formatting <party|staff|private> <flags...>");
            info(player, "Configure network-wide styling for party, staff, or private messages.");
            return true;
        }
        if (args.length < 2) {
            usage(player, "/chat channel formatting <party|staff|private> <flags...>");
            return true;
        }
        ChannelFormatTarget target = resolveChannelFormatTarget(args[1]);
        if (target == null) {
            fail(player, "Unknown channel <white>" + args[1] + "<red>. Use <white>party<red>, <white>staff<red>, or <white>private<red>.");
            return true;
        }
        if (args.length < 3) {
            info(player, "Add flags after the channel name. Try <white>/chat formats flags");
            return true;
        }
        String[] flagTokens = Arrays.copyOfRange(args, 2, args.length);
        return saveSystemFormat(player, target.scope(), target.permission(), target.scopeLabel(), flagTokens);
    }

    private record ChannelFormatTarget(ChatFormatScope scope, String permission, String scopeLabel) {
    }

    private static ChannelFormatTarget resolveChannelFormatTarget(String channelName) {
        if (channelName == null || channelName.isBlank()) {
            return null;
        }
        return switch (channelName.toLowerCase(Locale.ROOT)) {
            case "party" -> new ChannelFormatTarget(ChatFormatScope.PARTY, ChatPermissions.PARTY_MANAGE, "party");
            case "staff" -> new ChannelFormatTarget(ChatFormatScope.STAFF, ChatPermissions.STAFF_MANAGE, "staff");
            case "private" -> new ChannelFormatTarget(ChatFormatScope.PRIVATE, ChatPermissions.PRIVATE_MANAGE, "private");
            default -> null;
        };
    }

    private boolean handleSystemFormat(Player player, ChatFormatScope scope, String permission, String[] args) {
        String scopeLabel = scope.name().toLowerCase(Locale.ROOT);
        if (!ChatPermissionChecks.has(player, permission)) {
            deny(player, permission, "You do not have permission to manage " + scopeLabel + " chat formatting.");
            return true;
        }
        if (args.length == 0 || !args[0].equalsIgnoreCase("set")) {
            usage(player, "/chat channel formatting " + scopeLabel + " <flags...>");
            info(player, "Legacy: <white>/chat " + scopeLabel + " set <flags...><gray> also works.");
            return true;
        }
        if (args.length == 1) {
            info(player, "Add flags after <white>set<gray>. Try <white>/chat formats flags");
            return true;
        }
        String[] flagTokens = Arrays.copyOfRange(args, 1, args.length);
        return saveSystemFormat(player, scope, permission, scopeLabel, flagTokens);
    }

    private boolean saveSystemFormat(
            Player player,
            ChatFormatScope scope,
            String permission,
            String scopeLabel,
            String[] flagTokens
    ) {
        if (!ChatPermissionChecks.has(player, permission)) {
            deny(player, permission, "You do not have permission to manage " + scopeLabel + " chat formatting.");
            return true;
        }
        Set<String> appliedKeys = ChatFormatFlagKeys.usedKeys(flagTokens);
        if (appliedKeys.isEmpty()) {
            fail(player, "No valid flags found.");
            ChatCommandHelp.sendFlagReference(player);
            return true;
        }
        ChatFormatFlags parsed = ChatFormatFlagParser.parse(flagTokens);
        ChatFormatFlags flags = this.formatService.systemFormat(scope).merge(parsed);
        ChatFormatProfile profile = new ChatFormatProfile(scopeLabel, scope, flags);
        working(player, "Saving <white>" + scopeLabel + "<gray> format...");
        this.formatService.upsert(profile).thenRunAsync(
                () -> this.plugin.platformScheduler().runOnPlayer(player, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    success(player, "Updated <white>" + scopeLabel + "<green> chat format.");
                    info(player, "Synced network-wide.");
                    NetworkSoundCue.UI_BUTTON_CLICK.play(player);
                }),
                runnable -> this.plugin.platformScheduler().runAsync(runnable)
        ).exceptionally(error -> {
            notifyAsyncFailure(player, "save " + scopeLabel + " format");
            return null;
        });
        return true;
    }

    private void suggestFormatIds(Player player) {
        if (!this.formatService.rankFormats().isEmpty()) {
            info(player, "Saved ids: <white>" + ChatCommandHelp.formatIdList(this.formatService));
        }
    }

    private void deny(Player player, String permission, String message) {
        fail(player, message + ChatPermissionChecks.permissionHint(player, permission));
    }

    private static String summarizeFlagKeys(Set<String> keys) {
        return keys.stream().sorted().collect(Collectors.joining("<gray>, <white>"));
    }

    private void notifyAsyncFailure(Player player, String action) {
        this.plugin.platformScheduler().runOnPlayer(player, () -> {
            if (player.isOnline()) {
                fail(player, "Could not " + action + ". Try again or check server logs.");
                NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
            }
        });
    }

    private static void info(Player player, String message) {
        player.sendMessage(format("<gray>" + message));
    }

    private static void usage(Player player, String command) {
        player.sendMessage(format("<yellow>Usage: <white>" + command));
    }

    private static void working(Player player, String message) {
        player.sendMessage(format("<#AAAAFF>" + message));
    }

    private static void success(Player player, String message) {
        player.sendMessage(format("<green>" + message));
    }

    private static void fail(Player player, String message) {
        player.sendMessage(format("<red>" + message));
    }

    private static net.kyori.adventure.text.Component format(String miniMessage) {
        return ServerTextUtil.miniMessageComponent(PREFIX + miniMessage);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || !ChatPermissionChecks.canUseTabComplete(player)) {
            return List.of();
        }
        if (args.length == 1) {
            return prefixMatch(args[0], ChatCommandHelp.visibleSubcommands(player));
        }
        String sub = args[0].equalsIgnoreCase("format") ? "formats" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("formats") && ChatPermissionChecks.has(player, ChatPermissions.FORMATS_MANAGE)) {
            return completeFormats(player, args);
        }
        if (sub.equals("channel")) {
            return completeChannelFormatting(player, args);
        }
        if (sub.equals("private") && ChatPermissionChecks.has(player, ChatPermissions.PRIVATE_MANAGE)) {
            return completeSystemFormat(args);
        }
        if (sub.equals("party") && ChatPermissionChecks.has(player, ChatPermissions.PARTY_MANAGE)) {
            return completeSystemFormat(args);
        }
        if (sub.equals("staff") && ChatPermissionChecks.has(player, ChatPermissions.STAFF_MANAGE)) {
            return completeSystemFormat(args);
        }
        return List.of();
    }

    private List<String> completeChannelFormatting(Player player, String[] args) {
        if (args.length == 2) {
            return prefixMatch(args[1], "formatting");
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("formatting")) {
            List<String> channels = new ArrayList<>();
            if (ChatPermissionChecks.has(player, ChatPermissions.PARTY_MANAGE)) {
                channels.add("party");
            }
            if (ChatPermissionChecks.has(player, ChatPermissions.STAFF_MANAGE)) {
                channels.add("staff");
            }
            if (ChatPermissionChecks.has(player, ChatPermissions.PRIVATE_MANAGE)) {
                channels.add("private");
            }
            return prefixMatch(args[2], channels.toArray(String[]::new));
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("formatting")) {
            return completeFlagTokens(Arrays.copyOfRange(args, 3, args.length), args[args.length - 1]);
        }
        return List.of();
    }

    private List<String> completeFormats(Player player, String[] args) {
        if (args.length == 2) {
            return prefixMatch(args[1], "add", "set", "remove", "list", "flags", "show");
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (args.length == 3 && (action.equals("remove") || action.equals("set") || action.equals("show"))) {
            return prefixMatch(args[2], this.formatService.rankFormats().stream().map(ChatFormatProfile::id).toArray(String[]::new));
        }
        if (args.length == 3 && action.equals("add")) {
            return prefixMatch(args[2], this.formatService.rankFormats().stream().map(ChatFormatProfile::id).toArray(String[]::new));
        }
        if ((action.equals("add") || action.equals("set")) && args.length >= 3) {
            return completeFlagTokens(Arrays.copyOfRange(args, 2, args.length), args[args.length - 1]);
        }
        return List.of();
    }

    private static List<String> completeSystemFormat(String[] args) {
        if (args.length == 2) {
            return prefixMatch(args[1], "set");
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("set")) {
            return completeFlagTokens(Arrays.copyOfRange(args, 2, args.length), args[args.length - 1]);
        }
        return List.of();
    }

    private static List<String> completeFlagTokens(String[] flagTokens, String currentToken) {
        if (flagTokens.length == 0) {
            return List.of();
        }
        Set<String> used = ChatFormatFlagKeys.usedKeys(Arrays.copyOf(flagTokens, Math.max(0, flagTokens.length - 1)));
        String partial = currentToken == null ? "" : currentToken;
        if (partial.contains("=") && flagTokens.length > 1) {
            used = ChatFormatFlagKeys.usedKeys(flagTokens);
            partial = "";
        }
        return prefixMatch(partial, ChatFormatFlagKeys.tabSuggestions(partial, used).toArray(String[]::new));
    }

    private static List<String> prefixMatch(String token, String... options) {
        String lower = token == null ? "" : token.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches.add(option);
            }
        }
        return matches;
    }
}
