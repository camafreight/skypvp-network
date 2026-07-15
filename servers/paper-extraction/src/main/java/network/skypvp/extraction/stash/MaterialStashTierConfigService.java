package network.skypvp.extraction.stash;

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
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import org.bukkit.plugin.java.JavaPlugin;

/** Loads tiered material stash capacity upgrades from JSON. */
public final class MaterialStashTierConfigService {

    private final Path configPath;
    private List<MaterialStashTierDefinition> tiers;
    private Map<Integer, MaterialStashTierDefinition> byTier;

    public MaterialStashTierConfigService(JavaPlugin plugin, Logger logger) {
        Path folder = plugin.getDataFolder().toPath().resolve("crafting");
        this.configPath = folder.resolve("stash-tiers.json");
        try {
            Files.createDirectories(folder);
            copyDefault(plugin, "crafting/stash-tiers.json", configPath);
        } catch (IOException exception) {
            logger.warning("[StashTiers] Failed to prepare config folder: " + exception.getMessage());
        }
        reload(logger);
    }

    public void reload(Logger logger) {
        List<MaterialStashTierDefinition> loaded = loadTiers(readJson(configPath, "crafting/stash-tiers.json"));
        this.tiers = List.copyOf(loaded);
        Map<Integer, MaterialStashTierDefinition> indexed = new LinkedHashMap<>();
        for (MaterialStashTierDefinition tier : loaded) {
            indexed.put(tier.tier(), tier);
        }
        this.byTier = Map.copyOf(indexed);
        logger.info("[StashTiers] Loaded " + tiers.size() + " capacity tiers.");
    }

    public List<MaterialStashTierDefinition> tiers() {
        return tiers;
    }

    public int defaultTier() {
        return 1;
    }

    public int maxTier() {
        return tiers.isEmpty() ? defaultTier() : tiers.get(tiers.size() - 1).tier();
    }

    public MaterialStashTierDefinition tier(int tier) {
        return byTier.getOrDefault(clampTier(tier), tiers.get(0));
    }

    public Optional<MaterialStashTierDefinition> nextTier(int currentTier) {
        int next = currentTier + 1;
        return Optional.ofNullable(byTier.get(next));
    }

    public int clampTier(int tier) {
        if (tiers.isEmpty()) {
            return defaultTier();
        }
        if (tier < tiers.get(0).tier()) {
            return tiers.get(0).tier();
        }
        if (tier > maxTier()) {
            return maxTier();
        }
        return byTier.containsKey(tier) ? tier : defaultTier();
    }

    private static List<MaterialStashTierDefinition> loadTiers(JsonObject root) {
        List<MaterialStashTierDefinition> loaded = new ArrayList<>();
        if (root == null) {
            return loaded;
        }
        JsonArray array = root.getAsJsonArray("tiers");
        if (array == null) {
            return loaded;
        }
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            loaded.add(new MaterialStashTierDefinition(
                    object.get("tier").getAsInt(),
                    text(object, "name"),
                    object.get("maxCapacity").getAsInt(),
                    object.get("maxSlots").getAsInt(),
                    longValue(object, "upgradeCoins"),
                    longValue(object, "upgradeGold")
            ));
        }
        loaded.sort(java.util.Comparator.comparingInt(MaterialStashTierDefinition::tier));
        return loaded;
    }

    private static String text(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    private static long longValue(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? 0L : element.getAsLong();
    }

    private static JsonObject readJson(Path path, String resource) {
        try {
            if (Files.exists(path)) {
                try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8)) {
                    return JsonParser.parseReader(reader).getAsJsonObject();
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
        try (InputStream stream = MaterialStashTierConfigService.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                return new JsonObject();
            }
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        } catch (IOException exception) {
            return new JsonObject();
        }
    }

    private static void copyDefault(JavaPlugin plugin, String resource, Path target) throws IOException {
        if (Files.exists(target)) {
            return;
        }
        try (InputStream stream = plugin.getResource(resource)) {
            if (stream == null) {
                return;
            }
            Files.copy(stream, target);
        }
    }
}
