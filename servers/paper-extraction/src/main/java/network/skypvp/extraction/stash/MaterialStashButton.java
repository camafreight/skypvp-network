package network.skypvp.extraction.stash;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import network.skypvp.extraction.crafting.CraftingMaterialService;
import network.skypvp.paper.gui.GuiClickContext;
import network.skypvp.paper.gui.GuiTextLibrary;
import network.skypvp.shared.currency.CurrencyFormat;
import network.skypvp.shared.ServerTextUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;

/**
 * Canonical material stash menu button — bundle preview, tier, and capacity bar.
 * Use {@link #item(CraftingMaterialService, UUID)} anywhere a stash shortcut is shown.
 */
public final class MaterialStashButton {

    public static final String TITLE = "<gold>Material Stash";
    public static final String OPEN_FOOTER = "<yellow>Click to open stash";

    /** Max distinct material stacks shown inside the bundle preview. */
    private static final int MAX_PREVIEW_STACKS = 8;

    /** Vanilla bundle tooltip capacity is {@code /64} occupancy units. */
    private static final int BUNDLE_CAPACITY_UNITS = 64;

    private MaterialStashButton() {
    }

    /** Bundle icon with stash contents preview and capacity lore. */
    public static ItemStack item(CraftingMaterialService materials, UUID playerId) {
        CraftingMaterialService.StashStatus status = materials.stashStatus(playerId);
        List<String> lore = loreLines(status);

        ItemStack bundle = new ItemStack(Material.BUNDLE);
        BundleMeta meta = (BundleMeta) bundle.getItemMeta();
        meta.displayName(ServerTextUtil.miniMessageComponent(TITLE));
        meta.lore(lore.stream().map(ServerTextUtil::miniMessageComponent).toList());
        meta.setItems(bundleContents(materials.snapshotSlots(playerId), status));
        bundle.setItemMeta(meta);
        return bundle;
    }

    public static List<String> loreLines(CraftingMaterialService.StashStatus status) {
        List<String> lore = new ArrayList<>();
        lore.add("<white>Current Tier: <gold>" + status.tierName());
        lore.add(GuiTextLibrary.progressBar(status.used(), status.max(), 20, "<aqua>", "<dark_gray>"));
        lore.add("<aqua>" + status.percent() + "% <gray>("
                + CurrencyFormat.formatCompact(status.used()) + "/" + CurrencyFormat.formatCompact(status.max()) + ")");
        lore.add("");
        lore.add(OPEN_FOOTER);
        return lore;
    }

    /** Opens the stash GUI and runs {@code onBack} when the player uses Back from the stash. */
    public static void open(Player player, CraftingMaterialService materials, Runnable onBack) {
        if (player == null || materials == null) {
            return;
        }
        materials.openStashGui(player, onBack);
    }

    /** Convenience for {@link network.skypvp.paper.gui.GuiMenu} wallet / hub slots. */
    public static void handleClick(GuiClickContext context, CraftingMaterialService materials, Runnable onBack) {
        open(context.viewer(), materials, onBack);
    }

    /**
     * Fills the bundle so its native {@code /64} bar matches stash capacity — not vanilla "Full!"
     * from cramming preview stacks. Preview materials are added first, then cyan panes pad to the
     * exact occupancy budget.
     */
    private static List<ItemStack> bundleContents(Map<Integer, ItemStack> slots, CraftingMaterialService.StashStatus status) {
        int budget = bundleOccupancyBudget(status);
        if (budget <= 0) {
            return List.of();
        }

        List<ItemStack> contents = new ArrayList<>();
        int usedUnits = 0;

        List<ItemStack> previews = previewCandidates(slots);
        for (int i = 0; i < previews.size() && i < MAX_PREVIEW_STACKS; i++) {
            ItemStack preview = previews.get(i).clone();
            preview.setAmount(1);
            int cost = occupancyUnits(preview);
            if (usedUnits + cost > budget) {
                break;
            }
            contents.add(preview);
            usedUnits += cost;
        }

        int remaining = budget - usedUnits;
        while (remaining > 0) {
            int amount = Math.min(64, remaining);
            ItemStack fill = new ItemStack(Material.CYAN_STAINED_GLASS_PANE);
            fill.setAmount(amount);
            contents.add(fill);
            remaining -= occupancyUnits(fill);
        }
        return contents;
    }

    /** Maps stash used/max to bundle occupancy units (0–64). */
    private static int bundleOccupancyBudget(CraftingMaterialService.StashStatus status) {
        if (status.used() <= 0 || status.max() <= 0) {
            return 0;
        }
        long budget = (long) status.used() * BUNDLE_CAPACITY_UNITS / status.max();
        if (budget <= 0) {
            return 1;
        }
        return (int) Math.min(BUNDLE_CAPACITY_UNITS, budget);
    }

    /** Bundle {@code /64} numerator contribution for one stack. */
    private static int occupancyUnits(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return 0;
        }
        int maxStack = Math.max(1, stack.getMaxStackSize());
        return Math.max(1, (int) Math.ceil((double) stack.getAmount() * BUNDLE_CAPACITY_UNITS / maxStack));
    }

    private static List<ItemStack> previewCandidates(Map<Integer, ItemStack> slots) {
        if (slots == null || slots.isEmpty()) {
            return List.of();
        }
        return slots.values().stream()
                .filter(stack -> stack != null && !stack.getType().isAir())
                .sorted(Comparator.comparingInt(MaterialStashStackAmount::read).reversed())
                .map(ItemStack::clone)
                .toList();
    }
}
