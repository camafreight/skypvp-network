package network.skypvp.paper.library;

import java.util.logging.Logger;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;

public final class WorldGroundItemCleanup {

    private WorldGroundItemCleanup() {
    }

    public static int clearGroundItems(World world) {
        if (world == null) {
            return 0;
        }
        int removed = 0;
        for (Entity entity : world.getEntities()) {
            if (entity instanceof Item || entity instanceof ExperienceOrb) {
                entity.remove();
                removed++;
            }
        }
        return removed;
    }

    public static int clearGroundItemsInChunk(Chunk chunk) {
        if (chunk == null) {
            return 0;
        }
        int removed = 0;
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Item || entity instanceof ExperienceOrb) {
                entity.remove();
                removed++;
            }
        }
        return removed;
    }

    public static int clearGroundItems(World world, Logger logger, String reason) {
        int removed = clearGroundItems(world);
        if (removed > 0 && logger != null) {
            logger.info("[WorldCleanup] Removed " + removed + " ground item(s) in '" + world.getName() + "' (" + reason + ").");
        }
        return removed;
    }
}
