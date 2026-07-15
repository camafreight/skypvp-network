package network.skypvp.extraction.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.extraction.crafting.CraftingConfigService;
import network.skypvp.extraction.crafting.CraftingMaterialService;
import network.skypvp.extraction.text.ExtractionTexts;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gui.GuiButtonLibrary;
import network.skypvp.paper.gui.GuiClickContext;
import network.skypvp.paper.gui.GuiItems;
import network.skypvp.paper.gui.GuiWorkstationFrame;
import network.skypvp.paper.gui.GuiWorkstationMenu;
import network.skypvp.paper.library.NetworkSoundCue;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Salvage bench — preview material returns before confirming salvage. */
public final class ArmorySalvageMenu extends GuiWorkstationMenu {

    private final PaperCorePlugin core;
    private final CraftingConfigService craftingConfig;
    private final CraftingMaterialService materials;

    public ArmorySalvageMenu(PaperCorePlugin core, CraftingConfigService craftingConfig, CraftingMaterialService materials) {
        this.core = Objects.requireNonNull(core, "core");
        this.craftingConfig = Objects.requireNonNull(craftingConfig, "craftingConfig");
        this.materials = Objects.requireNonNull(materials, "materials");
    }

    @Override
    public Component title() {
        return Component.text("Salvage Bench", NamedTextColor.RED);
    }

    @Override
    public int size() {
        return ExtractionGuiLayout.SIZE;
    }

    @Override
    protected int anchorSlot() {
        return ExtractionGuiLayout.WORKSTATION_INPUT_SLOT;
    }

    @Override
    protected boolean acceptsAnchor(ItemStack stack) {
        return ArmorySalvageService.quote(core.customItemService(), craftingConfig, stack).isPresent();
    }

    @Override
    protected ItemStack anchorPlaceholder() {
        return GuiItems.named(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<red>Deposit Gear", List.of(
                "<gray>Salvage custom armor, modules,",
                "<gray>medic items, shields, and rechargers.",
                "<red>Items with attachments cannot be salvaged."
        ));
    }

    @Override
    protected Component anchorRejectedMessage() {
        return Component.text("That item cannot be salvaged here.", NamedTextColor.RED);
    }

    @Override
    protected void buildFrame(GuiWorkstationFrame frame, Player viewer, ItemStack anchor) {
        frame.filler(GuiItems.named(Material.GRAY_STAINED_GLASS_PANE, " ", List.of()));
        frame.button(ExtractionGuiLayout.CLOSE_SLOT, GuiButtonLibrary.close("Close salvage bench"), ctx -> ctx.close());
        frame.button(ExtractionGuiLayout.BACK_SLOT, GuiButtonLibrary.back("Return to the armory"), ExtractionGuiLayout::backOrClose);
        frame.decoration(ExtractionGuiLayout.HEADER_SLOT, GuiButtonLibrary.menuHeader("<red>Salvage", lore -> {
            lore.plain("Deposit gear to preview material returns.");
            lore.plain("Click Salvage to confirm.");
        }));

        if (anchor == null) {
            frame.decoration(ExtractionGuiLayout.WORKSTATION_ACTION_SLOT, GuiItems.named(Material.BARRIER, "<red>Salvage", List.of(
                    "<gray>Deposit an item first."
            )));
            return;
        }

        Optional<ArmorySalvageService.SalvageQuote> quote =
                ArmorySalvageService.quote(core.customItemService(), craftingConfig, anchor);
        if (quote.isEmpty()) {
            frame.decoration(ExtractionGuiLayout.WORKSTATION_ACTION_SLOT, GuiItems.named(Material.BARRIER, "<red>Invalid", List.of()));
            return;
        }
        ArmorySalvageService.SalvageQuote value = quote.get();
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + value.summary());
        if (value.blocked()) {
            lore.add("<red>" + value.blockReason());
            frame.decoration(ExtractionGuiLayout.WORKSTATION_ACTION_SLOT, GuiItems.named(Material.BARRIER, "<red>Blocked", lore));
            return;
        }
        lore.add("");
        lore.add("<gold>You will receive:");
        for (ArmorySalvageService.MaterialReturn mat : value.materials()) {
            lore.add("<yellow>" + mat.amount() + "x " + mat.displayName());
        }
        lore.add("");
        lore.add("<yellow>Click to salvage");
        frame.button(ExtractionGuiLayout.WORKSTATION_ACTION_SLOT, GuiItems.named(Material.GRINDSTONE, "<gold>Salvage", lore), ctx -> {
            Player player = ctx.viewer();
            ItemStack deposited = ctx.event().getView().getTopInventory().getItem(anchorSlot());
            Optional<ArmorySalvageService.SalvageQuote> current =
                    ArmorySalvageService.quote(core.customItemService(), craftingConfig, deposited);
            if (current.isEmpty() || current.get().blocked()) {
                NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
                send(player, current.map(ArmorySalvageService.SalvageQuote::blockReason).orElse("<red>Deposit a salvageable item first."));
                return;
            }
            for (ArmorySalvageService.MaterialReturn mat : current.get().materials()) {
                materials.grant(player.getUniqueId(), mat.materialId(), mat.amount());
            }
            ctx.event().getView().getTopInventory().setItem(anchorSlot(), null);
            NetworkSoundCue.UI_BUTTON_CLICK.play(player);
            send(player, "<green>Salvaged <white>" + current.get().summary() + "<green> into crafting materials.");
            ctx.reopen(this);
        });
    }

    private void send(Player player, String miniMessage) {
        player.sendMessage(ExtractionTexts.miniMessageTemplate(miniMessage, ExtractionTexts.locale(player)));
    }
}
