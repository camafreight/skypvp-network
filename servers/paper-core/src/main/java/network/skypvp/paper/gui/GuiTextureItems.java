package network.skypvp.paper.gui;

import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Custom model helpers for GUI chrome icons from the {@code skypvp:ui_*} resource-pack items.
 */
public final class GuiTextureItems {

    public static final String UI_CLOSE = "ui_x";
    public static final String UI_BACK = "ui_left";
    public static final String UI_QUESTION = "ui_question_mark";
    public static final String UI_EXCLAMATION = "ui_exclamation";
    public static final String UI_SCROLL_UP = "ui_scroll_up";
    public static final String UI_SCROLL_DOWN = "ui_scroll_down";
    public static final String UI_BLANK = "ui_blank";

    /** Neutral base material — art comes from {@link ItemMeta#setItemModel}. */
    private static final Material CHROME_BASE = Material.IRON_BARS;

    private GuiTextureItems() {
    }

    public static ItemStack chrome(String modelId, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(CHROME_BASE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            if (lore != null && !lore.isEmpty()) {
                meta.lore(lore);
            }
            meta.setItemModel(modelKey(modelId));
            item.setItemMeta(meta);
        }
        return item;
    }

    public static ItemStack chrome(String modelId, String miniTitle, List<String> miniLore) {
        return chrome(
                modelId,
                network.skypvp.shared.ServerTextUtil.miniMessageComponent(miniTitle),
                miniLore == null ? List.of() : miniLore.stream()
                        .map(network.skypvp.shared.ServerTextUtil::miniMessageComponent)
                        .toList()
        );
    }

    public static void applyModel(ItemStack item, String modelId) {
        if (item == null || modelId == null || modelId.isBlank()) {
            return;
        }
        item.editMeta(meta -> meta.setItemModel(modelKey(modelId)));
    }

    public static NamespacedKey modelKey(String modelId) {
        return new NamespacedKey("skypvp", modelId);
    }

    /** @deprecated prefer {@link #chrome(String, Component, List)} with pack item models */
    @Deprecated
    public static ItemStack chromePane(Material material, int customModelData) {
        ItemStack item = new ItemStack(material == null ? Material.GRAY_STAINED_GLASS_PANE : material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setCustomModelData(customModelData);
            meta.displayName(Component.empty());
            item.setItemMeta(meta);
        }
        return item;
    }
}
