package network.skypvp.extraction.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.extraction.crafting.BlueprintCrafter;
import network.skypvp.extraction.crafting.BlueprintDefinition;
import network.skypvp.extraction.crafting.BlueprintDiscoveryService;
import network.skypvp.extraction.crafting.CraftingConfigService;
import network.skypvp.extraction.crafting.CraftingMaterialDefinition;
import network.skypvp.extraction.crafting.CraftingMaterialDepositHelper;
import network.skypvp.extraction.crafting.CraftingMaterialDepositHelper.SlotBinding;
import network.skypvp.extraction.crafting.CraftingMaterialItemFactory;
import network.skypvp.extraction.crafting.CraftingMaterialService;
import network.skypvp.extraction.integration.WeaponMechanicsBridge;
import network.skypvp.extraction.text.ExtractionTexts;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gui.GuiButtonLibrary;
import network.skypvp.paper.gui.GuiClickContext;
import network.skypvp.paper.gui.GuiDepositSlot;
import network.skypvp.paper.gui.GuiItems;
import network.skypvp.paper.gui.GuiMaterialDepositWorkbenchMenu;
import network.skypvp.paper.gui.GuiWorkstationFrame;
import network.skypvp.paper.item.api.CustomItemService;
import network.skypvp.paper.library.NetworkSoundCue;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Physical material deposit workbench via {@link GuiMaterialDepositWorkbenchMenu}. */
public final class BlueprintCraftWorkbenchMenu extends GuiMaterialDepositWorkbenchMenu {

    private static final int AUTO_PLACE_SLOT = 40;
    private static final int CRAFT_SLOT = ExtractionGuiLayout.WORKSTATION_ACTION_SLOT;
    private static final int PREVIEW_SLOT = 4;

    private final PaperCorePlugin core;
    private final CraftingConfigService config;
    private final CraftingMaterialService stash;
    private final BlueprintDiscoveryService discovery;
    private final BlueprintDefinition blueprint;
    private final WeaponMechanicsBridge weaponBridge;
    private final List<SlotBinding> bindings;
    private final List<GuiDepositSlot> depositSlots;

    public BlueprintCraftWorkbenchMenu(
            PaperCorePlugin core,
            CraftingConfigService config,
            CraftingMaterialService stash,
            BlueprintDiscoveryService discovery,
            BlueprintDefinition blueprint,
            WeaponMechanicsBridge weaponBridge
    ) {
        this.core = Objects.requireNonNull(core, "core");
        this.config = Objects.requireNonNull(config, "config");
        this.stash = Objects.requireNonNull(stash, "stash");
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.blueprint = Objects.requireNonNull(blueprint, "blueprint");
        this.weaponBridge = Objects.requireNonNull(weaponBridge, "weaponBridge");
        this.bindings = CraftingMaterialDepositHelper.bindingsFor(blueprint);
        this.depositSlots = CraftingMaterialDepositHelper.depositSlots(bindings);
    }

    @Override
    public Component title() {
        return Component.text("Craft: " + blueprint.displayName(), NamedTextColor.GOLD);
    }

    @Override
    public int size() {
        return ExtractionGuiLayout.SIZE;
    }

    @Override
    protected CustomItemService customItemService() {
        return core.customItemService();
    }

    @Override
    protected List<GuiDepositSlot> depositSlots() {
        return depositSlots;
    }

    @Override
    protected ItemStack depositPlaceholder(GuiDepositSlot depositSlot) {
        String materialId = bindingFor(depositSlot.slot()).materialId();
        CraftingMaterialDefinition def = CraftingMaterialDefinition.byId(materialId, config.materials()).orElse(null);
        String name = def == null ? materialId : def.displayName();
        int required = depositSlot.requirement().minimumAmount();
        List<String> lore = List.of(
                "<gray>Deposit: <yellow>" + required + "x",
                "<dark_gray>Click or shift-click to place"
        );
        // Canonical material stack carries the mat_<id> item model from the pack.
        ItemStack canonical = network.skypvp.extraction.crafting.CraftingMaterialItemFactory.create(
                core.customItemService(), config, materialId, 1);
        if (canonical != null && !canonical.getType().isAir()) {
            return GuiItems.restyled(canonical, "<white>" + name, lore);
        }
        Material icon = def == null ? Material.LIGHT_GRAY_STAINED_GLASS_PANE : def.icon();
        return GuiItems.named(icon, "<white>" + name, lore);
    }

    @Override
    protected void buildFrame(GuiWorkstationFrame frame, Player viewer, Inventory inventory) {
        frame.filler(GuiItems.named(Material.GRAY_STAINED_GLASS_PANE, " ", List.of()));
        frame.button(ExtractionGuiLayout.CLOSE_SLOT, GuiButtonLibrary.close("Close workbench"), GuiClickContext::close);
        frame.button(ExtractionGuiLayout.BACK_SLOT, GuiButtonLibrary.back("Return to blueprints"), ExtractionGuiLayout::backOrClose);
        frame.decoration(PREVIEW_SLOT, previewItem());
        frame.button(CRAFT_SLOT, craftButton(inventory), ctx -> craft(ctx, viewer, inventory));
        frame.button(AUTO_PLACE_SLOT, autoPlaceButton(), ctx -> {
            autoPlace(viewer, inventory);
            ctx.refresh();
        });
    }

