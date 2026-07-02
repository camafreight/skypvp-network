package network.skypvp.paper.text;

import net.kyori.adventure.text.Component;
import network.skypvp.shared.ServerTextCatalog;
import network.skypvp.shared.ServerTextUtil;
import network.skypvp.shared.chat.ClientLocaleUtil;

/**
 * Paper-core facade for {@link ServerTextCatalog} / {@link ServerTextUtil} so mode plugins
 * (separate classloaders) do not reference network-core classes directly.
 */
public final class ServerTextBridge {

    public void registerCatalogPack(String packName, ClassLoader resourceLoader, java.util.logging.Logger logger) {
        ServerTextCatalog.registerPack(packName, resourceLoader, logger);
    }

    public String defaultLocale() {
        return ClientLocaleUtil.defaultMinecraftLocale();
    }

    public String normalizeLocale(String locale) {
        return ClientLocaleUtil.normalizeMinecraftLocale(locale);
    }

    public String catalogSource(String key) {
        return ServerTextCatalog.source(key);
    }

    public String localized(String key, String locale, Object... args) {
        return ServerTextUtil.localized(key, locale, args);
    }

    public String localizeTemplate(String template, String locale) {
        return ServerTextUtil.localizeTemplate(template, locale);
    }

    public Component miniMessageKey(String key, String locale, Object... args) {
        return ServerTextUtil.miniMessageKey(key, locale, args);
    }

    public Component miniMessageTemplate(String template, String locale) {
        return ServerTextUtil.miniMessageComponent(template, locale);
    }

    public Component miniMessageTemplate(String template) {
        return ServerTextUtil.miniMessageComponent(template);
    }

    public int scoreboardLineWidthPixels() {
        return ServerTextUtil.SCOREBOARD_LINE_WIDTH_PIXELS;
    }

    public int componentVisibleWidth(Component component) {
        return ServerTextUtil.componentVisibleWidth(component);
    }

    public Component centerComponent(Component component, int lineWidthPixels) {
        return ServerTextUtil.centerComponent(component, lineWidthPixels);
    }

    public String toSmallCaps(String input) {
        return ServerTextUtil.toSmallCaps(input);
    }
}
