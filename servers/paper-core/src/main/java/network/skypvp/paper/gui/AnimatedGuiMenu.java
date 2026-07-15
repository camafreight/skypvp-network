package network.skypvp.paper.gui;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * A {@link GuiMenu} whose background/chrome slots can cycle through {@link GuiAnimation} frames.
 * Interactive slots are still owned by the menu's button map and are re-applied after each frame.
 */
public interface AnimatedGuiMenu extends GuiMenu {

    GuiAnimation backgroundAnimation();

    /** Re-applies one animation frame without clearing interactive button slots. */
    void renderAnimatedFrame(Player viewer, Inventory inventory, int frameIndex);

    default boolean hasAnimatedButtons() {
        return false;
    }

    default void renderAnimatedButtons(Player viewer, Inventory inventory, long tickMillis) {
    }
}
