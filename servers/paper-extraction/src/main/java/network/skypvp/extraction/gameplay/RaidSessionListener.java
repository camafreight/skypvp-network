package network.skypvp.extraction.gameplay;

import network.skypvp.extraction.engine.BreachInstance;
import org.bukkit.entity.Player;

/** Hooks for per-raid session systems (scrapper caps, future raid-only buffs). */
public interface RaidSessionListener {

    default void onPlayerRaidSessionStarted(Player player) {
    }

    default void onPlayerRaidSessionEnded(Player player) {
    }

    default void onInstanceReset(BreachInstance instance) {
    }
}
