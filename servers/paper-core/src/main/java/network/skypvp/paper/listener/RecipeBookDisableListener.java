package network.skypvp.paper.listener;

import com.destroystokyo.paper.event.player.PlayerRecipeBookClickEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Disables vanilla recipe-book crafting clicks network-wide.
 *
 * <p>The button itself is client-side and is hidden via transparent sprites in the forced resource pack
 * ({@code textures/gui/sprites/recipe_book/button*.png}). This listener stops accidental invisible clicks from
 * moving items into the crafting grid.</p>
 */
public final class RecipeBookDisableListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRecipeBookClick(PlayerRecipeBookClickEvent event) {
        event.setCancelled(true);
    }
}
