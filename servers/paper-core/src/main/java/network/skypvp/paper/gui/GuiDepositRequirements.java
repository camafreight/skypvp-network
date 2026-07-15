package network.skypvp.paper.gui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import network.skypvp.paper.item.CustomItemStacks;
import network.skypvp.paper.item.api.CustomItemService;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Canonical deposit-slot requirement checks for workbench GUIs. Every menu that accepts player-placed custom items
 * should validate through {@link #accepts(CustomItemService, ItemStack, GuiCustomItemRequirement)} and succeed only
 * when {@link #met(CustomItemService, Inventory, List)} returns true.
 */
public final class GuiDepositRequirements {

    private GuiDepositRequirements() {
    }

    public static boolean accepts(CustomItemService service, ItemStack stack, GuiCustomItemRequirement requirement) {
        return CustomItemStacks.matches(service, stack, requirement);
    }

    public static int depositedAmount(CustomItemService service, ItemStack stack, GuiCustomItemRequirement requirement) {
        return CustomItemStacks.depositedAmount(service, stack, requirement);
    }

    public static boolean met(CustomItemService service, Inventory inventory, List<GuiDepositSlot> slots) {
        Objects.requireNonNull(inventory, "inventory");
        if (service == null || slots == null) {
            return false;
        }
        for (GuiDepositSlot slot : slots) {
            ItemStack deposited = inventory.getItem(slot.slot());
            int have = depositedAmount(service, deposited, slot.requirement());
            if (have < slot.requirement().minimumAmount()) {
                return false;
            }
        }
        return true;
    }

    public static Map<Integer, ItemStack> snapshot(Inventory inventory, List<GuiDepositSlot> slots) {
        Map<Integer, ItemStack> snap = new LinkedHashMap<>();
        if (inventory == null || slots == null) {
            return snap;
        }
        for (GuiDepositSlot slot : slots) {
            ItemStack item = inventory.getItem(slot.slot());
            if (item != null && !item.getType().isAir()) {
                snap.put(slot.slot(), item.clone());
            }
        }
        return snap;
    }

    public static void restore(Inventory inventory, Map<Integer, ItemStack> snapshot) {
        if (inventory == null || snapshot == null) {
            return;
        }
        for (Map.Entry<Integer, ItemStack> entry : snapshot.entrySet()) {
            inventory.setItem(entry.getKey(), entry.getValue() == null ? null : entry.getValue().clone());
        }
    }

    /** Consumes exact required amounts from deposit slots (caller must verify {@link #met} first). */
    public static void consume(CustomItemService service, Inventory inventory, List<GuiDepositSlot> slots) {
        if (service == null || inventory == null || slots == null) {
            return;
        }
        for (GuiDepositSlot slot : slots) {
            ItemStack deposited = inventory.getItem(slot.slot());
            if (!accepts(service, deposited, slot.requirement())) {
                inventory.setItem(slot.slot(), null);
                continue;
            }
            int required = slot.requirement().minimumAmount();
            int remaining = deposited.getAmount() - required;
            if (remaining <= 0) {
                inventory.setItem(slot.slot(), null);
            } else {
                ItemStack updated = deposited.clone();
                updated.setAmount(remaining);
                inventory.setItem(slot.slot(), updated);
            }
        }
    }

    public static void returnDeposits(
            CustomItemService service,
            Player player,
            Inventory inventory,
            List<GuiDepositSlot> slots
    ) {
        if (service == null || player == null || inventory == null || slots == null) {
            return;
        }
        for (GuiDepositSlot slot : slots) {
            ItemStack deposited = inventory.getItem(slot.slot());
            if (!accepts(service, deposited, slot.requirement())) {
                continue;
            }
            inventory.setItem(slot.slot(), null);
            player.getInventory().addItem(deposited).values()
                    .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        }
    }
}
