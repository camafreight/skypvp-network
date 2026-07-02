package network.skypvp.extraction.gameplay;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;

public final class BreachPlayerVitality {

    private BreachPlayerVitality() {
    }

    public static void replenish(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        player.setFireTicks(0);
        player.setFallDistance(0.0F);
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(maxHealth != null ? maxHealth.getValue() : 20.0);
        player.setFoodLevel(20);
        player.setSaturation(20.0F);
    }
}
