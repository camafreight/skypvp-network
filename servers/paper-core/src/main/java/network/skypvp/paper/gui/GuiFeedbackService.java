package network.skypvp.paper.gui;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Event-driven temporary slot feedback for SkyPvP GUI menus.
 * Syncs on inventory open/click and clears when the scoped menu closes.
 */
public final class GuiFeedbackService {

    public static final long DEFAULT_DURATION_MS = 5000L;

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final Map<UUID, Map<String, SlotBinding>> bindingsByPlayer = new ConcurrentHashMap<>();

    public void presentSlot(Player player, String menuTitleEquals, int slot, GuiFeedback feedback, ItemStack restoreItem) {
        if (player == null || menuTitleEquals == null || menuTitleEquals.isBlank() || feedback == null) {
            return;
        }
        if (slot < 0) {
            return;
        }
        ItemStack restore = restoreItem == null ? null : restoreItem.clone();
        String key = bindingKey(menuTitleEquals, slot);
        this.bindingsByPlayer
                .computeIfAbsent(player.getUniqueId(), ignored -> new ConcurrentHashMap<>())
                .put(key, new SlotBinding(menuTitleEquals, slot, feedback, restore));
        this.sync(player);
    }

    public void clear(Player player) {
        if (player == null) {
            return;
        }
        this.bindingsByPlayer.remove(player.getUniqueId());
    }

    public void onInventoryClosed(Player player, Component closedTitle) {
        if (player == null) {
            return;
        }
        String title = this.plainTitle(closedTitle);
        if (title.isBlank()) {
            return;
        }
        Map<String, SlotBinding> bindings = this.bindingsByPlayer.get(player.getUniqueId());
        if (bindings == null || bindings.isEmpty()) {
            return;
        }
        bindings.entrySet().removeIf(entry -> title.equals(entry.getValue().menuTitleEquals()));
        if (bindings.isEmpty()) {
            this.bindingsByPlayer.remove(player.getUniqueId());
        }
    }

    public void sync(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Map<String, SlotBinding> bindings = this.bindingsByPlayer.get(player.getUniqueId());
        if (bindings == null || bindings.isEmpty()) {
            return;
        }
        String openTitle = this.plainTitle(player.getOpenInventory().title());
        if (openTitle.isBlank()) {
            return;
        }
        Inventory inventory = player.getOpenInventory().getTopInventory();
        boolean changed = false;
        Iterator<Map.Entry<String, SlotBinding>> iterator = bindings.entrySet().iterator();
        while (iterator.hasNext()) {
            SlotBinding binding = iterator.next().getValue();
            if (!openTitle.equals(binding.menuTitleEquals())) {
                continue;
            }
            if (binding.feedback().expired()) {
                if (binding.restoreItem() != null) {
                    inventory.setItem(binding.slot(), binding.restoreItem().clone());
                    changed = true;
                }
                iterator.remove();
                continue;
            }
            inventory.setItem(binding.slot(), GuiButtonLibrary.feedbackAction(binding.feedback()));
            changed = true;
        }
        if (bindings.isEmpty()) {
            this.bindingsByPlayer.remove(player.getUniqueId());
        }
        if (changed) {
            player.updateInventory();
        }
    }

    private static String bindingKey(String menuTitleEquals, int slot) {
        return menuTitleEquals + "#" + slot;
    }

    private String plainTitle(Component title) {
        return title == null ? "" : PLAIN.serialize(title);
    }

    private record SlotBinding(String menuTitleEquals, int slot, GuiFeedback feedback, ItemStack restoreItem) {
        private SlotBinding {
            menuTitleEquals = Objects.requireNonNull(menuTitleEquals, "menuTitleEquals");
            feedback = Objects.requireNonNull(feedback, "feedback");
        }
    }
}
