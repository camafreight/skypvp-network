package network.skypvp.extraction.gameplay.scrapper;

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

/** Loads scrapper tier caps, loot pools, and upgrade material costs. */
public final class ScrapperTierConfigService {

    public record MaterialCost(String materialId, int amount) {
    }

    public record TierDefinition(
            int tier,
            String name,
            int sessionCap,
            List<String> materials,
            List<MaterialCost> upgradeMaterials
    ) {
    }

    private final Path configPath;
    private List<TierDefinition> tiers;
    private Map<Integer, TierDefinition> byTier;

    public ScrapperTierConfigService(JavaPlugin plugin, Logger logger) {
        Path folder = plugin.getDataFolder().toPath().resolve("crafting");
        this.configPath = folder.resolve("scrapper-config.json");
        try {
            Files.createDirectories(folder);
            copyDefault(plugin, "crafting/scrapper-config.json", configPath);
        } catch (IOException exception) {
            logger.warning("[Scrapper] Failed to prepare config folder: " + exception.getMessage());
        }
        reload(logger);
    }

    public void reload(Logger logger) {
        List<TierDefinition> loaded = loadTiers(readJson(configPath, "crafting/scrapper-config.json"));
        this.tiers = List.copyOf(loaded);
        Map<Integer, TierDefinition> indexed = new LinkedHashMap<>();
        for (TierDefinition tier : loaded) {
            indexed.put(tier.tier(), tier);
        }
        this.byTier = Map.copyOf(indexed);
        logger.info("[Scrapper] Loaded " + tiers.size() + " tier(s).");
    }

    public int defaultTier() {
        return 1;
    }

    public int maxTier() {
        return tiers.isEmpty() ? defaultTier() : tiers.get(tiers.size() - 1).tier();
    }

    public TierDefinition tier(int tier) {
        return byTier.getOrDefault(clampTier(tier), tiers.get(0));
    }

    public Optional<TierDefinition> nextTier(int currentTier) {
        return Optional.ofNullable(byTier.get(currentTier + 1));
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
        return tier;
    }

    private static List<TierDefinition> loadTiers(JsonObject root) {
        if (root == null || !root.has("tiers") || !root.get("tiers").isJsonArray()) {
            return List.of(new TierDefinition(1, "Salvage Sifter", 48,
                    List.of("cloth_scrap", "metal_shards", "fiber_bundle"), List.of()));
        }
        List<TierDefinition> loaded = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("tiers")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            int tier = entry.has("tier") ? entry.get("tier").getAsInt() : loaded.size() + 1;
            String name = entry.has("name") ? entry.get("name").getAsString() : "Tier " + tier;
            int sessionCap = entry.has("sessionCap") ? entry.get("sessionCap").getAsInt() : 48;
            List<String> materials = new ArrayList<>();
            if (entry.has("materials") && entry.get("materials").isJsonArray()) {
                for (JsonElement mat : entry.getAsJsonArray("materials")) {
                    if (mat.isJsonPrimitive()) {
                        materials.add(mat.getAsString());
                    }
                }
            }
            List<MaterialCost> upgradeMaterials = new ArrayList<>();
            if (entry.has("upgradeMaterials") && entry.get("upgradeMaterials").isJsonArray()) {
                for (JsonElement costElement : entry.getAsJsonArray("upgradeMaterials")) {
                    if (!costElement.isJsonObject()) {
                        continue;
                    }
                    JsonObject cost = costElement.getAsJsonObject();
                    String id = cost.has("id") ? cost.get("id").getAsString() : null;
                    int amount = cost.has("amount") ? cost.get("amount").getAsInt() : 0;
                    if (id != null && !id.isBlank() && amount > 0) {
                        upgradeMaterials.add(new MaterialCost(id, amount));
                    }
                }
            }
            loaded.add(new TierDefinition(tier, name, sessionCap, List.copyOf(materials), List.copyOf(upgradeMaterials)));
        }
        return loaded.isEmpty()
                ? List.of(new TierDefinition(1, "Salvage Sifter", 48,
                List.of("cloth_scrap", "metal_shards", "fiber_bundle"), List.of()))
                : loaded;
    }

    private static JsonObject readJson(Path configPath, String resourcePath) {
        try {
            if (Files.isRegularFile(configPath)) {
                return JsonParser.parseReader(Files.newBufferedReader(configPath)).getAsJsonObject();
            }
        } catch (IOException | RuntimeException ignored) {
        }
        try (InputStream input = ScrapperTierConfigService.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (input != null) {
                return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return null;
    }

    private static void copyDefault(JavaPlugin plugin, String resourcePath, Path target) throws IOException {
        if (Files.exists(target)) {
            return;
        }
        try (InputStream input = plugin.getResource(resourcePath)) {
            if (input != null) {
                Files.copy(input, target);
            }
        }
    }
}
