package network.skypvp.extraction.text;

import net.kyori.adventure.text.Component;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.text.ServerTextBridge;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** English-only catalog lookups for the extraction game mode. */
public final class ExtractionTexts {
    private ExtractionTexts() {
    }

    private static ServerTextBridge bridge() {
        if (Bukkit.getPluginManager().getPlugin("SkyPvPCore") instanceof PaperCorePlugin core) {
            return core.serverTextBridge();
        }
        return null;
    }

    public static String locale(Player player) {
        ServerTextBridge text = bridge();
        if (player == null) {
            return text != null ? text.defaultLocale() : "en_us";
        }
        if (Bukkit.getPluginManager().getPlugin("SkyPvPCore") instanceof PaperCorePlugin core
                && core.playerLocaleService() != null) {
            return core.playerLocaleService().locale(player.getUniqueId());
        }
        return text != null ? text.normalizeLocale(player.getLocale()) : player.getLocale();
    }

    public static String catalogSource(String key) {
        ServerTextBridge text = bridge();
        return text != null ? text.catalogSource(key) : key;
    }

    public static String text(Player player, String catalogKey, Object... args) {
        return text(catalogKey, player == null ? defaultLocale() : locale(player), args);
    }

    public static String text(String catalogKey, String targetLocale, Object... args) {
        ServerTextBridge text = bridge();
        return text != null ? text.localized(catalogKey, targetLocale, args) : catalogKey;
    }

    public static Component miniMessage(Player player, String catalogKey, Object... args) {
        ServerTextBridge text = bridge();
        String targetLocale = player == null ? defaultLocale() : locale(player);
        return text != null
                ? text.miniMessageKey(catalogKey, targetLocale, args)
                : Component.text(catalogKey);
    }

    public static Component plain(Player player, String catalogKey, Object... args) {
        return Component.text(text(player, catalogKey, args));
    }

    public static Component miniMessageTemplate(String template, String locale) {
        ServerTextBridge text = bridge();
        return text != null ? text.miniMessageTemplate(template, locale) : Component.text(template);
    }

    public static String localizeTemplate(String template, String locale) {
        ServerTextBridge text = bridge();
        return text != null ? text.localizeTemplate(template, locale) : template;
    }

    public static String normalizeLocale(String locale) {
        ServerTextBridge text = bridge();
        return text != null ? text.normalizeLocale(locale) : locale;
    }

    public static String defaultLocale() {
        ServerTextBridge text = bridge();
        return text != null ? text.defaultLocale() : "en_us";
    }

    public static int scoreboardLineWidthPixels() {
        ServerTextBridge text = bridge();
        return text != null ? text.scoreboardLineWidthPixels() : 320;
    }

    public static int componentVisibleWidth(Component component) {
        ServerTextBridge text = bridge();
        return text != null ? text.componentVisibleWidth(component) : 0;
    }

    public static Component centerComponent(Component component, int lineWidthPixels) {
        ServerTextBridge text = bridge();
        return text != null ? text.centerComponent(component, lineWidthPixels) : component;
    }

    public static String toSmallCaps(String input) {
        ServerTextBridge text = bridge();
        return text != null ? text.toSmallCaps(input) : input;
    }
}
