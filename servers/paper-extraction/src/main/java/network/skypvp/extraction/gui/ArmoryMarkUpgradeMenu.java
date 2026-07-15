package network.skypvp.extraction.gui;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.extraction.crafting.CraftingMaterialService;
import network.skypvp.extraction.item.ArmorMark;
import network.skypvp.extraction.item.InfuseArmorPiece;
import network.skypvp.extraction.item.InfuseArmorMutator;
import network.skypvp.extraction.item.InfuseArmorPayload;
import network.skypvp.extraction.item.InfuseArmorPiece;
import network.skypvp.extraction.item.ShieldSlotRules;
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

/** Workbench for upgrading Infuse chestplate armor marks using crafting materials. */
public final class ArmoryMarkUpgradeMenu extends GuiWorkstationMenu {

    private static final int[] MARK_COSTS = {0, 4, 8, 14, 22, 32, 45};
    /** Player level required to HOLD each armor mark (index = mark level). Gates the tier ladder. */
    private static final int[] MARK_LEVEL_REQUIREMENTS = {0, 0, 5, 15, 30, 50, 70, 90};

    private static int levelRequirementFor(ArmorMark mark) {
        int index = Math.min(MARK_LEVEL_REQUIREMENTS.length - 1, Math.max(0, mark.level()));
        return MARK_LEVEL_REQUIREMENTS[index];
    }

    private final PaperCorePlugin core;
    private final CraftingMaterialService materials;

    public ArmoryMarkUpgradeMenu(PaperCorePlugin core, CraftingMaterialService materials) {
        this.core = Objects.requireNonNull(core, "core");
        this.materials = Objects.requireNonNull(materials, "materials");
    }

    @Override
    public Component title() {
        return Component.text("Mark Upgrade", NamedTextColor.AQUA);
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
        CustomItemService service = core.customItemService();
        return service != null && InfuseArmorMutator.isInfuseArmor(service, stack);
    }

