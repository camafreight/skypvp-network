package network.skypvp.shared.chat;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/**
 * Per-viewer chat translation: only translates when the viewer opted in and speaks a different language.
 */
public final class ChatTranslationService {
    private final ChatTranslator translator;
    private final Logger logger;
    private final String surface;

    public ChatTranslationService(ChatTranslator translator) {
        this(translator, null, "shared");
    }

    public ChatTranslationService(ChatTranslator translator, Logger logger, String surface) {
        this.translator = Objects.requireNonNull(translator, "translator");
        this.logger = logger;
        this.surface = surface == null || surface.isBlank() ? "shared" : surface;
    }

    public boolean enabled() {
        return this.translator.enabled();
    }

    public String messageForViewer(
            String plainMessage,
            String senderLocale,
            String viewerLocale,
            boolean viewerAutoTranslateEnabled
    ) {
        return messageForViewer(plainMessage, senderLocale, viewerLocale, viewerAutoTranslateEnabled, null, null);
    }

    public String messageForViewer(
            String plainMessage,
            String senderLocale,
            String viewerLocale,
            boolean viewerAutoTranslateEnabled,
            UUID senderId,
            UUID viewerId
    ) {
        if (!viewerAutoTranslateEnabled) {
            logDecision(
                    senderId,
                    viewerId,
                    senderLocale,
                    viewerLocale,
                    false,
                    ChatTranslationDiagnostics.Outcome.SKIPPED_AUTO_TRANSLATE_OFF,
                    plainMessage
            );
            return plainMessage;
        }
        if (!this.translator.enabled()) {
            logDecision(
                    senderId,
                    viewerId,
                    senderLocale,
                    viewerLocale,
                    true,
                    ChatTranslationDiagnostics.Outcome.SKIPPED_TRANSLATOR_DISABLED,
                    plainMessage
            );
            return plainMessage;
        }
        if (ClientLocaleUtil.sameLanguage(senderLocale, viewerLocale)) {
            logDecision(
                    senderId,
                    viewerId,
                    senderLocale,
                    viewerLocale,
                    true,
                    ChatTranslationDiagnostics.Outcome.SKIPPED_SAME_LANGUAGE,
                    plainMessage
            );
            return plainMessage;
        }

        String translated = this.translator.translate(plainMessage, senderLocale, viewerLocale);
        if (translated == null || translated.isBlank() || translated.equals(plainMessage)) {
            logDecision(
                    senderId,
                    viewerId,
                    senderLocale,
                    viewerLocale,
                    true,
                    ChatTranslationDiagnostics.Outcome.FALLBACK_EMPTY,
                    plainMessage
            );
            return plainMessage;
        }

        logDecision(
                senderId,
                viewerId,
                senderLocale,
                viewerLocale,
                true,
                ChatTranslationDiagnostics.Outcome.TRANSLATED,
                plainMessage
        );
        return translated;
    }

    public String messageForViewer(
            String plainMessage,
            String senderLocale,
            String viewerLocale,
            BooleanSupplier viewerAutoTranslateEnabled
    ) {
        return messageForViewer(
                plainMessage,
                senderLocale,
                viewerLocale,
                viewerAutoTranslateEnabled != null && viewerAutoTranslateEnabled.getAsBoolean()
        );
    }

    private void logDecision(
            UUID senderId,
            UUID viewerId,
            String senderLocale,
            String viewerLocale,
            boolean autoTranslate,
            ChatTranslationDiagnostics.Outcome outcome,
            String message
    ) {
        if (this.logger == null) {
            return;
        }
        ChatTranslationDiagnostics.logDecision(
                this.logger,
                this.surface,
                senderId,
                viewerId,
                senderLocale,
                viewerLocale,
                autoTranslate,
                this.translator.enabled(),
                outcome,
                message
        );
    }
}
