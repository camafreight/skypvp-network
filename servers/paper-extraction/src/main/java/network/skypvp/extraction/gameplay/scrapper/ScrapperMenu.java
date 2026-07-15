package network.skypvp.extraction.gameplay.scrapper;

import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.extraction.crafting.CraftingConfigService;
import network.skypvp.extraction.crafting.CraftingMaterialItemFactory;
import network.skypvp.extraction.gui.ExtractionGuiLayout;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gui.GuiButtonLibrary;
import network.skypvp.paper.gui.GuiClickContext;
import network.skypvp.paper.gui.GuiItems;
import network.skypvp.paper.gui.GuiMenu;
import network.skypvp.paper.gui.GuiTextLibrary;
import network.skypvp.paper.library.NetworkSoundCue;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Hub scrapper status and material collection GUI. */
public final class ScrapperMenu implements GuiMenu {

    private static final int COLLECT_SLOT = ExtractionGuiLayout.WORKSTATION_ACTION_SLOT;
    private static final int INFO_SLOT = ExtractionGuiLayout.HEADER_SLOT;

    private final PaperCorePlugin core;
    private final ScrapperService scrapperService;
    private final CraftingConfigService craftingConfig;

    public ScrapperMenu(PaperCorePlugin core, ScrapperService scrapperService, CraftingConfigService craftingConfig) {
        this.core = Objects.requireNonNull(core, "core");
        this.scrapperService = Objects.requireNonNull(scrapperService, "scrapperService");
        this.craftingConfig = Objects.requireNonNull(craftingConfig, "craftingConfig");
    }

    @Override
    public Component title() {
        return Component.text("Scrapper Bay", NamedTextColor.GOLD);
    }

    @Override
    public int size() {
        return ExtractionGuiLayout.SIZE;
    }

    @Override
    public void render(Player viewer, Inventory inventory) {
        ItemStack filler = GuiItems.named(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler.clone());
        }
        inventory.setItem(ExtractionGuiLayout.CLOSE_SLOT, GuiButtonLibrary.close("Close scrapper"));
        inventory.setItem(INFO_SLOT, infoItem(viewer));
        inventory.setItem(COLLECT_SLOT, collectButton(viewer));

        List<ScrapperService.BufferedMaterial> buffered = scrapperService.bufferedMaterials(viewer);
        List<Integer> pageSlots = ExtractionGuiLayout.PAGE_SLOTS;
        for (int index = 0; index < pageSlots.size(); index++) {
            if (index >= buffered.size()) {
                break;
            }
            ScrapperService.BufferedMaterial entry = buffered.get(index);
            ItemStack stack = CraftingMaterialItemFactory.create(
                    core.customItemService(),
                    craftingConfig,
                    entry.materialId(),
                    Math.min(64, entry.amount())
            );
            if (stack == null) {
                continue;
            }
            stack = stack.clone();
            stack.setAmount(Math.min(stack.getMaxStackSize(), Math.max(1, Math.min(64, entry.amount()))));
            inventory.setItem(pageSlots.get(index), stack);
        }
    }

    @Override
    public void onClick(GuiClickContext context) {
        int rawSlot = context.rawSlot();
        Player player = context.viewer();
        if (rawSlot == ExtractionGuiLayout.CLOSE_SLOT) {
            context.close();
            return;
        }
        if (rawSlot == COLLECT_SLOT) {
            collectAll(context, player);
        }
    }

    private void collectAll(GuiClickContext context, Player player) {
        if (scrapperService.sessionBuffered(player) <= 0) {
            player.sendMessage(Component.text("Nothing to collect yet.", NamedTextColor.GRAY));
            NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
            return;
        }
        ScrapperService.WithdrawResult result = scrapperService.withdrawAllToInventory(player);
        if (result.movedUnits() <= 0) {
            player.sendMessage(Component.text("Your inventory is full.", NamedTextColor.RED));
            NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
            return;
        }
        NetworkSoundCue.UI_BUTTON_CLICK.play(player);
        if (!scrapperService.hasBufferedMaterials(player) && core.questSignals() != null) {
            // Salvage fully collected — retire the scrapper main-quest marker.
            core.questSignals().complete(
                    player,
                    network.skypvp.extraction.questdialogue.ScrapperQuestSignalProvider.QUEST_ID
            );
        }
        if (result.inventoryFull() && result.leftoverUnits() > 0) {
            player.sendMessage(Component.text(
                    "Collected " + result.movedUnits() + " materials. Inventory full — "
                            + result.leftoverUnits() + " still in the scrapper.",
                    NamedTextColor.YELLOW
            ));
        } else {
            player.sendMessage(Component.text("Collected " + result.movedUnits() + " materials.", NamedTextColor.GREEN));
        }
        context.refresh();
    }

    private ItemStack infoItem(Player viewer) {
        int tier = scrapperService.playerTier(viewer);
        return GuiButtonLibrary.menuHeader(scrapperService.tierName(viewer), lore -> lore
                .fact("Tier", String.valueOf(tier))
                .fact("Raid progress", scrapperService.sessionCollected(viewer) + "/" + scrapperService.sessionCap(viewer))
                .fact("Buffered", String.valueOf(scrapperService.sessionBuffered(viewer)))
                .plain("Salvage gathers passively during raids")
                .plain("Collect here after returning to the hub"));
    }

    private ItemStack collectButton(Player viewer) {
        int buffered = scrapperService.sessionBuffered(viewer);
        Material icon = buffered > 0 ? Material.LIME_WOOL : Material.GRAY_WOOL;
        return GuiButtonLibrary.positiveAction(
                icon,
                "Collect All",
                lore -> lore
                        .fact("Buffered", String.valueOf(buffered))
                        .plain("Moves salvage into your inventory")
                        .footerStrong("<#55FF55>", buffered > 0 ? "Click to collect" : "Nothing buffered yet")
        );
    }
}
