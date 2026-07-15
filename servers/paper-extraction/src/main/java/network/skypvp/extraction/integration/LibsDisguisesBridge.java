package network.skypvp.extraction.integration;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Applies player disguises through LibsDisguises (required by MythicMobs {@code Disguise: player} blocks).
 */
public final class LibsDisguisesBridge {

    private static final String STEVE_DISGUISE = "RuinsRaiderSteve";
    private static final String ALEX_DISGUISE = "RuinsRaiderAlex";

    private static volatile Boolean available;
    private static volatile Logger startupLogger;

    private LibsDisguisesBridge() {
    }

    public static boolean isAvailable() {
        if (available != null) {
            return available;
        }
        Plugin plugin = Bukkit.getPluginManager().getPlugin("LibsDisguises");
        available = plugin != null && plugin.isEnabled();
        return available;
    }

    public static void logStartupStatus(Logger logger) {
        startupLogger = logger;
        if (logger == null) {
            return;
        }
        if (!isAvailable()) {
            logger.warning("[Breach] LibsDisguises is NOT loaded — Ruins gunners will appear as husks. "
                    + "Run ./gradlew downloadLibsDisguisesPlugin and redeploy extraction.");
            return;
        }
        logger.info("[Breach] LibsDisguises detected — Ruins gunners will render as player models.");
        if (customDisguise(STEVE_DISGUISE) == null || customDisguise(ALEX_DISGUISE) == null) {
            // LibsDisguises 11.x rewrites plugins/LibsDisguises/disguises.yml as a TRANSLATION file at boot,
            // wiping the staged custom Disguises section. Register the bundled copies through the API instead;
            // without them every spawn falls back to a name-based skin whose Mojang lookup crashes on Folia.
            registerBundledDisguises(logger);
        }
        if (customDisguise(STEVE_DISGUISE) == null || customDisguise(ALEX_DISGUISE) == null) {
            logger.warning("[Breach] LibsDisguises is missing saved disguises RuinsRaiderSteve/RuinsRaiderAlex "
                    + "and bundled registration failed — gunner skins will use the slow name-lookup fallback.");
        }
    }

    private static void registerBundledDisguises(Logger logger) {
        try (java.io.InputStream in = LibsDisguisesBridge.class.getResourceAsStream("/ld/ruins-disguises.yml")) {
            if (in == null) {
                return;
            }
            org.bukkit.configuration.file.YamlConfiguration yaml =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                            new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            org.bukkit.configuration.ConfigurationSection section = yaml.getConfigurationSection("Disguises");
            if (section == null) {
                return;
            }
            Class<?> disguiseApi = Class.forName("me.libraryaddict.disguise.DisguiseAPI");
            Method addCustomDisguise = disguiseApi.getMethod("addCustomDisguise", String.class, String.class);
            for (String key : section.getKeys(false)) {
                String params = section.getString(key);
                if (params == null || params.isBlank() || customDisguise(key) != null) {
                    continue;
                }
                addCustomDisguise.invoke(null, key, params);
                logger.info("[Breach] Registered bundled LibsDisguises custom disguise '" + key + "'.");
            }
        } catch (Exception exception) {
            logger.warning("[Breach] Failed to register bundled raider disguises: " + exception.getMessage());
        }
    }

