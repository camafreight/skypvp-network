package network.skypvp.proxy.service;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.shared.chat.ClientLocaleUtil;

/**
 * Tracks each connected player's Minecraft client locale on the proxy.
 */
public final class PlayerLocaleService {
    private final ConcurrentHashMap<UUID, String> locales = new ConcurrentHashMap<>();

    public void capture(UUID playerId, Locale locale) {
        if (playerId == null) {
            return;
        }
        String value = locale == null ? ClientLocaleUtil.defaultMinecraftLocale() : locale.toString();
        this.locales.put(playerId, ClientLocaleUtil.normalizeMinecraftLocale(value));
    }

    public void evict(UUID playerId) {
        if (playerId != null) {
            this.locales.remove(playerId);
        }
    }

    public String locale(UUID playerId) {
        if (playerId == null) {
            return ClientLocaleUtil.defaultMinecraftLocale();
        }
        return this.locales.getOrDefault(playerId, ClientLocaleUtil.defaultMinecraftLocale());
    }
}
