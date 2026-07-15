package network.skypvp.extraction.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.extraction.crafting.CraftingMaterialService;
import network.skypvp.extraction.crafting.ItemConfigOverrides;
import network.skypvp.extraction.crafting.MedicShopConfigService;
import network.skypvp.extraction.item.MedicConsumableType;
import network.skypvp.extraction.stash.MaterialStashButton;
import network.skypvp.extraction.text.ExtractionTexts;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gui.GuiButtonLibrary;
import network.skypvp.paper.gui.GuiClickContext;
import network.skypvp.paper.gui.GuiItems;
import network.skypvp.paper.gui.GuiMenu;
import network.skypvp.paper.gui.PaginatedGuiMenu;
import network.skypvp.paper.item.api.CustomItemService;
import network.skypvp.paper.library.NetworkSoundCue;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Medic bay shop — materials-only (coins/gold are Black Market only). */
public final class MedicMenu implements GuiMenu {

    private enum MedicFilter {
        ALL("All Supplies"),
        HEALING("Healing Only"),
        SYRINGES("Syringes Only");

        private final String label;

        MedicFilter(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }

        MedicFilter next() {
            return switch (this) {
                case ALL -> HEALING;
                case HEALING -> SYRINGES;
                case SYRINGES -> ALL;
            };
        }
    }

    private final PaperCorePlugin core;
    private final CraftingMaterialService materials;
    private final MedicShopConfigService medicShop;
    private MedicFilter filter = MedicFilter.ALL;
    private boolean sortPriceAscending = true;
    private final PaginatedGuiMenu<MedicConsumableType> delegate;

    public MedicMenu(PaperCorePlugin core, CraftingMaterialService materials, MedicShopConfigService medicShop) {
        this.core = Objects.requireNonNull(core, "core");
        this.materials = Objects.requireNonNull(materials, "materials");
        this.medicShop = Objects.requireNonNull(medicShop, "medicShop");
        this.delegate = PaginatedGuiMenu.<MedicConsumableType>create(
                        Component.text("Medic Bay", NamedTextColor.GREEN), ExtractionGuiLayout.SIZE)
                .pageSlots(ExtractionGuiLayout.PAGE_SLOTS)
                .entries(ignored -> entriesFor())
                .renderItem(this::renderItem)
                .onItemClick(this::purchase)
                .button(ExtractionGuiLayout.CLOSE_SLOT, viewer -> GuiButtonLibrary.close("Close the medic bay"), GuiClickContext::close)
                .button(ExtractionGuiLayout.BACK_SLOT, viewer -> GuiButtonLibrary.back("Return to the previous menu"), ExtractionGuiLayout::backOrClose)
                .button(ExtractionGuiLayout.HEADER_SLOT, viewer -> headerButton(), ctx -> {
                })
                .button(ExtractionGuiLayout.FILTER_SLOT, viewer -> filterButton(), this::cycleFilter)
                .button(ExtractionGuiLayout.SORT_SLOT, viewer -> sortButton(), this::toggleSort)
                .button(ExtractionGuiLayout.WALLET_SLOT, viewer -> MaterialStashButton.item(materials, viewer.getUniqueId()), ctx ->
                        MaterialStashButton.handleClick(ctx, materials, () ->
                                core.guiManager().open(ctx.viewer(), this)))
                .previousButton(ExtractionGuiLayout.PREVIOUS_PAGE_SLOT, GuiButtonLibrary::previousPage)
                .nextButton(ExtractionGuiLayout.NEXT_PAGE_SLOT, GuiButtonLibrary::nextPage)
                .liveRefresh(true)
                .build();
    }

