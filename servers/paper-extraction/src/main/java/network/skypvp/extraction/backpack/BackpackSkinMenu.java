package network.skypvp.extraction.backpack;

import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import network.skypvp.extraction.item.BackpackSkins;
import network.skypvp.extraction.text.ExtractionTexts;
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

/**
 * Skin browser for the worn backpack. Every {@link BackpackSkins} variant previews with its
 * real pack model at the wearer's tier; unlocks are permission-driven, the equipped choice
 * lives in the item payload. Back returns to the pack view.
 */
final class BackpackSkinMenu implements GuiMenu {

    private static final int SIZE = 45;
    private static final int BACK_SLOT = 40;
    private static final int[] SKIN_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };

    private final BackpackService service;
    private final Player owner;
    private final int tier;

    BackpackSkinMenu(BackpackService service, Player owner, int tier) {
        this.service = Objects.requireNonNull(service, "service");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.tier = tier;
    }

    @Override
    public Component title() {
        return ExtractionTexts.miniMessageTemplate(
                "<gold><bold>Backpack Skins</bold></gold>", ExtractionTexts.locale(owner));
    }

    @Override
    public int size() {
        return SIZE;
    }

    @Override
    public void render(Player viewer, Inventory inventory) {
        ItemStack filler = filler();
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, filler.clone());
        }
        String equipped = service.equippedSkin(viewer);
        List<BackpackSkins.Skin> skins = BackpackSkins.ALL;
        for (int index = 0; index < SKIN_SLOTS.length; index++) {
            inventory.setItem(SKIN_SLOTS[index],
                    index < skins.size() ? skinButton(viewer, skins.get(index), equipped) : filler.clone());
        }
        inventory.setItem(BACK_SLOT, GuiButtonLibrary.back("Return to your backpack"));
    }

    private ItemStack skinButton(Player viewer, BackpackSkins.Skin skin, String equipped) {
        boolean selected = skin.id().equals(equipped);
        boolean unlocked = BackpackSkins.isUnlocked(viewer, skin.id());
        String accent = String.format("#%06X", skin.color().value());
        GuiTextLibrary.LoreBuilder lore = GuiTextLibrary.lore()
                .plain("Cosmetic pack skin — all tiers");
        if (selected) {
            lore.footerStrong("<#55FF55>", "Currently equipped");
        } else if (unlocked) {
            lore.footerStrong("<#55FF55>", "Click to equip this skin");
        } else {
            lore.fact("Status", "Locked", "<#FF5555>")
                    .footer("<#888888>", "Unlock this skin in the store");
        }
        ItemStack item = GuiItems.named(
                Material.PAPER,
                GuiTextLibrary.title(unlocked ? accent : "#888888", skin.displayName()),
                lore.build()
        );
        item.editMeta(meta -> {
            meta.setItemModel(BackpackSkins.modelKey(tier, skin.id()));
            if (selected) {
                meta.setEnchantmentGlintOverride(true);
            }
        });
        return item;
    }

    private static ItemStack filler() {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        pane.editMeta(meta -> meta.displayName(Component.empty()));
        return pane;
    }

    @Override
    public void onClick(GuiClickContext context) {
        int rawSlot = context.rawSlot();
        if (rawSlot == BACK_SLOT) {
            service.reopenFromSkinMenu(context.viewer());
            return;
        }
        for (int index = 0; index < SKIN_SLOTS.length && index < BackpackSkins.ALL.size(); index++) {
            if (SKIN_SLOTS[index] != rawSlot) {
                continue;
            }
            BackpackSkins.Skin skin = BackpackSkins.ALL.get(index);
            if (!BackpackSkins.isUnlocked(context.viewer(), skin.id())) {
                context.playSound(NetworkSoundCue.UI_BUTTON_FAILURE);
                return;
            }
            if (service.applySkin(context.viewer(), skin.id())) {
                context.playSound(NetworkSoundCue.UI_BUTTON_CLICK);
                context.refresh();
            }
            return;
        }
    }
}
