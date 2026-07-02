package network.skypvp.shared.chat;

import java.util.Locale;
import java.util.Map;

/**
 * Maps Minecraft client locale codes (e.g. {@code en_us}, {@code zh_cn}) to Azure Translator
 * BCP-47 codes (e.g. {@code en}, {@code zh-Hans}, {@code pt-pt}, {@code nb}).
 *
 * <p>Minecraft uses lowercase {@code language_country} from the Client Settings packet /
 * {@link org.bukkit.entity.Player#getLocale()}. Azure expects BCP-47 as documented at
 * <a href="https://learn.microsoft.com/en-us/azure/ai-services/translator/language-support">language-support</a>.
 */
public final class ClientLocaleUtil {
    private static final String DEFAULT_MINECRAFT_LOCALE = "en_us";

    /**
     * Full Minecraft locale keys that do not follow the generic {@code lang_REGION} rule.
     * Keys are normalized via {@link #normalizeMinecraftLocale(String)}.
     */
    private static final Map<String, String> MINECRAFT_TO_AZURE = Map.ofEntries(
            // Chinese — Azure uses script subtags, not region
            Map.entry("zh_cn", "zh-Hans"),
            Map.entry("zh_tw", "zh-Hant"),
            Map.entry("zh_hk", "zh-Hant"),
            Map.entry("zh_sg", "zh-Hans"),
            // Portuguese — Azure uses pt (Brazil) and pt-pt (Portugal)
            Map.entry("pt_br", "pt"),
            Map.entry("pt_pt", "pt-pt"),
            // Norwegian — Minecraft no_no; Azure uses nb (Bokmål)
            Map.entry("no_no", "nb"),
            Map.entry("nb_no", "nb"),
            Map.entry("nn_no", "nn"),
            // Filipino / legacy Tagalog
            Map.entry("fil_ph", "fil"),
            Map.entry("tl_ph", "fil"),
            // Serbian — Azure distinguishes script
            Map.entry("sr_sp", "sr-Cyrl"),
            Map.entry("sr_cs", "sr-Latn"),
            // Legacy / special ISO codes
            Map.entry("in_id", "id"),
            Map.entry("iw_il", "he"),
            // Japanese / Korean — Azure uses language-only codes
            Map.entry("ja_jp", "ja"),
            Map.entry("ko_kr", "ko")
    );

    private ClientLocaleUtil() {
    }

    public static String defaultMinecraftLocale() {
        return DEFAULT_MINECRAFT_LOCALE;
    }

    /** Normalizes to Minecraft-style {@code language_country} (lowercase, underscores). */
    public static String normalizeMinecraftLocale(String locale) {
        if (locale == null || locale.isBlank()) {
            return DEFAULT_MINECRAFT_LOCALE;
        }
        String trimmed = locale.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        // Java Locale.toString() on Velocity may yield language-only tags (e.g. "en")
        if (!trimmed.contains("_") && trimmed.length() == 2) {
            return trimmed + "_" + defaultRegionForLanguage(trimmed);
        }
        return trimmed;
    }

    /**
     * Converts a Minecraft or Java locale string to an Azure Translator language code.
     */
    public static String toAzureLanguage(String minecraftOrAzureLocale) {
        String normalized = normalizeMinecraftLocale(minecraftOrAzureLocale);
        String override = MINECRAFT_TO_AZURE.get(normalized);
        if (override != null) {
            return override;
        }

        int underscore = normalized.indexOf('_');
        String language = underscore < 0 ? normalized : normalized.substring(0, underscore);
        String region = underscore < 0 ? "" : normalized.substring(underscore + 1);

        // Chinese without explicit country
        if ("zh".equals(language)) {
            return switch (region) {
                case "tw", "hk", "mo" -> "zh-Hant";
                case "cn", "sg", "" -> "zh-Hans";
                default -> "zh-Hans";
            };
        }

        // Portuguese
        if ("pt".equals(language)) {
            return "br".equals(region) || region.isBlank() ? "pt" : "pt-pt";
        }

        // Norwegian
        if ("no".equals(language) || "nb".equals(language)) {
            return "nb";
        }
        if ("nn".equals(language)) {
            return "nn";
        }

        // Filipino
        if ("fil".equals(language) || "tl".equals(language)) {
            return "fil";
        }

        // Indonesian (in = legacy ISO 639-1)
        if ("in".equals(language) || "id".equals(language)) {
            return "id";
        }

        // Hebrew (iw = legacy ISO 639-1)
        if ("iw".equals(language) || "he".equals(language)) {
            return "he";
        }

        // Japanese / Korean — Azure accepts language-only
        if ("ja".equals(language) || "ko".equals(language)) {
            return language;
        }

        // Most languages: use language only when region is absent; otherwise lang-REGION
        if (region.isBlank()) {
            return language;
        }
        return language + "-" + region.toUpperCase(Locale.ROOT);
    }

