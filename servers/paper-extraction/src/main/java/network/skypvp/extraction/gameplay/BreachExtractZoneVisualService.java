package network.skypvp.extraction.gameplay;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Logger;
import org.bukkit.block.data.BlockData;
import network.skypvp.extraction.config.BreachConfigService;
import network.skypvp.extraction.model.BreachMapMeta;
import network.skypvp.extraction.model.BreachState;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Per-player extract zone visuals using client-only block overlays ({@link Player#sendBlockChange}).
 * Fake beacons include a client-only air column to the build limit so enclosed maps still get beams.
 */
public final class BreachExtractZoneVisualService {

    public enum ExtractAvailability {
        OPEN(Material.LIME_STAINED_GLASS, Color.fromRGB(30, 255, 80)),
        CLOSING_SOON(Material.ORANGE_STAINED_GLASS, Color.fromRGB(255, 170, 30)),
        CLOSED(Material.RED_STAINED_GLASS, Color.fromRGB(255, 40, 40));

        private final Material glassMaterial;
        private final Color particleColor;

        ExtractAvailability(Material glassMaterial, Color particleColor) {
            this.glassMaterial = glassMaterial;
            this.particleColor = particleColor;
        }

        public Material glassMaterial() {
            return glassMaterial;
        }

        public Color particleColor() {
            return particleColor;
        }
    }

    private static final int MAX_RING_PARTICLES = 36;
    private static final int MAX_PILLAR_PARTICLES = 8;
    private static final int MAX_BLOCK_CHANGES_PER_TICK = 16;
    private static final int MAX_SKY_CLEAR_BLOCKS = 32;
    private static final BlockData BEACON_BLOCK_DATA = Material.BEACON.createBlockData();
    private static final BlockData PYRAMID_BLOCK_DATA = Material.IRON_BLOCK.createBlockData();
    private static final BlockData AIR_BLOCK_DATA = Material.AIR.createBlockData();

    private final BreachConfigService configService;
    private final Logger logger;
    private final Map<UUID, WorldVisuals> visualsByWorld = new ConcurrentHashMap<>();
    private final Set<UUID> overlayActivePlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, UUID> overlayWorldByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerVisualState> lastVisualStateByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerOverlayCache> overlayCacheByPlayer = new ConcurrentHashMap<>();

    private static final long OVERLAY_MAINTAIN_INTERVAL_MS = 15000L;

    private final Map<UUID, Deque<BlockChangeEntry>> pendingBlockChangesByPlayer = new ConcurrentHashMap<>();

    public BreachExtractZoneVisualService(JavaPlugin plugin, BreachConfigService configService) {
        Objects.requireNonNull(plugin, "plugin");
        this.configService = Objects.requireNonNull(configService, "configService");
        this.logger = plugin.getLogger();
    }

    public void setupWorld(World world, BreachMapMeta mapMeta) {
        if (world == null || mapMeta == null || mapMeta.extractZones().isEmpty()) {
            return;
        }

        teardownWorld(world);
        List<ZoneVisual> zones = new ArrayList<>();
        for (BreachMapMeta.ExtractZone zone : mapMeta.extractZones()) {
            zones.add(new ZoneVisual(zone, FakeBeaconLayout.fromZone(world, zone)));
        }
        this.visualsByWorld.put(world.getUID(), new WorldVisuals(zones));
        this.logger.info("[Breach] Registered " + zones.size() + " per-player fake beacon extract zone visual(s) in '"
                + world.getName() + "'.");
    }

    public void tickWorld(
            World world,
            BreachState state,
            int remainingSeconds,
            List<Player> viewers,
            Function<Player, ExtractZonePlayerView> playerViewLookup,
            boolean includeParticles
    ) {
        if (world == null) {
            return;
        }
        WorldVisuals visuals = this.visualsByWorld.get(world.getUID());
        if (visuals == null || visuals.zones().isEmpty() || viewers == null || viewers.isEmpty()) {
            return;
        }

        ExtractAvailability globalAvailability = resolveAvailability(
                state,
                remainingSeconds,
                this.configService.extractClosingSoonSeconds()
        );
        Set<UUID> activeViewerIds = new HashSet<>();
        long nowMillis = System.currentTimeMillis();

        for (Player viewer : viewers) {
            if (!viewer.isOnline() || !viewer.getWorld().equals(world)) {
                continue;
            }
            activeViewerIds.add(viewer.getUniqueId());
            this.flushPendingBlockChanges(viewer, MAX_BLOCK_CHANGES_PER_TICK);

            ExtractZonePlayerView playerView = playerViewLookup != null
                    ? playerViewLookup.apply(viewer)
                    : ExtractZonePlayerView.defaults();
            if (playerView.extracted()) {
                this.clearPlayer(viewer, world);
                continue;
            }

            ExtractAvailability playerAvailability = resolvePlayerAvailability(globalAvailability, playerView);
            Material glassMaterial = playerAvailability.glassMaterial();
            if (playerView.inExtractZone() && playerAvailability == ExtractAvailability.OPEN) {
                glassMaterial = Material.GREEN_STAINED_GLASS;
            }

            PlayerVisualState nextState = new PlayerVisualState(playerAvailability, glassMaterial);
            PlayerVisualState previousState = this.lastVisualStateByPlayer.get(viewer.getUniqueId());
            boolean glassColorChanged = previousState == null || !previousState.equals(nextState);

            PlayerOverlayCache overlayCache = this.overlayCacheByPlayer.computeIfAbsent(
                    viewer.getUniqueId(),
                    ignored -> new PlayerOverlayCache(world.getUID())
            );
            if (!world.getUID().equals(overlayCache.worldId)) {
                overlayCache.reset(world.getUID());
            }
            boolean forceFullOverlay = (nowMillis - overlayCache.lastFullRefreshAtMillis) >= OVERLAY_MAINTAIN_INTERVAL_MS;

            for (ZoneVisual zoneVisual : visuals.zones()) {
                if (this.configService.extractZoneFakeBeaconEnabled()) {
                    long zoneKey = zoneAnchorKey(zoneVisual.layout().beacon());
                    boolean staticApplied = overlayCache.staticZonesApplied.contains(zoneKey);
                    boolean sendStatic = forceFullOverlay || !staticApplied;
                    boolean sendGlass = glassColorChanged || forceFullOverlay;
                    if (sendStatic || sendGlass) {
                        this.applyFakeBeacon(viewer, zoneVisual, glassMaterial, sendStatic, sendGlass);
                        if (sendStatic) {
                            overlayCache.staticZonesApplied.add(zoneKey);
                        }
                    }
                    this.overlayActivePlayers.add(viewer.getUniqueId());
                    this.overlayWorldByPlayer.put(viewer.getUniqueId(), world.getUID());
                }
                if (includeParticles && this.configService.extractZoneParticlesEnabled()) {
                    this.renderCylinderForPlayer(viewer, zoneVisual, playerAvailability, playerView, glassMaterial);
                }
            }
            if (forceFullOverlay) {
                overlayCache.lastFullRefreshAtMillis = nowMillis;
            }
            this.lastVisualStateByPlayer.put(viewer.getUniqueId(), nextState);
        }

        if (includeParticles) {
            this.pruneInactiveOverlays(world, activeViewerIds);
        }
    }

    public void tickWorld(
            World world,
            BreachState state,
            int remainingSeconds,
            List<Player> viewers,
            Function<Player, ExtractZonePlayerView> playerViewLookup
    ) {
        this.tickWorld(world, state, remainingSeconds, viewers, playerViewLookup, true);
    }

    /**
     * Clears overlays for players in this world who should no longer see extract zone visuals.
     * Must be called with the full active viewer set for the instance, not a single player batch.
     */
    public void pruneInactiveOverlays(World world, Set<UUID> activeViewerIds) {
        if (world == null) {
            return;
        }
        UUID worldId = world.getUID();
        for (UUID playerId : Set.copyOf(this.overlayActivePlayers)) {
            if (!worldId.equals(this.overlayWorldByPlayer.get(playerId))) {
                continue;
            }
            if (activeViewerIds.contains(playerId)) {
                continue;
            }
            Player player = world.getPlayers().stream()
                    .filter(candidate -> candidate.getUniqueId().equals(playerId))
                    .findFirst()
                    .orElse(null);
            if (player != null) {
                this.clearPlayer(player, world);
            } else {
                this.overlayActivePlayers.remove(playerId);
                this.overlayWorldByPlayer.remove(playerId);
                this.lastVisualStateByPlayer.remove(playerId);
            }
        }
    }

    public void clearPlayer(Player player, World world) {
        if (player == null || world == null) {
            return;
        }
        WorldVisuals visuals = this.visualsByWorld.get(world.getUID());
        if (visuals == null) {
            return;
        }
        for (ZoneVisual zoneVisual : visuals.zones()) {
            this.restoreFakeBeacon(player, zoneVisual.layout());
        }
        this.overlayActivePlayers.remove(player.getUniqueId());
        this.overlayWorldByPlayer.remove(player.getUniqueId());
        this.lastVisualStateByPlayer.remove(player.getUniqueId());
        this.overlayCacheByPlayer.remove(player.getUniqueId());
        this.pendingBlockChangesByPlayer.remove(player.getUniqueId());
    }

    public void teardownWorld(World world) {
        if (world == null) {
            return;
        }
        UUID worldId = world.getUID();
        for (Player player : world.getPlayers()) {
            this.clearPlayer(player, world);
        }
        this.overlayCacheByPlayer.entrySet().removeIf(entry -> worldId.equals(entry.getValue().worldId));
        this.visualsByWorld.remove(worldId);
    }

    public static ExtractAvailability resolveAvailability(BreachState state, int remainingSeconds, int closingSoonSeconds) {
        if (state != BreachState.ACTIVE) {
            return ExtractAvailability.CLOSED;
        }
        if (remainingSeconds <= closingSoonSeconds) {
            return ExtractAvailability.CLOSING_SOON;
        }
        return ExtractAvailability.OPEN;
    }

    public static ExtractAvailability resolvePlayerAvailability(
            ExtractAvailability globalAvailability,
            ExtractZonePlayerView playerView
    ) {
        return globalAvailability;
    }

    private void applyFakeBeacon(
            Player player,
            ZoneVisual zoneVisual,
            Material glassMaterial,
            boolean includeStatic,
            boolean includeGlass
    ) {
        if (!includeStatic && !includeGlass) {
            return;
        }
        FakeBeaconLayout layout = zoneVisual.layout();
        Map<Location, BlockData> batch = new HashMap<>();
        if (includeStatic) {
            for (Location skyBlock : layout.skyClearColumn()) {
                batch.put(skyBlock, AIR_BLOCK_DATA);
            }
            for (Location pyramidBlock : layout.pyramidBase()) {
                batch.put(pyramidBlock, PYRAMID_BLOCK_DATA);
            }
            batch.put(layout.beacon(), BEACON_BLOCK_DATA);
        }
        if (includeGlass) {
            batch.put(layout.glass(), glassMaterial.createBlockData());
            batch.put(layout.beacon(), BEACON_BLOCK_DATA);
        }
        this.sendBlockChanges(player, batch);
    }

    private static long zoneAnchorKey(Location beacon) {
        return ((long) beacon.getBlockX() & 0x3FFFFFFL) << 38
                | ((long) beacon.getBlockY() & 0xFFFL) << 26
                | ((long) beacon.getBlockZ() & 0x3FFFFFFL);
    }

    private void sendBlockChanges(Player player, Map<Location, BlockData> changes) {
        if (player == null || changes == null || changes.isEmpty()) {
            return;
        }
        if (changes.size() <= MAX_BLOCK_CHANGES_PER_TICK) {
            for (Map.Entry<Location, BlockData> entry : changes.entrySet()) {
                player.sendBlockChange(entry.getKey(), entry.getValue());
            }
            return;
        }
        Deque<BlockChangeEntry> pending = this.pendingBlockChangesByPlayer.computeIfAbsent(
                player.getUniqueId(),
                ignored -> new ArrayDeque<>()
        );
        for (Map.Entry<Location, BlockData> entry : changes.entrySet()) {
            pending.addLast(new BlockChangeEntry(entry.getKey(), entry.getValue()));
        }
    }

    private void flushPendingBlockChanges(Player player, int maxPerTick) {
        Deque<BlockChangeEntry> pending = this.pendingBlockChangesByPlayer.get(player.getUniqueId());
        if (pending == null || pending.isEmpty()) {
            return;
        }
        int sent = 0;
        while (sent < maxPerTick) {
            BlockChangeEntry next = pending.pollFirst();
            if (next == null) {
                break;
            }
            player.sendBlockChange(next.location(), next.blockData());
            sent++;
        }
        if (pending.isEmpty()) {
            this.pendingBlockChangesByPlayer.remove(player.getUniqueId());
        }
    }

    private record BlockChangeEntry(Location location, BlockData blockData) {
    }

    private void restoreFakeBeacon(Player player, FakeBeaconLayout layout) {
        for (Map.Entry<Location, BlockData> entry : layout.realBlockData().entrySet()) {
            player.sendBlockChange(entry.getKey(), entry.getValue());
        }
    }

    private void renderCylinderForPlayer(
            Player player,
            ZoneVisual zoneVisual,
            ExtractAvailability availability,
            ExtractZonePlayerView playerView,
            Material glassMaterial
    ) {
        BreachMapMeta.ExtractZone zone = zoneVisual.zone();
        double centerX = zone.centerX();
        double centerZ = zone.centerZ();
        double minY = zone.minY() + 0.2;
        double maxY = zone.maxY() + 0.8;
        double radius = Math.max(0.8, zone.horizontalRadius() * 0.85);
        Color color = availability.particleColor();
        if (playerView.inExtractZone() && availability == ExtractAvailability.OPEN) {
            color = Color.fromRGB(80, 255, 120);
        } else if (glassMaterial == Material.ORANGE_STAINED_GLASS) {
            color = Color.fromRGB(255, 170, 30);
        } else if (glassMaterial == Material.RED_STAINED_GLASS) {
            color = Color.fromRGB(255, 40, 40);
        }

        Particle.DustOptions dust = new Particle.DustOptions(color, 1.25F);
        int rings = Math.min(10, Math.max(4, (int) Math.ceil(zone.height() * 0.6)));
        int pointsPerRing = Math.min(16, Math.max(8, (int) Math.ceil(radius * 3.0)));
        if (rings * pointsPerRing > MAX_RING_PARTICLES) {
            pointsPerRing = Math.max(8, MAX_RING_PARTICLES / rings);
        }
        double timeOffset = (System.currentTimeMillis() % 3600L) / 3600.0 * Math.PI * 2.0;

        for (int ring = 0; ring <= rings; ring++) {
            double y = minY + (maxY - minY) * ring / rings;
            for (int point = 0; point < pointsPerRing; point++) {
                double angle = (Math.PI * 2.0 * point / pointsPerRing) + timeOffset + (ring * 0.12);
                double x = centerX + radius * Math.cos(angle);
                double z = centerZ + radius * Math.sin(angle);
                player.spawnParticle(Particle.DUST, x, y, z, 1, 0.0, 0.0, 0.0, 0.0, dust, true);
            }
        }

        int pillarSteps = Math.min(MAX_PILLAR_PARTICLES, Math.max(4, (int) Math.ceil(zone.height())));
        for (int step = 0; step <= pillarSteps; step++) {
            double y = minY + (maxY - minY) * step / pillarSteps;
            player.spawnParticle(Particle.DUST, centerX, y, centerZ, 2, 0.08, 0.08, 0.08, 0.0, dust, true);
        }
    }

    private record WorldVisuals(List<ZoneVisual> zones) {
    }

    private record ZoneVisual(BreachMapMeta.ExtractZone zone, FakeBeaconLayout layout) {
    }

    private record PlayerVisualState(ExtractAvailability availability, Material glassMaterial) {
    }

    private static final class PlayerOverlayCache {
        private UUID worldId;
        private long lastFullRefreshAtMillis;
        private final Set<Long> staticZonesApplied = new HashSet<>();

        private PlayerOverlayCache(UUID worldId) {
            this.worldId = worldId;
        }

        private void reset(UUID nextWorldId) {
            this.worldId = nextWorldId;
            this.lastFullRefreshAtMillis = 0L;
            this.staticZonesApplied.clear();
        }
    }

    private record FakeBeaconLayout(
            Location beacon,
            Location glass,
            List<Location> pyramidBase,
            List<Location> skyClearColumn,
            Map<Location, BlockData> realBlockData
    ) {
        static FakeBeaconLayout fromZone(World world, BreachMapMeta.ExtractZone zone) {
            int centerX = (int) Math.floor(zone.centerX());
            int centerZ = (int) Math.floor(zone.centerZ());
            int beaconY = resolveBeaconY(world, zone, centerX, centerZ);
            Location beacon = new Location(world, centerX, beaconY, centerZ);
            Location glass = beacon.clone().add(0, 1, 0);

            List<Location> pyramid = new ArrayList<>(9);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    pyramid.add(new Location(world, centerX + dx, beaconY - 1, centerZ + dz));
                }
            }

            List<Location> skyClearColumn = buildSkyClearColumn(world, beacon);

            Map<Location, BlockData> realBlockData = new java.util.LinkedHashMap<>();
            for (Location overlay : allOverlayBlocks(beacon, glass, pyramid, skyClearColumn)) {
                realBlockData.put(overlay.clone(), overlay.getBlock().getBlockData());
            }
            return new FakeBeaconLayout(beacon, glass, List.copyOf(pyramid), List.copyOf(skyClearColumn), Map.copyOf(realBlockData));
        }

        private static List<Location> buildSkyClearColumn(World world, Location beacon) {
            List<Location> column = new ArrayList<>();
            int x = beacon.getBlockX();
            int z = beacon.getBlockZ();
            int startY = beacon.getBlockY() + 2;
            int maxY = world.getMaxHeight() - 1;
            for (int y = startY; y <= maxY && column.size() < MAX_SKY_CLEAR_BLOCKS; y++) {
                Location location = new Location(world, x, y, z);
                if (!location.getBlock().getType().isAir()) {
                    column.add(location);
                }
            }
            return column;
        }

        private static List<Location> allOverlayBlocks(
                Location beacon,
                Location glass,
                List<Location> pyramidBase,
                List<Location> skyClearColumn
        ) {
            List<Location> blocks = new ArrayList<>(pyramidBase.size() + skyClearColumn.size() + 2);
            blocks.addAll(pyramidBase);
            blocks.add(beacon);
            blocks.add(glass);
            blocks.addAll(skyClearColumn);
            return blocks;
        }

        private static int resolveBeaconY(World world, BreachMapMeta.ExtractZone zone, int centerX, int centerZ) {
            int maxY = (int) Math.floor(zone.maxY());
            int minY = (int) Math.floor(zone.minY());
            for (int y = maxY; y >= minY; y--) {
                Material type = world.getBlockAt(centerX, y, centerZ).getType();
                if (type.isSolid() && type != Material.BARRIER) {
                    return y + 1;
                }
            }
            return minY + 1;
        }
    }
}
