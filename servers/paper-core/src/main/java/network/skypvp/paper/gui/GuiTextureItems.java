package network.skypvp.paper.gui;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Helpers for building animated GUI chrome items (typically custom model data from a resource pack).
 */
public final class GuiTextureItems {

    private GuiTextureItems() {
    }

    public static ItemStack chromePane(Material material, int customModelData) {
        ItemStack item = new ItemStack(material == null ? Material.GRAY_STAINED_GLASS_PANE : material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setCustomModelData(customModelData);
            meta.displayName(net.kyori.adventure.text.Component.empty());
            item.setItemMeta(meta);
        }
        return item;
    }
}
