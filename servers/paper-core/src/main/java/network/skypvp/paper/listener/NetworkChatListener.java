package network.skypvp.paper.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.chat.ChatChannelAccess;
import network.skypvp.paper.chat.ChatFormatService;
import network.skypvp.paper.chat.ChatModerationService;
import network.skypvp.paper.chat.ChatTranslationDeliveryService;
import network.skypvp.paper.chat.LocalChatScopeSupport;
import network.skypvp.paper.chat.PartyMembershipRepository;
import network.skypvp.paper.service.PlayerSocialSettingsService;
import network.skypvp.paper.service.ProfanityFilter;
import network.skypvp.paper.service.RankService;
import network.skypvp.shared.NetworkChannels;
import network.skypvp.shared.NetworkChatEvent;
import network.skypvp.shared.PartyChatEvent;
import network.skypvp.shared.RankRecord;
import network.skypvp.shared.RedisEventPublisher;
import network.skypvp.shared.ServerTextUtil;
import network.skypvp.shared.SocialChatRules;
import network.skypvp.shared.StaffChatEvent;
import network.skypvp.shared.chat.ChatChannel;
import network.skypvp.shared.chat.ChatFormatFlags;
import network.skypvp.shared.chat.ChatFormatScope;
import network.skypvp.shared.chat.ChatModerationAction;
import network.skypvp.shared.chat.ChatModerationVerdict;
import network.skypvp.shared.chat.ChatPermissions;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public final class NetworkChatListener implements Listener {
    private static final int RATE_LIMIT_MAX = 3;
    private static final long RATE_LIMIT_WINDOW_MS = 2000L;

    private final PaperCorePlugin plugin;
    private final RankService rankService;
    private final PlayerSocialSettingsService socialSettingsService;
    private final ChatFormatService formatService;
    private final ChatModerationService moderationService;
    private final ChatTranslationDeliveryService translationDeliveryService;
    private final PartyMembershipRepository partyMembershipRepository;
    private final ConcurrentHashMap<UUID, Deque<Long>> chatTimestamps = new ConcurrentHashMap<>();

    public NetworkChatListener(
            PaperCorePlugin plugin,
            RankService rankService,
            PlayerSocialSettingsService socialSettingsService,
            ChatFormatService formatService,
            ChatModerationService moderationService,
            ChatTranslationDeliveryService translationDeliveryService,
            PartyMembershipRepository partyMembershipRepository
    ) {
        this.plugin = plugin;
        this.rankService = rankService;
        this.socialSettingsService = socialSettingsService;
        this.formatService = formatService;
        this.moderationService = moderationService;
        this.translationDeliveryService = translationDeliveryService;
        this.partyMembershipRepository = partyMembershipRepository;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onAsyncChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        if (!passesRateLimit(playerId, player)) {
            event.setCancelled(true);
            return;
        }

        if (this.moderationService != null && this.moderationService.isMutedBlocking(playerId)) {
            event.setCancelled(true);
            player.sendMessage(ServerTextUtil.miniMessageKey("chat.muted", locale(player)));
            return;
        }

        String plainMsg = PlainTextComponentSerializer.plainText().serialize(event.message());
        RankRecord rank = this.rankService.getCached(playerId);
        String rankKey = rank.rankKey() == null ? "default" : rank.rankKey();
        boolean moderationBypass = player.hasPermission(ChatPermissions.MODERATION_BYPASS)
                || SocialChatRules.isAnnouncementMessage(plainMsg);
        boolean staffMessage = SocialChatRules.isStaffRank(rankKey) || SocialChatRules.isAnnouncementMessage(plainMsg);

        if (this.moderationService != null && !moderationBypass) {
            ChatModerationVerdict verdict = this.moderationService.reviewBlocking(player, plainMsg);
            if (verdict.action() == ChatModerationAction.MUTE || verdict.action() == ChatModerationAction.WARN) {
                event.setCancelled(true);
                if (verdict.action() == ChatModerationAction.WARN) {
                    player.sendMessage(ServerTextUtil.miniMessageKey("chat.moderation_blocked", locale(player)));
                }
                return;
            }
            if (verdict.offenseCount() > 0) {
                event.setCancelled(true);
                player.sendMessage(ServerTextUtil.miniMessageKey("chat.moderation_blocked", locale(player)));
                return;
            }
        }

        this.dispatchChat(event, player, plainMsg, rank, rankKey, staffMessage);
    }

    private void dispatchChat(
            AsyncChatEvent event,
            Player player,
            String plainMsg,
            RankRecord rank,
            String rankKey,
            boolean staffMessage
    ) {
        if (!staffMessage && this.socialSettingsService != null && this.socialSettingsService.isProfanityFilterEnabled(player.getUniqueId())) {
            String filtered = ProfanityFilter.filter(plainMsg);
            if (!filtered.equals(plainMsg)) {
                plainMsg = filtered;
                event.message(Component.text(filtered));
            }
        }

        ChatChannel channel = this.socialSettingsService == null
                ? ChatChannel.ALL
                : this.socialSettingsService.activeChatChannel(player.getUniqueId());

        switch (channel) {
            case STAFF -> handleStaffChat(event, player, plainMsg);
            case PARTY -> handlePartyChat(event, player, plainMsg);
            case PRIVATE -> {
                event.setCancelled(true);
                player.sendMessage(ServerTextUtil.miniMessageKey("chat.private_usage", locale(player)));
            }
            case ALL -> handleGlobalChat(event, player, plainMsg, rank, rankKey, staffMessage);
        }
    }

    private void handleStaffChat(AsyncChatEvent event, Player player, String plainMsg) {
        event.setCancelled(true);
        if (!player.hasPermission(ChatChannel.STAFF.permission())) {
            player.sendMessage(ServerTextUtil.miniMessageKey("chat.staff_denied", locale(player)));
            return;
        }
        Component staffLine = this.formatService.render(
                player,
                this.formatService.systemFormat(ChatFormatScope.STAFF),
                plainMsg,
                ChatChannel.STAFF.displayName()
        );
        Bukkit.getOnlinePlayers().stream()
                .filter(viewer -> viewer.hasPermission("skypvp.staff"))
                .forEach(viewer -> viewer.sendMessage(staffLine));
        RedisEventPublisher pub = this.plugin.redisPublisher();
        if (pub != null) {
            pub.publishJson(
                    NetworkChannels.STAFF_CHAT,
                    new StaffChatEvent(player.getUniqueId().toString(), player.getName(), this.plugin.serverId(), plainMsg)
            );
        }
    }

    private void handlePartyChat(AsyncChatEvent event, Player player, String plainMsg) {
        event.setCancelled(true);
        PartyMembershipRepository.PartySnapshot party = this.partyMembershipRepository.findParty(player.getUniqueId()).join().orElse(null);
        if (party == null) {
            player.sendMessage(ServerTextUtil.miniMessageKey("chat.party_required", locale(player)));
            return;
        }
        ChatFormatFlags flags = this.formatService.systemFormat(ChatFormatScope.PARTY);
        UUID senderId = player.getUniqueId();
        String senderLocale = this.translationDeliveryService == null
                ? player.getLocale()
                : this.translationDeliveryService.senderLocale(player);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!party.memberIds().contains(online.getUniqueId())) {
                continue;
            }
            String messageForViewer = plainMsg;
            if (this.translationDeliveryService != null) {
                messageForViewer = this.translationDeliveryService.messageForViewer(senderId, senderLocale, plainMsg, online.getUniqueId());
            }
            Component line = this.formatService.render(player, flags, messageForViewer, ChatChannel.PARTY.displayName());
            online.sendMessage(line);
        }
        RedisEventPublisher pub = this.plugin.redisPublisher();
        if (pub != null) {
            pub.publishJson(
                    NetworkChannels.PARTY_CHAT,
                    new PartyChatEvent(
                            this.plugin.serverId(),
                            player.getUniqueId().toString(),
                            player.getName(),
                            party.partyId().toString(),
                            plainMsg,
                            party.memberIds().stream().map(UUID::toString).toList(),
                            senderLocale
                    )
            );
        }
    }

    private void handleGlobalChat(
            AsyncChatEvent event,
            Player player,
            String plainMsg,
            RankRecord rank,
            String rankKey,
            boolean staffMessage
    ) {
        applyFormatting(
                event,
                player,
                plainMsg,
                ChatChannel.ALL,
                this.formatService.resolveRankFormat(player),
                senderLocale(player)
        );
        LocalChatScopeSupport.restrictGlobalAudience(event);
        if (!staffMessage && this.socialSettingsService != null) {
            event.viewers().removeIf(audience -> {
                if (!(audience instanceof Player viewer)) {
                    return false;
                }
                if (viewer.getUniqueId().equals(player.getUniqueId())) {
                    return false;
                }
                if (viewer.hasPermission(PlayerSocialSettingsService.PERMISSION_CHAT_TOGGLE_BYPASS)) {
                    return false;
                }
                return !this.socialSettingsService.isChatEnabled(viewer.getUniqueId());
            });
        }
        if (LocalChatScopeSupport.skipGlobalRedisBroadcast()) {
            return;
        }
        String senderLocale = this.translationDeliveryService == null
                ? player.getLocale()
                : this.translationDeliveryService.senderLocale(player);
        NetworkChatEvent chatEvent = new NetworkChatEvent(
                this.plugin.serverId(),
                player.getUniqueId().toString(),
                player.getName(),
                rankKey,
                rank.displayName() == null ? "Player" : rank.displayName(),
                plainMsg,
                senderLocale
        );
        RedisEventPublisher pub = this.plugin.redisPublisher();
        if (pub != null) {
            pub.publishJson(NetworkChannels.GLOBAL_CHAT, chatEvent);
        }
    }

    private void applyFormatting(
            AsyncChatEvent event,
            Player player,
            String plainMsg,
            ChatChannel channel,
            ChatFormatFlags flags,
            String senderLocale
    ) {
        UUID senderId = player.getUniqueId();
        event.renderer((source, sourceDisplayName, message, viewer) -> {
            String body = plainMsg;
            if (this.translationDeliveryService != null && viewer instanceof Player viewerPlayer) {
                body = this.translationDeliveryService.messageForViewer(
                        senderId,
                        senderLocale,
                        plainMsg,
                        viewerPlayer.getUniqueId()
                );
            }
            return this.formatService.render(player, flags, body, ChatChannelAccess.channelLabel(channel));
        });
    }

    private String locale(Player player) {
        if (player == null) {
            return network.skypvp.shared.chat.ClientLocaleUtil.defaultMinecraftLocale();
        }
        if (this.plugin.playerLocaleService() != null) {
            return this.plugin.playerLocaleService().locale(player.getUniqueId());
        }
        return network.skypvp.shared.chat.ClientLocaleUtil.normalizeMinecraftLocale(player.getLocale());
    }

    private String senderLocale(Player player) {
        if (this.translationDeliveryService == null || player == null) {
            return network.skypvp.shared.chat.ClientLocaleUtil.defaultMinecraftLocale();
        }
        return this.translationDeliveryService.senderLocale(player);
    }

    private boolean passesRateLimit(UUID playerId, Player player) {
        Deque<Long> times = this.chatTimestamps.computeIfAbsent(playerId, ignored -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > RATE_LIMIT_WINDOW_MS) {
                times.pollFirst();
            }
            if (times.size() >= RATE_LIMIT_MAX) {
                player.sendMessage(ServerTextUtil.miniMessageKey("chat.rate_limit", locale(player)));
                return false;
            }
            times.addLast(now);
        }
        return true;
    }
}
