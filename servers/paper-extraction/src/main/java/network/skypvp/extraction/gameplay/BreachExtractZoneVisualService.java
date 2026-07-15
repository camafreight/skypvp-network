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
import network.skypvp.extraction.engine.BreachInstance;
import network.skypvp.extraction.model.BreachMapMeta;
import network.skypvp.extraction.model.BreachState;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemDisplay.ItemDisplayTransform;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Extract zone visuals:
 * <ul>
 *   <li>Shared custom {@link ItemDisplay} beam ({@code skypvp:extract_beacon_beam}) —
 *       crossed translucent planes using the vanilla beacon_beam texture, continuously
 *       rotated to mimic the real beacon pillar from floor up toward world max height.</li>
 *   <li>Optional per-player fake beacon overlays ({@link Player#sendBlockChange}).</li>
 *   <li>Dense zone-boundary dust particles.</li>
 * </ul>
 */
public final class BreachExtractZoneVisualService {

    private static final NamespacedKey BEAM_MODEL = new NamespacedKey("skypvp", "extract_beacon_beam");
    /** Crossed-plane span in Blockbench units (6 → 10). 16 units = 1 block at scale 1. */
    private static final float BEAM_MODEL_WIDTH_UNITS = 4.0F;
    private static final float BEAM_MODEL_HEIGHT_UNITS = 16.0F;
    private static final float BEAM_MODEL_WIDTH_BLOCKS = BEAM_MODEL_WIDTH_UNITS / 16.0F;
    private static final float BEAM_MODEL_HEIGHT_BLOCKS = BEAM_MODEL_HEIGHT_UNITS / 16.0F;

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

    private static final int MAX_RING_PARTICLES = 160;
    private static final int MAX_PILLAR_PARTICLES = 40;
    private static final int MAX_BLOCK_CHANGES_PER_TICK = 16;
    private static final int MAX_SKY_CLEAR_BLOCKS = 32;
    private static final BlockData BEACON_BLOCK_DATA = Material.BEACON.createBlockData();
    private static final BlockData PYRAMID_BLOCK_DATA = Material.IRON_BLOCK.createBlockData();
    private static final BlockData AIR_BLOCK_DATA = Material.AIR.createBlockData();

    private final JavaPlugin plugin;
    private final BreachConfigService configService;
    private final network.skypvp.paper.platform.ServerPlatform platform;
    private final Logger logger;
    private final Map<UUID, WorldVisuals> visualsByWorld = new ConcurrentHashMap<>();
    private final Set<UUID> overlayActivePlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, UUID> overlayWorldByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerVisualState> lastVisualStateByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerOverlayCache> overlayCacheByPlayer = new ConcurrentHashMap<>();

    private static final long OVERLAY_MAINTAIN_INTERVAL_MS = 15000L;

    /** Per-player queues drained on each owner's region thread — must be concurrent. */
    private final Map<UUID, Deque<BlockChangeEntry>> pendingBlockChangesByPlayer = new ConcurrentHashMap<>();

    public BreachExtractZoneVisualService(
            JavaPlugin plugin,
            BreachConfigService configService,
            network.skypvp.paper.platform.ServerPlatform platform
    ) {
        Objects.requireNonNull(plugin, "plugin");
        this.plugin = plugin;
        this.configService = Objects.requireNonNull(configService, "configService");
        this.platform = platform;
        this.logger = plugin.getLogger();
    }

    public void setupWorld(World world, BreachMapMeta mapMeta) {
        if (world == null || mapMeta == null || mapMeta.extractZones().isEmpty()) {
            return;
        }

        teardownWorld(world);
        List<ZoneVisual> zones = new ArrayList<>();
        for (BreachMapMeta.ExtractZone zone : mapMeta.extractZones()) {
            ZoneVisual visual = new ZoneVisual(zone, FakeBeaconLayout.fromZone(world, zone));
            if (this.configService.extractZoneBeamEnabled()) {
                visual.spawnBeam(world, this.configService);
            }
            zones.add(visual);
        }
        this.visualsByWorld.put(world.getUID(), new WorldVisuals(zones));
        this.logger.info("[Breach] Registered " + zones.size() + " extract zone visual(s) in '"
                + world.getName() + "' (itemBeam=" + this.configService.extractZoneBeamEnabled()
                + ", fakeBeacon=" + this.configService.extractZoneFakeBeaconEnabled()
                + ", particles=" + this.configService.extractZoneParticlesEnabled() + ").");
    }

    public void tickWorld(
            BreachInstance instance,
            List<Player> viewers,
            Function<Player, ExtractZonePlayerView> playerViewLookup,
            boolean includeParticles
    ) {
        if (instance == null || instance.world() == null) {
            return;
        }
        World world = instance.world();
        WorldVisuals visuals = this.visualsByWorld.get(world.getUID());
        if (visuals == null || visuals.zones().isEmpty() || viewers == null || viewers.isEmpty()) {
            return;
        }
        BreachExtractZoneSchedule schedule = instance.extractZoneSchedule();
        BreachState state = instance.state();
        int remainingSeconds = instance.remainingSeconds();
        Set<UUID> activeViewerIds = new HashSet<>();
        long nowMillis = System.currentTimeMillis();

        // Availability is pure schedule math — compute once here (any thread), then emit
        // per VIEWER on that player's region thread and per BEAM on the entity's owning
        // region. This used to run entirely on one region (the spawn anchor), which both
        // mutated beam displays in other regions (Folia violation) and burst every
        // viewer's packet work onto the busiest region each second (tick spikes).
        Map<String, ExtractAvailability> availabilityByZone = new HashMap<>();
        for (ZoneVisual zoneVisual : visuals.zones()) {
            availabilityByZone.put(zoneVisual.zone().id(), schedule != null
                    ? schedule.zoneAvailability(zoneVisual.zone().id(), state, remainingSeconds)
                    : resolveAvailability(state, remainingSeconds, this.configService.extractClosingSoonSeconds()));
        }

        for (Player viewer : viewers) {
            if (!viewer.isOnline() || !viewer.getWorld().equals(world)) {
                continue;
            }
            activeViewerIds.add(viewer.getUniqueId());
            if (this.platform != null) {
                this.platform.runOnPlayer(viewer, () ->
                        this.renderViewerScheduled(viewer, world, instance, availabilityByZone, playerViewLookup, includeParticles, nowMillis));
            } else {
                this.renderViewerScheduled(viewer, world, instance, availabilityByZone, playerViewLookup, includeParticles, nowMillis);
            }
        }

        if (this.configService.extractZoneBeamEnabled()) {
            this.dispatchBeamUpdates(visuals, availabilityByZone);
        }

        if (includeParticles) {
            this.pruneInactiveOverlays(world, activeViewerIds);
        }
    }

    /** Per-viewer overlay/particle emission; runs on the viewer's region thread. */
    private void renderViewerScheduled(
            Player viewer,
            World world,
            BreachInstance instance,
            Map<String, ExtractAvailability> availabilityByZone,
            Function<Player, ExtractZonePlayerView> playerViewLookup,
            boolean includeParticles,
            long nowMillis
    ) {
        if (!viewer.isOnline() || !viewer.getWorld().equals(world)) {
            return;
        }
        WorldVisuals visuals = this.visualsByWorld.get(world.getUID());
        if (visuals == null) {
            return;
        }
        this.flushPendingBlockChanges(viewer, MAX_BLOCK_CHANGES_PER_TICK);

        ExtractZonePlayerView playerView = playerViewLookup != null
                ? playerViewLookup.apply(viewer)
                : ExtractZonePlayerView.defaults();
        if (playerView.extracted()) {
            this.clearPlayer(viewer, world);
            return;
        }

        PlayerOverlayCache overlayCache = this.overlayCacheByPlayer.computeIfAbsent(
                viewer.getUniqueId(),
                ignored -> new PlayerOverlayCache(world.getUID())
        );
        if (!world.getUID().equals(overlayCache.worldId)) {
            overlayCache.reset(world.getUID());
        }
        boolean forceFullOverlay = (nowMillis - overlayCache.lastFullRefreshAtMillis) >= OVERLAY_MAINTAIN_INTERVAL_MS;

        for (ZoneVisual zoneVisual : visuals.zones()) {
            ExtractAvailability zoneAvailability = availabilityByZone.getOrDefault(
                    zoneVisual.zone().id(), ExtractAvailability.CLOSED);
            ExtractAvailability playerAvailability = resolvePlayerAvailability(zoneAvailability, playerView);
            Material glassMaterial = playerAvailability.glassMaterial();
            if (playerView.inExtractZone()
                    && playerAvailability == ExtractAvailability.OPEN
                    && instance.isInOpenExtractZone(viewer.getLocation())
                    && zoneVisual.zone().contains(
                            viewer.getLocation().getX(),
                            viewer.getLocation().getY(),
                            viewer.getLocation().getZ())) {
                glassMaterial = Material.GREEN_STAINED_GLASS;
            }

            if (this.configService.extractZoneFakeBeaconEnabled()) {
                long zoneKey = zoneAnchorKey(zoneVisual.layout().beacon());
                boolean staticApplied = overlayCache.staticZonesApplied.contains(zoneKey);
                boolean sendStatic = forceFullOverlay || !staticApplied;
                Material previousGlass = overlayCache.glassByZone.get(zoneKey);
                boolean sendGlass = forceFullOverlay || !Objects.equals(previousGlass, glassMaterial);
                if (sendStatic || sendGlass) {
                    this.applyFakeBeacon(viewer, zoneVisual, glassMaterial, sendStatic, sendGlass);
                    if (sendStatic) {
                        overlayCache.staticZonesApplied.add(zoneKey);
                    }
                    if (sendGlass) {
                        overlayCache.glassByZone.put(zoneKey, glassMaterial);
                    }
                }
                this.overlayActivePlayers.add(viewer.getUniqueId());
                this.overlayWorldByPlayer.put(viewer.getUniqueId(), world.getUID());
            }
            if (includeParticles && this.configService.extractZoneParticlesEnabled()
                    && this.tryParticleBudget(24)) {
                this.renderCylinderForPlayer(viewer, zoneVisual, playerAvailability, playerView, glassMaterial);
            }
        }
        if (forceFullOverlay) {
            overlayCache.lastFullRefreshAtMillis = nowMillis;
        }
    }

    /** Display-entity mutations must run on each beam's owning region, not the caller's. */
    private void dispatchBeamUpdates(WorldVisuals visuals, Map<String, ExtractAvailability> availabilityByZone) {
        for (ZoneVisual zoneVisual : visuals.zones()) {
            ExtractAvailability zoneAvailability = availabilityByZone.getOrDefault(
                    zoneVisual.zone().id(), ExtractAvailability.CLOSED);
            org.bukkit.entity.ItemDisplay beam = zoneVisual.beam;
            if (beam == null) {
                continue;
            }
            if (this.platform != null) {
                this.platform.runAtEntity(beam, () ->
                        zoneVisual.updateBeam(zoneAvailability, ExtractZonePlayerView.defaults(), zoneAvailability.glassMaterial()));
            } else {
                zoneVisual.updateBeam(zoneAvailability, ExtractZonePlayerView.defaults(), zoneAvailability.glassMaterial());
            }
        }
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
            if (this.platform != null) {
                this.platform.runOnPlayer(viewer, () ->
                        this.renderViewerGlobal(viewer, world, globalAvailability, playerViewLookup, includeParticles, nowMillis));
            } else {
                this.renderViewerGlobal(viewer, world, globalAvailability, playerViewLookup, includeParticles, nowMillis);
            }
        }

        if (this.configService.extractZoneBeamEnabled()) {
            Map<String, ExtractAvailability> availabilityByZone = new HashMap<>();
            for (ZoneVisual zoneVisual : visuals.zones()) {
                availabilityByZone.put(zoneVisual.zone().id(), globalAvailability);
            }
            this.dispatchBeamUpdates(visuals, availabilityByZone);
        }

        if (includeParticles) {
            this.pruneInactiveOverlays(world, activeViewerIds);
        }
    }

    /** Per-viewer emission for the schedule-less overload; runs on the viewer's region thread. */
    private void renderViewerGlobal(
            Player viewer,
            World world,
            ExtractAvailability globalAvailability,
            Function<Player, ExtractZonePlayerView> playerViewLookup,
            boolean includeParticles,
            long nowMillis
    ) {
        if (!viewer.isOnline() || !viewer.getWorld().equals(world)) {
            return;
        }
        WorldVisuals visuals = this.visualsByWorld.get(world.getUID());
        if (visuals == null) {
            return;
        }
        this.flushPendingBlockChanges(viewer, MAX_BLOCK_CHANGES_PER_TICK);

        ExtractZonePlayerView playerView = playerViewLookup != null
                ? playerViewLookup.apply(viewer)
                : ExtractZonePlayerView.defaults();
        if (playerView.extracted()) {
            this.clearPlayer(viewer, world);
            return;
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
            if (includeParticles && this.configService.extractZoneParticlesEnabled()
                    && this.tryParticleBudget(24)) {
                this.renderCylinderForPlayer(viewer, zoneVisual, playerAvailability, playerView, glassMaterial);
            }
        }
        if (forceFullOverlay) {
            overlayCache.lastFullRefreshAtMillis = nowMillis;
        }
        this.lastVisualStateByPlayer.put(viewer.getUniqueId(), nextState);
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
        WorldVisuals visuals = this.visualsByWorld.remove(worldId);
        if (visuals != null) {
            for (ZoneVisual zoneVisual : visuals.zones()) {
                zoneVisual.removeBeam();
            }
        }
        this.overlayCacheByPlayer.entrySet().removeIf(entry -> worldId.equals(entry.getValue().worldId));
    }

    public static ExtractAvailability resolveAvailability(BreachState state, int remainingSeconds, int closingSoonSeconds) {
        if (state == BreachState.TOXIC || state == BreachState.ENDING || state == BreachState.RESETTING) {
            return ExtractAvailability.CLOSED;
        }
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
                ignored -> new java.util.concurrent.ConcurrentLinkedDeque<>()
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

    private boolean tryParticleBudget(int units) {
        if (!(this.plugin.getServer().getPluginManager().getPlugin("SkyPvPCore")
                instanceof network.skypvp.paper.PaperCorePlugin core)
                || core.clientUpdatePipeline() == null) {
            return true;
        }
        return core.clientUpdatePipeline().tryAcquire(
                network.skypvp.paper.clientupdate.UpdateChannel.PARTICLE,
                units
        );
    }

    /** Full cylinder detail only renders for viewers within this range of the zone center. */
    private static final double CYLINDER_DETAIL_RANGE_SQ = 64.0D * 64.0D;
    /** Beyond this, no particles at all — the fake beacon beam handles long-range marking. */
    private static final double MARKER_RANGE_SQ = 96.0D * 96.0D;

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

        // Every spawnParticle call is one packet per receiving client — this method used to
        // push 400+ FORCED dust packets per viewer per engine tick for every zone on the map,
        // which arrived as a burst and froze clients. Now: no force flag (respects client
        // particle settings + vanilla 32-block cull), distant viewers get a slim marker
        // pillar only, and ring/pillar densities are sized for readability, not coverage.
        Particle.DustOptions dust = new Particle.DustOptions(color, 1.85F);
        double viewerDistanceSq = player.getLocation().distanceSquared(
                new org.bukkit.Location(player.getWorld(), centerX, (minY + maxY) * 0.5, centerZ));
        if (viewerDistanceSq > MARKER_RANGE_SQ) {
            // Clients discard non-forced particles this far out — the fake beacon beam is
            // the long-range marker; sending these packets would be pure waste.
            return;
        }
        boolean detailed = viewerDistanceSq <= CYLINDER_DETAIL_RANGE_SQ;
        double timeOffset = (System.currentTimeMillis() % 3600L) / 3600.0 * Math.PI * 2.0;

        if (detailed) {
            int rings = Math.min(6, Math.max(3, (int) Math.ceil(zone.height() * 0.4)));
            int pointsPerRing = Math.min(14, Math.max(8, (int) Math.ceil(radius * 2.5)));
            if (rings * pointsPerRing > MAX_RING_PARTICLES) {
                pointsPerRing = Math.max(8, MAX_RING_PARTICLES / rings);
            }
            for (int ring = 0; ring <= rings; ring++) {
                double y = minY + (maxY - minY) * ring / rings;
                for (int point = 0; point < pointsPerRing; point++) {
                    double angle = (Math.PI * 2.0 * point / pointsPerRing) + timeOffset + (ring * 0.12);
                    double x = centerX + radius * Math.cos(angle);
                    double z = centerZ + radius * Math.sin(angle);
                    player.spawnParticle(Particle.DUST, x, y, z, 1, 0.0, 0.0, 0.0, 0.0, dust, false);
                }
            }
        }

        int pillarSteps = Math.min(12, Math.max(6, (int) Math.ceil(zone.height() * 0.8)));
        for (int step = 0; step <= pillarSteps; step++) {
            double y = minY + (maxY - minY) * step / pillarSteps;
            player.spawnParticle(Particle.DUST, centerX, y, centerZ, 1, 0.12, 0.12, 0.12, 0.0, dust, false);
            if ((step & 1) == 0) {
                player.spawnParticle(Particle.END_ROD, centerX, y, centerZ, 1, 0.15, 0.05, 0.15, 0.01);
            }
        }
    }

    private record WorldVisuals(List<ZoneVisual> zones) {
    }

    private static final class ZoneVisual {
        private final BreachMapMeta.ExtractZone zone;
        private final FakeBeaconLayout layout;
        private ItemDisplay beam;
        private Color lastBeamColor;
        private float beamYawDegrees;
        private float beamHeightBlocks;
        private float beamThicknessBlocks;

        private ZoneVisual(BreachMapMeta.ExtractZone zone, FakeBeaconLayout layout) {
            this.zone = zone;
            this.layout = layout;
        }

        private BreachMapMeta.ExtractZone zone() {
            return this.zone;
        }

        private FakeBeaconLayout layout() {
            return this.layout;
        }

        private void spawnBeam(World world, BreachConfigService config) {
            this.removeBeam();
            // Full pillar from near world floor to max height so the beam stays visible at render edge.
            double baseY = world.getMinHeight() + 2.0D;
            double maxReach = world.getMaxHeight() - 0.5D;
            double height = Math.max(16.0D, maxReach - baseY);
            double thickness = Math.max(0.35, config.extractZoneBeamThicknessBlocks());
            this.beamHeightBlocks = (float) height;
            this.beamThicknessBlocks = (float) thickness;
            this.beamYawDegrees = 0.0F;

            Location spawnAt = new Location(world, this.zone.centerX(), baseY, this.zone.centerZ());
            // Model units → blocks: scale so configured thickness/height match world meters.
            float scaleXZ = (float) (thickness / BEAM_MODEL_WIDTH_BLOCKS);
            float scaleY = (float) (height / BEAM_MODEL_HEIGHT_BLOCKS);
            ItemStack item = createBeamItem(ExtractAvailability.OPEN.particleColor());
            try {
                this.beam = world.spawn(spawnAt, ItemDisplay.class, entity -> {
                    entity.setItemStack(item);
                    entity.setItemDisplayTransform(ItemDisplayTransform.FIXED);
                    entity.setBillboard(Display.Billboard.FIXED);
                    entity.setGlowing(false);
                    entity.setPersistent(false);
                    entity.setInterpolationDuration(2);
                    entity.setTeleportDuration(0);
                    entity.setViewRange(6.0F);
                    entity.setBrightness(new Display.Brightness(15, 15));
                    entity.setRotation(0.0F, 0.0F);
                    // Model centered on (8,8,8); lift by half height so the pillar sits on the floor.
                    entity.setTransformation(new Transformation(
                            new Vector3f(0.0F, this.beamHeightBlocks * 0.5F, 0.0F),
                            new Quaternionf(),
                            new Vector3f(scaleXZ, scaleY, scaleXZ),
                            new Quaternionf()
                    ));
                });
                this.lastBeamColor = ExtractAvailability.OPEN.particleColor();
            } catch (RuntimeException ex) {
                this.beam = null;
            }
        }

        private void updateBeam(
                ExtractAvailability availability,
                ExtractZonePlayerView playerView,
                Material glassMaterial
        ) {
            if (this.beam == null || !this.beam.isValid() || this.beam.isDead()) {
                return;
            }
            Color color = availability.particleColor();
            if (playerView != null && playerView.inExtractZone() && availability == ExtractAvailability.OPEN) {
                color = Color.fromRGB(80, 255, 120);
            } else if (glassMaterial == Material.GREEN_STAINED_GLASS) {
                color = Color.fromRGB(80, 255, 120);
            } else if (glassMaterial == Material.ORANGE_STAINED_GLASS) {
                color = Color.fromRGB(255, 170, 30);
            } else if (glassMaterial == Material.RED_STAINED_GLASS) {
                color = Color.fromRGB(255, 40, 40);
            }
            if (!Objects.equals(color, this.lastBeamColor)) {
                this.beam.setItemStack(createBeamItem(color));
                this.lastBeamColor = color;
            }

            // Continuous Y-spin from wall-clock (unbounded degrees so interpolation never wraps).
            this.beamYawDegrees = System.currentTimeMillis() / 25.0F; // ~9s per full turn
            float scaleXZ = this.beamThicknessBlocks / BEAM_MODEL_WIDTH_BLOCKS;
            float scaleY = this.beamHeightBlocks / BEAM_MODEL_HEIGHT_BLOCKS;
            this.beam.setInterpolationDuration(20);
            this.beam.setInterpolationDelay(0);
            this.beam.setTransformation(new Transformation(
                    new Vector3f(0.0F, this.beamHeightBlocks * 0.5F, 0.0F),
                    new Quaternionf().rotateY((float) Math.toRadians(this.beamYawDegrees)),
                    new Vector3f(scaleXZ, scaleY, scaleXZ),
                    new Quaternionf()
            ));
        }

        private void removeBeam() {
            if (this.beam != null) {
                try {
                    if (this.beam.isValid() && !this.beam.isDead()) {
                        this.beam.remove();
                    }
                } catch (RuntimeException ignored) {
                    // World may already be unloading.
                }
                this.beam = null;
                this.lastBeamColor = null;
            }
        }

        private static ItemStack createBeamItem(Color tint) {
            Color resolved = tint != null ? tint : Color.fromRGB(30, 255, 80);
            ItemStack item = new ItemStack(Material.LEATHER_HORSE_ARMOR);
            item.editMeta(meta -> {
                meta.setItemModel(BEAM_MODEL);
                if (meta instanceof LeatherArmorMeta leather) {
                    leather.setColor(resolved);
                }
            });
            return item;
        }
    }

    private record PlayerVisualState(ExtractAvailability availability, Material glassMaterial) {
    }

    private static final class PlayerOverlayCache {
        private UUID worldId;
        private long lastFullRefreshAtMillis;
        private final Set<Long> staticZonesApplied = new HashSet<>();
        private final Map<Long, Material> glassByZone = new HashMap<>();

        private PlayerOverlayCache(UUID worldId) {
            this.worldId = worldId;
        }

        private void reset(UUID nextWorldId) {
            this.worldId = nextWorldId;
            this.lastFullRefreshAtMillis = 0L;
            this.staticZonesApplied.clear();
            this.glassByZone.clear();
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
            int floorSurfaceY = resolveFloorSurfaceY(world, zone, centerX, centerZ);
            // Glass sits in the walkable floor block (one below a mistaken ceiling hit from the scan).
            Location glass = new Location(world, centerX, floorSurfaceY, centerZ);
            Location beacon = glass.clone().add(0, -1, 0);
            int pyramidY = beacon.getBlockY() - 1;

            List<Location> pyramid = new ArrayList<>(9);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    pyramid.add(new Location(world, centerX + dx, pyramidY, centerZ + dz));
                }
            }

            List<Location> skyClearColumn = buildSkyClearColumn(world, glass);

            Map<Location, BlockData> realBlockData = new java.util.LinkedHashMap<>();
            for (Location overlay : allOverlayBlocks(beacon, glass, pyramid, skyClearColumn)) {
                realBlockData.put(overlay.clone(), overlay.getBlock().getBlockData());
            }
            return new FakeBeaconLayout(beacon, glass, List.copyOf(pyramid), List.copyOf(skyClearColumn), Map.copyOf(realBlockData));
        }

        private static List<Location> buildSkyClearColumn(World world, Location glass) {
            List<Location> column = new ArrayList<>();
            int x = glass.getBlockX();
            int z = glass.getBlockZ();
            int startY = glass.getBlockY() + 1;
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

        /** Top walkable block at the zone center — stained glass is overlaid here (flush with the floor). */
        private static int resolveFloorSurfaceY(World world, BreachMapMeta.ExtractZone zone, int centerX, int centerZ) {
            int maxY = (int) Math.floor(zone.maxY());
            int minY = (int) Math.floor(zone.minY());
            for (int y = maxY; y >= minY; y--) {
                Material type = world.getBlockAt(centerX, y, centerZ).getType();
                if (!type.isSolid() || type == Material.BARRIER) {
                    continue;
                }
                Material above = y >= world.getMaxHeight() - 1
                        ? Material.AIR
                        : world.getBlockAt(centerX, y + 1, centerZ).getType();
                if (above.isSolid() && above != Material.BARRIER) {
                    continue;
                }
                return y;
            }
            return minY;
        }
    }
}
