package network.skypvp.proxy.chat;

import com.velocitypowered.api.proxy.Player;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import network.skypvp.shared.chat.ChatChannel;
import network.skypvp.shared.chat.ChatFormatFlags;
import network.skypvp.shared.chat.ChatFormatProfile;
import network.skypvp.shared.chat.ChatFormatScope;
import network.skypvp.shared.chat.ChatMessageRenderer;
import network.skypvp.shared.chat.ChatRenderContext;
import org.slf4j.Logger;

public final class ProxyChatFormatService {
    private final ProxyChatFormatRepository repository;
    private final Logger logger;
    private final Map<String, ChatFormatProfile> rankFormats = new ConcurrentHashMap<>();
    private final Map<ChatFormatScope, ChatFormatFlags> systemDefaults = new EnumMap<>(ChatFormatScope.class);

    public ProxyChatFormatService(ProxyChatFormatRepository repository, Logger logger) {
        this.repository = repository;
        this.logger = logger;
        this.systemDefaults.put(ChatFormatScope.RANK, ChatFormatFlags.EMPTY);
        this.systemDefaults.put(ChatFormatScope.PRIVATE, ChatFormatFlags.EMPTY);
        this.systemDefaults.put(ChatFormatScope.PARTY, ChatFormatFlags.EMPTY);
        this.systemDefaults.put(ChatFormatScope.STAFF, ChatFormatFlags.EMPTY);
        this.reload();
    }

    public void reload() {
        List<ChatFormatProfile> profiles = this.repository.loadAll();
        this.rankFormats.clear();
        this.systemDefaults.put(ChatFormatScope.RANK, ChatFormatFlags.EMPTY);
        this.systemDefaults.put(ChatFormatScope.PRIVATE, ChatFormatFlags.EMPTY);
        this.systemDefaults.put(ChatFormatScope.PARTY, ChatFormatFlags.EMPTY);
        this.systemDefaults.put(ChatFormatScope.STAFF, ChatFormatFlags.EMPTY);
        for (ChatFormatProfile profile : profiles) {
            if (profile.scope() == ChatFormatScope.RANK) {
                this.rankFormats.put(profile.id().toLowerCase(), profile);
            } else {
                this.systemDefaults.put(profile.scope(), profile.flags());
            }
        }
        this.logger.info("[ChatFormats] Loaded {} format profile(s) on proxy.", profiles.size());
    }

    public ChatFormatFlags systemFormat(ChatFormatScope scope) {
        return this.systemDefaults.getOrDefault(scope, ChatFormatFlags.EMPTY);
    }

    public Optional<ChatFormatProfile> findRankFormat(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(this.rankFormats.get(id.toLowerCase()));
    }

    public ChatFormatFlags resolveRankFormat(Player sender, String rankKeyFallback) {
        ChatFormatFlags best = this.systemDefaults.getOrDefault(ChatFormatScope.RANK, ChatFormatFlags.EMPTY);
        int bestPriority = Integer.MIN_VALUE;
        if (sender != null) {
            for (ChatFormatProfile profile : this.rankFormats.values()) {
                if (!ProxyChatPermissionChecks.hasFormatPermission(sender, profile.id())) {
                    continue;
                }
                if (profile.flags().priority() >= bestPriority) {
                    bestPriority = profile.flags().priority();
                    best = profile.flags();
                }
            }
        }
        if (bestPriority == Integer.MIN_VALUE && rankKeyFallback != null && !rankKeyFallback.isBlank()) {
            return this.findRankFormat(rankKeyFallback).map(ChatFormatProfile::flags).orElse(best);
        }
        return best;
    }

    public Component render(String playerName, ChatFormatFlags flags, String message, String channelLabel) {
        return render(playerName, flags, message, channelLabel, null, null);
    }

    public Component render(
            String playerName,
            ChatFormatFlags flags,
            String message,
            String channelLabel,
            Player sender,
            String rankKeyFallback
    ) {
        ChatRenderContext context = new ChatRenderContext(playerName, message, channelLabel);
        ProxyChatRenderContextHolder.RenderState state = new ProxyChatRenderContextHolder.RenderState(
                context,
                sender,
                rankKeyFallback,
                this
        );
        return ProxyChatRenderContextHolder.callWith(
                state,
                () -> ChatMessageRenderer.render(
                        flags,
                        context,
                        text -> ProxyChatPlaceholderBridge.apply(text, context, sender, this, rankKeyFallback)
                )
        );
    }

    public Component renderParty(String senderName, String message) {
        return renderParty(senderName, message, null, null);
    }

    public Component renderParty(String senderName, String message, Player sender, String rankKeyFallback) {
        return render(
                senderName,
                systemFormat(ChatFormatScope.PARTY),
                message,
                ChatChannel.PARTY.displayName(),
                sender,
                rankKeyFallback
        );
    }

    public Component renderStaff(String senderName, String message) {
        return render(senderName, systemFormat(ChatFormatScope.STAFF), message, ChatChannel.STAFF.displayName());
    }

    public Component renderGlobal(Player sender, String senderName, String rankKey, String message) {
        ChatFormatFlags flags = resolveRankFormat(sender, rankKey);
        return render(senderName, flags, message, ChatChannel.ALL.displayName(), sender, rankKey);
    }

    public Component renderPrivateOutgoing(String senderName, String targetName, String message) {
        return renderPrivateOutgoing(senderName, targetName, message, null);
    }

    public Component renderPrivateOutgoing(String senderName, String targetName, String message, Player sender) {
        ChatFormatFlags flags = systemFormat(ChatFormatScope.PRIVATE);
        ChatRenderContext context = new ChatRenderContext("You", message, "", targetName);
        ProxyChatRenderContextHolder.RenderState state = new ProxyChatRenderContextHolder.RenderState(
                context,
                sender,
                null,
                this
        );
        return ProxyChatRenderContextHolder.callWith(
                state,
                () -> ChatMessageRenderer.render(
                        flags,
                        context,
                        text -> ProxyChatPlaceholderBridge.apply(text, context, sender, this, null)
                )
        );
    }

    public Component renderPrivateIncoming(String senderName, String targetName, String message) {
        return renderPrivateIncoming(senderName, targetName, message, null, null);
    }

    public Component renderPrivateIncoming(
            String senderName,
            String targetName,
            String message,
            Player sender,
            String rankKeyFallback
    ) {
        ChatFormatFlags flags = systemFormat(ChatFormatScope.PRIVATE);
        ChatRenderContext context = new ChatRenderContext(senderName, message, "", "You");
        ProxyChatRenderContextHolder.RenderState state = new ProxyChatRenderContextHolder.RenderState(
                context,
                sender,
                rankKeyFallback,
                this
        );
        return ProxyChatRenderContextHolder.callWith(
                state,
                () -> ChatMessageRenderer.render(
                        flags,
                        context,
                        text -> ProxyChatPlaceholderBridge.apply(text, context, sender, this, rankKeyFallback)
                )
        );
    }

    public List<ChatFormatProfile> rankFormats() {
        return this.rankFormats.values().stream()
                .sorted(Comparator.comparingInt((ChatFormatProfile profile) -> profile.flags().priority()).reversed())
                .toList();
    }
}
