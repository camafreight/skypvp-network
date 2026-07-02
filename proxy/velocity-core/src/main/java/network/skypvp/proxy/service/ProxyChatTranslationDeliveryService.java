package network.skypvp.proxy.service;

import java.util.Objects;
import java.util.UUID;
import network.skypvp.proxy.repository.PlayerSocialSettingsRepository;
import network.skypvp.shared.chat.ChatTranslationDiagnostics;
import network.skypvp.shared.chat.ChatTranslationService;

public final class ProxyChatTranslationDeliveryService {
    private final ChatTranslationService translationService;
    private final PlayerLocaleService localeService;
    private final PlayerSocialSettingsRepository socialSettingsRepository;

    public ProxyChatTranslationDeliveryService(
            ChatTranslationService translationService,
            PlayerLocaleService localeService,
            PlayerSocialSettingsRepository socialSettingsRepository
    ) {
        this.translationService = Objects.requireNonNull(translationService, "translationService");
        this.localeService = Objects.requireNonNull(localeService, "localeService");
        this.socialSettingsRepository = socialSettingsRepository;
    }

    public boolean enabled() {
        return this.translationService.enabled();
    }

    public String messageForViewer(UUID senderId, String senderLocale, String plainMessage, UUID viewerId) {
        if (viewerId != null && senderId != null && viewerId.equals(senderId)) {
            return plainMessage;
        }

        boolean autoTranslate = this.socialSettingsRepository != null
                && this.socialSettingsRepository.isAutoTranslateEnabled(viewerId);
        boolean settingsLoaded = this.socialSettingsRepository != null
                && this.socialSettingsRepository.isLoaded(viewerId);

        if (ChatTranslationDiagnostics.debugEnabled() && !settingsLoaded) {
            java.util.logging.Logger.getLogger("SkyPvP-Proxy-Translation").info(
                    "[chat-translation] proxy viewer settings not loaded yet for "
                            + viewerId
                            + " (autoTranslate reads as false until DB preload completes)"
            );
        }

        return this.translationService.messageForViewer(
                plainMessage,
                senderLocale,
                this.localeService.locale(viewerId),
                autoTranslate,
                senderId,
                viewerId
        );
    }

    public String locale(UUID playerId) {
        return this.localeService.locale(playerId);
    }
}