    public static boolean isPlayerDisguised(LivingEntity entity) {
        if (entity == null || !isAvailable()) {
            return false;
        }
        try {
            Class<?> disguiseApi = Class.forName("me.libraryaddict.disguise.DisguiseAPI");
            Method isDisguised = disguiseApi.getMethod("isDisguised", Entity.class);
            return (boolean) isDisguised.invoke(null, entity);
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }

    public static boolean applyPlayerDisguise(LivingEntity entity, String displayName, String skinName) {
        if (entity == null || !isAvailable()) {
            return false;
        }
        try {
            Object disguise = cloneCustomDisguise(disguiseKeyForSkin(skinName));
            if (disguise == null) {
                disguise = buildFallbackDisguise(skinName);
            }
            if (disguise == null) {
                logApplyFailure(skinName, new IllegalStateException("Could not construct player disguise"));
                return false;
            }

            String resolvedName = blankToDefault(displayName, "Raider");
            tryInvoke(disguise, "setEntity", Entity.class, entity);
            tryInvoke(disguise, "setName", String.class, resolvedName);
            tryInvoke(disguise, "setDisguiseName", String.class, resolvedName);
            tryInvoke(disguise, "setNameVisible", boolean.class, false);
            tryInvoke(disguise, "setModifyBoundingBox", boolean.class, false);
            tryInvoke(disguise, "setReplaceSounds", boolean.class, true);

            Class<?> disguiseApi = Class.forName("me.libraryaddict.disguise.DisguiseAPI");
            Class<?> disguiseType = Class.forName("me.libraryaddict.disguise.disguisetypes.Disguise");
            disguiseApi.getMethod("disguiseEntity", Entity.class, disguiseType).invoke(null, entity, disguise);

            boolean disguised = isPlayerDisguised(entity);
            if (!disguised) {
                logApplyFailure(skinName, new IllegalStateException("disguiseEntity completed but entity is still undisguised"));
            }
            return disguised;
        } catch (ReflectiveOperationException exception) {
            logApplyFailure(skinName, exception);
            return false;
        }
    }

    public static boolean ensurePlayerDisguise(LivingEntity entity, String displayName, String skinName) {
        if (isPlayerDisguised(entity)) {
            return true;
        }
        return applyPlayerDisguise(entity, displayName, skinName);
    }

    /** Re-sends disguise metadata to a viewer who just loaded the entity (death, spectator, reconnect). */
    public static void refreshDisguiseForPlayer(LivingEntity entity, Player player) {
        if (entity == null || player == null || !isAvailable()) {
            return;
        }
        if (!isPlayerDisguised(entity)) {
            return;
        }
        try {
            Class<?> disguiseApi = Class.forName("me.libraryaddict.disguise.DisguiseAPI");
            try {
                disguiseApi.getMethod("refreshToPlayer", Entity.class, org.bukkit.entity.Player.class)
                        .invoke(null, entity, player);
                return;
            } catch (NoSuchMethodException ignored) {
                // Fall through to generic refresh hooks.
            }
            Object disguise = disguiseApi.getMethod("getDisguise", Entity.class).invoke(null, entity);
            if (disguise != null) {
                try {
                    disguise.getClass().getMethod("refresh", org.bukkit.entity.Player.class).invoke(disguise, player);
                    return;
                } catch (NoSuchMethodException ignored) {
                    // Fall through.
                }
            }
            disguiseApi.getMethod("refresh", Entity.class).invoke(null, entity);
        } catch (ReflectiveOperationException exception) {
            Logger logger = startupLogger;
            if (logger != null) {
                logger.log(Level.FINE, "[Breach] Failed to refresh disguise for " + player.getName(), exception);
            }
        }
    }

    public static void refreshDisguise(LivingEntity entity) {
        if (entity == null || !isAvailable() || !isPlayerDisguised(entity)) {
            return;
        }
        try {
            Class<?> disguiseApi = Class.forName("me.libraryaddict.disguise.DisguiseAPI");
            disguiseApi.getMethod("refresh", Entity.class).invoke(null, entity);
        } catch (ReflectiveOperationException ignored) {
            // Optional across LibsDisguises versions.
        }
    }

    /** Visible arm swing for disguised gunners — husk {@code swingHand} does not reach clients. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void playSwingAnimation(LivingEntity entity) {
        if (entity == null) {
            return;
        }
        entity.swingHand(org.bukkit.inventory.EquipmentSlot.HAND);
        if (!isAvailable() || !isPlayerDisguised(entity)) {
            return;
        }
        try {
            Class<?> disguiseApi = Class.forName("me.libraryaddict.disguise.DisguiseAPI");
            Object disguise = disguiseApi.getMethod("getDisguise", Entity.class).invoke(null, entity);
            if (disguise == null) {
                return;
            }
            Class<?> animationEnum = Class.forName("me.libraryaddict.disguise.utilities.animations.DisguiseAnimation");
            Object swing = enumConstant(animationEnum, "SWING_MAIN_ARM", "SWING_ARM");
            if (swing == null) {
                return;
            }
            disguise.getClass().getMethod("playAnimation", animationEnum).invoke(disguise, swing);
        } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
            // LibsDisguises version mismatch — swingHand already attempted.
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object enumConstant(Class enumType, String... names) {
        for (String name : names) {
            try {
                return Enum.valueOf(enumType, name);
            } catch (IllegalArgumentException ignored) {
                // try next alias
            }
        }
        return null;
    }

    public static String skinForMobType(String mobType) {
        if (mobType == null) {
            return "Steve";
        }
        return switch (mobType.toLowerCase(Locale.ROOT)) {
            case "ruinssmgunner", "ruinspistolgunner", "ruinskniferusher" -> "Alex";
            default -> "Steve";
        };
    }

    public static String disguiseKeyForSkin(String skinName) {
        return "Alex".equalsIgnoreCase(sanitizeProfileName(skinName)) ? ALEX_DISGUISE : STEVE_DISGUISE;
    }

    private static Object cloneCustomDisguise(String key) {
        Object template = customDisguise(key);
        if (template == null) {
            return null;
        }
        try {
            Method clone = template.getClass().getMethod("clone");
            return clone.invoke(template);
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }

    private static Object customDisguise(String key) {
        if (key == null || key.isBlank() || !isAvailable()) {
            return null;
        }
        try {
            Class<?> disguiseApi = Class.forName("me.libraryaddict.disguise.DisguiseAPI");
            Method getCustomDisguise = disguiseApi.getMethod("getCustomDisguise", String.class);
            return getCustomDisguise.invoke(null, key);
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }

    private static Object buildFallbackDisguise(String skinName) throws ReflectiveOperationException {
        String profileName = sanitizeProfileName(skinName);
        Class<?> disguiseClass = Class.forName("me.libraryaddict.disguise.disguisetypes.PlayerDisguise");
        Object disguise = disguiseClass.getConstructor(String.class, String.class).newInstance(profileName, profileName);
        tryInvoke(disguise, "setSkin", String.class, profileName);
        return disguise;
    }

    private static void logApplyFailure(String skinName, Exception exception) {
        Logger logger = startupLogger;
        if (logger == null) {
            return;
        }
        logger.log(Level.WARNING, "[Breach] Failed to apply LibsDisguises player disguise (skin="
                + skinName + "): " + exception.getMessage(), exception);
    }

    private static void tryInvoke(Object target, String methodName, Class<?> paramType, Object value) {
        try {
            Method method = target.getClass().getMethod(methodName, paramType);
            method.invoke(target, value);
        } catch (ReflectiveOperationException ignored) {
            // Optional across LibsDisguises versions.
        }
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String sanitizeProfileName(String name) {
        String safe = name == null ? "Steve" : name.replaceAll("[^a-zA-Z0-9_]", "");
        if (safe.length() > 16) {
            safe = safe.substring(0, 16);
        }
        return safe.isBlank() ? "Steve" : safe;
    }
}
