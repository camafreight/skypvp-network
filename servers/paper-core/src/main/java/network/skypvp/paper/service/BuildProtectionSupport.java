package network.skypvp.paper.service;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;

public final class BuildProtectionSupport {
    private BuildProtectionSupport() {
    }

    public static boolean isProtectedLandscapeBlock(Material material) {
        if (material == null || material.isAir()) {
            return false;
        }
        return material == Material.FARMLAND
                || material == Material.DIRT_PATH
                || Tag.CROPS.isTagged(material)
                || Tag.FLOWER_POTS.isTagged(material)
                || Tag.SAPLINGS.isTagged(material)
                || Tag.LEAVES.isTagged(material)
                || Tag.REPLACEABLE.isTagged(material)
                || material.name().endsWith("_BED")
                || isAgeableCrop(material);
    }

    public static boolean isProtectedLandscapeBlock(Block block) {
        if (block == null) {
            return false;
        }
        Material material = block.getType();
        if (isProtectedLandscapeBlock(material)) {
            return true;
        }
        return block.getBlockData() instanceof Ageable;
    }

    private static boolean isAgeableCrop(Material material) {
        return switch (material) {
            case WHEAT, CARROTS, POTATOES, BEETROOTS, NETHER_WART, COCOA, SWEET_BERRY_BUSH, CAVE_VINES, CAVE_VINES_PLANT,
                 TORCHFLOWER, PITCHER_CROP, PITCHER_PLANT -> true;
            default -> false;
        };
    }
}
