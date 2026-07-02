package network.skypvp.proxy.service;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import network.skypvp.proxy.chat.ProxyChatFormatService;
import network.skypvp.proxy.registry.NetworkStateRegistry;
import network.skypvp.proxy.registry.PrivateMessageRegistry;
import network.skypvp.shared.BrandStyle;
import network.skypvp.shared.NetworkChannels;
import network.skypvp.shared.PrivateMessageEvent;
import network.skypvp.shared.RedisEventPublisher;
import network.skypvp.shared.ServerTextUtil;
import org.slf4j.Logger;

/**
 * Delivers styled private messages on the proxy using {@link ProxyChatFormatService} and
 * optionally publishes {@link PrivateMessageEvent} for web sync or web-originated delivery.
 */
public final class ProxyPrivateMessageService {
    private final ProxyServer proxyServer;
    private final PrivateMessageRegistry messageRegistry;
    private final NetworkStateRegistry stateRegistry;
    private final ProxyChatFormatService chatFormatService;
    private final RedisEventPublisher redisPublisher;
    private final ProxyChatModerationService moderationService;
    private final ProxyChatTranslationDeliveryService translationDeliveryService;
    private final Logger logger;

    public ProxyPrivateMessageService(
            ProxyServer proxyServer,
            PrivateMessageRegistry messageRegistry,
            NetworkStateRegistry stateRegistry,
            ProxyChatFormatService chatFormatService,
            RedisEventPublisher redisPublisher,
            ProxyChatModerationService moderationService,
            Logger logger
    ) {
        this(proxyServer, messageRegistry, stateRegistry, chatFormatService, redisPublisher, moderationService, null, logger);
    }

    public ProxyPrivateMessageService(
            ProxyServer proxyServer,
            PrivateMessageRegistry messageRegistry,
            NetworkStateRegistry stateRegistry,
            ProxyChatFormatService chatFormatService,
            RedisEventPublisher redisPublisher,
            ProxyChatModerationService moderationService,
            ProxyChatTranslationDeliveryService translationDeliveryService,
            Logger logger
    ) {
        this.proxyServer = proxyServer;
        this.messageRegistry = messageRegistry;
        this.stateRegistry = stateRegistry;
        this.chatFormatService = chatFormatService;
        this.redisPublisher = redisPublisher;
        this.moderationService = moderationService;
        this.translationDeliveryService = translationDeliveryService;
        this.logger = logger;
    }

    public boolean deliver(CommandSource from, Player target, String message) {
        if (target == null || message == null) {
            return false;
        }
        if (from instanceof Player sender && this.moderationService != null) {
            ProxyChatModerationService.ReviewOutcome outcome = this.moderationService.reviewOutgoingPrivateMessage(sender, message);
            if (!outcome.allowed()) {
                if (outcome.feedbackMessage() != null) {
                    sender.sendMessage(outcome.feedbackMessage());
                }
                return false;
            }
        }
        String fromName = from instanceof Player player ? player.getUsername() : "Console";
        String senderUuid = from instanceof Player player ? player.getUniqueId().toString() : null;
        deliverStyled(fromName, senderUuid, from, target, message, true);
        publishProxyEvent(senderUuid, fromName, target, message);
        return true;
    }

    public void deliverFromEvent(PrivateMessageEvent event) {
        if (event == null || !event.deliverToPlayers()) {
            return;
        }
        Optional<Player> targetOpt = resolveTarget(event.targetUuid(), event.targetName());
        if (targetOpt.isEmpty()) {
            this.logger.debug("[PM] Target '{}' is not online for web-originated message.", event.targetName());
            return;
        }
        Player target = targetOpt.get();
        String senderName = event.senderName();
        String message = event.plainMessage();
        UUID senderId = null;
        Player senderPlayer = null;
        if (event.senderUuid() != null && !event.senderUuid().isBlank()) {
            try {
                senderId = UUID.fromString(event.senderUuid());
                senderPlayer = this.proxyServer.getPlayer(senderId).orElse(null);
            } catch (IllegalArgumentException ignored) {
            }
        }
        String senderLocale = resolveSenderLocale(senderPlayer, senderId);
        String targetMessage = this.translationDeliveryService == null
                ? message
                : this.translationDeliveryService.messageForViewer(senderId, senderLocale, message, target.getUniqueId());
        String senderRankKey = resolveSenderRankKey(senderPlayer, event.senderUuid());
        Component incoming = renderIncoming(senderName, target.getUsername(), targetMessage, senderPlayer, senderRankKey);
        target.sendMessage(incoming);

        if (senderId != null) {
            final UUID resolvedSenderId = senderId;
            this.proxyServer.getPlayer(resolvedSenderId).ifPresent(sender -> {
                Component outgoing = renderOutgoing(sender.getUsername(), target.getUsername(), message, sender);
                sender.sendMessage(outgoing);
                this.messageRegistry.recordMessage(resolvedSenderId, target.getUniqueId());
            });
        }

        deliverSpy(senderName, null, target.getUsername(), message);
    }