    @Override
    public Component title() {
        return delegate.title();
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public void render(Player viewer, org.bukkit.inventory.Inventory inventory) {
        delegate.render(viewer, inventory);
    }

    @Override
    public void onClick(GuiClickContext context) {
        delegate.onClick(context);
    }

    private List<MedicConsumableType> entriesFor() {
        List<MedicConsumableType> entries = new ArrayList<>();
        for (MedicConsumableType type : MedicConsumableType.values()) {
            boolean include = switch (filter) {
                case ALL -> true;
                case HEALING -> type.isHealing();
                case SYRINGES -> type.isSyringe();
            };
            if (include) {
                entries.add(type);
            }
        }
        Comparator<MedicConsumableType> comparator = Comparator.comparingInt(MedicMenu::materialCostFor);
        if (!sortPriceAscending) {
            comparator = comparator.reversed();
        }
        entries.sort(comparator);
        return entries;
    }

    private ItemStack headerButton() {
        return GuiButtonLibrary.menuHeader("<green>Medic Bay", lore -> {
            lore.plain("Healing supplies and stamina syringes.");
            lore.plain("Usable during active breach raids.");
            lore.plain(" ");
            lore.plain("Payment: crafting materials only");
            lore.plain("Coins and gold are spent at the Black Market.");
        });
    }

    private ItemStack filterButton() {
        return GuiButtonLibrary.secondaryAction(Material.HOPPER, "Filter", lore -> {
            lore.fact("Showing", filter.label());
            lore.footer("<gray>", "Click to cycle");
        });
    }

    private ItemStack sortButton() {
        return GuiButtonLibrary.secondaryAction(Material.COMPARATOR, "Sort", lore -> {
            lore.fact("Order", sortPriceAscending ? "Cheapest first" : "Most expensive first");
            lore.footer("<gray>", "Click to toggle");
        });
    }

    private void cycleFilter(GuiClickContext context) {
        filter = filter.next();
        context.reopen(this);
    }

    private void toggleSort(GuiClickContext context) {
        sortPriceAscending = !sortPriceAscending;
        context.reopen(this);
    }

    private ItemStack renderItem(Player viewer, MedicConsumableType type) {
        List<String> lore = new ArrayList<>();
        String blurb = ItemConfigOverrides.blurb(type.typeId()).orElse(type.blurb());
        lore.add("<gray>" + blurb);
        lore.add("");
        lore.addAll(type.statMiniMessageLines());
        lore.add("");
        MedicMaterialCost cost = materialCost(type);
        int owned = materials.balance(viewer.getUniqueId(), cost.materialId());
        String color = owned >= cost.amount() ? "<green>" : "<red>";
        lore.add("<gray>Cost: " + color + cost.amount() + "x " + cost.displayName());
        lore.add("<gray>Owned: <yellow>" + owned);
        lore.add("<yellow>Click to purchase");
        String display = ItemConfigOverrides.displayName(type.typeId()).orElse(type.displayName());
        String colorHex = "<" + type.color().asHexString() + ">";
        // Canonical consumable stack — carries the medic_<id> item model from the pack.
        return GuiItems.restyled(
                network.skypvp.extraction.item.ExtractionCustomItemProvider.createMedicConsumable(
                        core.customItemService(), type),
                colorHex + display, lore);
    }

    private void purchase(GuiClickContext context, MedicConsumableType type) {
        Player player = context.viewer();
        CustomItemService service = core.customItemService();
        if (service == null) {
            send(player, "<red>The medic bay is unavailable right now.");
            return;
        }
        if (player.getInventory().firstEmpty() < 0) {
            NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
            send(player, "<red>Make room in your inventory first.");
            return;
        }
        MedicMaterialCost cost = materialCost(type);
        if (!materials.trySpend(player.getUniqueId(), Map.of(cost.materialId(), cost.amount()))) {
            NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
            send(player, "<red>Not enough " + cost.displayName() + ". Need <gold>" + cost.amount() + "<red>.");
            return;
        }
        deliverPurchase(context, player, service, type, () ->
                materials.grant(player.getUniqueId(), cost.materialId(), cost.amount()));
    }

    private void deliverPurchase(
            GuiClickContext context,
            Player player,
            CustomItemService service,
            MedicConsumableType type,
            Runnable refund
    ) {
        ItemStack item = service.create(type.typeId(), builder -> {
        });
        if (item == null || item.getType().isAir()) {
            refund.run();
            NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
            send(player, "<red>Purchase failed — materials refunded.");
            context.reopen(this);
            return;
        }
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        if (!leftovers.isEmpty()) {
            refund.run();
            NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
            send(player, "<red>Inventory full — materials refunded.");
            context.reopen(this);
            return;
        }
        NetworkSoundCue.UI_BUTTON_CLICK.play(player);
        String name = ItemConfigOverrides.displayName(type.typeId()).orElse(type.displayName());
        send(player, "<green>Purchased <white>" + name + "<green>.");
        context.reopen(this);
    }

    public static int materialCostFor(MedicConsumableType type) {
        return ItemConfigOverrides.medicMaterialCost(type)
                .map(MedicShopConfigService.MaterialCost::amount)
                .orElseGet(() -> defaultMaterialCost(type).amount());
    }

    private MedicMaterialCost materialCost(MedicConsumableType type) {
        return ItemConfigOverrides.medicMaterialCost(type)
                .map(cost -> new MedicMaterialCost(cost.materialId(), cost.amount(), cost.displayName()))
                .orElseGet(() -> defaultMaterialCost(type));
    }

    private static MedicMaterialCost defaultMaterialCost(MedicConsumableType type) {
        return switch (type) {
            case BANDAGE_RAG -> new MedicMaterialCost("cloth_scrap", 4, "Cloth Scrap");
            case STERILE_BANDAGE -> new MedicMaterialCost("fiber_bundle", 3, "Fiber Bundle");
            case MEDKIT -> new MedicMaterialCost("field_suture", 4, "Field Suture");
            case SURGICAL_KIT -> new MedicMaterialCost("aether_resin", 2, "Aether Resin");
            case ADRENALINE_SHOT -> new MedicMaterialCost("stim_compound", 2, "Stim Compound");
            case STAMINA_STABILIZER -> new MedicMaterialCost("stim_compound", 3, "Stim Compound");
            case OVERDRIVE_SERUM -> new MedicMaterialCost("aether_resin", 3, "Aether Resin");
        };
    }

    private record MedicMaterialCost(String materialId, int amount, String displayName) {
    }

    private void send(Player player, String miniMessage) {
        player.sendMessage(ExtractionTexts.miniMessageTemplate(miniMessage, ExtractionTexts.locale(player)));
    }
}
