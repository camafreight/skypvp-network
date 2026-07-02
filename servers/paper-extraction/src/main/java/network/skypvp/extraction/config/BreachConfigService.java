package network.skypvp.extraction.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import network.skypvp.extraction.model.BreachMapMeta;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class BreachConfigService {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final Path mapTemplateRoot;
    private final FileConfiguration breachConfig;
    private final FileConfiguration mapsConfig;
    private final Map<String, BreachMapEntry> mapEntries = new LinkedHashMap<>();
    private final Map<String, List<BreachLootEntry>> lootTiers = new LinkedHashMap<>();
    private final Map<String, BreachMapMeta> loadedMaps = new LinkedHashMap<>();

    public BreachConfigService(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.logger = plugin.getLogger();
        this.mapTemplateRoot = resolveMapTemplateRoot(plugin);
        this.breachConfig = loadYaml(plugin, "breach.yml");
        this.mapsConfig = loadMapsFile(plugin, breachConfig.getString("maps-file", "breach-maps.yml"));
        logger.info("[Breach] Map template root: " + mapTemplateRoot.toAbsolutePath());
        reloadMapEntries();
        reloadLootTiers();
    }

    public void reload() {
        this.mapEntries.clear();
        this.loadedMaps.clear();
        this.lootTiers.clear();
        reloadMapEntries();
        reloadLootTiers();
    }

    public Path mapTemplateRoot() {
        return mapTemplateRoot;
    }

    public int maxBreachesPerPod() {
        return Math.max(1, breachConfig.getInt("settings.max-breaches-per-pod", 9));
    }

    public int maxPlayersPerPod() {
        return Math.max(1, breachConfig.getInt("settings.max-players-per-pod", 500));
    }

    public int minPlayers() {
        return Math.max(1, breachConfig.getInt("settings.min-players", 2));
    }

    public int startingCountdownSeconds() {
        return joiningCountdownSeconds();
    }

    public int joiningCountdownSeconds() {
        return Math.max(3, breachConfig.getInt("settings.starting-countdown-seconds", 10));
    }

    public int resetDelaySeconds() {
        return Math.max(1, breachConfig.getInt("settings.reset-delay-seconds", 5));
    }

    public int queueTimeoutSeconds() {
        return Math.max(10, breachConfig.getInt("settings.queue-timeout-seconds", 120));
    }

    public int worldPrewarmPerTemplate() {
        return worldPrewarmPerTemplate(false);
    }

    public int worldPrewarmPerTemplate(boolean folia) {
        int defaultValue = folia ? 0 : 1;
        return Math.max(0, breachConfig.getInt("settings.world-prewarm-per-template", defaultValue));
    }

    public int extractDwellSeconds() {
        return Math.max(1, breachConfig.getInt("settings.extract-dwell-seconds", 5));
    }

    public boolean autoDiscoverChests() {
        return breachConfig.getBoolean("settings.auto-discover-chests", true);
    }

    public boolean enhancedLootChests() {
        return breachConfig.getBoolean("settings.enhanced-loot-chests", true);
    }

    public boolean placeConfiguredChestBlocks() {
        return breachConfig.getBoolean("settings.place-configured-chest-blocks", true);
    }

    public long lootChestAmbientIntervalTicks() {
        return Math.max(10L, breachConfig.getLong("settings.loot-chest-ambient-interval-ticks", 40L));
    }

    public double lootChestAmbientRadius() {
        return Math.max(2.0, breachConfig.getDouble("settings.loot-chest-ambient-radius", 8.0));
    }

    public LootChestFx lootChestFx(String tier) {
        String key = tier == null || tier.isBlank() ? "common" : tier.trim().toLowerCase(Locale.ROOT);
        ConfigurationSection section = breachConfig.getConfigurationSection("loot-chest-fx." + key);
        if (section == null) {
            section = breachConfig.getConfigurationSection("loot-chest-fx.common");
        }
        if (section == null) {
            return LootChestFx.defaults(key);
        }
        return LootChestFx.fromSection(section, LootChestFx.defaults(key));
    }

    public String defaultLootTier() {
        String tier = breachConfig.getString("settings.default-loot-tier", "defaultChests");
        if (tier == null || tier.isBlank()) {
            return "defaultChests";
        }
        return tier.trim().toLowerCase(Locale.ROOT);
    }

    public int extractClosingSoonSeconds() {
        return Math.max(30, breachConfig.getInt("settings.extract-closing-soon-seconds", 300));
    }

    public boolean extractZoneParticlesEnabled() {
        return breachConfig.getBoolean("settings.extract-zone-particles-enabled", true);
    }

    public boolean extractZoneFakeBeaconEnabled() {
        return breachConfig.getBoolean("settings.extract-zone-fake-beacon-enabled", true);
    }

    public int combatTagSeconds() {
        return Math.max(1, breachConfig.getInt("settings.combat-tag-seconds", 15));
    }

    public HitscanSettings hitscanSettings() {
        HitscanSettings defaults = HitscanSettings.defaults();
        if (!breachConfig.getBoolean("hitscan.enabled", defaults.enabled())) {
            return new HitscanSettings(
                    false,
                    defaults.maxRangeBlocks(),
                    defaults.tracerSpacingBlocks(),
                    defaults.tracerViewRangeBlocks(),
                    defaults.tracerParticle(),
                    defaults.impactEffectsEnabled(),
                    defaults.impactViewRangeBlocks(),
                    defaults.impactBlockParticleCount(),
                    defaults.impactEntityParticleCount(),
                    defaults.impactBlockChipOverlay(),
                    defaults.impactBlockChipDamage(),
                    defaults.impactEntityParticle(),
                    defaults.visualDeferTicks(),
                    defaults.maxVisualJobsPerTick(),
                    defaults.visualQueueCapacity(),
                    defaults.maxTracerPoints(),
                    defaults.asyncVisualPrep(),
                    defaults.combatDispatchThreads(),
                    defaults.combatQueueCapacity(),
                    defaults.combatDeferTicks(),
                    defaults.simulatedProjectileWeapons()
            );
        }

        java.util.List<String> simulated = breachConfig.getStringList("hitscan.simulated-projectile-weapons");
        Set<String> simulatedWeapons = simulated == null || simulated.isEmpty()
                ? defaults.simulatedProjectileWeapons()
                : HitscanSettings.normalizeWeaponTitles(simulated);

        return new HitscanSettings(
                true,
                Math.max(8.0, breachConfig.getDouble("hitscan.max-range-blocks", defaults.maxRangeBlocks())),
                Math.max(1.0, breachConfig.getDouble("hitscan.tracer-spacing-blocks", defaults.tracerSpacingBlocks())),
                Math.max(8.0, breachConfig.getDouble("hitscan.tracer-view-range-blocks", defaults.tracerViewRangeBlocks())),
                HitscanSettings.parseParticle(
                        breachConfig.getString("hitscan.tracer-particle"),
                        defaults.tracerParticle()
                ),
                breachConfig.getBoolean("hitscan.impact.enabled", defaults.impactEffectsEnabled()),
                Math.max(8.0, breachConfig.getDouble("hitscan.impact.view-range-blocks", defaults.impactViewRangeBlocks())),
                Math.max(0, breachConfig.getInt("hitscan.impact.block-particle-count", defaults.impactBlockParticleCount())),
                Math.max(0, breachConfig.getInt("hitscan.impact.entity-particle-count", defaults.impactEntityParticleCount())),
                breachConfig.getBoolean("hitscan.impact.block-chip-overlay", defaults.impactBlockChipOverlay()),
                (float) Math.max(0.05, Math.min(0.95, breachConfig.getDouble(
                        "hitscan.impact.block-chip-damage",
                        defaults.impactBlockChipDamage()
                ))),
                HitscanSettings.parseParticle(
                        breachConfig.getString("hitscan.impact.entity-particle"),
                        defaults.impactEntityParticle()
                ),
                Math.max(0L, breachConfig.getLong("hitscan.visuals.defer-ticks", defaults.visualDeferTicks())),
                Math.max(1, breachConfig.getInt("hitscan.visuals.max-jobs-per-tick", defaults.maxVisualJobsPerTick())),
                Math.max(64, breachConfig.getInt("hitscan.visuals.queue-capacity", defaults.visualQueueCapacity())),
                Math.max(1, breachConfig.getInt("hitscan.visuals.max-tracer-points", defaults.maxTracerPoints())),
                breachConfig.getBoolean("hitscan.visuals.async-prep", defaults.asyncVisualPrep()),
                Math.max(0, breachConfig.getInt("hitscan.combat.dispatch-threads", defaults.combatDispatchThreads())),
                Math.max(64, breachConfig.getInt("hitscan.combat.queue-capacity", defaults.combatQueueCapacity())),
                Math.max(0L, breachConfig.getLong("hitscan.combat.defer-ticks", defaults.combatDeferTicks())),
                simulatedWeapons
        );
    }

    public List<BreachLootEntry> lootTable(String tier) {
        if (tier == null || tier.isBlank()) {
            return lootTiers.getOrDefault("common", List.of());
        }
        return lootTiers.getOrDefault(tier.trim().toLowerCase(Locale.ROOT), lootTiers.getOrDefault("common", List.of()));
    }

    private void reloadLootTiers() {
        ConfigurationSection tiersSection = breachConfig.getConfigurationSection("loot-tiers");
        if (tiersSection == null) {
            lootTiers.put("common", List.of(
                    new BreachLootEntry.WeaponMechanicsEntry("Uzi", 1, 0.35),
                    new BreachLootEntry.MaterialEntry(Material.IRON_SWORD, 1, 0.65),
                    new BreachLootEntry.MaterialEntry(Material.BREAD, 8, 0.85),
                    new BreachLootEntry.MaterialEntry(Material.ARROW, 16, 0.75)
            ));
            lootTiers.put("rare", List.of(
                    new BreachLootEntry.WeaponMechanicsEntry("AK_47", 1, 0.28),
                    new BreachLootEntry.MaterialEntry(Material.DIAMOND, 2, 0.85),
                    new BreachLootEntry.MaterialEntry(Material.GOLDEN_APPLE, 2, 0.65),
                    new BreachLootEntry.MaterialEntry(Material.IRON_CHESTPLATE, 1, 0.5)
            ));
            lootTiers.put("defaultchests", List.of(
                    new BreachLootEntry.WeaponMechanicsEntry("Combat_Knife", 1, 0.55),
                    new BreachLootEntry.MaterialEntry(Material.STONE_SWORD, 1, 0.85),
                    new BreachLootEntry.MaterialEntry(Material.COOKED_BEEF, 4, 0.75),
                    new BreachLootEntry.MaterialEntry(Material.ARROW, 8, 0.5)
            ));
            return;
        }
        for (String tierKey : tiersSection.getKeys(false)) {
            List<?> entries = tiersSection.getList(tierKey);
            if (entries == null || entries.isEmpty()) {
                continue;
            }
            List<BreachLootEntry> parsed = new ArrayList<>();
            for (Object raw : entries) {
                BreachLootEntry entry = parseLootEntry(raw);
                if (entry != null) {
                    parsed.add(entry);
                } else {
                    logger.warning("[Breach] Ignoring invalid loot entry in tier '" + tierKey + "': " + raw);
                }
            }
            lootTiers.put(tierKey.toLowerCase(Locale.ROOT), List.copyOf(parsed));
        }
    }

    private BreachLootEntry parseLootEntry(Object raw) {
        if (raw instanceof String text) {
            return parseLootEntryString(text);
        }
        if (raw instanceof Map<?, ?> map) {
            return parseLootEntryMap(map);
        }
        return null;
    }

    private BreachLootEntry parseLootEntryString(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("codec|")) {
            return parseCodecPipeEntry(trimmed.substring("codec|".length()));
        }
        if (lower.startsWith("wm|") || lower.startsWith("weapon-mechanics|")) {
            int separator = trimmed.indexOf('|');
            return parseWeaponMechanicsPipeEntry(trimmed.substring(separator + 1));
        }
        if (lower.startsWith("wm:") || lower.startsWith("weapon-mechanics:")) {
            int separator = trimmed.indexOf(':');
            return parseWeaponMechanicsColonEntry(trimmed.substring(separator + 1));
        }
        return parseMaterialColonEntry(trimmed);
    }

    private BreachLootEntry parseLootEntryMap(Map<?, ?> map) {
        String type = stringValue(map.get("type"));
        if (type == null || type.isBlank()) {
            type = "material";
        }
        int amount = parseConfigInt(map.get("amount"), 1);
        double chance = parseConfigDouble(map.get("chance"), 1.0);

        return switch (type.trim().toLowerCase(Locale.ROOT)) {
            case "codec", "itemstack", "itemstackcodec" -> {
                String payload = firstNonBlank(
                        stringValue(map.get("payload")),
                        stringValue(map.get("codec")),
                        stringValue(map.get("item"))
                );
                if (payload == null) {
                    yield null;
                }
                yield new BreachLootEntry.CodecEntry(payload, clampAmount(amount), clampChance(chance));
            }
            case "wm", "weapon-mechanics", "weaponmechanics" -> {
                String weapon = firstNonBlank(
                        stringValue(map.get("weapon")),
                        stringValue(map.get("title")),
                        stringValue(map.get("weapon-title"))
                );
                if (weapon == null) {
                    yield null;
                }
                yield new BreachLootEntry.WeaponMechanicsEntry(weapon, clampAmount(amount), clampChance(chance));
            }
            default -> {
                String materialName = firstNonBlank(
                        stringValue(map.get("material")),
                        stringValue(map.get("item"))
                );
                if (materialName == null) {
                    yield null;
                }
                Material material;
                try {
                    material = Material.valueOf(materialName.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    yield null;
                }
                yield new BreachLootEntry.MaterialEntry(material, clampAmount(amount), clampChance(chance));
            }
        };
    }

    private static BreachLootEntry parseCodecPipeEntry(String rest) {
        int firstSeparator = rest.indexOf('|');
        int secondSeparator = rest.indexOf('|', firstSeparator + 1);
        if (firstSeparator <= 0 || secondSeparator <= firstSeparator + 1) {
            return null;
        }
        int amount = parseConfigInt(rest.substring(0, firstSeparator), 1);
        double chance = parseConfigDouble(rest.substring(firstSeparator + 1, secondSeparator), 1.0);
        String payload = rest.substring(secondSeparator + 1).trim();
        if (payload.isBlank()) {
            return null;
        }
        return new BreachLootEntry.CodecEntry(payload, clampAmount(amount), clampChance(chance));
    }

    private static BreachLootEntry parseWeaponMechanicsPipeEntry(String rest) {
        int lastSeparator = rest.lastIndexOf('|');
        int middleSeparator = rest.lastIndexOf('|', lastSeparator - 1);
        if (middleSeparator <= 0 || lastSeparator <= middleSeparator + 1) {
            return null;
        }
        String weaponTitle = rest.substring(0, middleSeparator).trim();
        if (weaponTitle.isBlank()) {
            return null;
        }
        int amount = parseConfigInt(rest.substring(middleSeparator + 1, lastSeparator), 1);
        double chance = parseConfigDouble(rest.substring(lastSeparator + 1), 1.0);
        return new BreachLootEntry.WeaponMechanicsEntry(weaponTitle, clampAmount(amount), clampChance(chance));
    }

    private static BreachLootEntry parseWeaponMechanicsColonEntry(String rest) {
        String[] parts = rest.split(":");
        if (parts.length < 2) {
            return null;
        }
        int amountIndex = parts.length - 2;
        int chanceIndex = parts.length - 1;
        if (parts.length == 2) {
            amountIndex = 1;
            chanceIndex = -1;
        }
        int amount = parseConfigInt(parts[amountIndex], 1);
        double chance = chanceIndex >= 0 ? parseConfigDouble(parts[chanceIndex], 1.0) : 1.0;
        String weaponTitle = String.join(":", java.util.Arrays.copyOf(parts, amountIndex)).trim();
        if (weaponTitle.isBlank()) {
            return null;
        }
        return new BreachLootEntry.WeaponMechanicsEntry(weaponTitle, clampAmount(amount), clampChance(chance));
    }

    private static BreachLootEntry parseMaterialColonEntry(String raw) {
        String[] parts = raw.split(":");
        if (parts.length < 2) {
            return null;
        }
        Material material;
        try {
            material = Material.valueOf(parts[0].trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
        int amount = parseConfigInt(parts[1], 1);
        double chance = parts.length >= 3 ? parseConfigDouble(parts[2], 1.0) : 1.0;
        return new BreachLootEntry.MaterialEntry(material, clampAmount(amount), clampChance(chance));
    }

    private static int clampAmount(int amount) {
        return Math.max(1, amount);
    }

    private static double clampChance(double chance) {
        return Math.max(0.0, Math.min(1.0, chance));
    }

    private static int parseConfigInt(Object raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static double parseConfigDouble(Object raw, double fallback) {
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String stringValue(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        return value.isBlank() ? null : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    public String defaultMapId() {
        String configured = breachConfig.getString("settings.default-map", "");
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return mapEntries.keySet().stream().findFirst().orElse("aether-ruins");
    }

    public List<String> enabledMapIds() {
        return mapEntries.values().stream()
                .filter(BreachMapEntry::enabled)
                .map(BreachMapEntry::mapId)
                .toList();
    }

    public List<BreachMapEntry> enabledMapEntries() {
        return mapEntries.values().stream()
                .filter(BreachMapEntry::enabled)
                .toList();
    }

    public Optional<BreachMapEntry> mapEntry(String mapId) {
        if (mapId == null || mapId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapEntries.get(mapId.trim().toLowerCase(Locale.ROOT)));
    }

    public Optional<BreachMapMeta> mapMeta(String mapId) {
        if (mapId == null || mapId.isBlank()) {
            return Optional.empty();
        }
        String key = mapId.trim().toLowerCase(Locale.ROOT);
        BreachMapMeta cached = loadedMaps.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<BreachMapEntry> entry = mapEntry(key);
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        try {
            BreachMapMeta meta = loadMapMeta(entry.get());
            loadedMaps.put(key, meta);
            return Optional.of(meta);
        } catch (IOException ex) {
            logger.warning("[Breach] Failed to load map meta for '" + key + "': " + ex.getMessage());
            return Optional.empty();
        }
    }

    public Map<String, BreachMapMeta> loadedMapsSnapshot() {
        for (BreachMapEntry entry : mapEntries.values()) {
            if (entry.enabled()) {
                mapMeta(entry.mapId());
            }
        }
        return Map.copyOf(loadedMaps);
    }

    private void reloadMapEntries() {
        ConfigurationSection mapsSection = mapsConfig.getConfigurationSection("maps");
        if (mapsSection == null) {
            logger.warning("[Breach] No maps configured in breach-maps.yml");
            return;
        }
        for (String key : mapsSection.getKeys(false)) {
            ConfigurationSection section = mapsSection.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            String mapId = section.getString("map-id", key).trim().toLowerCase(Locale.ROOT);
            String template = section.getString("template", mapId).trim();
            boolean enabled = section.getBoolean("enabled", true);
            mapEntries.put(mapId, new BreachMapEntry(mapId, template, enabled));
        }
    }

    private BreachMapMeta loadMapMeta(BreachMapEntry entry) throws IOException {
        Path metaFile = mapTemplateRoot.resolve(entry.template()).resolve("meta.json");
        if (!Files.isRegularFile(metaFile)) {
            throw new IOException("Missing meta.json at " + metaFile);
        }
        return BreachMapMeta.fromPath(metaFile, entry.mapId());
    }

    private static FileConfiguration loadYaml(JavaPlugin plugin, String fileName) {
        Path file = plugin.getDataFolder().toPath().resolve(fileName);
        if (!Files.isRegularFile(file)) {
            plugin.saveResource(fileName, false);
        }
        return YamlConfiguration.loadConfiguration(file.toFile());
    }

    private static FileConfiguration loadMapsFile(JavaPlugin plugin, String mapsFileName) {
        Path mapsFile = plugin.getDataFolder().toPath().resolve(mapsFileName);
        if (!Files.isRegularFile(mapsFile)) {
            plugin.saveResource(mapsFileName, false);
        }
        return YamlConfiguration.loadConfiguration(mapsFile.toFile());
    }

    private static Path resolveMapTemplateRoot(JavaPlugin plugin) {
        String fromEnv = System.getenv("SPVP_MAP_TEMPLATE_ROOT");
        if (fromEnv != null && !fromEnv.isBlank()) {
            Path path = Paths.get(fromEnv.trim());
            if (!path.isAbsolute()) {
                path = plugin.getServer().getWorldContainer().toPath().resolve(path).normalize();
            }
            if (Files.isDirectory(path)) {
                return path;
            }
        }

        Path containerDefault = Paths.get("/opt/skypvp/map-templates");
        if (Files.isDirectory(containerDefault)) {
            return containerDefault;
        }

        String configured = plugin.getConfig().getString("map-template-root", "");
        if (configured != null && !configured.isBlank()) {
            Path path = Paths.get(configured.trim());
            if (!path.isAbsolute()) {
                path = plugin.getServer().getWorldContainer().toPath().resolve(path).normalize();
            }
            if (Files.isDirectory(path)) {
                return path;
            }
        }

        Path serverRoot = plugin.getServer().getWorldContainer().toPath();
        Path primary = serverRoot.resolve("../../config/map-templates").normalize();
        if (Files.isDirectory(primary)) {
            return primary;
        }
        Path local = serverRoot.resolve("config/map-templates").normalize();
        if (Files.isDirectory(local)) {
            return local;
        }
        return containerDefault;
    }

    public record BreachMapEntry(String mapId, String template, boolean enabled) {
        public BreachMapEntry {
            mapId = mapId == null ? "unknown" : mapId.trim().toLowerCase(Locale.ROOT);
            template = template == null || template.isBlank() ? mapId : template.trim();
        }
    }

    public record LootChestFx(
            Particle particle,
            Sound sound,
            float volume,
            float pitch,
            int particleCount,
            double particleSpeed,
            String glowColor
    ) {
        public static LootChestFx defaults(String tier) {
            return switch (tier == null ? "common" : tier.toLowerCase(Locale.ROOT)) {
                case "epic" -> new LootChestFx(
                        Particle.TOTEM_OF_UNDYING,
                        Sound.BLOCK_BEACON_AMBIENT,
                        0.5f,
                        1.2f,
                        6,
                        0.02,
                        "light_purple"
                );
                case "rare" -> new LootChestFx(
                        Particle.WAX_OFF,
                        Sound.BLOCK_AMETHYST_BLOCK_CHIME,
                        0.45f,
                        1.1f,
                        5,
                        0.015,
                        "aqua"
                );
                default -> new LootChestFx(
                        Particle.END_ROD,
                        Sound.BLOCK_NOTE_BLOCK_CHIME,
                        0.35f,
                        1.0f,
                        4,
                        0.01,
                        "yellow"
                );
            };
        }

        public static LootChestFx fromSection(ConfigurationSection section, LootChestFx fallback) {
            Particle particle = parseParticle(section.getString("particle"), fallback.particle());
            Sound sound = parseSound(section.getString("sound"), fallback.sound());
            float volume = (float) section.getDouble("volume", fallback.volume());
            float pitch = (float) section.getDouble("pitch", fallback.pitch());
            int particleCount = section.getInt("particle-count", fallback.particleCount());
            double particleSpeed = section.getDouble("particle-speed", fallback.particleSpeed());
            String glowColor = section.getString("glow-color", fallback.glowColor());
            return new LootChestFx(particle, sound, volume, pitch, particleCount, particleSpeed, glowColor);
        }

        private static Particle parseParticle(String raw, Particle fallback) {
            if (raw == null || raw.isBlank()) {
                return fallback;
            }
            try {
                return Particle.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return fallback;
            }
        }

        private static Sound parseSound(String raw, Sound fallback) {
            if (raw == null || raw.isBlank()) {
                return fallback;
            }
            try {
                return Sound.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return fallback;
            }
        }
    }
}
