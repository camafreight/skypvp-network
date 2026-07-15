package network.skypvp.extraction.listener;

import java.util.Objects;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.item.api.CustomItemService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Sweeps carried custom items through {@link CustomItemService#reconcile} so stacks whose
 * display material / item model changed in a newer build self-update:
 * <ul>
 *   <li>on join — the whole player inventory (covers items persisted in player data)</li>
 *   <li>on pickup — ground items (covers old stacks dropped before an art change)</li>
 * </ul>
 * Vault and material stash contents are reconciled in their own decode paths.
 */
public final class ExtractionItemReconcileListener implements Listener {

    private final PaperCorePlugin core;

    public ExtractionItemReconcileListener(PaperCorePlugin core) {
        this.core = Objects.requireNonNull(core, "core");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // One tick later on the player's region thread: the join inventory is fully loaded
        // and the sweep runs where inventory mutation is legal on Folia.
        core.platformScheduler().runOnPlayerLater(player, () -> sweepInventory(player), 1L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        CustomItemService items = core.customItemService();
        if (items == null) {
            return;
        }
        ItemStack current = event.getItem().getItemStack();
        ItemStack updated = items.reconcile(current);
        if (updated != null && updated != current) {
            event.getItem().setItemStack(updated);
        }
    }

    private void sweepInventory(Player player) {
        if (!player.isOnline()) {
            return;
        }
        CustomItemService items = core.customItemService();
        if (items == null) {
            return;
        }
        ItemStack[] contents = player.getInventory().getContents();
        boolean changed = false;
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            ItemStack updated = items.reconcile(stack);
            if (updated != null && updated != stack) {
                contents[slot] = updated;
                changed = true;
            }
        }
        if (changed) {
            player.getInventory().setContents(contents);
        }
    }
}
