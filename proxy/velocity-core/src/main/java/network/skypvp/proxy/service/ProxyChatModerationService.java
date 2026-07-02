package network.skypvp.proxy.service;

import com.velocitypowered.api.proxy.Player;
import java.time.Instant;
import java.util.UUID;
import network.skypvp.proxy.repository.PunishmentRepository;
import network.skypvp.shared.PunishmentRecord;
import network.skypvp.shared.ServerTextUtil;
import network.skypvp.shared.chat.ChatModerationEscalation;
import network.skypvp.shared.chat.ChatModerationSettings;
import network.skypvp.shared.chat.ChatPermissions;
import network.skypvp.shared.chat.TextModerationClient;
import network.skypvp.shared.chat.TextModerationResult;
import net.kyori.adventure.text.Component;

public final class ProxyChatModerationService {
    private static final String BLOCKED_REASON = "Inappropriate language detected by chat moderation.";
    private static final String MUTE_REASON = "Repeated chat moderation violations.";

    private final PunishmentRepository punishmentRepository;
    private final TextModerationClient moderationClient;
    private final ChatModerationSettings settings;

    public ProxyChatModerationService(
            PunishmentRepository punishmentRepository,
            TextModerationClient moderationClient,
            ChatModerationSettings settings
    ) {
        this.punishmentRepository = punishmentRepository;
        this.moderationClient = moderationClient;
        this.settings = settings == null ? ChatModerationSettings.defaults() : settings;
    }

    public boolean isMuted(UUID playerId) {
        if (playerId == null || this.punishmentRepository == null) {
            return false;
        }
        return this.punishmentRepository.findActivePunishment(playerId, PunishmentRecord.PunishmentType.MUTE).isPresent();
    }

    public ReviewOutcome reviewOutgoingPrivateMessage(Player sender, String message) {
        if (sender == null || message == null || message.isBlank()) {
            return ReviewOutcome.allow();
        }
        if (isMuted(sender.getUniqueId())) {
            return ReviewOutcome.block(mutedFeedback());
        }
        if (sender.hasPermission(ChatPermissions.MODERATION_BYPASS)) {
            return ReviewOutcome.allow();
        }
        if (!this.settings.enabled() || this.moderationClient == null || !this.moderationClient.enabled()) {
            return ReviewOutcome.allow();
        }
        TextModerationResult result = this.moderationClient.moderate(message, this.settings.language());
        if (!result.flagged(this.settings.category3Threshold(), this.settings.honorReviewRecommended())) {
            return ReviewOutcome.allow();
        }
        if (this.punishmentRepository == null) {
            return ReviewOutcome.block(blockedFeedback());
        }

        int activeWarnCount = this.punishmentRepository.countActiveWarnings(
                sender.getUniqueId(),
                ChatModerationEscalation.ISSUER
        );
        ChatModerationEscalation.Decision decision = ChatModerationEscalation.decide(activeWarnCount, this.settings);
        if (decision == ChatModerationEscalation.Decision.MUTE) {
            issueMute(sender, MUTE_REASON, this.settings.muteDurationSeconds());
            return ReviewOutcome.block(mutedFeedback());
        }
        issueWarn(sender, BLOCKED_REASON);
        return ReviewOutcome.block(blockedFeedback());
    }

    public boolean shouldDeliverWebPrivateMessage(UUID senderUuid) {
        return !isMuted(senderUuid);
    }

    private void issueWarn(Player player, String reason) {
        if (this.punishmentRepository == null) {
            return;
        }
        this.punishmentRepository.issue(
                player.getUniqueId(),
                player.getUsername(),
                PunishmentRecord.PunishmentType.WARN,
                reason,
                ChatModerationEscalation.ISSUER,
                null
        );
        player.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>[WARN] <reset><#FFFFFF>" + reason));
    }

    private void issueMute(Player player, String reason, int durationSeconds) {
        if (this.punishmentRepository == null) {
            return;
        }
        Instant expiresAt = durationSeconds <= 0 ? null : Instant.now().plusSeconds(durationSeconds);
        this.punishmentRepository.issue(
                player.getUniqueId(),
                player.getUsername(),
                PunishmentRecord.PunishmentType.MUTE,
                reason,
                ChatModerationEscalation.ISSUER,
                expiresAt
        );
        player.sendMessage(ServerTextUtil.miniMessageComponent(
                "<#FF5555>[MUTE] <reset><#FFFFFF>You have been muted for chat violations."
        ));
    }

    private static Component mutedFeedback() {
        return ServerTextUtil.miniMessageComponent("<red>You are muted.");
    }

    private static Component blockedFeedback() {
        return ServerTextUtil.miniMessageComponent("<red>Your message was blocked by chat moderation.");
    }

    public record ReviewOutcome(boolean allowed, Component feedbackMessage) {
        public static ReviewOutcome allow() {
            return new ReviewOutcome(true, null);
        }

        public static ReviewOutcome block(Component feedbackMessage) {
            return new ReviewOutcome(false, feedbackMessage);
        }
    }
}
