package network.skypvp.paper.gamemode.api;

import org.bukkit.entity.Player;

/**
 * Optional lobby-mode placeholder bridge registered by the lobby plugin.
 */
public interface LobbyPlaceholderBridge {

    /**
     * @return resolved value or empty string when unknown
     */
    String resolve(Player player, String key);
}
