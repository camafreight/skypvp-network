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
import network.skypvp.extraction.item.ArmorSet;
import network.skypvp.extraction.item.GearRarity;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

/** Coin-priced preset armor kits for the armory black market. */
public final class BlackMarketConfigService {

    public record Listing(
            String id,
            String displayName,
            String description,
            Material icon,
            long coinCost,
            long goldCost,
            ArmorSet armorSet,
            GearRarity rarity
    ) {
    }

    private final JavaPlugin plugin;
    private final Path dataFile;
    private List<Listing> listings = List.of();

    public BlackMarketConfigService(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        Path folder = plugin.getDataFolder().toPath().resolve("crafting");
        this.dataFile = folder.resolve("black-market.json");
        try {
            Files.createDirectories(folder);
            copyDefault(plugin, "crafting/black-market.json", dataFile);
        } catch (IOException exception) {
            logger.warning("Failed to prepare black market config: " + exception.getMessage());
        }
        reload(logger);
    }

    public List<Listing> listings() {
        return listings;
    }

    public void reload(Logger logger) {
        JsonObject root = readJson(dataFile, plugin, "crafting/black-market.json");
        listings = loadListings(root);
        logger.info("[BlackMarket] Loaded " + listings.size() + " listing(s).");
    }

    private static List<Listing> loadListings(JsonObject root) {
        List<Listing> loaded = new ArrayList<>();
        if (!root.has("listings") || !root.get("listings").isJsonArray()) {
            return List.copyOf(loaded);
        }
        for (JsonElement element : root.getAsJsonArray("listings")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String id = text(entry, "id");
            if (id.isBlank()) {
                continue;
            }
            ArmorSet set = ArmorSet.byId(text(entry, "armorSet", "vanguard")).orElse(ArmorSet.VANGUARD);
            GearRarity rarity;
            try {
                rarity = GearRarity.valueOf(text(entry, "rarity", "COMMON").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                rarity = GearRarity.COMMON;
            }
            Material icon = Material.matchMaterial(text(entry, "icon", "NETHERITE_CHESTPLATE").toUpperCase(Locale.ROOT));
            if (icon == null) {
                icon = Material.NETHERITE_CHESTPLATE;
            }
            long coinCost = Math.max(0L, entry.has("coinCost") ? entry.get("coinCost").getAsLong() : 500L);
            long goldCost = Math.max(0L, entry.has("goldCost") ? entry.get("goldCost").getAsLong() : 0L);
            loaded.add(new Listing(
                    id,
                    text(entry, "displayName", id),
                    text(entry, "description", ""),
                    icon,
                    coinCost,
                    goldCost,
                    set,
                    rarity
            ));
        }
        return List.copyOf(loaded);
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
        return text(object, key, "");
    }

    private static String text(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }
}
