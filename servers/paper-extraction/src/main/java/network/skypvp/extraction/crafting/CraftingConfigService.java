package network.skypvp.extraction.crafting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

/** Loads crafting materials and blueprints from JSON (WeaponMechanics-style per-domain files). */
public final class CraftingConfigService {

    private final JavaPlugin plugin;
    private final Path dataFolder;
    private List<CraftingMaterialDefinition> materials;
    private List<BlueprintDefinition> blueprints;

    public CraftingConfigService(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.dataFolder = plugin.getDataFolder().toPath().resolve("crafting");
        try {
            Files.createDirectories(dataFolder);
            copyDefault(plugin, "crafting/materials.json", dataFolder.resolve("materials.json"));
            copyDefault(plugin, "crafting/blueprints.json", dataFolder.resolve("blueprints.json"));
        } catch (IOException exception) {
            logger.warning("Failed to prepare crafting config folder: " + exception.getMessage());
        }
        CraftingReloadResult loaded = loadSnapshot();
        this.materials = List.copyOf(loadMaterials(readJson(dataFolder.resolve("materials.json"), "crafting/materials.json")));
        this.blueprints = mergeBlueprints(loadBlueprints(readJson(dataFolder.resolve("blueprints.json"), "crafting/blueprints.json")));
        logger.info("[Crafting] Loaded " + loaded.materials() + " materials and " + loaded.blueprints() + " blueprints.");
    }

    public CraftingReloadResult reload(Logger logger) {
        CraftingReloadResult result = loadSnapshot();
        this.materials = List.copyOf(loadMaterials(readJson(dataFolder.resolve("materials.json"), "crafting/materials.json")));
        this.blueprints = mergeBlueprints(loadBlueprints(readJson(dataFolder.resolve("blueprints.json"), "crafting/blueprints.json")));
        logger.info("[Crafting] Reloaded " + result.materials() + " materials and " + result.blueprints() + " blueprints.");
        return result;
    }

    private CraftingReloadResult loadSnapshot() {
        int materials = loadMaterials(readJson(dataFolder.resolve("materials.json"), "crafting/materials.json")).size();
        int blueprints = mergeBlueprints(loadBlueprints(readJson(dataFolder.resolve("blueprints.json"), "crafting/blueprints.json"))).size();
        return new CraftingReloadResult(materials, blueprints, 0, 0);
    }

    /** JSON entries override generated defaults when they share the same id. */
    private static List<BlueprintDefinition> mergeBlueprints(List<BlueprintDefinition> fromJson) {
        Map<String, BlueprintDefinition> merged = new LinkedHashMap<>();
        for (BlueprintDefinition generated : BlueprintCatalogFactory.generatedCatalog()) {
            merged.put(generated.id(), generated);
        }
        for (BlueprintDefinition json : fromJson) {
            merged.put(json.id(), json);
        }
        return List.copyOf(merged.values());
    }

    public List<CraftingMaterialDefinition> materials() {
        return materials;
    }

    public List<BlueprintDefinition> blueprints() {
        return blueprints;
    }

    private static void copyDefault(JavaPlugin plugin, String resource, Path target) throws IOException {
        if (Files.exists(target)) {
            return;
        }
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        try (InputStream input = plugin.getResource(resource)) {
            if (input != null) {
                Files.copy(input, target);
            }
        }
    }

    private JsonObject readJson(Path file, String fallbackResource) {
        try {
            if (Files.exists(file)) {
                return JsonParser.parseReader(Files.newBufferedReader(file, StandardCharsets.UTF_8)).getAsJsonObject();
            }
        } catch (IOException | RuntimeException ignored) {
        }
        try (InputStream input = plugin.getResource(fallbackResource)) {
            if (input == null) {
                return new JsonObject();
            }
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException exception) {
            return new JsonObject();
        }
    }

    private static List<CraftingMaterialDefinition> loadMaterials(JsonObject root) {
        List<CraftingMaterialDefinition> loaded = new ArrayList<>();
        if (!root.has("materials") || !root.get("materials").isJsonArray()) {
            return List.copyOf(loaded);
        }
        for (JsonElement element : root.getAsJsonArray("materials")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String id = text(entry, "id");
            if (id.isBlank()) {
                continue;
            }
            Material icon = Material.matchMaterial(text(entry, "icon").toUpperCase(Locale.ROOT));
            if (icon == null) {
                icon = Material.PAPER;
            }
            CraftingMaterialTier tier = CraftingMaterialTier.valueOf(text(entry, "tier", "CORE").toUpperCase(Locale.ROOT));
            loaded.add(new CraftingMaterialDefinition(id, text(entry, "displayName", id), icon, tier, text(entry, "description", "")));
        }
        return List.copyOf(loaded);
    }

    private static List<BlueprintDefinition> loadBlueprints(JsonObject root) {
        List<BlueprintDefinition> loaded = new ArrayList<>();
        if (!root.has("blueprints") || !root.get("blueprints").isJsonArray()) {
            return List.copyOf(loaded);
        }
        for (JsonElement element : root.getAsJsonArray("blueprints")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String id = text(entry, "id");
            if (id.isBlank()) {
                continue;
            }
            BlueprintCategory category = BlueprintCategory.valueOf(text(entry, "category", "MISC").toUpperCase(Locale.ROOT));
            Material icon = Material.matchMaterial(text(entry, "icon").toUpperCase(Locale.ROOT));
            if (icon == null) {
                icon = Material.PAPER;
            }
            List<BlueprintDefinition.MaterialCost> costs = new ArrayList<>();
            if (entry.has("materials") && entry.get("materials").isJsonArray()) {
                for (JsonElement costElement : entry.getAsJsonArray("materials")) {
                    if (!costElement.isJsonObject()) {
                        continue;
                    }
                    JsonObject cost = costElement.getAsJsonObject();
                    costs.add(new BlueprintDefinition.MaterialCost(text(cost, "id"), Math.max(1, cost.has("amount") ? cost.get("amount").getAsInt() : 1)));
                }
            }
            JsonObject output = entry.has("output") && entry.get("output").isJsonObject() ? entry.getAsJsonObject("output") : new JsonObject();
            BlueprintDefinition.OutputType type = BlueprintDefinition.OutputType.valueOf(text(output, "type", "MEDIC").toUpperCase(Locale.ROOT));
            loaded.add(new BlueprintDefinition(
                    id,
                    category,
                    text(entry, "displayName", id),
                    icon,
                    entry.has("starter") && entry.get("starter").getAsBoolean(),
                    List.copyOf(costs),
                    new BlueprintDefinition.BlueprintOutput(type, text(output, "recipeKey", id))
            ));
        }
        return List.copyOf(loaded);
    }

    private static String text(JsonObject object, String key) {
        return text(object, key, "");
    }

    private static String text(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }
}
