package network.skypvp.extraction.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public record BreachMapMeta(
        String mapId,
        String displayName,
        int maxPlayers,
        int durationSeconds,
        List<SpawnPoint> spawnPoints,
        List<ExtractZone> extractZones,
        List<LootChest> lootChests,
        List<BossSpawn> bossSpawns,
        List<WorldGuardRegion> worldGuardRegions,
        List<PointOfInterest> pointsOfInterest
) {

    public BreachMapMeta {
        mapId = normalize(mapId, "unknown");
        displayName = normalize(displayName, mapId);
        maxPlayers = Math.max(1, maxPlayers);
        durationSeconds = Math.max(30, durationSeconds);
        spawnPoints = List.copyOf(spawnPoints == null ? List.of() : spawnPoints);
        extractZones = List.copyOf(extractZones == null ? List.of() : extractZones);
        lootChests = List.copyOf(lootChests == null ? List.of() : lootChests);
        bossSpawns = List.copyOf(bossSpawns == null ? List.of() : bossSpawns);
        worldGuardRegions = List.copyOf(worldGuardRegions == null ? List.of() : worldGuardRegions);
        pointsOfInterest = List.copyOf(pointsOfInterest == null ? List.of() : pointsOfInterest);
    }

    public static BreachMapMeta fromPath(Path metaFile, String fallbackMapId) throws IOException {
        Objects.requireNonNull(metaFile, "metaFile");
        try (Reader reader = Files.newBufferedReader(metaFile, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            String mapId = textOrDefault(root, "mapId", fallbackMapId);
            return fromJson(root, mapId);
        }
    }

    public static BreachMapMeta fromJson(JsonObject root, String fallbackMapId) {
        Objects.requireNonNull(root, "root");
        String mapId = textOrDefault(root, "mapId", fallbackMapId);
        return new BreachMapMeta(
                mapId,
                textOrDefault(root, "displayName", mapId),
                intOrDefault(root, "maxPlayers", 8),
                intOrDefault(root, "durationSeconds", 900),
                parseSpawnPoints(root.getAsJsonArray("spawnPoints")),
                parseExtractZones(root.getAsJsonArray("extractZones")),
                parseLootChests(root.getAsJsonArray("lootChests")),
                parseBossSpawns(root.getAsJsonArray("bossSpawns")),
                parseWorldGuardRegions(root.getAsJsonArray("worldGuardRegions")),
                parsePointsOfInterest(root.getAsJsonArray("pointsOfInterest"))
        );
    }

    public record SpawnPoint(String id, double x, double y, double z, float yaw, float pitch, int safetyRating) {
        public SpawnPoint {
            id = normalize(id, "spawn");
            safetyRating = Math.max(0, Math.min(100, safetyRating));
        }

        public SpawnPoint(String id, double x, double y, double z, float yaw, float pitch) {
            this(id, x, y, z, yaw, pitch, 50);
        }
    }

    public record ExtractZone(String id, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public ExtractZone {
            id = normalize(id, "extract");
        }

        public boolean contains(double x, double y, double z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }

        public double centerX() {
            return (minX + maxX) / 2.0;
        }

        public double centerY() {
            return (minY + maxY) / 2.0;
        }

        public double centerZ() {
            return (minZ + maxZ) / 2.0;
        }

        public double horizontalRadius() {
            return Math.max((maxX - minX) / 2.0, (maxZ - minZ) / 2.0);
        }

        public double height() {
            return Math.max(1.0, maxY - minY);
        }
    }

    public record LootChest(String id, double x, double y, double z, String tier) {
        public LootChest {
            id = normalize(id, "chest");
            tier = normalize(tier, "common");
        }
    }

    public record BossSpawn(String id, String mythicMobId, double x, double y, double z, int delaySeconds, double level) {
        public BossSpawn {
            id = normalize(id, "boss");
            mythicMobId = normalize(mythicMobId, "");
            delaySeconds = Math.max(0, delaySeconds);
            level = Math.max(1.0, level);
        }
    }

    public record WorldGuardRegion(String id, String regionName, Map<String, String> flags) {
        public WorldGuardRegion {
            id = normalize(id, "region");
            regionName = normalize(regionName, id);
            flags = flags == null || flags.isEmpty() ? Map.of() : Map.copyOf(flags);
        }
    }

    public record PointOfInterest(String id, String label, double x, double y, double z) {
        public PointOfInterest {
            id = normalize(id, "poi");
            label = normalize(label, id);
        }
    }

    public record ScanBounds(int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
        public ScanBounds {
            if (minChunkX > maxChunkX || minChunkZ > maxChunkZ) {
                throw new IllegalArgumentException("Invalid scan bounds");
            }
        }

        public int estimatedChunkCount() {
            return (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
        }
    }

    public ScanBounds gameplayChunkBounds(int paddingBlocks) {
        int padding = Math.max(16, paddingBlocks);
        double minX = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;

        for (SpawnPoint point : spawnPoints) {
            minX = Math.min(minX, point.x());
            minZ = Math.min(minZ, point.z());
            maxX = Math.max(maxX, point.x());
            maxZ = Math.max(maxZ, point.z());
        }
        for (ExtractZone zone : extractZones) {
            minX = Math.min(minX, zone.minX());
            minZ = Math.min(minZ, zone.minZ());
            maxX = Math.max(maxX, zone.maxX());
            maxZ = Math.max(maxZ, zone.maxZ());
        }
        for (LootChest chest : lootChests) {
            minX = Math.min(minX, chest.x());
            minZ = Math.min(minZ, chest.z());
            maxX = Math.max(maxX, chest.x());
            maxZ = Math.max(maxZ, chest.z());
        }
        for (BossSpawn boss : bossSpawns) {
            minX = Math.min(minX, boss.x());
            minZ = Math.min(minZ, boss.z());
            maxX = Math.max(maxX, boss.x());
            maxZ = Math.max(maxZ, boss.z());
        }
        for (PointOfInterest poi : pointsOfInterest) {
            minX = Math.min(minX, poi.x());
            minZ = Math.min(minZ, poi.z());
            maxX = Math.max(maxX, poi.x());
            maxZ = Math.max(maxZ, poi.z());
        }

        if (!Double.isFinite(minX)) {
            return new ScanBounds(0, 0, 0, 0);
        }

        return new ScanBounds(
                floorChunkCoord(minX - padding),
                floorChunkCoord(maxX + padding),
                floorChunkCoord(minZ - padding),
                floorChunkCoord(maxZ + padding)
        );
    }

    private static int floorChunkCoord(double blockCoord) {
        return (int) Math.floor(blockCoord) >> 4;
    }

    private static List<SpawnPoint> parseSpawnPoints(JsonArray array) {
        if (array == null) {
            return List.of(new SpawnPoint("default", 0.5, 100.0, 0.5, 0.0F, 0.0F));
        }
        List<SpawnPoint> points = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            points.add(new SpawnPoint(
                    textOrDefault(obj, "id", "spawn-" + points.size()),
                    doubleOrDefault(obj, "x", 0.5),
                    doubleOrDefault(obj, "y", 100.0),
                    doubleOrDefault(obj, "z", 0.5),
                    floatOrDefault(obj, "yaw", 0.0F),
                    floatOrDefault(obj, "pitch", 0.0F),
                    intOrDefault(obj, "safetyRating", 50)
            ));
        }
        return points.isEmpty()
                ? List.of(new SpawnPoint("default", 0.5, 100.0, 0.5, 0.0F, 0.0F))
                : Collections.unmodifiableList(points);
    }

    private static List<ExtractZone> parseExtractZones(JsonArray array) {
        if (array == null) {
            return List.of();
        }
        List<ExtractZone> zones = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            zones.add(new ExtractZone(
                    textOrDefault(obj, "id", "extract-" + zones.size()),
                    doubleOrDefault(obj, "minX", 0.0),
                    doubleOrDefault(obj, "minY", 0.0),
                    doubleOrDefault(obj, "minZ", 0.0),
                    doubleOrDefault(obj, "maxX", 0.0),
                    doubleOrDefault(obj, "maxY", 255.0),
                    doubleOrDefault(obj, "maxZ", 0.0)
            ));
        }
        return Collections.unmodifiableList(zones);
    }

    private static List<LootChest> parseLootChests(JsonArray array) {
        if (array == null) {
            return List.of();
        }
        List<LootChest> chests = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            chests.add(new LootChest(
                    textOrDefault(obj, "id", "chest-" + chests.size()),
                    doubleOrDefault(obj, "x", 0.5),
                    doubleOrDefault(obj, "y", 64.0),
                    doubleOrDefault(obj, "z", 0.5),
                    textOrDefault(obj, "tier", "common")
            ));
        }
        return Collections.unmodifiableList(chests);
    }

    private static List<BossSpawn> parseBossSpawns(JsonArray array) {
        if (array == null) {
            return List.of();
        }
        List<BossSpawn> spawns = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            spawns.add(new BossSpawn(
                    textOrDefault(obj, "id", "boss-" + spawns.size()),
                    textOrDefault(obj, "mythicMobId", ""),
                    doubleOrDefault(obj, "x", 0.5),
                    doubleOrDefault(obj, "y", 64.0),
                    doubleOrDefault(obj, "z", 0.5),
                    intOrDefault(obj, "delaySeconds", 0),
                    doubleOrDefault(obj, "level", 1.0)
            ));
        }
        return Collections.unmodifiableList(spawns);
    }

    private static List<WorldGuardRegion> parseWorldGuardRegions(JsonArray array) {
        if (array == null) {
            return List.of();
        }
        List<WorldGuardRegion> regions = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            Map<String, String> flags = Map.of();
            if (obj.has("flags") && obj.get("flags").isJsonObject()) {
                JsonObject flagObj = obj.getAsJsonObject("flags");
                java.util.LinkedHashMap<String, String> parsed = new java.util.LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> entry : flagObj.entrySet()) {
                    parsed.put(entry.getKey(), entry.getValue().getAsString());
                }
                flags = Map.copyOf(parsed);
            }
            regions.add(new WorldGuardRegion(
                    textOrDefault(obj, "id", "region-" + regions.size()),
                    textOrDefault(obj, "regionName", textOrDefault(obj, "id", "region-" + regions.size())),
                    flags
            ));
        }
        return Collections.unmodifiableList(regions);
    }

    private static List<PointOfInterest> parsePointsOfInterest(JsonArray array) {
        if (array == null) {
            return List.of();
        }
        List<PointOfInterest> pois = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            pois.add(new PointOfInterest(
                    textOrDefault(obj, "id", "poi-" + pois.size()),
                    textOrDefault(obj, "label", textOrDefault(obj, "id", "poi-" + pois.size())),
                    doubleOrDefault(obj, "x", 0.5),
                    doubleOrDefault(obj, "y", 64.0),
                    doubleOrDefault(obj, "z", 0.5)
            ));
        }
        return Collections.unmodifiableList(pois);
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static String textOrDefault(JsonObject obj, String key, String fallback) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString().trim();
        }
        return fallback;
    }

    private static int intOrDefault(JsonObject obj, String key, int fallback) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsInt();
        }
        return fallback;
    }

    private static double doubleOrDefault(JsonObject obj, String key, double fallback) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsDouble();
        }
        return fallback;
    }

    private static float floatOrDefault(JsonObject obj, String key, float fallback) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsFloat();
        }
        return fallback;
    }
}
