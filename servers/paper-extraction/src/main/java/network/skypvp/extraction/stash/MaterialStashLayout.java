package network.skypvp.extraction.stash;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import network.skypvp.extraction.crafting.MaterialStashConstants;
import network.skypvp.paper.gui.GuiButtonLibrary;
import network.skypvp.paper.gui.GuiItems;
import network.skypvp.paper.gui.GuiTextLibrary;
import network.skypvp.shared.ServerTextUtil;
import network.skypvp.shared.currency.CurrencyFormat;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** 54-slot material stash layout (gear vault is separate). */
public final class MaterialStashLayout {

    public static final int INVENTORY_SIZE = 54;
    public static final int MAX_SLOTS = MaterialStashConstants.MAX_SLOTS;

    public static final int CLOSE_SLOT = 0;
    public static final int BACK_SLOT = 8;
    public static final int INFO_SLOT = 4;
    public static final int UPGRADE_SLOT = 7;

    private static final int[] CONTENT_SLOT_INDICES = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43,
            46, 47, 48, 49, 50, 51, 52, 53
    };

    private static final int[] CONTENT_SLOTS = CONTENT_SLOT_INDICES.clone();

    static {
        if (CONTENT_SLOTS.length != MAX_SLOTS) {
            throw new ExceptionInInitializerError(
                    "Material stash defines " + CONTENT_SLOTS.length + " content slots but MAX_SLOTS is " + MAX_SLOTS);
        }
        for (int slot : CONTENT_SLOTS) {
            if (isControlSlotUnchecked(slot)) {
                throw new ExceptionInInitializerError("Material stash content slot " + slot + " overlaps a control slot");
            }
        }
    }

    private static boolean isControlSlotUnchecked(int slot) {
        return slot == CLOSE_SLOT || slot == BACK_SLOT || slot == INFO_SLOT || slot == UPGRADE_SLOT;
    }
    private static final Set<Integer> CONTENT_SLOT_SET = Set.of(box(CONTENT_SLOTS));
    private static final Set<Integer> CONTROL_SLOT_SET = Set.of(CLOSE_SLOT, BACK_SLOT, INFO_SLOT, UPGRADE_SLOT);
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private MaterialStashLayout() {
    }

    private static Integer[] box(int[] values) {
        Integer[] boxed = new Integer[values.length];
        for (int i = 0; i < values.length; i++) {
            boxed[i] = values[i];
        }
        return boxed;
    }

    public static int[] contentSlotArray() {
        return CONTENT_SLOTS.clone();
    }

    public static int contentIndex(int rawSlot) {
        for (int i = 0; i < CONTENT_SLOTS.length; i++) {
            if (CONTENT_SLOTS[i] == rawSlot) {
                return i;
            }
        }
        return -1;
    }

    public static boolean isContentSlot(int slot) {
        return CONTENT_SLOT_SET.contains(slot);
    }

    public static boolean isControlSlot(int slot) {
        return CONTROL_SLOT_SET.contains(slot);
    }

    public static Inventory createInventory(MaterialStashHolder holder) {
        Component title = Component.text("Material Stash", NamedTextColor.GOLD);
        Inventory inventory = Bukkit.createInventory(holder, INVENTORY_SIZE, title);
        holder.bindInventory(inventory);
        render(inventory, holder);
        return inventory;
    }

    public static void render(Inventory inventory, MaterialStashHolder holder) {
        fillChrome(inventory);
        fillContent(inventory, holder);
        fillControls(inventory, holder);
    }

    private static void fillChrome(Inventory inventory) {
        ItemStack filler = fillerPane();
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            if (!isControlSlot(slot) && !isContentSlot(slot)) {
                inventory.setItem(slot, filler.clone());
            }
        }
    }

    private static void fillControls(Inventory inventory, MaterialStashHolder holder) {
        inventory.setItem(CLOSE_SLOT, GuiButtonLibrary.close("Close stash"));
        inventory.setItem(BACK_SLOT, GuiButtonLibrary.back("Return"));
        inventory.setItem(INFO_SLOT, infoItem(holder));
        inventory.setItem(UPGRADE_SLOT, upgradeItem(holder));
    }

    private static void fillContent(Inventory inventory, MaterialStashHolder holder) {
        for (int index = 0; index < CONTENT_SLOTS.length; index++) {
            if (!holder.isSlotUnlocked(index)) {
                inventory.setItem(CONTENT_SLOTS[index], lockedSlotItem(holder, index));
                continue;
            }
            ItemStack item = holder.get(index);
            inventory.setItem(CONTENT_SLOTS[index], item == null ? null : forDisplay(item));
        }
    }

    /** Display copy with stash amount lore; holder stores the real stack size. */
    static ItemStack forDisplay(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return null;
        }
        ItemStack display = stack.clone();
        if (display.getAmount() != 1) {
            display.setAmount(1);
        }
        var meta = display.getItemMeta();
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.removeIf(line -> CurrencyFormat.isQuantityLoreLine(PLAIN.serialize(line).trim()));
        lore.add(ServerTextUtil.miniMessageComponent(amountLoreLine(MaterialStashStackAmount.read(stack))));
        meta.lore(lore);
        display.setItemMeta(meta);
        return display;
    }

    static String amountLoreLine(int amount) {
        return "<aqua>x" + CurrencyFormat.formatQuantity(amount);
    }

    private static ItemStack fillerPane() {
        return GuiItems.named(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
    }

    static ItemStack infoItem(MaterialStashHolder holder) {
        int used = holder.usedCapacity();
        int max = holder.maxCapacity();
        int percent = holder.capacityPercent();
        List<String> lore = new ArrayList<>();
        lore.add("<yellow>How to deposit");
        lore.add("<gray>Shift-click a stack in your inventory.");
        lore.add("<gray>Click a slot with an item on cursor.");
        lore.add("");
        lore.add("<yellow>How to withdraw");
        lore.add("<gray>Shift-click a stash stack — fills inventory.");
        lore.add("<gray>Left-click — one stack (64) to cursor.");
        lore.add("<gray>Right-click — half a stack to cursor.");
        lore.add("<gray>Amounts show as <aqua>x<count> <gray>on each material.");
        lore.add("");
        lore.add("<white>Current Tier: <gold>" + holder.tierName());
        lore.add(GuiTextLibrary.progressBar(used, max, 20, "<aqua>", "<dark_gray>"));
        lore.add("<aqua>" + percent + "% <gray>(" + CurrencyFormat.formatCompact(used) + "/" + CurrencyFormat.formatCompact(max) + ")");
        lore.add("<dark_gray>Separate from your gear vault.");
        return GuiItems.named(Material.BUNDLE, "<gold>Material Stash", lore);
    }

    static ItemStack upgradeItem(MaterialStashHolder holder) {
        MaterialStashTierDefinition next = holder.nextTier();
        if (next == null) {
            return GuiItems.named(Material.NETHER_STAR, "<gold>Max Tier", List.of(
                    "<gray>Your stash is fully upgraded.",
                    "<dark_gray>" + holder.tierName()
            ));
        }
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Upgrade to <gold>" + next.name());
        lore.add("<gray>+" + (next.maxSlots() - holder.unlockedSlots()) + " slots, "
                + CurrencyFormat.formatCompact(next.maxCapacity()) + " capacity");
        lore.add("");
        lore.add(priceLine(next.upgradeCoins(), next.upgradeGold()));
        lore.add("");
        lore.add("<yellow>Click to purchase upgrade");
        return GuiItems.named(Material.EMERALD, "<green>Upgrade Stash", lore);
    }

    static ItemStack lockedSlotItem(MaterialStashHolder holder, int index) {
        MaterialStashTierDefinition next = holder.nextTier();
        List<String> lore = new ArrayList<>();
        lore.add("<red>Locked stash slot");
        if (next != null) {
            lore.add("<gray>Upgrade to <gold>" + next.name() + " <gray>to unlock.");
        } else {
            lore.add("<gray>Maximum tier reached.");
        }
        return GuiItems.named(Material.GRAY_STAINED_GLASS_PANE, "<dark_gray>Locked #" + (index + 1), lore);
    }

    static String priceLine(long coins, long gold) {
        String coinPart = coins > 0L ? "<gold>" + CurrencyFormat.formatCoins(coins) + " coins" : "";
        String goldPart = gold > 0L ? "<yellow>" + CurrencyFormat.formatGold(gold) + " gold" : "";
        if (!coinPart.isEmpty() && !goldPart.isEmpty()) {
            return "<gray>Cost: " + coinPart + " <dark_gray>| " + goldPart;
        }
        if (!coinPart.isEmpty()) {
            return "<gray>Cost: " + coinPart;
        }
        if (!goldPart.isEmpty()) {
            return "<gray>Cost: " + goldPart;
        }
        return "<gray>Cost: <green>Free";
    }

    public static boolean isDecorative(ItemStack item) {
        if (item == null || item.getType().isAir() || item.getType() != Material.GRAY_STAINED_GLASS_PANE) {
            return false;
        }
        var meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) {
            return false;
        }
        String plain = PLAIN.serialize(meta.displayName());
        return plain.contains("Locked #");
    }
}
