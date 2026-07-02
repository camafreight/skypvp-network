package network.skypvp.paper.gamemode.api;

import org.bukkit.entity.Player;

/**
 * Optional mode-specific restrictions for hub submenu entries (vault, loadouts, party, etc.).
 */
public interface NetworkMenuAccess {

    /**
     * @param submenuKey one of PARTY, SOCIALS, LOADOUTS, VAULT, REWARDS
     */
    default boolean isHubSubmenuLocked(Player player, String submenuKey) {
        return false;
    }

    default String hubSubmenuLockReason(Player player) {
        return "Unavailable during a raid.";
    }
}