    @Override
    protected void onDepositsChanged(Player viewer) {
        if (boundInventory() != null) {
            boundInventory().setItem(CRAFT_SLOT, craftButton(boundInventory()));
        }
    }

    private void craft(GuiClickContext context, Player player, Inventory inventory) {
        CustomItemService service = customItemService();
        if (!discovery.isDiscovered(player.getUniqueId(), blueprint.id())) {
            NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
            player.sendMessage(ExtractionTexts.miniMessageTemplate("<red>You have not discovered this blueprint.", ExtractionTexts.locale(player)));
            return;
        }
        if (!requirementsMet(inventory)) {
            NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
            player.sendMessage(ExtractionTexts.miniMessageTemplate("<red>Place all required materials in the deposit slots.", ExtractionTexts.locale(player)));
            return;
        }
        consumeDeposits(inventory);
        ItemStack output = BlueprintCrafter.craft(service, blueprint, weaponBridge).orElse(null);
        if (output == null) {
            NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
            player.sendMessage(ExtractionTexts.miniMessageTemplate("<red>Craft failed — invalid blueprint output.", ExtractionTexts.locale(player)));
            return;
        }
        player.getInventory().addItem(output).values()
                .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        NetworkSoundCue.UI_BUTTON_CLICK.play(player);
        player.sendMessage(ExtractionTexts.miniMessageTemplate("<green>Crafted <white>" + blueprint.displayName() + "<green>.", ExtractionTexts.locale(player)));
        context.refresh();
    }

    private void autoPlace(Player player, Inventory inventory) {
        CraftingMaterialDepositHelper.PlacementResult result = CraftingMaterialDepositHelper.autoPlace(
                customItemService(), config, stash, player, inventory, bindings);
        if (!result.success()) {
            NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
            player.sendMessage(ExtractionTexts.miniMessageTemplate("<red>" + result.message(), ExtractionTexts.locale(player)));
            return;
        }
        NetworkSoundCue.UI_BUTTON_SUCCESS.play(player);
        player.sendMessage(ExtractionTexts.miniMessageTemplate("<green>Materials placed from inventory and stash.", ExtractionTexts.locale(player)));
    }

    private ItemStack previewItem() {
        if (blueprint.output().type() == BlueprintDefinition.OutputType.WEAPON && weaponBridge.isAvailable()) {
            return weaponBridge.generateWeapon(blueprint.output().recipeKey(), 1)
                    .map(stack -> {
                        ItemStack preview = stack.clone();
                        preview.setAmount(1);
                        return preview;
                    })
                    .orElseGet(this::fallbackPreview);
        }
        return fallbackPreview();
    }

    private ItemStack fallbackPreview() {
        // Canonical crafted result (real item model/lore base) — config icon only as last resort.
        return network.skypvp.extraction.crafting.BlueprintCrafter.craft(core.customItemService(), blueprint)
                .map(stack -> GuiItems.restyled(stack, "<gold>" + blueprint.displayName(), requirementLore()))
                .orElseGet(() -> GuiItems.named(blueprint.icon(), "<gold>" + blueprint.displayName(), requirementLore()));
    }

    private ItemStack craftButton(Inventory inventory) {
        boolean ready = requirementsMet(inventory);
        List<String> lore = new ArrayList<>(requirementLore());
        lore.add("");
        lore.add(ready ? "<yellow>Click to craft" : "<red>Deposit all materials first");
        Material icon = ready ? Material.SMITHING_TABLE : Material.BARRIER;
        return GuiItems.named(icon, "<gold>Craft Item", lore);
    }

    private ItemStack autoPlaceButton() {
        return GuiItems.named(Material.CHEST, "<aqua>Auto-Place Materials", List.of(
                "<gray>Pulls matching materials from",
                "<gray>your inventory, then your stash.",
                "",
                "<yellow>Click to fill deposit slots"
        ));
    }

    private List<String> requirementLore() {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Required materials:");
        for (BlueprintDefinition.MaterialCost cost : blueprint.materials()) {
            lore.add("<yellow>" + cost.amount() + "x " + materialName(cost.materialId()));
        }
        return lore;
    }

    private String materialName(String id) {
        return CraftingMaterialDefinition.byId(id, config.materials())
                .map(CraftingMaterialDefinition::displayName)
                .orElse(id);
    }

    private SlotBinding bindingFor(int slot) {
        for (SlotBinding binding : bindings) {
            if (binding.slot() == slot) {
                return binding;
            }
        }
        throw new IllegalArgumentException("Unknown deposit slot: " + slot);
    }
}
