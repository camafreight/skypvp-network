package network.skypvp.extraction.gui;

import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.extraction.crafting.CraftingMaterialService;
import network.skypvp.extraction.stash.MaterialStashButton;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gui.GuiButtonLibrary;
import network.skypvp.paper.gui.GuiClickContext;
import network.skypvp.paper.gui.GuiItems;
import network.skypvp.paper.gui.GuiMenuBuilder;
import network.skypvp.shared.currency.CurrencyFormat;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Armory hub: gear maintenance only — Infuse, Salvage, Mark Upgrade, Shield Repair — with the
 * material stash promoted to the header row. Everything else moved to its own command:
 * {@code /blackmarket}, {@code /craft}, {@code /refinery}, {@code /medic}.
 */
public final class ArmoryHubMenu {

    private static final int INFUSE_SLOT = 19;
    private static final int SALVAGE_SLOT = 21;
    private static final int MARK_UPGRADE_SLOT = 23;
    private static final int SHIELD_REPAIR_SLOT = 25;

    private final PaperCorePlugin core;
    private final CraftingMaterialService materials;
    private final HubEconomyService economy;

    public ArmoryHubMenu(PaperCorePlugin core, CraftingMaterialService materials, HubEconomyService economy) {
        this.core = Objects.requireNonNull(core, "core");
        this.materials = Objects.requireNonNull(materials, "materials");
        this.economy = Objects.requireNonNull(economy, "economy");
    }

    public GuiMenuBuilder build() {
        GuiMenuBuilder menu = GuiMenuBuilder.create(Component.text("Armory", NamedTextColor.DARK_GRAY), ExtractionGuiLayout.SIZE)
                .fill(GuiItems.named(Material.GRAY_STAINED_GLASS_PANE, " ", List.of()))
                .button(ExtractionGuiLayout.CLOSE_SLOT, GuiButtonLibrary.close("Close the armory"), GuiClickContext::close)
                .button(ExtractionGuiLayout.BACK_SLOT, GuiButtonLibrary.back("Return to the previous menu"), ExtractionGuiLayout::backOrClose)
                .animatedButton(ExtractionGuiLayout.WALLET_SLOT, (viewer, tick) -> coinWalletButton(viewer), ctx -> {
                });

        // Material stash lives in the header row — deposits/withdrawals without leaving the armory.
        menu.animatedButton(ExtractionGuiLayout.HEADER_SLOT, (viewer, tick) -> MaterialStashButton.item(materials, viewer.getUniqueId()), ctx ->
                MaterialStashButton.handleClick(ctx, materials, () ->
                        core.guiManager().open(ctx.viewer(), build().build())));

        menu.button(INFUSE_SLOT, GuiItems.named(Material.ANVIL, "<gold>Infuse & Overclock", List.of(
                        "<gray>Deposit any Infuse piece — the bench",
                        "<gray>detects it and shows its sockets.",
                        "",
                        "<yellow>Click to open")),
                ctx -> ctx.open(new InfuseMenu(core, true)));
        menu.button(SALVAGE_SLOT, GuiItems.named(Material.GRINDSTONE, "<red>Salvage Bench", List.of(
                        "<gray>Break down gear for material refunds.",
                        "",
                        "<yellow>Click to open")),
                ctx -> ctx.open(new ArmorySalvageMenu(core, coreCraftingConfig(), materials)));
        menu.button(MARK_UPGRADE_SLOT, GuiItems.named(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE, "<aqua>Mark Upgrade", List.of(
                        "<gray>Raise armor marks on any piece.",
                        "",
                        "<yellow>Click to open")),
                ctx -> ctx.open(new ArmoryMarkUpgradeMenu(core, materials)));
        menu.button(SHIELD_REPAIR_SLOT, GuiItems.named(Material.SMITHING_TABLE, "<aqua>Shield Repair", List.of(
                        "<gray>Restore destroyed or depleted shields.",
                        "",
                        "<yellow>Click to open")),
                ctx -> ctx.open(new ArmoryRepairMenu(core, materials)));
        return menu;
    }

    private network.skypvp.extraction.crafting.CraftingConfigService coreCraftingConfig() {
        return materials.config();
    }

    private org.bukkit.inventory.ItemStack coinWalletButton(Player viewer) {
        long coins = economy.cachedCoins(viewer.getUniqueId());
        long gold = economy.cachedGold(viewer.getUniqueId());
        return GuiItems.named(Material.GOLD_INGOT, "<gold>Currency Wallet", List.of(
                "<gray>Coins: <yellow>" + CurrencyFormat.formatCoins(coins),
                "<gray>Gold: <yellow>" + CurrencyFormat.formatGold(gold),
                "<dark_gray>Spend with /blackmarket."
        ));
    }

    public static void notifyCoins(org.bukkit.entity.Player player, long coins) {
        // Legacy hook — coin wallet removed from material crafting loop.
    }
}
