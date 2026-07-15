package network.skypvp.paper.integration;

import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.java.JavaPlugin;

/** Applies real sprint state to combat NPCs via NMS and optional LibsDisguises metadata. */
public final class LivingEntitySprintBridge {

    private final Logger logger;
    private final boolean nmsAvailable;
    private final Method getHandleMethod;
    private final Method setNmsSprintingMethod;

    public LivingEntitySprintBridge(JavaPlugin plugin) {
        this.logger = plugin.getLogger();
        Method getHandle = null;
        Method setSprinting = null;
        boolean available = false;
        try {
            Class<?> craftLiving = Class.forName("org.bukkit.craftbukkit.entity.CraftLivingEntity");
            getHandle = craftLiving.getMethod("getHandle");
            Class<?> nmsLiving = Class.forName("net.minecraft.world.entity.LivingEntity");
            setSprinting = nmsLiving.getMethod("setSprinting", boolean.class);
            available = true;
        } catch (ReflectiveOperationException exception) {
            logger.warning("[SkyPvP] LivingEntity sprint bridge unavailable: " + exception.getMessage());
        }
        this.getHandleMethod = getHandle;
        this.setNmsSprintingMethod = setSprinting;
        this.nmsAvailable = available;
    }

    public boolean isAvailable() {
        return nmsAvailable;
    }

    public void setSprinting(LivingEntity entity, boolean sprinting) {
        if (entity == null) {
            return;
        }
        setNmsSprinting(entity, sprinting);
        setDisguiseSprinting(entity, sprinting);
    }

    private void setNmsSprinting(LivingEntity entity, boolean sprinting) {
        if (!nmsAvailable) {
            return;
        }
        try {
            Object handle = getHandleMethod.invoke(entity);
            setNmsSprintingMethod.invoke(handle, sprinting);
        } catch (ReflectiveOperationException exception) {
            logger.log(Level.FINE, "[SkyPvP] Failed to set NMS sprint state", exception);
        }
    }

    private static void setDisguiseSprinting(LivingEntity entity, boolean sprinting) {
        if (entity == null || !isPlayerDisguised(entity)) {
            return;
        }
        try {
            Class<?> disguiseApi = Class.forName("me.libraryaddict.disguise.DisguiseAPI");
            Object disguise = disguiseApi.getMethod("getDisguise", Entity.class).invoke(null, entity);
            if (disguise == null) {
                return;
            }
            Object watcher = disguise.getClass().getMethod("getWatcher").invoke(disguise);
            watcher.getClass().getMethod("setSprinting", boolean.class).invoke(watcher, sprinting);
        } catch (ReflectiveOperationException ignored) {
            // Optional across LibsDisguises versions.
        }
    }

    private static boolean isPlayerDisguised(LivingEntity entity) {
        try {
            Class<?> disguiseApi = Class.forName("me.libraryaddict.disguise.DisguiseAPI");
            Object disguise = disguiseApi.getMethod("getDisguise", Entity.class).invoke(null, entity);
            return disguise != null;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }
}
