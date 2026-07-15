package network.skypvp.extraction.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import network.skypvp.extraction.item.ArmorMark;
import network.skypvp.extraction.item.ArmorModuleType;
import network.skypvp.extraction.item.ExtractionCustomItemProvider;
import network.skypvp.extraction.item.InfuseArmorMutator;
import network.skypvp.extraction.item.InfuseArmorPiece;
import network.skypvp.extraction.item.InfuseArmorPayload;
import network.skypvp.extraction.item.ShieldSocketReference;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gui.GuiButtonLibrary;
import network.skypvp.paper.gui.GuiItems;
import network.skypvp.paper.gui.GuiWorkstationFrame;
import network.skypvp.paper.gui.GuiWorkstationMenu;
import network.skypvp.paper.gui.GuiWorkstationSocket;
import network.skypvp.paper.gui.GuiWorkstationSocket.InstallOutcome;
import network.skypvp.paper.gui.GuiWorkstationSocket.RemoveOutcome;
import network.skypvp.paper.item.api.CustomItemService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Infuse &amp; Overclocking station. This is a pure, declarative {@link GuiWorkstationMenu}: it describes the deposit
 * slot, the socket icons, and the domain transforms (via {@link InfuseArmorMutator}). All inventory/cursor/shift/close
 * handling lives in the core framework, so the deposited chestplate can never be lost or duplicated.
 */
public final class InfuseMenu extends GuiWorkstationMenu {

    public static final int SIZE = ExtractionGuiLayout.SIZE;
    public static final int CLOSE_SLOT = ExtractionGuiLayout.CLOSE_SLOT;
    public static final int INFO_SLOT = ExtractionGuiLayout.HEADER_SLOT;
    public static final int BACK_SLOT = ExtractionGuiLayout.BACK_SLOT;
    public static final int ARMOR_SLOT = 13;
    public static final int SHIELD_SLOT = 22;
    public static final int[] MODULE_SLOTS = {29, 30, 31, 32, 33};
    public static final int OVERCLOCK_SLOT = 40;

    private final PaperCorePlugin core;
    private final boolean hasBack;

    public InfuseMenu(PaperCorePlugin core, boolean hasBack) {
        this.core = core;
        this.hasBack = hasBack;
    }

    private CustomItemService service() {
        return core.customItemService();
    }

    @Override
    public Component title() {
        return Component.text("Infuse & Overclocking", NamedTextColor.DARK_AQUA);
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
        return InfuseArmorMutator.isInfuseArmor(service(), stack);
    }

