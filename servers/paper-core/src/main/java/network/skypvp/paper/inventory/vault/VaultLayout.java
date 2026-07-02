package network.skypvp.paper.inventory.vault;

import java.util.HashSet;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.paper.gui.GuiButtonLibrary;
import network.skypvp.paper.gui.GuiItems;
import network.skypvp.paper.gui.GuiTextLibrary;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class VaultLayout {

    public static final int INVENTORY_SIZE = 54;
    public static final int SLOTS_PER_ROW = 8;
    public static final int SLOTS_PER_PAGE = 40;
    public static final int MAX_VAULT_SLOTS = 200;

    public static final int CLOSE_SLOT = 0;
    public static final int BACK_SLOT = 8;
    public static final int SCROLL_UP_SLOT = 17;
    public static final int SCROLL_TRACK_TOP_SLOT = 26;
    public static final int SCROLL_TRACK_MIDDLE_SLOT = 35;
    public static final int SCROLL_TRACK_LOWER_SLOT = 44;
    public static final int SCROLL_DOWN_SLOT = 53;

    public static final int[] CONTENT_SLOTS = buildContentSlots();
    public static final int[] HEADER_FILLER_SLOTS = {1, 2, 3, 4, 5, 6, 7};

    private static final Set<Integer> CONTENT_SLOT_SET = box(CONTENT_SLOTS);
    private static final Set<Integer> CONTROL_SLOT_SET = buildControlSlots();

    private VaultLayout() {
    }

    public static boolean isContentSlot(int slot) {
        return CONTENT_SLOT_SET.contains(slot);
    }

    public static boolean isControlSlot(int slot) {
        return CONTROL_SLOT_SET.contains(slot);
    }

    public static int contentSlotIndex(int slot) {
        for (int index = 0; index < CONTENT_SLOTS.length; index++) {
            if (CONTENT_SLOTS[index] == slot) {
                return index;
            }
        }
        return -1;
    }

    public static int vaultIndexForContentSlot(int scrollRow, int contentSlotIndex) {
        return scrollRow * SLOTS_PER_ROW + contentSlotIndex;
    }

    public static int maxScrollRow() {
        return (MAX_VAULT_SLOTS - SLOTS_PER_PAGE) / SLOTS_PER_ROW;
    }

    public static int scrollRowForVaultIndex(int vaultIndex) {
        return Math.min(Math.max(0, vaultIndex / SLOTS_PER_ROW), maxScrollRow());
    }

    public static int visibleSlotStart(int scrollRow) {
        return scrollRow * SLOTS_PER_ROW + 1;
    }

    public static int visibleSlotEnd(int scrollRow) {
        return Math.min(scrollRow * SLOTS_PER_ROW + SLOTS_PER_PAGE, MAX_VAULT_SLOTS);
    }

    public static int totalScrollPositions() {
        return maxScrollRow() + 1;
    }

    public static boolean isPlaceholderItem(ItemStack item) {
        return VaultDecorationTags.hasKind(item, VaultDecorationTags.KIND_LOCKED)
                || VaultDecorationTags.hasKind(item, VaultDecorationTags.KIND_UNAVAILABLE);
    }

    public static boolean isPurchasableSlotItem(ItemStack item) {
        return VaultDecorationTags.hasKind(item, VaultDecorationTags.KIND_PURCHASABLE);
    }

    public static boolean isDecorativeSlotItem(ItemStack item) {
        return VaultDecorationTags.isDecorative(item);
    }

    public static Inventory createInventory(VaultHolder holder) {
        int start = visibleSlotStart(holder.page());
        int end = visibleSlotEnd(holder.page());
        Component title = Component.text()
                .append(Component.text("Vault", NamedTextColor.GOLD))
                .append(Component.text(" • ", NamedTextColor.DARK_GRAY))
                .append(Component.text(start + "-" + end, NamedTextColor.YELLOW))
                .append(Component.text(" / " + MAX_VAULT_SLOTS, NamedTextColor.GRAY))
                .append(Component.text(" • ", NamedTextColor.DARK_GRAY))
                .append(Component.text(holder.unlockedRows() + " rows", NamedTextColor.AQUA))
                .build();
        Inventory inventory = Bukkit.createInventory(holder, INVENTORY_SIZE, title);
        holder.bindInventory(inventory);
        render(inventory, holder);
        return inventory;
    }

    public static void render(Inventory inventory, VaultHolder holder) {
        fillFrame(inventory);
        fillControls(inventory, holder);
        fillContent(inventory, holder);
    }

    public static void syncContentFromInventory(Inventory inventory, VaultHolder holder) {
        int page = holder.page();
        for (int index = 0; index < CONTENT_SLOTS.length; index++) {
            int vaultIndex = vaultIndexForContentSlot(page, index);
            if (!holder.isDepositableVaultIndex(vaultIndex)) {
                continue;
            }
            ItemStack current = inventory.getItem(CONTENT_SLOTS[index]);
            if (isDecorativeSlotItem(current)) {
                continue;
            }
            ItemStack item = cloneOrNull(current);
            if (item == null) {
                holder.remove(vaultIndex);
            } else {
                holder.put(vaultIndex, item);
            }
        }
    }

    private static void fillContent(Inventory inventory, VaultHolder holder) {
        int page = holder.page();
        int unlockedRows = holder.unlockedRows();
        for (int index = 0; index < CONTENT_SLOTS.length; index++) {
            int vaultIndex = vaultIndexForContentSlot(page, index);
            if (vaultIndex >= MAX_VAULT_SLOTS) {
                inventory.setItem(CONTENT_SLOTS[index], unavailableSlotItem());
                continue;
            }
            int row = VaultSlotAccess.rowForVaultIndex(vaultIndex);
            if (holder.isDepositableVaultIndex(vaultIndex)) {
                inventory.setItem(CONTENT_SLOTS[index], cloneOrNull(holder.get(vaultIndex)));
                continue;
            }
            if (VaultSlotAccess.isPurchasableRow(row, unlockedRows)) {
                inventory.setItem(CONTENT_SLOTS[index], purchasableRowSlotItem(row, VaultRowPricing.priceForRow(row)));
                continue;
            }
            inventory.setItem(CONTENT_SLOTS[index], lockedFutureRowItem(row, unlockedRows));
        }
    }

    private static void fillFrame(Inventory inventory) {
        ItemStack filler = fillerPane();
        inventory.setItem(CLOSE_SLOT, GuiButtonLibrary.close("Close vault"));
        inventory.setItem(BACK_SLOT, GuiButtonLibrary.back("Return to previous menu"));
        for (int slot : HEADER_FILLER_SLOTS) {
            inventory.setItem(slot, filler);
        }
    }

    private static void fillControls(Inventory inventory, VaultHolder holder) {
        int scrollRow = holder.page();
        int maxScrollRow = maxScrollRow();
        inventory.setItem(SCROLL_UP_SLOT, scrollButton("Scroll Up", scrollRow > 0));
        inventory.setItem(SCROLL_TRACK_TOP_SLOT, scrollTrack(scrollRow, maxScrollRow, 0));
        inventory.setItem(SCROLL_TRACK_MIDDLE_SLOT, scrollTrack(scrollRow, maxScrollRow, 1));
        inventory.setItem(SCROLL_TRACK_LOWER_SLOT, scrollTrack(scrollRow, maxScrollRow, 2));
        inventory.setItem(SCROLL_DOWN_SLOT, scrollButton("Scroll Down", scrollRow < maxScrollRow));
    }

    private static ItemStack scrollButton(String label, boolean enabled) {
        return GuiItems.named(
                Material.IRON_BARS,
                GuiTextLibrary.title(enabled ? "#FFD700" : "#888888", label),
                GuiTextLibrary.lore()
                        .plain(enabled ? "Scroll one row" : "No more rows in this direction")
                        .build()
        );
    }

    private static ItemStack scrollTrack(int scrollRow, int maxScrollRow, int segment) {
        int start = visibleSlotStart(scrollRow);
        int end = visibleSlotEnd(scrollRow);
        return GuiItems.named(
                Material.IRON_BARS,
                GuiTextLibrary.title("#888888", "Stash View"),
                GuiTextLibrary.lore()
                        .fact("Slots", start + "-" + end)
                        .fact("Row", (scrollRow + 1) + " / " + (maxScrollRow + 1))
                        .plain(segment == 1 ? "Use the chains above and below to scroll" : " ")
                        .build()
        );
    }

    private static ItemStack fillerPane() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack purchasableRowSlotItem(int rowIndex, long price) {
        int slotStart = rowIndex * SLOTS_PER_ROW + 1;
        int slotEnd = Math.min((rowIndex + 1) * SLOTS_PER_ROW, MAX_VAULT_SLOTS);
        return VaultDecorationTags.tag(
                GuiItems.named(
                        Material.GOLD_NUGGET,
                        GuiTextLibrary.title("#FFD700", "Unlock Row " + (rowIndex + 1)),
                        GuiTextLibrary.lore()
                                .plain("Expand your vault stash by one row")
                                .fact("Slots", slotStart + "-" + slotEnd)
                                .fact("Cost", VaultRowPricing.formatCoins(price) + " coins")
                                .footerStrong("<#55FF55>", "Click to purchase this row")
                                .build()
                ),
                VaultDecorationTags.KIND_PURCHASABLE
        );
    }

    private static ItemStack lockedFutureRowItem(int rowIndex, int unlockedRows) {
        long nextPrice = VaultRowPricing.priceForRow(unlockedRows);
        return VaultDecorationTags.tag(
                GuiItems.named(
                        Material.BARRIER,
                        GuiTextLibrary.title("#888888", "Locked Row"),
                        GuiTextLibrary.lore()
                                .fact("Row", (rowIndex + 1) + " / " + VaultSlotAccess.maxRows())
                                .plain("Unlock row " + (unlockedRows + 1) + " first")
                                .fact("Next unlock", VaultRowPricing.formatCoins(nextPrice) + " coins")
                                .footer("<#888888>", "Rows unlock one at a time")
                                .build()
                ),
                VaultDecorationTags.KIND_LOCKED
        );
    }

    private static ItemStack unavailableSlotItem() {
        return VaultDecorationTags.tag(
                GuiItems.named(
                        Material.BARRIER,
                        GuiTextLibrary.title("#555555", "Unavailable"),
                        GuiTextLibrary.lore()
                                .plain("This vault slot cannot be used")
                                .build()
                ),
                VaultDecorationTags.KIND_UNAVAILABLE
        );
    }

    private static int[] buildContentSlots() {
        return new int[] {
                9, 10, 11, 12, 13, 14, 15, 16,
                18, 19, 20, 21, 22, 23, 24, 25,
                27, 28, 29, 30, 31, 32, 33, 34,
                36, 37, 38, 39, 40, 41, 42, 43,
                45, 46, 47, 48, 49, 50, 51, 52
        };
    }

    private static Set<Integer> buildControlSlots() {
        Set<Integer> slots = new HashSet<>();
        slots.add(CLOSE_SLOT);
        slots.add(BACK_SLOT);
        slots.add(SCROLL_UP_SLOT);
        slots.add(SCROLL_TRACK_TOP_SLOT);
        slots.add(SCROLL_TRACK_MIDDLE_SLOT);
        slots.add(SCROLL_TRACK_LOWER_SLOT);
        slots.add(SCROLL_DOWN_SLOT);
        for (int slot : HEADER_FILLER_SLOTS) {
            slots.add(slot);
        }
        return slots;
    }

    private static Set<Integer> box(int[] values) {
        Set<Integer> set = new HashSet<>();
        for (int value : values) {
            set.add(value);
        }
        return set;
    }

    private static ItemStack cloneOrNull(ItemStack item) {
        return item == null || item.getType().isAir() ? null : item.clone();
    }
}
