package network.skypvp.extraction.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.extraction.crafting.BlueprintCategory;
import network.skypvp.extraction.crafting.BlueprintDefinition;
import network.skypvp.extraction.crafting.BlueprintDiscoveryService;
import network.skypvp.extraction.crafting.CraftingConfigService;
import network.skypvp.extraction.crafting.CraftingMaterialDefinition;
import network.skypvp.extraction.crafting.CraftingMaterialService;
import network.skypvp.extraction.integration.WeaponMechanicsBridge;
import network.skypvp.extraction.item.ArmorModuleType;
import network.skypvp.extraction.item.ArmorSet;
import network.skypvp.extraction.item.ArmorSetBonusTexts;
import network.skypvp.extraction.item.InfuseArmorPiece;
import network.skypvp.extraction.item.MedicConsumableType;
import network.skypvp.extraction.crafting.CraftingMaterialItemFactory;
import network.skypvp.extraction.stash.MaterialStashButton;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.item.api.CustomItemService;
import network.skypvp.paper.gui.GuiButtonLibrary;
import network.skypvp.paper.gui.GuiClickContext;
import network.skypvp.paper.gui.GuiItems;
import network.skypvp.paper.gui.GuiMenu;
import network.skypvp.paper.gui.PaginatedGuiMenu;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Blueprint browser — only shows recipes the player has discovered. */
public final class BlueprintSelectMenu implements GuiMenu {

    private final PaperCorePlugin core;
    private final CraftingConfigService config;
    private final CraftingMaterialService materials;
    private final BlueprintDiscoveryService discovery;
    private final WeaponMechanicsBridge weaponBridge;
    private BlueprintCategory filter = BlueprintCategory.ALL;
    private final PaginatedGuiMenu<BlueprintDefinition> delegate;

    public BlueprintSelectMenu(
            PaperCorePlugin core,
            CraftingConfigService config,
            CraftingMaterialService materials,
            BlueprintDiscoveryService discovery,
            WeaponMechanicsBridge weaponBridge
    ) {
        this.core = Objects.requireNonNull(core, "core");
        this.config = Objects.requireNonNull(config, "config");
        this.materials = Objects.requireNonNull(materials, "materials");
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.weaponBridge = Objects.requireNonNull(weaponBridge, "weaponBridge");
        this.delegate = PaginatedGuiMenu.<BlueprintDefinition>create(
                        Component.text("Blueprint Archive", NamedTextColor.GOLD), ExtractionGuiLayout.SIZE)
                .pageSlots(ExtractionGuiLayout.PAGE_SLOTS)
                .entries(viewer -> entriesFor(viewer.getUniqueId()))
                .renderItem(this::renderBlueprint)
                .onItemClick(this::openWorkbench)
                .button(ExtractionGuiLayout.CLOSE_SLOT, viewer -> GuiButtonLibrary.close("Close blueprints"), GuiClickContext::close)
                .button(ExtractionGuiLayout.BACK_SLOT, viewer -> GuiButtonLibrary.back("Return to the armory"), ExtractionGuiLayout::backOrClose)
                .button(ExtractionGuiLayout.HEADER_SLOT, viewer -> GuiButtonLibrary.menuHeader("<gold>Blueprints", lore -> {
                    lore.plain("Select a discovered recipe, then");
                    lore.plain("provide materials at the workbench.");
                }), ctx -> {
                })
                .button(ExtractionGuiLayout.FILTER_SLOT, viewer -> filterButton(), this::cycleFilter)
                .button(ExtractionGuiLayout.WALLET_SLOT, viewer -> MaterialStashButton.item(materials, viewer.getUniqueId()), ctx ->
                        MaterialStashButton.handleClick(ctx, materials, () ->
                                core.guiManager().open(ctx.viewer(), this)))
                .previousButton(ExtractionGuiLayout.PREVIOUS_PAGE_SLOT, GuiButtonLibrary::previousPage)
                .nextButton(ExtractionGuiLayout.NEXT_PAGE_SLOT, GuiButtonLibrary::nextPage)
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

    private List<BlueprintDefinition> entriesFor(UUID playerId) {
        List<BlueprintDefinition> discovered = discovery.discoveredBlueprints(playerId);
        if (filter == BlueprintCategory.ALL) {
            return discovered;
        }
        return discovered.stream()
                .filter(bp -> bp.category() == filter)
                .toList();
    }

    private org.bukkit.inventory.ItemStack renderBlueprint(Player viewer, BlueprintDefinition blueprint) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Category: <white>" + blueprint.category().title());
        lore.add("");
        lore.addAll(previewLines(blueprint));
        lore.add("");
        lore.add("<dark_gray>Materials:");
        CustomItemService service = core.customItemService();
        for (BlueprintDefinition.MaterialCost cost : blueprint.materials()) {
            int stash = materials.balance(viewer.getUniqueId(), cost.materialId());
            int physical = CraftingMaterialItemFactory.countInInventory(service, viewer, cost.materialId());
            int have = stash + physical;
            String color = have >= cost.amount() ? "<green>" : "<red>";
            lore.add(color + cost.amount() + "x " + materialName(cost.materialId())
                    + " <gray>(" + have + " available)");
        }
        lore.add("");
        lore.add("<yellow>Click to open workbench");
        if (blueprint.output().type() == BlueprintDefinition.OutputType.WEAPON && weaponBridge.isAvailable()) {
            // Restyle the generated weapon stack itself (keeps the WM item model, not just the material).
            return weaponBridge.generateWeapon(blueprint.output().recipeKey(), 1)
                    .map(stack -> GuiItems.restyled(stack, "<white>" + blueprint.displayName(), lore))
                    .orElseGet(() -> GuiItems.named(blueprint.icon(), "<white>" + blueprint.displayName(), lore));
        }
        // Canonical crafted result (armor/shield/module/medic) so entries always show real item art.
        return network.skypvp.extraction.crafting.BlueprintCrafter.craft(service, blueprint)
                .map(stack -> GuiItems.restyled(stack, "<white>" + blueprint.displayName(), lore))
                .orElseGet(() -> GuiItems.named(blueprint.icon(), "<white>" + blueprint.displayName(), lore));
    }

