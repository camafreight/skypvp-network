package network.skypvp.extraction.crafting;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;
import network.skypvp.extraction.item.ArmorModuleType;
import network.skypvp.extraction.item.InfuseArmorPiece;
import network.skypvp.extraction.item.MedicConsumableType;
import org.bukkit.plugin.java.JavaPlugin;

/** Optional per-item JSON overrides (WeaponMechanics-style layout foundation). */
public final class ItemConfigService {

    private final Path itemsFolder;
    private Map<String, JsonObject> overrides = Map.of();

    public ItemConfigService(JavaPlugin plugin, Logger logger) {
        this.itemsFolder = plugin.getDataFolder().toPath().resolve("crafting").resolve("items");
        ensureDefaultItems(plugin, itemsFolder, logger);
        reload(logger);
    }

    public Map<String, JsonObject> overrides() {
        return overrides;
    }

    public void reload(Logger logger) {
        Map<String, JsonObject> loaded = new LinkedHashMap<>();
        try {
            Files.createDirectories(itemsFolder);
            try (var stream = Files.walk(itemsFolder)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".json"))
                        .forEach(path -> loadFile(path, loaded, logger));
            }
        } catch (IOException exception) {
            logger.warning("[ItemConfig] Failed to scan items folder: " + exception.getMessage());
        }
        overrides = Collections.unmodifiableMap(loaded);
        logger.info("[ItemConfig] Loaded " + overrides.size() + " item override(s).");
    }

    private static void ensureDefaultItems(JavaPlugin plugin, Path itemsFolder, Logger logger) {
        try {
            Files.createDirectories(itemsFolder);
            for (InfuseArmorPiece piece : InfuseArmorPiece.values()) {
                String file = "infuse_" + piece.name().toLowerCase() + ".json";
                copyDefault(plugin, "crafting/items/" + file, itemsFolder.resolve(file));
            }
            for (MedicConsumableType type : MedicConsumableType.values()) {
                String file = "medic_" + type.id() + ".json";
                copyDefault(plugin, "crafting/items/" + file, itemsFolder.resolve(file));
            }
            for (ArmorModuleType type : ArmorModuleType.values()) {
                String file = "module_" + type.id() + ".json";
                copyDefault(plugin, "crafting/items/" + file, itemsFolder.resolve(file));
            }
        } catch (IOException exception) {
            logger.warning("[ItemConfig] Failed to prepare item config folder: " + exception.getMessage());
        }
    }

    private static void copyDefault(JavaPlugin plugin, String resource, Path target) throws IOException {
        if (Files.exists(target)) {
            return;
        }
        try (InputStream input = plugin.getResource(resource)) {
            if (input != null) {
                Files.copy(input, target);
            }
        }
    }

    private static void loadFile(Path path, Map<String, JsonObject> loaded, Logger logger) {
        try {
            JsonObject root = JsonParser.parseReader(Files.newBufferedReader(path, StandardCharsets.UTF_8)).getAsJsonObject();
            String id = path.getFileName().toString().replace(".json", "");
            loaded.put(id, root);
        } catch (IOException | RuntimeException exception) {
            logger.warning("[ItemConfig] Ignoring " + path.getFileName() + ": " + exception.getMessage());
        }
    }
}
