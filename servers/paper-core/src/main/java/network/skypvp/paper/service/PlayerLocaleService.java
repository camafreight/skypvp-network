package network.skypvp.paper.service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.shared.chat.ClientLocaleUtil;
import org.bukkit.entity.Player;

/**
 * Tracks each player's Minecraft client locale from join + live Client Settings packets.
 */
public final class PlayerLocaleService {
    private final ConcurrentHashMap<UUID, String> locales = new ConcurrentHashMap<>();

    public void capture(Player player) {
        if (player == null) {
            return;
        }
        this.locales.put(player.getUniqueId(), ClientLocaleUtil.normalizeMinecraftLocale(player.getLocale()));
    }

    public void update(UUID playerId, String locale) {
        if (playerId == null) {
            return;
        }
        this.locales.put(playerId, ClientLocaleUtil.normalizeMinecraftLocale(locale));
    }

    public String locale(UUID playerId) {
        if (playerId == null) {
            return ClientLocaleUtil.defaultMinecraftLocale();
        }
        return this.locales.getOrDefault(playerId, ClientLocaleUtil.defaultMinecraftLocale());
    }

    public void evict(UUID playerId) {
        if (playerId != null) {
            this.locales.remove(playerId);
        }
    }
}
