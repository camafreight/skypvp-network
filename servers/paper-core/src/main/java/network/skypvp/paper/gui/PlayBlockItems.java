package network.skypvp.paper.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockDataMeta;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Lobby play-signage blocks from the {@code skypvp:play_block_*} resource-pack item models.
 * Placed as {@link Material#LIGHT} with pack-remapped levels {@link #LIGHT_LEVEL_01} / {@link #LIGHT_LEVEL_02}.
 */
public final class PlayBlockItems {

    public static final String MODEL_01 = "play_block_01";
    public static final String MODEL_02 = "play_block_02";
    /** Pack maps {@code light[level=14]} → {@code skypvp:block/play_block_01}. */
    public static final int LIGHT_LEVEL_01 = 14;
    /** Pack maps {@code light[level=15]} → {@code skypvp:block/play_block_02}. */
    public static final int LIGHT_LEVEL_02 = 15;

    private PlayBlockItems() {
    }

    public static ItemStack playBlock01() {
        return placeable(MODEL_01, LIGHT_LEVEL_01, "Play Block (PL)");
    }

    public static ItemStack playBlock02() {
        return placeable(MODEL_02, LIGHT_LEVEL_02, "Play Block (AY)");
    }

    private static ItemStack placeable(String modelId, int lightLevel, String label) {
        ItemStack stack = new ItemStack(Material.LIGHT);
        BlockData data = Bukkit.createBlockData(Material.LIGHT);
        if (data instanceof Levelled levelled) {
            levelled.setLevel(lightLevel);
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof BlockDataMeta blockDataMeta) {
            blockDataMeta.setBlockData(data);
            stack.setItemMeta(blockDataMeta);
        }
        stack.editMeta(editMeta -> applyMeta(editMeta, modelId, label));
        return stack;
    }

    private static void applyMeta(ItemMeta meta, String modelId, String label) {
        meta.setItemModel(modelKey(modelId));
        meta.displayName(Component.text(label));
    }

    public static NamespacedKey modelKey(String modelId) {
        return new NamespacedKey("skypvp", modelId);
    }
}
