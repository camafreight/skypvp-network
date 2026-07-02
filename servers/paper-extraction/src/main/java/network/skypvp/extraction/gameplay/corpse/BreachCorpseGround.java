package network.skypvp.extraction.gameplay.corpse;

import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

public final class BreachCorpseGround {

    // surfaceY is the solid block the player died on; its top face is at surfaceY + 1.0. The prone (SWIMMING) body
    // renders around the entity's feet, so anchoring just above the block top lays it flat ON the ground instead of
    // sunk inside the block.
    private static final double BODY_Y_OFFSET = 1.0;

    private BreachCorpseGround() {
    }

    public record Anchor(Location bodyLocation) {
    }

    public static Optional<Anchor> resolve(Location deathLocation) {
        if (deathLocation == null || deathLocation.getWorld() == null) {
            return Optional.empty();
        }
        World world = deathLocation.getWorld();
        int blockX = deathLocation.getBlockX();
        int blockZ = deathLocation.getBlockZ();
        int surfaceY = findSurfaceY(world, blockX, blockZ, deathLocation.getBlockY());
        if (surfaceY < world.getMinHeight()) {
            return Optional.empty();
        }
        Location body = new Location(
                world,
                blockX + 0.5,
                surfaceY + BODY_Y_OFFSET,
                blockZ + 0.5,
                deathLocation.getYaw(),
                0.0F
        );
        return Optional.of(new Anchor(body));
    }

    private static int findSurfaceY(World world, int blockX, int blockZ, int startY) {
        int y = Math.min(startY, world.getMaxHeight() - 1);
        for (; y >= world.getMinHeight(); y--) {
            Material type = world.getBlockAt(blockX, y, blockZ).getType();
            if (type.isSolid() && type != Material.BARRIER) {
                return y;
            }
        }
        return world.getMinHeight() - 1;
    }
}
