package network.skypvp.extraction.backpack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.extraction.item.BackpackDefinition;
import network.skypvp.extraction.item.BackpackSkins;
import network.skypvp.paper.gui.GuiButtonLibrary;
import network.skypvp.paper.gui.GuiItems;
import network.skypvp.paper.gui.GuiTextLibrary;
import network.skypvp.paper.gui.GuiTextureItems;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Scrollable backpack GUI layout — same chrome grammar as the vault: a 54-slot screen whose
 * column 8 hosts the scroll rail, column 0-7 rows 1-5 the 40 visible content slots, and whose
 * TITLE draws the custom skin (font-in-title technique) with the shared vault scroll thumb.
 * Pack slot {@code i} maps to scroll geometry as {@code row = i / 8}.
 */
final class BackpackLayout {

    static final int INVENTORY_SIZE = 54;
    static final int SLOTS_PER_ROW = 8;
    static final int SLOTS_PER_PAGE = 40;

    static final int CLOSE_SLOT = 0;
    static final int SKINS_SLOT = 8;
    static final int SCROLL_UP_SLOT = 17;
    static final int SCROLL_TRACK_TOP_SLOT = 26;
    static final int SCROLL_TRACK_MIDDLE_SLOT = 35;
    static final int SCROLL_TRACK_LOWER_SLOT = 44;
    static final int SCROLL_DOWN_SLOT = 53;

    static final int[] CONTENT_SLOTS = {
            9, 10, 11, 12, 13, 14, 15, 16,
            18, 19, 20, 21, 22, 23, 24, 25,
            27, 28, 29, 30, 31, 32, 33, 34,
            36, 37, 38, 39, 40, 41, 42, 43,
            45, 46, 47, 48, 49, 50, 51, 52
    };
    static final int[] HEADER_FILLER_SLOTS = {1, 2, 3, 4, 5, 6, 7};

    // --- Custom GUI skin (font-in-title technique) ---------------------------------------
    // Codepoints mirror resource-packs/skypvp-core/assets/skypvp/font/{hud,gui}.json.
    // The thumb glyphs are the shared vault ones — recyclable across all scrollable GUIs.
    private static final net.kyori.adventure.key.Key HUD_FONT = net.kyori.adventure.key.Key.key("skypvp", "hud");
    private static final net.kyori.adventure.key.Key GUI_FONT = net.kyori.adventure.key.Key.key("skypvp", "gui");
    private static final char OFF_N4 = (char) 0xE853;
    private static final char OFF_N8 = (char) 0xE854;
    private static final char OFF_N16 = (char) 0xE855;
    private static final char OVERLAY = (char) 0xE8A7;
    /** Thumb glyphs at 6 vertical stops; +k selects the stop. */
    private static final char THUMB_BASE = (char) 0xE8A1;
    private static final int THUMB_STOPS = 6;

    private BackpackLayout() {
    }

    static boolean isContentSlot(int slot) {
        return contentSlotIndex(slot) >= 0;
    }

    static int contentSlotIndex(int slot) {
        for (int index = 0; index < CONTENT_SLOTS.length; index++) {
            if (CONTENT_SLOTS[index] == slot) {
                return index;
            }
        }
        return -1;
    }

    static int packIndexForContentSlot(int scrollRow, int contentSlotIndex) {
        return scrollRow * SLOTS_PER_ROW + contentSlotIndex;
    }

    /** Extra scroll positions past the first page; 0 while capacity fits on one screen. */
    static int maxScrollRow(int capacity) {
        return Math.max(0, (int) Math.ceil((capacity - SLOTS_PER_PAGE) / (double) SLOTS_PER_ROW));
    }

    /**
     * Same pixel-cursor math as the vault title: shift -8 to the GUI origin, draw the
     * 176x222 overlay (advance 177; scroll rail + button plates baked in), rewind -20 to
     * the groove column, drop the thumb glyph (advance 11) at the stop matching the scroll
     * position. Scrolls re-open the menu so the client receives the new thumb position.
     */
    static Component skinnedTitle(int scrollRow, int maxScrollRow) {
        int maxRow = Math.max(1, maxScrollRow);
        int bucket = Math.min(THUMB_STOPS - 1,
                (int) Math.round(scrollRow * (THUMB_STOPS - 1) / (double) maxRow));
        return Component.text()
                .append(Component.text(String.valueOf(OFF_N8)).font(HUD_FONT))
                .append(Component.text(String.valueOf(OVERLAY)).font(GUI_FONT).color(NamedTextColor.WHITE))
                .append(Component.text(String.valueOf(OFF_N16) + OFF_N4).font(HUD_FONT))
                .append(Component.text(String.valueOf((char) (THUMB_BASE + bucket)))
                        .font(GUI_FONT).color(NamedTextColor.WHITE))
                .build();
    }

    static void render(Inventory inventory, BackpackViewState state) {
        if (state.inventoryLive()) {
            syncContentFromInventory(inventory, state, state.scrollRow());
        }
        fillFrame(inventory, state);
        fillControls(inventory, state);
        fillContent(inventory, state);
        state.markInventoryLive();
    }

