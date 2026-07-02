package network.skypvp.extraction.integration;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import network.skypvp.extraction.model.BreachMapMeta;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

public final class BreachWorldGuardBridge {

    private final Logger logger;
    private final boolean worldGuardPresent;
    private final Method fuzzyMatchFlag;
    private final Class<?> stateFlagStateClass;

    public BreachWorldGuardBridge(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        this.logger = plugin.getLogger();
        Method matchFlag = null;
        Class<?> stateClass = null;
        boolean present = false;

        try {
            Class.forName("com.sk89q.worldguard.WorldGuard");
            present = true;
            try {
                Class<?> flagsClass = Class.forName("com.sk89q.worldguard.protection.flags.Flags");
                matchFlag = flagsClass.getMethod("fuzzyMatchFlag", String.class);
                stateClass = Class.forName("com.sk89q.worldguard.protection.flags.StateFlag$State");
            } catch (ReflectiveOperationException ignored) {
                matchFlag = null;
                stateClass = null;
            }
        } catch (ReflectiveOperationException ignored) {
            present = false;
        }

        this.worldGuardPresent = present;
        this.fuzzyMatchFlag = matchFlag;
        this.stateFlagStateClass = stateClass;

        if (worldGuardPresent) {
            logger.info("[Breach] WorldGuard bridge enabled.");
        } else {
            logger.info("[Breach] WorldGuard not present; region bridge disabled.");
        }
    }

    public boolean isAvailable() {
        return worldGuardPresent;
    }

    public void applyRegions(World world, List<BreachMapMeta.WorldGuardRegion> regions) {
        if (!worldGuardPresent || world == null || regions == null || regions.isEmpty()) {
            return;
        }

        try {
            Class<?> worldGuardClass = Class.forName("com.sk89q.worldguard.WorldGuard");
            Object wgInstance = worldGuardClass.getMethod("getInstance").invoke(null);
            Object platform = wgInstance.getClass().getMethod("getPlatform").invoke(wgInstance);
            Object regionContainer = platform.getClass().getMethod("getRegionContainer").invoke(platform);

            Class<?> bukkitAdapterClass = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
            Object adaptedWorld = bukkitAdapterClass.getMethod("adapt", World.class).invoke(null, world);
            Object regionManager = regionContainer.getClass()
                    .getMethod("get", Class.forName("com.sk89q.worldedit.world.World"))
                    .invoke(regionContainer, adaptedWorld);
            if (regionManager == null) {
                return;
            }

            Method getRegion = regionManager.getClass().getMethod("getRegion", String.class);
            Method setFlag = regionManager.getClass().getMethod("setFlag", String.class,
                    Class.forName("com.sk89q.worldguard.protection.flags.Flag"), Object.class);

            for (BreachMapMeta.WorldGuardRegion regionDef : regions) {
                Object region = getRegion.invoke(regionManager, regionDef.regionName());
                if (region == null) {
                    logger.fine("[Breach] WorldGuard region not found: " + regionDef.regionName());
                    continue;
                }
                for (Map.Entry<String, String> entry : regionDef.flags().entrySet()) {
                    if (fuzzyMatchFlag == null) {
                        continue;
                    }
                    Object flag = fuzzyMatchFlag.invoke(null, entry.getKey().toLowerCase(Locale.ROOT));
                    if (flag == null) {
                        continue;
                    }
                    Object parsedValue = parseFlagValue(entry.getValue());
                    setFlag.invoke(regionManager, regionDef.regionName(), flag, parsedValue);
                }
            }
        } catch (ReflectiveOperationException ex) {
            logger.warning("[Breach] Failed to apply WorldGuard regions: " + ex.getMessage());
        }
    }

    private Object parseFlagValue(String raw) throws ReflectiveOperationException {
        if (raw == null || stateFlagStateClass == null) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if ("allow".equals(normalized) || "true".equals(normalized)) {
            return Enum.valueOf(stateFlagStateClass.asSubclass(Enum.class), "ALLOW");
        }
        if ("deny".equals(normalized) || "false".equals(normalized)) {
            return Enum.valueOf(stateFlagStateClass.asSubclass(Enum.class), "DENY");
        }
        return raw;
    }
}
