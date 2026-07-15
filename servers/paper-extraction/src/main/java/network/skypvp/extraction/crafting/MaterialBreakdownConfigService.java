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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import org.bukkit.plugin.java.JavaPlugin;

/** Hot-reloadable material → lower-tier material breakdown recipes. */
public final class MaterialBreakdownConfigService {

    public record Output(String materialId, int amount) {
    }

    public record Recipe(String inputId, int inputAmount, List<Output> outputs) {
    }

    private final JavaPlugin plugin;
    private final Path dataFile;
    private double defaultEfficiency = 0.7D;
    private Map<String, Recipe> recipesByInput = Map.of();

    public MaterialBreakdownConfigService(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        Path folder = plugin.getDataFolder().toPath().resolve("crafting");
        this.dataFile = folder.resolve("material-breakdown.json");
        try {
            Files.createDirectories(folder);
            copyDefault(plugin, "crafting/material-breakdown.json", dataFile);
        } catch (IOException exception) {
            logger.warning("[MaterialBreakdown] Failed to prepare config: " + exception.getMessage());
        }
        reload(logger);
    }

    public double defaultEfficiency() {
        return defaultEfficiency;
    }

    public Optional<Recipe> recipeFor(String materialId) {
        if (materialId == null || materialId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(recipesByInput.get(materialId.trim().toLowerCase(Locale.ROOT)));
    }

    public List<Recipe> recipes() {
        return List.copyOf(recipesByInput.values());
    }

    public void reload(Logger logger) {
        JsonObject root = readJson(dataFile, plugin, "crafting/material-breakdown.json");
        defaultEfficiency = root.has("defaultEfficiency")
                ? Math.max(0.1D, Math.min(1.0D, root.get("defaultEfficiency").getAsDouble()))
                : 0.7D;
        Map<String, Recipe> loaded = new LinkedHashMap<>();
        if (root.has("recipes") && root.get("recipes").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("recipes")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                parseRecipe(element.getAsJsonObject()).ifPresent(recipe -> loaded.put(recipe.inputId(), recipe));
            }
        }
        recipesByInput = Collections.unmodifiableMap(loaded);
        logger.info("[MaterialBreakdown] Loaded " + recipesByInput.size() + " breakdown recipe(s) @ "
                + (int) (defaultEfficiency * 100) + "% efficiency.");
    }

    private static Optional<Recipe> parseRecipe(JsonObject entry) {
        String input = text(entry, "input");
        if (input.isBlank()) {
            return Optional.empty();
        }
        int inputAmount = entry.has("inputAmount") ? Math.max(1, entry.get("inputAmount").getAsInt()) : 1;
        List<Output> outputs = new ArrayList<>();
        if (entry.has("outputs") && entry.get("outputs").isJsonArray()) {
            for (JsonElement out : entry.getAsJsonArray("outputs")) {
                if (!out.isJsonObject()) {
                    continue;
                }
                JsonObject obj = out.getAsJsonObject();
                String id = text(obj, "id");
                int amount = obj.has("amount") ? Math.max(1, obj.get("amount").getAsInt()) : 1;
                if (!id.isBlank()) {
                    outputs.add(new Output(id.trim().toLowerCase(Locale.ROOT), amount));
                }
            }
        }
        if (outputs.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Recipe(input.trim().toLowerCase(Locale.ROOT), inputAmount, List.copyOf(outputs)));
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

    private static JsonObject readJson(Path file, JavaPlugin plugin, String fallbackResource) {
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

    private static String text(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString().trim() : "";
    }
}
