package network.skypvp.paper.chat;

import java.util.Objects;
import java.util.UUID;
import network.skypvp.paper.service.PlayerLocaleService;
import network.skypvp.paper.service.PlayerSocialSettingsService;
import network.skypvp.shared.chat.ChatTranslationDiagnostics;
import network.skypvp.shared.chat.ChatTranslationService;
import org.bukkit.entity.Player;

public final class ChatTranslationDeliveryService {
    private final ChatTranslationService translationService;
    private final PlayerLocaleService localeService;
    private final PlayerSocialSettingsService socialSettingsService;

    public ChatTranslationDeliveryService(
            ChatTranslationService translationService,
            PlayerLocaleService localeService,
            PlayerSocialSettingsService socialSettingsService
    ) {
        this.translationService = Objects.requireNonNull(translationService, "translationService");
        this.localeService = Objects.requireNonNull(localeService, "localeService");
        this.socialSettingsService = Objects.requireNonNull(socialSettingsService, "socialSettingsService");
    }

    public boolean enabled() {
        return this.translationService.enabled();
    }

    public String messageForViewer(UUID senderId, String plainMessage, UUID viewerId) {
        return messageForViewer(senderId, null, plainMessage, viewerId);
    }

    public String messageForViewer(UUID senderId, String senderLocale, String plainMessage, UUID viewerId) {
        if (viewerId != null && senderId != null && viewerId.equals(senderId)) {
            return plainMessage;
        }

        String resolvedSenderLocale = senderLocale == null || senderLocale.isBlank()
                ? this.localeService.locale(senderId)
                : senderLocale;
        String viewerLocale = this.localeService.locale(viewerId);
        boolean autoTranslate = this.socialSettingsService.isAutoTranslateEnabled(viewerId);
        boolean settingsLoaded = this.socialSettingsService.isLoaded(viewerId);

        if (ChatTranslationDiagnostics.debugEnabled() && !settingsLoaded) {
            org.bukkit.Bukkit.getLogger().info(
                    "[chat-translation] paper viewer settings not loaded yet for "
                            + viewerId
                            + " (autoTranslate reads as false until DB preload completes)"
            );
        }

        return this.translationService.messageForViewer(
                plainMessage,
                resolvedSenderLocale,
                viewerLocale,
                autoTranslate,
                senderId,
                viewerId
        );
    }

    public String senderLocale(Player sender) {
        return sender == null ? this.localeService.locale(null) : this.localeService.locale(sender.getUniqueId());
    }
}