    private List<String> previewLines(BlueprintDefinition blueprint) {
        List<String> lines = new ArrayList<>();
        if (blueprint.output().type() == BlueprintDefinition.OutputType.MEDIC) {
            MedicConsumableType.byId(blueprint.output().recipeKey()).ifPresent(type -> lines.addAll(type.statMiniMessageLines()));
        } else if (blueprint.output().type() == BlueprintDefinition.OutputType.ARMOR
                && blueprint.output().recipeKey().startsWith("armor_")) {
            String[] parts = blueprint.output().recipeKey().split("_");
            if (parts.length >= 4) {
                ArmorSet.byId(parts[1]).ifPresent(set -> {
                    InfuseArmorPiece.byId(parts[2]).ifPresent(piece -> lines.addAll(ArmorSetBonusTexts.miniMessageLines(set, piece.setBonusShare())));
                });
            }
        } else if (blueprint.output().type() == BlueprintDefinition.OutputType.MODULE) {
            String key = blueprint.output().recipeKey();
            ArmorModuleType.byId(key.startsWith("module_") ? key.substring("module_".length()) : key)
                    .ifPresent(type -> {
                        for (ArmorModuleType.ModuleEffect effect : type.effects()) {
                            String prefix = effect.positive() ? "<green>▲ " : "<red>▼ ";
                            lines.add(prefix + effect.label());
                        }
                    });
        } else if (blueprint.output().type() == BlueprintDefinition.OutputType.SHIELD) {
            lines.add("<gray>Socket into chestplate shield slot");
        } else if (blueprint.output().type() == BlueprintDefinition.OutputType.RECHARGER) {
            network.skypvp.extraction.item.RechargerTier tier =
                    network.skypvp.extraction.item.RechargerTier.fromId(blueprint.output().recipeKey());
            lines.add("<gray>Recharges socketed shield buffer");
            lines.add("<aqua>Rate: <white>" + tier.rateLabel());
        } else if (blueprint.output().type() == BlueprintDefinition.OutputType.SHIELD_REPAIR_KIT) {
            lines.add("<gray>Field-repair a socketed shield during raids");
        } else if (blueprint.output().type() == BlueprintDefinition.OutputType.WEAPON) {
            String weaponId = blueprint.output().recipeKey();
            lines.add("<gray>Weapon: <white>" + weaponId.replace('_', ' '));
            if (!weaponBridge.isAvailable()) {
                lines.add("<red>WeaponMechanics unavailable");
            }
        }
        return lines;
    }

    private String materialName(String id) {
        return config.materials().stream()
                .filter(mat -> mat.id().equals(id))
                .map(CraftingMaterialDefinition::displayName)
                .findFirst()
                .orElse(id);
    }

    private org.bukkit.inventory.ItemStack filterButton() {
        return GuiItems.named(Material.COMPASS, "<aqua>Filter: <white>" + filter.title(), List.of(
                "<gray>Showing <white>" + filter.title() + " <gray>blueprints",
                "<yellow>Click to cycle"
        ));
    }

    private void cycleFilter(GuiClickContext context) {
        filter = filter.next();
        context.reopen(this);
    }

    private void openWorkbench(GuiClickContext context, BlueprintDefinition blueprint) {
        context.open(new BlueprintCraftWorkbenchMenu(core, config, materials, discovery, blueprint, weaponBridge));
    }
}
