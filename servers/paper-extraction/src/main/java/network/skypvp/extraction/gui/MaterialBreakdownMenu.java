package network.skypvp.extraction.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.extraction.crafting.CraftingConfigService;
import network.skypvp.extraction.crafting.MaterialBreakdownConfigService;
import network.skypvp.extraction.crafting.MaterialBreakdownService;
import network.skypvp.extraction.crafting.MaterialBreakdownService.BreakdownQuote;
import network.skypvp.extraction.text.ExtractionTexts;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gui.GuiButtonLibrary;
import network.skypvp.paper.gui.GuiClickContext;
import network.skypvp.paper.gui.GuiItems;
import network.skypvp.paper.gui.GuiWorkstationFrame;
import network.skypvp.paper.gui.GuiWorkstationMenu;
import network.skypvp.paper.item.api.CustomItemService;
import network.skypvp.paper.library.NetworkSoundCue;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Breaks higher-tier crafting materials into lower-tier components (with efficiency loss). */
public final class MaterialBreakdownMenu extends GuiWorkstationMenu {

    private final PaperCorePlugin core;
    private final CraftingConfigService craftingConfig;
    private final MaterialBreakdownConfigService breakdownConfig;

    public MaterialBreakdownMenu(
            PaperCorePlugin core,
            CraftingConfigService craftingConfig,
            MaterialBreakdownConfigService breakdownConfig
    ) {
        this.core = Objects.requireNonNull(core, "core");
        this.craftingConfig = Objects.requireNonNull(craftingConfig, "craftingConfig");
        this.breakdownConfig = Objects.requireNonNull(breakdownConfig, "breakdownConfig");
    }

    @Override
    public Component title() {
        return Component.text("Material Refinery", NamedTextColor.GOLD);
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
        return acceptsCustomItem(core.customItemService(), stack,
                MaterialBreakdownService.refinableMaterialRequirement(breakdownConfig));
    }

    @Override
    protected ItemStack anchorPlaceholder() {
        int efficiency = (int) (breakdownConfig.defaultEfficiency() * 100);
        return GuiItems.named(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<gold>Deposit Material", List.of(
                "<gray>Place a refinable material here.",
                "<gray>Breaks down into lower-tier parts.",
                "<dark_gray>Yield: ~" + efficiency + "% efficiency"
        ));
    }

    @Override
    protected Component anchorRejectedMessage() {
        return Component.text("That material cannot be broken down here.", NamedTextColor.RED);
    }

    @Override
    protected void buildFrame(GuiWorkstationFrame frame, Player viewer, ItemStack anchor) {
        frame.filler(GuiItems.named(Material.GRAY_STAINED_GLASS_PANE, " ", List.of()));
        frame.button(ExtractionGuiLayout.CLOSE_SLOT, GuiButtonLibrary.close("Close refinery"), ctx -> ctx.close());
        frame.button(ExtractionGuiLayout.BACK_SLOT, GuiButtonLibrary.back("Return to the armory"), ExtractionGuiLayout::backOrClose);
        int efficiency = (int) (breakdownConfig.defaultEfficiency() * 100);
        frame.decoration(ExtractionGuiLayout.HEADER_SLOT, GuiButtonLibrary.menuHeader("<gold>Material Refinery", lore -> {
            lore.plain("Salvage refined mats into lower tiers.");
            lore.plain("Fiber → scrap, alloy → shards, etc.");
            lore.plain("~" + efficiency + "% yield per breakdown");
        }));

        if (anchor == null) {
            frame.decoration(ExtractionGuiLayout.WORKSTATION_ACTION_SLOT, GuiItems.named(Material.BARRIER, "<gold>Break Down", List.of(
                    "<gray>Deposit a material first."
            )));
            return;
        }

        CustomItemService service = core.customItemService();
        Optional<BreakdownQuote> quote = MaterialBreakdownService.quote(service, craftingConfig, breakdownConfig, anchor);
        if (quote.isEmpty()) {
            frame.decoration(ExtractionGuiLayout.WORKSTATION_ACTION_SLOT, GuiItems.named(Material.BARRIER, "<red>Invalid", List.of()));
            return;
        }
        BreakdownQuote value = quote.get();
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Input: <white>" + value.inputAmount() + "x " + value.inputName());
        if (value.blocked()) {
            lore.add("<red>" + value.blockReason());
            frame.decoration(ExtractionGuiLayout.WORKSTATION_ACTION_SLOT, GuiItems.named(Material.BARRIER, "<red>Blocked", lore));
            return;
        }
        lore.add("");
        lore.add("<gold>You will receive:");
        for (MaterialBreakdownService.MaterialYield yield : value.outputs()) {
            lore.add("<yellow>" + yield.amount() + "x " + yield.displayName());
        }
        lore.add("");
        lore.add("<yellow>Click to break down");
        frame.button(ExtractionGuiLayout.WORKSTATION_ACTION_SLOT, GuiItems.named(Material.GRINDSTONE, "<gold>Break Down", lore), ctx -> {
            Player player = ctx.viewer();
            ItemStack deposited = ctx.event().getView().getTopInventory().getItem(anchorSlot());
            Optional<BreakdownQuote> current = MaterialBreakdownService.quote(service, craftingConfig, breakdownConfig, deposited);
            if (current.isEmpty() || current.get().blocked()) {
                NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
                send(player, current.map(BreakdownQuote::blockReason).orElse("<red>Deposit a refinable material first."));
                return;
            }
            BreakdownQuote result = current.get();
            int consumed = result.inputAmount();
            ItemStack working = deposited.clone();
            if (working.getAmount() <= consumed) {
                ctx.event().getView().getTopInventory().setItem(anchorSlot(), null);
            } else {
                working.setAmount(working.getAmount() - consumed);
                ctx.event().getView().getTopInventory().setItem(anchorSlot(), working);
            }
            for (ItemStack output : MaterialBreakdownService.createOutputStacks(service, craftingConfig, result.outputs())) {
                player.getInventory().addItem(output).values()
                        .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
            }
            NetworkSoundCue.UI_BUTTON_CLICK.play(player);
            send(player, "<green>Broke down <white>" + consumed + "x " + result.inputName() + "<green> into lower-tier materials.");
            ctx.refresh();
        });
    }

    private void send(Player player, String miniMessage) {
        player.sendMessage(ExtractionTexts.miniMessageTemplate(miniMessage, ExtractionTexts.locale(player)));
    }
}
