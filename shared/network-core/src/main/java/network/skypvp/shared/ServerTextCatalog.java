package network.skypvp.shared;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Registry of English source strings for server UI, commands, and HUD copy.
 *
 * <p>Catalog packs live under classpath {@code text-catalogs/<pack>.json}.
 */
public final class ServerTextCatalog {
    private static final ConcurrentHashMap<String, String> ENTRIES = new ConcurrentHashMap<>();
    private static final Logger LOGGER = Logger.getLogger("SkyPvP-ServerText");

    static {
        registerPack("core", null);
    }

    private ServerTextCatalog() {
    }

    public static void register(String key, String englishMiniMessage) {
        if (key == null || key.isBlank() || englishMiniMessage == null || englishMiniMessage.isBlank()) {
            return;
        }
        ENTRIES.put(key.trim(), englishMiniMessage);
    }

    /** Loads or reloads a catalog pack from {@code text-catalogs/<packName>.json}. */
    public static void registerPack(String packName, Logger logger) {
        registerPack(packName, ServerTextCatalog.class.getClassLoader(), logger);
    }

    /** Loads a catalog pack from a game-mode plugin class loader. */
    public static void registerPack(String packName, ClassLoader classLoader, Logger logger) {
        Map<String, String> loaded = ServerTextCatalogLoader.loadPack(
                packName,
                classLoader,
                logger == null ? LOGGER : logger
        );
        if (!loaded.isEmpty()) {
            ENTRIES.putAll(loaded);
        }
    }

    public static String source(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        return ENTRIES.getOrDefault(key.trim(), key.trim());
    }

    public static boolean contains(String key) {
        return key != null && ENTRIES.containsKey(key.trim());
    }

    public static Collection<Map.Entry<String, String>> entries() {
        return ENTRIES.entrySet();
    }

    public static int size() {
        return ENTRIES.size();
    }

    public static void registerAll(Map<String, String> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        ENTRIES.putAll(new LinkedHashMap<>(entries));
    }
}
