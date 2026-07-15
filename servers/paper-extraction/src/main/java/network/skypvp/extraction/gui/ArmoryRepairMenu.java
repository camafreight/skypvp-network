package network.skypvp.extraction.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.extraction.crafting.CraftingMaterialService;
import network.skypvp.extraction.item.InfuseArmorMutator;
import network.skypvp.extraction.item.ShieldSocketReference;
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

/** Repairs destroyed or depleted shields socketed in Infuse chestplates. */
public final class ArmoryRepairMenu extends GuiWorkstationMenu {

    public static final int SIZE = ExtractionGuiLayout.SIZE;
    public static final int CLOSE_SLOT = ExtractionGuiLayout.CLOSE_SLOT;
    public static final int BACK_SLOT = ExtractionGuiLayout.BACK_SLOT;
    public static final int INFO_SLOT = ExtractionGuiLayout.HEADER_SLOT;
    public static final int ARMOR_SLOT = ExtractionGuiLayout.WORKSTATION_INPUT_SLOT;
    public static final int REPAIR_SLOT = ExtractionGuiLayout.WORKSTATION_ACTION_SLOT;

    private final PaperCorePlugin core;
    private final CraftingMaterialService materials;

    public ArmoryRepairMenu(PaperCorePlugin core, CraftingMaterialService materials) {
        this.core = Objects.requireNonNull(core, "core");
        this.materials = Objects.requireNonNull(materials, "materials");
    }

    @Override
    public Component title() {
        return Component.text("Shield Repair", NamedTextColor.AQUA);
    }

    @Override
    public int size() {
        return SIZE;
    }

    @Override
    protected int anchorSlot() {
        return ARMOR_SLOT;
    }

    @Override
    protected boolean acceptsAnchor(ItemStack stack) {
        CustomItemService service = core.customItemService();
        return InfuseArmorMutator.isInfuseChestplate(service, stack)
                || InfuseArmorMutator.isShieldModule(service, stack);
    }

    @Override
    protected ItemStack anchorPlaceholder() {
        return GuiItems.named(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<aqua>Deposit Item", List.of(
                "<gray>Place a chestplate with a socketed shield",
                "<gray>or a standalone shield module."
        ));
    }

    @Override
    protected Component anchorRejectedMessage() {
        return Component.text("Place an Infuse chestplate or shield module.", NamedTextColor.RED);
    }

    @Override
    protected void buildFrame(GuiWorkstationFrame frame, Player viewer, ItemStack anchor) {
        frame.filler(GuiItems.named(Material.GRAY_STAINED_GLASS_PANE, " ", List.of()));
        frame.button(CLOSE_SLOT, GuiButtonLibrary.close("Close shield repair"), ctx -> ctx.close());
        frame.button(BACK_SLOT, GuiButtonLibrary.back("Return to the armory"), ExtractionGuiLayout::backOrClose);
        frame.decoration(INFO_SLOT, GuiButtonLibrary.menuHeader("<aqua>Shield Repair", lore -> {
            lore.plain("Restores buffer and integrity.");
            lore.plain("Chestplate (socketed) or shield module.");
        }));
        frame.decoration(ExtractionGuiLayout.WALLET_SLOT, GuiItems.named(Material.CHEST, "<gold>Materials", List.of(
                "<gray>Capacitor Cells: <yellow>" + materials.balance(viewer.getUniqueId(), "capacitor_cell")
        )));

        if (anchor == null) {
            frame.decoration(REPAIR_SLOT, GuiItems.named(Material.BARRIER, "<aqua>Repair", List.of(
                    "<gray>Deposit armor first."
            )));
            return;
        }

        InfuseArmorMutator.RepairMutation preview =
                InfuseArmorMutator.repairShield(core.customItemService(), anchor);
        List<String> lore = new ArrayList<>();
        if (!preview.success()) {
            lore.add("<red>" + preview.message());
        } else {
            int cost = Math.max(2, (int) (preview.coinCost() / 50L));
            lore.add("<gray>Full shield restoration");
            lore.add("<gold>Cost: " + cost + "x Capacitor Cell");
            lore.add("<gray>Owned: " + materials.balance(viewer.getUniqueId(), "capacitor_cell"));
            lore.add("");
            lore.add("<yellow>Click to repair");
        }
        Material icon = preview.success() ? Material.SMITHING_TABLE : Material.BARRIER;
        frame.button(REPAIR_SLOT, GuiItems.named(icon, "<aqua>Repair Shield", lore), ctx -> {
            Player player = ctx.viewer();
            ItemStack deposited = ctx.event().getView().getTopInventory().getItem(ARMOR_SLOT);
            InfuseArmorMutator.RepairMutation result =
                    InfuseArmorMutator.repairShield(core.customItemService(), deposited);
            if (!result.success()) {
                NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
                send(player, "<red>" + result.message());
                return;
            }
            int cost = Math.max(2, (int) (result.coinCost() / 50L));
            if (!materials.trySpend(player.getUniqueId(), java.util.Map.of("capacitor_cell", cost))) {
                NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
                send(player, "<red>Not enough Capacitor Cells. Need <gold>" + cost + "<red>.");
                return;
            }
            ctx.event().getView().getTopInventory().setItem(ARMOR_SLOT, result.updatedArmor());
            NetworkSoundCue.UI_BUTTON_CLICK.play(player);
            send(player, "<green>Shield repaired successfully.");
            ctx.reopen(this);
        });
    }

    private void send(Player player, String miniMessage) {
        player.sendMessage(ExtractionTexts.miniMessageTemplate(miniMessage, ExtractionTexts.locale(player)));
    }
}
