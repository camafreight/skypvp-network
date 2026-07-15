package network.skypvp.extraction.hub;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.plugin.java.JavaPlugin;

/** Preset extraction hub NPC stations — wire via /npc create + /npc action using this catalog. */
public final class ExtractionHubStationCatalog {

    public record Station(
            String id,
            String displayName,
            String entityType,
            String actionType,
            String actionData,
            List<String> hologramLines
    ) {
    }

    private final List<Station> stations;

    public ExtractionHubStationCatalog(JavaPlugin plugin, Logger logger) {
        this.stations = List.copyOf(load(plugin, logger));
    }

    public List<Station> stations() {
        return stations;
    }

    private static List<Station> load(JavaPlugin plugin, Logger logger) {
        try (InputStream input = plugin.getResource("hub/npc-stations.json")) {
            if (input == null) {
                logger.warning("[HubNPC] Missing hub/npc-stations.json resource.");
                return List.of();
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!root.has("stations") || !root.get("stations").isJsonArray()) {
                return List.of();
            }
            List<Station> loaded = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray("stations")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject entry = element.getAsJsonObject();
                String id = text(entry, "id");
                if (id.isBlank()) {
                    continue;
                }
                List<String> hologram = new ArrayList<>();
                if (entry.has("hologramLines") && entry.get("hologramLines").isJsonArray()) {
                    for (JsonElement line : entry.getAsJsonArray("hologramLines")) {
                        if (line.isJsonPrimitive()) {
                            hologram.add(line.getAsString());
                        }
                    }
                }
                loaded.add(new Station(
                        id,
                        text(entry, "displayName", id),
                        text(entry, "entityType", "VILLAGER"),
                        text(entry, "actionType", "PLAYER_COMMAND"),
                        text(entry, "actionData", ""),
                        List.copyOf(hologram)
                ));
            }
            logger.info("[HubNPC] Loaded " + loaded.size() + " hub station preset(s).");
            return Collections.unmodifiableList(loaded);
        } catch (IOException | RuntimeException exception) {
            logger.warning("[HubNPC] Failed to load hub NPC catalog: " + exception.getMessage());
            return List.of();
        }
    }

    private static String text(JsonObject object, String key) {
        return text(object, key, "");
    }

    private static String text(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }
}