    @Override
    protected ItemStack anchorPlaceholder() {
        return icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "Deposit Infuse Armor", NamedTextColor.AQUA,
                List.of("Place any Infuse armor piece here", "to configure its sockets."));
    }

    @Override
    protected Component anchorRejectedMessage() {
        return Component.text("Only Infuse armor can go here.", NamedTextColor.RED);
    }

    @Override
    protected void buildFrame(GuiWorkstationFrame frame, Player viewer, ItemStack anchor) {
        frame.filler(GuiItems.named(Material.GRAY_STAINED_GLASS_PANE, " ", List.of()));
        frame.button(CLOSE_SLOT, GuiButtonLibrary.close("Close this menu"), context -> context.close());
        if (hasBack) {
            frame.button(BACK_SLOT, GuiButtonLibrary.back("Return to the armory"), ExtractionGuiLayout::backOrClose);
        }
        InfuseArmorPiece piece = InfuseArmorMutator.armorPieceOf(service(), anchor).orElse(InfuseArmorPiece.CHESTPLATE);
        frame.decoration(INFO_SLOT, GuiButtonLibrary.menuHeader("<aqua>Infuse & Overclock", lore -> {
            lore.plain("Deposit Infuse armor, then socket");
            lore.plain("compatible modules for that piece.");
            lore.plain(piece.isChestplate()
                    ? "Shields/overclocks: chestplate only."
                    : piece.label() + ": " + (anchor == null ? "?" : payloadFor(anchor).rarity().moduleSockets()) + " module socket(s).");
        }));

        if (anchor == null) {
            ItemStack locked = icon(Material.BARRIER, "Locked", NamedTextColor.RED,
                    List.of("Deposit Infuse armor first."));
            frame.decoration(SHIELD_SLOT, locked);
            for (int slot : MODULE_SLOTS) {
                frame.decoration(slot, locked);
            }
            frame.decoration(OVERCLOCK_SLOT, locked);
            return;
        }

        InfuseArmorPayload payload = payloadFor(anchor);
        if (piece.isChestplate()) {
            buildShieldSocket(frame, anchor, payload);
            buildOverclockSocket(frame, anchor, payload);
        } else {
            frame.decoration(SHIELD_SLOT, icon(Material.BARRIER, "Chestplate Only", NamedTextColor.DARK_GRAY,
                    List.of("Shield sockets are on chestplates.")));
            frame.decoration(OVERCLOCK_SLOT, icon(Material.BARRIER, "Chestplate Only", NamedTextColor.DARK_GRAY,
                    List.of("Overclocks are on chestplates.")));
        }
        buildModuleSockets(frame, anchor, payload, piece);
    }

    private InfuseArmorPayload payloadFor(ItemStack anchor) {
        return InfuseArmorPayload.decode(service().resolve(anchor).orElseThrow().payloadCopy());
    }

    private void buildShieldSocket(GuiWorkstationFrame frame, ItemStack anchor, InfuseArmorPayload payload) {
        if (!payload.rarity().hasShieldSlot()) {
            frame.decoration(SHIELD_SLOT, icon(Material.BARRIER, "No Shield Socket", NamedTextColor.DARK_GRAY,
                    List.of(payload.rarity().displayName() + " armor has no shield socket.")));
            return;
        }
        Optional<ShieldSocketReference> socketed = ShieldSocketReference.decode(
                payload.shieldModule() == null ? "" : payload.shieldModule());
        if (socketed.isEmpty()) {
            ArmorMark mark = payload.mark() == null ? ArmorMark.MK1 : payload.mark();
            if (mark.level() < ArmorMark.MK2.level()) {
                frame.decoration(SHIELD_SLOT, icon(Material.BARRIER, "Shield Socket Locked", NamedTextColor.DARK_GRAY,
                        List.of("Requires armor " + ArmorMark.MK2.displayName() + "+.")));
                return;
            }
            ItemStack empty = icon(Material.YELLOW_STAINED_GLASS_PANE, "Empty Shield Socket", NamedTextColor.GOLD,
                    List.of("Click a shield module here to install."));
            frame.socket(SHIELD_SLOT, GuiWorkstationSocket.builder(empty)
                    .accepts(stack -> InfuseArmorMutator.isShieldModule(service(), stack))
                    .onInstall(offered -> {
                        InfuseArmorMutator.ShieldMutation result =
                                InfuseArmorMutator.installShieldToStack(service(), anchor, offered);
                        return result.success()
                                ? InstallOutcome.accept(result.updatedArmor())
                                : InstallOutcome.reject(result.message());
                    })
                    .build());
            return;
        }
        ShieldSocketReference shield = socketed.get();
        frame.socket(SHIELD_SLOT, GuiWorkstationSocket.builder(shieldIcon(shield))
                .filled(true)
                .onRemove(() -> {
                    InfuseArmorMutator.ShieldClickResult result =
                            InfuseArmorMutator.uninstallShieldToStack(service(), anchor);
                    return result.success()
                            ? RemoveOutcome.success(result.updatedArmor(),
                                    ExtractionCustomItemProvider.createShieldModule(
                                            service(), result.shieldRarity(), result.variantId()))
                            : RemoveOutcome.fail(result.message());
                })
                .build());
    }

    private void buildModuleSockets(GuiWorkstationFrame frame, ItemStack anchor, InfuseArmorPayload payload, InfuseArmorPiece piece) {
        // Non-chest pieces carry a single piece-specific socket (stored at index 0 — the
        // legacy pieceModule field migrates there on decode); chest sockets scale by rarity.
        int sockets = piece.isChestplate()
                ? payload.rarity().moduleSockets()
                : Math.min(1, payload.rarity().moduleSockets());
        List<String> modules = payload.moduleSockets();
        for (int i = 0; i < MODULE_SLOTS.length; i++) {
            int slot = MODULE_SLOTS[i];
            if (i >= sockets) {
                frame.decoration(slot, icon(Material.BARRIER, "No Module Socket", NamedTextColor.DARK_GRAY,
                        List.of(piece.isChestplate()
                                ? payload.rarity().displayName() + " armor has " + sockets + " module socket(s)."
                                : piece.label() + "s have one module socket.")));
                continue;
            }
            Optional<ArmorModuleType> installed = ArmorModuleType.byId(modules.get(i));
            int index = i;
            if (installed.isEmpty()) {
                ItemStack empty = icon(Material.LIGHT_BLUE_STAINED_GLASS_PANE, "Empty Module Socket " + (i + 1),
                        NamedTextColor.AQUA, List.of("Click a compatible module to install."));
                frame.socket(slot, GuiWorkstationSocket.builder(empty)
                        .accepts(stack -> {
                            Optional<ArmorModuleType> type = InfuseArmorMutator.moduleTypeOf(service(), stack);
                            return type.isPresent()
                                    && !type.get().overclock()
                                    && type.get().compatiblePieces().contains(piece);
                        })
                        .onInstall(offered -> {
                            Optional<ArmorModuleType> type = InfuseArmorMutator.moduleTypeOf(service(), offered);
                            if (type.isEmpty() || type.get().overclock()) {
                                return InstallOutcome.reject("That isn't an armor module.");
                            }
                            InfuseArmorMutator.ModuleMutation result =
                                    InfuseArmorMutator.installModule(service(), anchor, index, type.get());
                            return result.success()
                                    ? InstallOutcome.accept(result.updatedArmor())
                                    : InstallOutcome.reject(result.message());
                        })
                        .build());
                continue;
            }
            ArmorModuleType type = installed.get();
            frame.socket(slot, GuiWorkstationSocket.builder(moduleIcon(type))
                    .filled(true)
                    .onRemove(() -> {
                        InfuseArmorMutator.ModuleMutation result =
                                InfuseArmorMutator.removeModule(service(), anchor, index);
                        return result.success()
                                ? RemoveOutcome.success(result.updatedArmor(),
                                        ExtractionCustomItemProvider.createArmorModule(service(), type))
                                : RemoveOutcome.fail(result.message());
                    })
                    .build());
        }
    }

    private void buildOverclockSocket(GuiWorkstationFrame frame, ItemStack anchor, InfuseArmorPayload payload) {
        if (payload.rarity().overclockSockets() <= 0) {
            frame.decoration(OVERCLOCK_SLOT, icon(Material.BARRIER, "No Overclock Socket", NamedTextColor.DARK_GRAY,
                    List.of(payload.rarity().displayName() + " armor has no overclock socket.")));
            return;
        }
        Optional<ArmorModuleType> installed = ArmorModuleType.byId(payload.overclockModule());
        if (installed.isEmpty()) {
            ItemStack empty = icon(Material.MAGENTA_STAINED_GLASS_PANE, "Empty Overclock Socket",
                    NamedTextColor.LIGHT_PURPLE, List.of("Click an overclock module here to install."));
            frame.socket(OVERCLOCK_SLOT, GuiWorkstationSocket.builder(empty)
                    .accepts(stack -> {
                        Optional<ArmorModuleType> type = InfuseArmorMutator.moduleTypeOf(service(), stack);
                        return type.isPresent() && type.get().overclock();
                    })
                    .onInstall(offered -> {
                        Optional<ArmorModuleType> type = InfuseArmorMutator.moduleTypeOf(service(), offered);
                        if (type.isEmpty() || !type.get().overclock()) {
                            return InstallOutcome.reject("Only overclock modules fit here.");
                        }
                        InfuseArmorMutator.ModuleMutation result =
                                InfuseArmorMutator.installOverclock(service(), anchor, type.get());
                        return result.success()
                                ? InstallOutcome.accept(result.updatedArmor())
                                : InstallOutcome.reject(result.message());
                    })
                    .build());
            return;
        }
        ArmorModuleType type = installed.get();
        frame.socket(OVERCLOCK_SLOT, GuiWorkstationSocket.builder(moduleIcon(type))
                .filled(true)
                .onRemove(() -> {
                    InfuseArmorMutator.ModuleMutation result =
                            InfuseArmorMutator.removeOverclock(service(), anchor);
                    return result.success()
                            ? RemoveOutcome.success(result.updatedArmor(),
                                    ExtractionCustomItemProvider.createArmorModule(service(), type))
                            : RemoveOutcome.fail(result.message());
                })
                .build());
    }

    // ---- Icon builders ------------------------------------------------------------------------------------

    private ItemStack shieldIcon(ShieldSocketReference shield) {
        List<String> lore = new ArrayList<>();
        if (shield.destroyed()) {
            lore.add("<red>SHIELD DOWN");
        } else {
            double ratio = shield.maxPoints() <= 0 ? 0 : shield.currentPoints() / shield.maxPoints();
            lore.add("<gray>Buffer: " + bar(ratio, "<aqua>") + " <aqua>" + Math.round(ratio * 100) + "%");
        }
        double integ = shield.integrity() <= 0 ? 0 : shield.remainingIntegrity() / shield.integrity();
        lore.add("<gray>Integrity: " + bar(integ, "<green>") + " <green>" + Math.round(integ * 100) + "%");
        lore.add("");
        lore.add("<gray>Click to remove");
        // Canonical shield module stack \u2014 carries the skypvp:shield_module art.
        ItemStack canonical = ExtractionCustomItemProvider.createShieldModule(
                service(), shield.shieldRarity(), shield.variantId());
        return GuiItems.restyled(canonical, "<gold>" + shield.displayLabel(), lore);
    }

    private ItemStack moduleIcon(ArmorModuleType type) {
        List<String> lore = new ArrayList<>();
        lore.add(type.overclock() ? "<gray>Overclock Module" : "<gray>Armor Module");
        lore.add("");
        for (ArmorModuleType.ModuleEffect effect : type.effects()) {
            String prefix = effect.positive() ? "<green>\u25B2 +" : "<red>\u25BC -";
            lore.add(prefix + effect.label());
        }
        lore.add("");
        lore.add("<gray>Click to remove");
        // Canonical module stack so future module art/model changes propagate here.
        ItemStack canonical = ExtractionCustomItemProvider.createArmorModule(service(), type);
        return GuiItems.restyled(canonical, colorTag(type.color()) + type.displayName(), lore);
    }

    private ItemStack icon(Material material, String name, NamedTextColor color, List<String> loreLines) {
        List<String> lore = new ArrayList<>();
        for (String line : loreLines) {
            lore.add(line.isEmpty() ? "" : "<gray>" + line);
        }
        return GuiItems.named(material, colorTag(color) + name, lore);
    }

    private static String bar(double ratio, String fillTag) {
        int segments = 10;
        double clamped = Math.max(0.0D, Math.min(1.0D, ratio));
        int filled = (int) Math.round(segments * clamped);
        if (filled == 0 && clamped > 0.0D) {
            filled = 1;
        }
        if (filled > segments) {
            filled = segments;
        }
        return "<dark_gray>[" + fillTag + "\u2588".repeat(filled)
                + "<dark_gray>" + "\u2588".repeat(segments - filled) + "]";
    }

    private static String colorTag(TextColor color) {
        return "<" + color.asHexString() + ">";
    }
}