    /**
     * Whether two players need translation — compares language families, not regional variants
     * (e.g. {@code en_us} and {@code en_gb} are both English and will not translate).
     */
    public static boolean sameLanguage(String left, String right) {
        return translationFamily(left).equalsIgnoreCase(translationFamily(right));
    }

    /**
     * Groups locales for "is this the same language?" checks.
     * Script-specific (Chinese) and Portuguese variants are kept distinct.
     */
    public static String translationFamily(String minecraftOrAzureLocale) {
        String azure = toAzureLanguage(minecraftOrAzureLocale);
        if (azure.startsWith("zh-")) {
            return azure;
        }
        if ("pt-pt".equalsIgnoreCase(azure)) {
            return "pt-pt";
        }
        if ("pt".equalsIgnoreCase(azure)) {
            return "pt";
        }
        int dash = azure.indexOf('-');
        return dash < 0 ? azure : azure.substring(0, dash);
    }

    /** Human-readable language name for LLM translation prompts. */
    public static String languageLabel(String minecraftOrAzureLocale) {
        String azure = toAzureLanguage(minecraftOrAzureLocale);
        String family = translationFamily(minecraftOrAzureLocale);
        String label = switch (family.toLowerCase(Locale.ROOT)) {
            case "en" -> "English";
            case "es" -> "Spanish";
            case "fr" -> "French";
            case "de" -> "German";
            case "pt" -> "Portuguese (Brazil)";
            case "pt-pt" -> "Portuguese (Portugal)";
            case "it" -> "Italian";
            case "nl" -> "Dutch";
            case "pl" -> "Polish";
            case "ru" -> "Russian";
            case "uk" -> "Ukrainian";
            case "ja" -> "Japanese";
            case "ko" -> "Korean";
            case "zh-hans" -> "Chinese (Simplified)";
            case "zh-hant" -> "Chinese (Traditional)";
            case "ar" -> "Arabic";
            case "he" -> "Hebrew";
            case "hi" -> "Hindi";
            case "th" -> "Thai";
            case "vi" -> "Vietnamese";
            case "tr" -> "Turkish";
            case "sv" -> "Swedish";
            case "da" -> "Danish";
            case "fi" -> "Finnish";
            case "nb", "nn", "no" -> "Norwegian";
            case "cs" -> "Czech";
            case "sk" -> "Slovak";
            case "hu" -> "Hungarian";
            case "ro" -> "Romanian";
            case "id" -> "Indonesian";
            case "fil" -> "Filipino";
            default -> family;
        };
        if (label.equals(family) && !azure.equalsIgnoreCase(family)) {
            return azure;
        }
        return label + " (" + azure + ")";
    }

    private static String defaultRegionForLanguage(String language) {
        return switch (language) {
            case "en" -> "us";
            case "de" -> "de";
            case "fr" -> "fr";
            case "es" -> "es";
            case "pt" -> "br";
            case "zh" -> "cn";
            case "ja" -> "jp";
            case "ko" -> "kr";
            case "ru" -> "ru";
            case "no", "nb" -> "no";
            case "nl" -> "nl";
            case "pl" -> "pl";
            case "it" -> "it";
            case "sv" -> "se";
            case "da" -> "dk";
            case "fi" -> "fi";
            case "tr" -> "tr";
            case "uk" -> "ua";
            case "cs" -> "cz";
            case "sk" -> "sk";
            case "hu" -> "hu";
            case "ro" -> "ro";
            case "ar" -> "sa";
            case "he", "iw" -> "il";
            case "hi" -> "in";
            case "th" -> "th";
            case "vi" -> "vn";
            case "id", "in" -> "id";
            case "fil", "tl" -> "ph";
            default -> "us";
        };
    }
}