    private void deliverStyled(
            String fromName,
            String senderUuid,
            CommandSource from,
            Player target,
            String message,
            boolean recordReply
    ) {
        UUID senderId = null;
        Player senderPlayer = from instanceof Player player ? player : null;
        if (senderUuid != null && !senderUuid.isBlank()) {
            try {
                senderId = UUID.fromString(senderUuid);
            } catch (IllegalArgumentException ignored) {
            }
        }
        String senderLocale = resolveSenderLocale(senderPlayer, senderId);
        String targetMessage = this.translationDeliveryService == null
                ? message
                : this.translationDeliveryService.messageForViewer(senderId, senderLocale, message, target.getUniqueId());
        String senderRankKey = resolveSenderRankKey(senderPlayer, senderUuid);
        Component toSender = renderOutgoing(fromName, target.getUsername(), message, senderPlayer);
        Component toTarget = renderIncoming(fromName, target.getUsername(), targetMessage, senderPlayer, senderRankKey);
        from.sendMessage(toSender);
        target.sendMessage(toTarget);
        if (recordReply && from instanceof Player sender) {
            this.messageRegistry.recordMessage(sender.getUniqueId(), target.getUniqueId());
        }
        deliverSpy(fromName, senderUuid, target.getUsername(), message);
    }

    private void deliverSpy(String fromName, String senderUuid, String targetName, String message) {
        String fromRankKey = "default";
        if (senderUuid != null && !senderUuid.isBlank()) {
            try {
                fromRankKey = this.stateRegistry.getPlayerRankKey(UUID.fromString(senderUuid));
            } catch (IllegalArgumentException ignored) {
            }
        }
        String targetRankKey = resolveTargetRankKey(targetName);
        String fromColor = BrandStyle.hexForRankKey(fromRankKey);
        String toColor = BrandStyle.hexForRankKey(targetRankKey);
        Component spyMsg = ServerTextUtil.miniMessageComponent(
                "<#555555>[SPY] <reset><"
                        + fromColor
                        + ">"
                        + fromName
                        + "</"
                        + fromColor
                        + "><#555555> → <reset><"
                        + toColor
                        + ">"
                        + targetName
                        + "</"
                        + toColor
                        + "><#555555>: <reset><#888888>"
                        + message
                        + "<reset>"
        );
        this.proxyServer.getAllPlayers().stream()
                .filter(player -> player.hasPermission("skypvp.staff.spy"))
                .filter(player -> !player.getUsername().equalsIgnoreCase(fromName))
                .filter(player -> !player.getUsername().equalsIgnoreCase(targetName))
                .forEach(player -> player.sendMessage(spyMsg));
    }

    private String resolveTargetRankKey(String targetName) {
        return this.proxyServer.getPlayer(targetName)
                .map(player -> this.stateRegistry.getPlayerRankKey(player.getUniqueId()))
                .orElse("default");
    }

    private Component renderOutgoing(String senderName, String targetName, String message, Player sender) {
        if (this.chatFormatService != null) {
            return this.chatFormatService.renderPrivateOutgoing(senderName, targetName, message, sender);
        }
        return legacyLine("You", targetName, message);
    }

    private Component renderIncoming(String senderName, String targetName, String message, Player sender, String rankKey) {
        if (this.chatFormatService != null) {
            return this.chatFormatService.renderPrivateIncoming(senderName, targetName, message, sender, rankKey);
        }
        return legacyLine(senderName, "You", message);
    }

    private String resolveSenderLocale(Player senderPlayer, UUID senderId) {
        if (senderPlayer != null) {
            return network.skypvp.shared.chat.ClientLocaleUtil.normalizeMinecraftLocale(
                    senderPlayer.getEffectiveLocale().toString()
            );
        }
        if (this.translationDeliveryService == null) {
            return network.skypvp.shared.chat.ClientLocaleUtil.defaultMinecraftLocale();
        }
        return this.translationDeliveryService.locale(senderId);
    }

    private String resolveSenderRankKey(Player senderPlayer, String senderUuid) {
        if (senderPlayer != null) {
            return this.stateRegistry.getPlayerRankKey(senderPlayer.getUniqueId());
        }
        if (senderUuid != null && !senderUuid.isBlank()) {
            try {
                return this.stateRegistry.getPlayerRankKey(UUID.fromString(senderUuid));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return "default";
    }

    private static Component legacyLine(String fromLabel, String toLabel, String message) {
        return ServerTextUtil.miniMessageComponent(
                "<#FFD700>[</#FFD700><gold>" + fromLabel + "</gold><#FFD700> → </#FFD700><gold>" + toLabel + "</gold><#FFD700>]<reset> <#888888>" + message + "<reset>"
        );
    }

    private void publishProxyEvent(String senderUuid, String senderName, Player target, String message) {
        if (this.redisPublisher == null) {
            return;
        }
        PrivateMessageEvent event = new PrivateMessageEvent(
                senderUuid,
                senderName,
                target.getUniqueId().toString(),
                target.getUsername(),
                message,
                PrivateMessageEvent.ORIGIN_PROXY,
                false,
                System.currentTimeMillis()
        );
        this.redisPublisher.publishJson(NetworkChannels.PRIVATE_MESSAGE, event);
    }

    private Optional<Player> resolveTarget(String targetUuid, String targetName) {
        if (targetUuid != null && !targetUuid.isBlank()) {
            try {
                Optional<Player> byId = this.proxyServer.getPlayer(UUID.fromString(targetUuid));
                if (byId.isPresent()) {
                    return byId;
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (targetName != null && !targetName.isBlank()) {
            return this.proxyServer.getPlayer(targetName);
        }
        return Optional.empty();
    }
}
