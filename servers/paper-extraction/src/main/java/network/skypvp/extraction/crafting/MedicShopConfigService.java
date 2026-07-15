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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import network.skypvp.extraction.item.MedicConsumableType;
import org.bukkit.plugin.java.JavaPlugin;

/** Consolidated medic bay pricing (coins + materials) hot-reloadable from JSON. */
public final class MedicShopConfigService {

    public record MaterialCost(String materialId, int amount, String displayName) {
    }

    public record Listing(long coinCost, MaterialCost material) {
    }

    private final JavaPlugin plugin;
    private final Path dataFile;
    private Map<String, Listing> listings = Map.of();

    public MedicShopConfigService(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        Path folder = plugin.getDataFolder().toPath().resolve("crafting");
        this.dataFile = folder.resolve("medic-shop.json");
        try {
            Files.createDirectories(folder);
            copyDefault(plugin, "crafting/medic-shop.json", dataFile);
        } catch (IOException exception) {
            logger.warning("[MedicShop] Failed to prepare medic shop config: " + exception.getMessage());
        }
        reload(logger);
    }

    public Optional<Listing> listing(MedicConsumableType type) {
        return Optional.ofNullable(listings.get(type.id()));
    }

    public Map<String, Listing> listings() {
        return listings;
    }

    public long coinCost(MedicConsumableType type) {
        Listing listing = listings.get(type.id());
        if (listing != null && listing.coinCost() > 0L) {
            return listing.coinCost();
        }
        return 0L;
    }

    public Optional<MaterialCost> materialCost(MedicConsumableType type) {
        Listing listing = listings.get(type.id());
        return listing == null || listing.material() == null ? Optional.empty() : Optional.of(listing.material());
    }

    public void reload(Logger logger) {
        JsonObject root = readJson(dataFile, plugin, "crafting/medic-shop.json");
        Map<String, Listing> loaded = new LinkedHashMap<>();
        if (root.has("listings") && root.get("listings").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("listings")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                parseListing(element.getAsJsonObject()).ifPresent(entry -> loaded.put(entry.getKey(), entry.getValue()));
            }
        }
        listings = Collections.unmodifiableMap(loaded);
        logger.info("[MedicShop] Loaded " + listings.size() + " medic listing(s).");
    }

    private static Optional<Map.Entry<String, Listing>> parseListing(JsonObject entry) {
        String id = text(entry, "id");
        if (id.isBlank()) {
            return Optional.empty();
        }
        long coinCost = entry.has("coinCost") ? Math.max(0L, entry.get("coinCost").getAsLong()) : 0L;
        MaterialCost material = null;
        if (entry.has("material") && entry.get("material").isJsonObject()) {
            JsonObject mat = entry.getAsJsonObject("material");
            String materialId = text(mat, "id");
            int amount = mat.has("amount") ? Math.max(0, mat.get("amount").getAsInt()) : 0;
            String displayName = text(mat, "displayName", materialId);
            if (!materialId.isBlank() && amount > 0) {
                material = new MaterialCost(materialId, amount, displayName);
            }
        }
        return Optional.of(Map.entry(id, new Listing(coinCost, material)));
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