    /**
     * Sync against an EXPLICIT scroll row: during a scroll re-open the state's row has
     * already advanced while the closing inventory still shows the previous rows — syncing
     * with the live row there would duplicate items into the new rows' indices. Callers
     * that outlive a scroll must pass the row they rendered.
     */
    static void syncContentFromInventory(Inventory inventory, BackpackViewState state, int scrollRow) {
        for (int index = 0; index < CONTENT_SLOTS.length; index++) {
            int packIndex = packIndexForContentSlot(scrollRow, index);
            if (packIndex >= state.capacity()) {
                continue;
            }
            state.put(packIndex, inventory.getItem(CONTENT_SLOTS[index]));
        }
    }

    private static void fillContent(Inventory inventory, BackpackViewState state) {
        int scrollRow = state.scrollRow();
        for (int index = 0; index < CONTENT_SLOTS.length; index++) {
            int packIndex = packIndexForContentSlot(scrollRow, index);
            if (packIndex < state.capacity()) {
                inventory.setItem(CONTENT_SLOTS[index], state.get(packIndex));
            } else {
                inventory.setItem(CONTENT_SLOTS[index], lockedSlotItem(state.tier()));
            }
        }
    }

    private static void fillFrame(Inventory inventory, BackpackViewState state) {
        inventory.setItem(CLOSE_SLOT, GuiButtonLibrary.close("Stow backpack"));
        inventory.setItem(SKINS_SLOT, skinsButton(state));
        for (int slot : HEADER_FILLER_SLOTS) {
            inventory.setItem(slot, null);
        }
    }

    private static void fillControls(Inventory inventory, BackpackViewState state) {
        int scrollRow = state.scrollRow();
        int maxScrollRow = state.maxScrollRow();
        inventory.setItem(SCROLL_UP_SLOT, scrollButton("Scroll Up", scrollRow > 0));
        inventory.setItem(SCROLL_TRACK_TOP_SLOT, scrollTrack(state, 0));
        inventory.setItem(SCROLL_TRACK_MIDDLE_SLOT, scrollTrack(state, 1));
        inventory.setItem(SCROLL_TRACK_LOWER_SLOT, scrollTrack(state, 2));
        inventory.setItem(SCROLL_DOWN_SLOT, scrollButton("Scroll Down", scrollRow < maxScrollRow));
    }

    private static ItemStack skinsButton(BackpackViewState state) {
        BackpackSkins.Skin skin = BackpackSkins.byId(state.skin());
        ItemStack item = GuiItems.named(
                Material.PAPER,
                GuiTextLibrary.title("#40F0FF", "Backpack Skins"),
                GuiTextLibrary.lore()
                        .fact("Equipped", skin.displayName())
                        .plain("Change the look of your pack")
                        .footerStrong("<#55FF55>", "Click to browse skins")
                        .build()
        );
        item.editMeta(meta -> meta.setItemModel(BackpackSkins.modelKey(state.tier(), skin.id())));
        return item;
    }

    private static ItemStack scrollButton(String label, boolean enabled) {
        ItemStack item = GuiItems.named(
                Material.IRON_BARS,
                GuiTextLibrary.title(enabled ? "#40F0FF" : "#888888", label),
                GuiTextLibrary.lore()
                        .plain(enabled ? "Scroll one row" : "No more rows in this direction")
                        .build()
        );
        // Custom arrow art rendered inside the overlay's baked button plates.
        boolean up = label.toLowerCase(java.util.Locale.ROOT).contains("up");
        item.editMeta(meta -> meta.setItemModel(GuiTextureItems.modelKey(
                up ? GuiTextureItems.UI_SCROLL_UP : GuiTextureItems.UI_SCROLL_DOWN)));
        return item;
    }

    private static ItemStack scrollTrack(BackpackViewState state, int segment) {
        int scrollRow = state.scrollRow();
        int start = scrollRow * SLOTS_PER_ROW + 1;
        int end = Math.min(scrollRow * SLOTS_PER_ROW + SLOTS_PER_PAGE, state.capacity());
        ItemStack item = GuiItems.named(
                Material.IRON_BARS,
                GuiTextLibrary.title("#888888", "Pack View"),
                GuiTextLibrary.lore()
                        .fact("Slots", start + "-" + end)
                        .fact("Row", (scrollRow + 1) + " / " + (state.maxScrollRow() + 1))
                        .plain(segment == 1 ? "Use the arrows above and below to scroll" : " ")
                        .build()
        );
        // Invisible item: the overlay's groove + thumb show through; tooltip stays hoverable.
        item.editMeta(meta -> meta.setItemModel(GuiTextureItems.modelKey(GuiTextureItems.UI_BLANK)));
        return item;
    }

    private static ItemStack lockedSlotItem(int tier) {
        boolean maxed = tier >= BackpackDefinition.MAX_TIER;
        ItemStack item = GuiItems.named(
                Material.IRON_BARS,
                GuiTextLibrary.title("#888888", "Locked Slot"),
                GuiTextLibrary.lore()
                        .plain(maxed
                                ? "This pack is fully expanded"
                                : "Tier " + (tier + 1) + " packs carry one more row")
                        .build()
        );
        item.editMeta(meta -> meta.setItemModel(GuiTextureItems.modelKey(GuiTextureItems.UI_BLANK)));
        return item;
    }
}
