package network.skypvp.paper.chat;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.repository.PaperPunishmentRepository;
import network.skypvp.shared.PunishmentRecord;
import network.skypvp.shared.chat.ChatModerationAction;
import network.skypvp.shared.chat.ChatModerationEscalation;
import network.skypvp.shared.chat.ChatModerationSettings;
import network.skypvp.shared.chat.ChatModerationVerdict;
import network.skypvp.shared.chat.TextModerationClient;
import network.skypvp.shared.chat.TextModerationResult;
import org.bukkit.entity.Player;

public final class ChatModerationService {
    private static final String BLOCKED_REASON = "Inappropriate language detected by chat moderation.";
    private static final String MUTE_REASON = "Repeated chat moderation violations.";

    private final PaperCorePlugin plugin;
    private final TextModerationClient moderationClient;
    private final PaperPunishmentRepository punishmentRepository;
    private final Logger logger;

    public ChatModerationService(
            PaperCorePlugin plugin,
            TextModerationClient moderationClient,
            PaperPunishmentRepository punishmentRepository,
            Logger logger
    ) {
        this.plugin = plugin;
        this.moderationClient = moderationClient;
        this.punishmentRepository = punishmentRepository;
        this.logger = logger;
    }

    public CompletableFuture<ChatModerationVerdict> reviewMessage(Player player, String message) {
        if (player == null || message == null || message.isBlank()) {
            return CompletableFuture.completedFuture(ChatModerationVerdict.allow());
        }
        ChatModerationSettings settings = loadSettings();
        if (!settings.enabled() || !this.moderationClient.enabled()) {
            return CompletableFuture.completedFuture(ChatModerationVerdict.allow());
        }
        TextModerationResult result = this.moderationClient.moderate(message, settings.language());
        if (!result.flagged(settings.category3Threshold(), settings.honorReviewRecommended())) {
            return CompletableFuture.completedFuture(ChatModerationVerdict.allow());
        }
        return this.punishmentRepository.countActiveWarnings(player.getUniqueId(), ChatModerationEscalation.ISSUER)
                .thenCompose(activeWarnCount -> {
                    ChatModerationEscalation.Decision decision = ChatModerationEscalation.decide(activeWarnCount, settings);
                    if (decision == ChatModerationEscalation.Decision.MUTE) {
                        return this.issueMute(player, MUTE_REASON, settings.muteDurationSeconds())
                                .thenApply(ignored -> new ChatModerationVerdict(
                                        ChatModerationAction.MUTE,
                                        MUTE_REASON,
                                        0,
                                        activeWarnCount
                                ));
                    }
                    return this.issueWarn(player, BLOCKED_REASON)
                            .thenApply(ignored -> new ChatModerationVerdict(
                                    ChatModerationAction.WARN,
                                    BLOCKED_REASON,
                                    0,
                                    activeWarnCount + 1
                            ));
                });
    }

    public CompletableFuture<Boolean> isMuted(UUID playerId) {
        return this.punishmentRepository.findActiveMute(playerId).thenApply(java.util.Optional::isPresent);
    }

    public boolean isMutedBlocking(UUID playerId) {
        return Boolean.TRUE.equals(this.isMuted(playerId).join());
    }

    public ChatModerationVerdict reviewBlocking(Player player, String message) {
        return this.reviewMessage(player, message).join();
    }

    private ChatModerationSettings loadSettings() {
        return new ChatModerationSettings(
                this.plugin.chatModerationEnabled(),
                this.plugin.chatModerationLanguage(),
                this.plugin.chatModerationCategory3Threshold(),
                this.plugin.chatModerationHonorReviewRecommended(),
                this.plugin.chatModerationFlagsBeforeWarn(),
                this.plugin.chatModerationWarnsBeforeMute(),
                this.plugin.chatModerationMuteDurationSeconds(),
                this.plugin.chatModerationContentSafetyMinSeverity()
        );
    }

    private CompletableFuture<Void> issueWarn(Player player, String reason) {
        return this.punishmentRepository.issue(
                player.getUniqueId(),
                player.getName(),
                PunishmentRecord.PunishmentType.WARN,
                reason,
                ChatModerationEscalation.ISSUER,
                null
        ).thenRun(() -> player.sendMessage(
                network.skypvp.shared.ServerTextUtil.miniMessageComponent(
                        "<#FF5555>[WARN] <reset><#FFFFFF>" + reason
                )
        ));
    }

    private CompletableFuture<Void> issueMute(Player player, String reason, int durationSeconds) {
        Instant expiresAt = durationSeconds <= 0 ? null : Instant.now().plusSeconds(durationSeconds);
        return this.punishmentRepository.issue(
                player.getUniqueId(),
                player.getName(),
                PunishmentRecord.PunishmentType.MUTE,
                reason,
                ChatModerationEscalation.ISSUER,
                expiresAt
        ).thenRun(() -> player.sendMessage(
                network.skypvp.shared.ServerTextUtil.miniMessageComponent(
                        "<#FF5555>[MUTE] <reset><#FFFFFF>You have been muted for chat violations."
                )
        ));
    }
}