    @Override
    protected ItemStack anchorPlaceholder() {
        return GuiItems.named(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<aqua>Deposit Armor", List.of(
                "<gray>Place any Infuse armor piece to",
                "<gray>upgrade its armor mark tier."
        ));
    }

    @Override
    protected Component anchorRejectedMessage() {
        return Component.text("Only Infuse armor can be upgraded here.", NamedTextColor.RED);
    }

    @Override
    protected void buildFrame(GuiWorkstationFrame frame, Player viewer, ItemStack anchor) {
        CustomItemService service = core.customItemService();
        frame.filler(GuiItems.named(Material.GRAY_STAINED_GLASS_PANE, " ", List.of()));
        frame.button(ExtractionGuiLayout.CLOSE_SLOT, GuiButtonLibrary.close("Close mark upgrade"), ctx -> ctx.close());
        frame.button(ExtractionGuiLayout.BACK_SLOT, GuiButtonLibrary.back("Return to the armory"), ExtractionGuiLayout::backOrClose);
        frame.decoration(ExtractionGuiLayout.HEADER_SLOT, GuiItems.named(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE, "<aqua>Mark Upgrade", List.of(
                "<gray>Upgrade armor marks on any Infuse piece",
                "<gray>to unlock higher-tier sockets."
        )));

        if (anchor == null) {
            frame.decoration(ExtractionGuiLayout.WORKSTATION_ACTION_SLOT, GuiItems.named(Material.BARRIER, "<aqua>Upgrade", List.of("<gray>Deposit armor first.")));
            return;
        }

        InfuseArmorPiece piece = InfuseArmorMutator.armorPieceOf(service, anchor).orElse(InfuseArmorPiece.CHESTPLATE);
        InfuseArmorPayload payload = InfuseArmorPayload.decode(service.resolve(anchor).map(i -> i.payloadCopy()).orElse(new byte[0]));
        ArmorMark current = payload.mark() == null ? ArmorMark.MK1 : payload.mark();
        ArmorMark next = nextMark(current, payload);
        if (next == null) {
            frame.decoration(ExtractionGuiLayout.WORKSTATION_ACTION_SLOT, GuiItems.named(Material.BARRIER, "<gray>Max Mark", List.of(
                    "<gray>Current: <white>" + current.displayName()
            )));
            return;
        }
        int cost = markCost(next);
        int owned = materials.balance(viewer.getUniqueId(), "alloy_plate");
        int requiredLevel = levelRequirementFor(next);
        int viewerLevel = core.playerLevelService() == null ? Integer.MAX_VALUE
                : core.playerLevelService().level(viewer.getUniqueId());
        boolean levelOk = viewerLevel >= requiredLevel;
        List<String> lore = List.of(
                "<gray>" + piece.label() + ": " + current.displayName() + " → <white>" + next.displayName(),
                "<gold>Cost: " + cost + "x Alloy Plate",
                "<gray>You own: " + owned,
                requiredLevel > 0
                        ? (levelOk ? "<gray>Requires level <green>" + requiredLevel
                                : "<red>Requires level " + requiredLevel + " <gray>(you are " + viewerLevel + ")")
                        : "<gray>No level requirement",
                "",
                !levelOk ? "<red>Level too low"
                        : (owned >= cost ? "<yellow>Click to upgrade" : "<red>Insufficient materials")
        );
        frame.button(ExtractionGuiLayout.WORKSTATION_ACTION_SLOT, GuiItems.named(Material.SMITHING_TABLE, "<aqua>Upgrade Mark", lore), ctx -> {
            Player player = ctx.viewer();
            ItemStack deposited = ctx.event().getView().getTopInventory().getItem(anchorSlot());
            if (!acceptsAnchor(deposited)) {
                NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
                return;
            }
            InfuseArmorPayload currentPayload = InfuseArmorPayload.decode(service.resolve(deposited).map(i -> i.payloadCopy()).orElse(new byte[0]));
            ArmorMark target = nextMark(currentPayload.mark(), currentPayload);
            if (target == null) {
                NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
                return;
            }
            ShieldSlotRules.Result validation = ShieldSlotRules.validateMarkUpgrade(currentPayload, target);
            if (validation instanceof ShieldSlotRules.Result.Failure failure) {
                NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
                player.sendMessage(ExtractionTexts.miniMessageTemplate("<red>" + failure.message(), ExtractionTexts.locale(player)));
                return;
            }
            if (core.playerLevelService() != null
                    && !core.playerLevelService().meetsLevel(player, levelRequirementFor(target), true)) {
                return;
            }
            int required = markCost(target);
            if (!materials.trySpend(player.getUniqueId(), java.util.Map.of("alloy_plate", required))) {
                NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
                player.sendMessage(ExtractionTexts.miniMessageTemplate("<red>Need <white>" + required + " Alloy Plate<red>.", ExtractionTexts.locale(player)));
                return;
            }
            ShieldSlotRules.Result result = InfuseArmorMutator.setMark(service, player, deposited, target);
            if (result instanceof ShieldSlotRules.Result.Failure failure) {
                materials.grant(player.getUniqueId(), "alloy_plate", required);
                NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
                player.sendMessage(ExtractionTexts.miniMessageTemplate("<red>" + failure.message(), ExtractionTexts.locale(player)));
                return;
            }
            NetworkSoundCue.UI_BUTTON_CLICK.play(player);
            player.sendMessage(ExtractionTexts.miniMessageTemplate("<green>Upgraded to <white>" + target.displayName() + "<green>.", ExtractionTexts.locale(player)));
            ctx.reopen(this);
        });
    }

    private static ArmorMark nextMark(ArmorMark current, InfuseArmorPayload payload) {
        ArmorMark[] values = ArmorMark.values();
        int maxLevel = payload.rarity().maxMark().level();
        for (ArmorMark mark : values) {
            if (mark.level() == current.level() + 1 && mark.level() <= maxLevel) {
                return mark;
            }
        }
        return null;
    }

    private static int markCost(ArmorMark mark) {
        int index = Math.min(MARK_COSTS.length - 1, Math.max(0, mark.level()));
        return MARK_COSTS[index];
    }
}
