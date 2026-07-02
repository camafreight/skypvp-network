package network.skypvp.shared;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Loads flat string catalogs from {@code text-catalogs/<pack>.json} on the classpath. */
public final class ServerTextCatalogLoader {
    private static final String CATALOG_ROOT = "text-catalogs/";
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    private ServerTextCatalogLoader() {
    }

    public static Map<String, String> loadPack(String packName, Logger logger) {
        return loadPack(packName, ServerTextCatalogLoader.class.getClassLoader(), logger);
    }

    public static Map<String, String> loadPack(String packName, ClassLoader classLoader, Logger logger) {
        if (packName == null || packName.isBlank()) {
            return Map.of();
        }
        ClassLoader loader = classLoader == null ? ServerTextCatalogLoader.class.getClassLoader() : classLoader;
        String resource = CATALOG_ROOT + packName.trim() + ".json";
        try (InputStream stream = loader.getResourceAsStream(resource)) {
            if (stream == null) {
                if (logger != null) {
                    logger.log(Level.FINE, "[server-text] Catalog pack not found: {0}", resource);
                }
                return Map.of();
            }
            Map<String, String> parsed = GSON.fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), MAP_TYPE);
            if (parsed == null || parsed.isEmpty()) {
                return Map.of();
            }
            Map<String, String> normalized = new LinkedHashMap<>();
            parsed.forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
                    normalized.put(key.trim(), value);
                }
            });
            if (logger != null) {
                logger.info("[server-text] Loaded catalog pack '" + packName + "' (" + normalized.size() + " entries).");
            }
            return Map.copyOf(normalized);
        } catch (Exception ex) {
            if (logger != null) {
                logger.log(Level.WARNING, "[server-text] Failed to load catalog pack " + packName + ": " + ex.getMessage());
            }
            return Map.of();
        }
    }
}
