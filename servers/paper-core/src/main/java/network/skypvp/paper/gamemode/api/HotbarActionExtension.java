package network.skypvp.paper.gamemode.api;

import org.bukkit.entity.Player;

/**
 * Optional mode-specific handlers for tagged core hotbar actions.
 */
public interface HotbarActionExtension {

    /**
     * @return true if the action was handled and default processing should stop
     */
    boolean tryHandle(Player player, String action);
}
