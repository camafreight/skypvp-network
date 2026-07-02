package network.skypvp.shared.chat;

import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Opt-in translation tracing via {@code SPVP_CHAT_TRANSLATION_DEBUG=true}. */
public final class ChatTranslationDiagnostics {
    public enum Outcome {
        SKIPPED_SELF,
        SKIPPED_AUTO_TRANSLATE_OFF,
        SKIPPED_TRANSLATOR_DISABLED,
        SKIPPED_SAME_LANGUAGE,
        TRANSLATED,
        FALLBACK_API_ERROR,
        FALLBACK_EMPTY
    }

    private static final boolean DEBUG = Boolean.parseBoolean(
            System.getenv().getOrDefault("SPVP_CHAT_TRANSLATION_DEBUG", "false").trim()
    );

    private ChatTranslationDiagnostics() {
    }

    public static boolean debugEnabled() {
        return DEBUG;
    }

    public static void logStartup(Logger logger, boolean pluginEnabled, ChatTranslator translator) {
        logger.info("[chat-translation] pluginEnabled="
                + pluginEnabled
                + " provider="
                + translator.providerId()
                + " clientReady="
                + translator.enabled()
                + " debug="
                + DEBUG
                + " "
                + translator.configuredSummary());
    }

    public static void logDecision(
            Logger logger,
            String surface,
            UUID senderId,
            UUID viewerId,
            String senderLocale,
            String viewerLocale,
            boolean autoTranslate,
            boolean translatorEnabled,
            Outcome outcome,
            String messagePreview
    ) {
        boolean alwaysLog = outcome == Outcome.FALLBACK_API_ERROR || outcome == Outcome.FALLBACK_EMPTY;
        if (!DEBUG && !alwaysLog) {
            return;
        }

        Level level = alwaysLog ? Level.WARNING : Level.INFO;
        logger.log(
                level,
                "[chat-translation] surface={0} outcome={1} sender={2} viewer={3} senderLocale={4} viewerLocale={5} "
                        + "autoTranslate={6} translatorEnabled={7} fromLang={8} toLang={9} preview=\"{10}\"",
                new Object[] {
                        surface,
                        outcome.name(),
                        shortId(senderId),
                        shortId(viewerId),
                        normalizeLocale(senderLocale),
                        normalizeLocale(viewerLocale),
                        autoTranslate,
                        translatorEnabled,
                        ClientLocaleUtil.toAzureLanguage(senderLocale),
                        ClientLocaleUtil.toAzureLanguage(viewerLocale),
                        preview(messagePreview)
                }
        );
    }

    public static void logApiFailure(Logger logger, String provider, int statusCode, String bodySnippet) {
        logger.warning("[chat-translation] " + provider + " HTTP " + statusCode + ": " + truncate(bodySnippet, 200));
    }

    public static String sanitizeEnv(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        int hash = trimmed.indexOf('#');
        if (hash >= 0) {
            trimmed = trimmed.substring(0, hash).trim();
        }
        return trimmed;
    }

    private static String normalizeLocale(String locale) {
        return ClientLocaleUtil.normalizeMinecraftLocale(locale);
    }

    private static String preview(String message) {
        if (message == null) {
            return "";
        }
        String singleLine = message.replace('\n', ' ').trim();
        return truncate(singleLine, 48);
    }

    private static String shortId(UUID id) {
        if (id == null) {
            return "-";
        }
        String raw = id.toString();
        return raw.substring(0, 8);
    }

    private static String maskEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return "(unset)";
        }
        return truncate(endpoint.trim(), 72);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "...";
    }
}
