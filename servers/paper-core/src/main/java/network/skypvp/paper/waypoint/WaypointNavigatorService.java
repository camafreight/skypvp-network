package network.skypvp.paper.waypoint;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.platform.PlatformTask;
import network.skypvp.shared.ServerTextUtil;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Core waypoint navigator: guides a player to world coordinates with
 * <ul>
 *   <li>a full-height beacon beam (world min+2 → max height) that leads the player by
 *       {@link #BEAM_FOLLOW_BLOCKS} while far, then snaps to the true target when close</li>
 *   <li>an optional octagon marker (UP/DOWN elevation hint in its center) + a label with a
 *       distance readout, pinned {@link #MARKER_MIN_DISTANCE_BLOCKS} out along the
 *       player→target ray at the target's own Y</li>
 *   <li>proximity hide within {@link Waypoint#hideWithinBlocks()} — visuals vanish when the
 *       player is on top of the target and return when they step back (objective must still
 *       call {@link #clear} to dismiss permanently)</li>
 * </ul>
 *
 * <p>Folia-safe: per-player tickers run on the player's region; world-anchored displays are
 * spawned/mutated via {@code runAtLocation}/{@code runAtEntity}. Entities are per-viewer
 * ({@code setVisibleByDefault(false)} + {@code showEntity}).
 *
 * <p>Replaces the old QuestShout floating board as the primary "guide me there" system.
 */
public final class WaypointNavigatorService implements Listener {

    /**
     * Beam + marker plate render as {@link TextDisplay} glyphs from the {@code skypvp:nav} font
     * (see {@code assets/skypvp/font/nav.json}) instead of ItemDisplays: item models are always
     * depth-tested and vanish behind terrain, while see-through text renders through blocks —
     * so the beam and octagon stay visible from spawn exactly like the destination label does.
     */
    private static final net.kyori.adventure.key.Key NAV_FONT = net.kyori.adventure.key.Key.key("skypvp", "nav");
    /** 16×160 px column glyph → 0.4 blocks wide, 4.0 blocks tall at text scale 1 (1px = 0.025 blocks). */
    private static final String BEAM_GLYPH = "\uE001";
    private static final float BEAM_GLYPH_WIDTH_BLOCKS = 0.4F;
    private static final float BEAM_GLYPH_HEIGHT_BLOCKS = 4.0F;
    /** 16×16 px octagon glyph scaled to 40px in the font → exactly 1 block at text scale 1. */
    private static final String MARKER_PLATE_GLYPH = "\uE002";
    private static final float BEAM_THICKNESS_BLOCKS = 0.85F;
    /** Beam leads the player by this many blocks toward the target while far away. */
    private static final double BEAM_FOLLOW_BLOCKS = 24.0D;
    /** Snap the beam (and HUD) to the true target once the player is this close horizontally. */
    private static final double BEAM_ANCHOR_BLOCKS = 8.0D;
    /** Re-teleport HUD entities when the follow anchor drifts farther than this (blocks). */
    private static final double ANCHOR_RETELEPORT_BLOCKS = 0.35D;
    private static final float DISPLAY_VIEW_RANGE = 128.0F;
    /**
     * Single FIXED-billboard pillar spun on Y (one revolution per period). Stacked segments
     * share the same yaw so joins stay coplanar; see {@link #BEAM_SEGMENT_STEP_BLOCKS}.
     */
    private static final long BEAM_SPIN_PERIOD_MS = 8000L;
    /**
     * While the target is farther than this, the HUD stack sits on the player→target ray
     * at exactly this horizontal distance; closer targets get the stack directly on them.
     */
    private static final double MARKER_MIN_DISTANCE_BLOCKS = 25.0D;
    /** Show UP/DOWN in the plate center once the target is this many blocks above/below. */
    private static final double ELEVATION_HINT_BLOCKS = 3.0D;
    /** Navigator label sits below the octagon plate (per hologram scale). */
    private static final float HOLO_LABEL_DROP_PER_SCALE = 0.42F;
    private static final float HOLO_LABEL_DROP_BASE = 0.18F;
    /**
     * Label text scale relative to the hologram scale. Larger than 1: with the HUD scale
     * capped at {@link #HOLO_MAX_SCALE} and the anchor pinned
     * {@link #MARKER_MIN_DISTANCE_BLOCKS} out, the description + distance line must stay
     * readable while the plate itself stays small.
     */
    private static final float HOLO_LABEL_TEXT_SCALE = 1.6F;
    private static final float HOLO_MIN_SCALE = 0.45F;
    private static final float HOLO_MAX_SCALE = 6.0F;
    private static final double HOLO_SCALE_NEAR = 8.0D;
    private static final double HOLO_SCALE_FAR = 160.0D;
    /**
     * TextDisplay transforms above ~6 clip on the client; stack fixed-height segments instead
     * of one pillar scaled to world height (that made the beam vanish). Tiny Y overlap hides
     * any remaining glyph-edge seam when segments are repeated.
     */
    private static final float BEAM_SEGMENT_SCALE_Y = 6.0F;
    private static final float BEAM_SEGMENT_HEIGHT_BLOCKS = BEAM_GLYPH_HEIGHT_BLOCKS * BEAM_SEGMENT_SCALE_Y;
    private static final float BEAM_SEGMENT_OVERLAP_BLOCKS = 0.15F;
    private static final float BEAM_SEGMENT_STEP_BLOCKS =
            BEAM_SEGMENT_HEIGHT_BLOCKS - BEAM_SEGMENT_OVERLAP_BLOCKS;
    private static final int BEAM_MAX_SEGMENTS = 16;
    /** Marker plate size relative to the hologram scale (model is 1 block at scale 1). */
    private static final float MARKER_PLATE_SCALE = 0.85F;
    /** Icon glyph size relative to the hologram scale. */
    private static final float MARKER_ICON_SCALE = 1.9F;
    /** Glyph is ~0.25 blocks tall per text scale; recenter it on the plate middle. */
    private static final float MARKER_ICON_HALF_GLYPH = 0.125F;
    /** How far the plate center floats above the hologram anchor, per hologram scale. */
    private static final float MARKER_LIFT_PER_SCALE = 0.55F;
    private static final float MARKER_LIFT_BASE = 0.45F;
    /** Push the icon toward the viewer so it never z-fights the plate quad. */
    private static final float MARKER_ICON_VIEW_OFFSET = 0.06F;

    private final PaperCorePlugin core;
    private final Map<UUID, PlayerNavigation> sessions = new ConcurrentHashMap<>();

    public WaypointNavigatorService(PaperCorePlugin core) {
        this.core = Objects.requireNonNull(core, "core");
        core.getServer().getPluginManager().registerEvents(this, core);
    }

    public boolean isNavigating(UUID playerId) {
        PlayerNavigation session = sessions.get(playerId);
        return session != null && !session.waypoints.isEmpty();
    }

    public boolean isNavigating(UUID playerId, String waypointId) {
        PlayerNavigation session = sessions.get(playerId);
        return session != null && waypointId != null && session.waypoints.containsKey(waypointId);
    }

    public Optional<Waypoint> waypoint(UUID playerId, String waypointId) {
        PlayerNavigation session = sessions.get(playerId);
        if (session == null || waypointId == null) {
            return Optional.empty();
        }
        ActiveWaypoint active = session.waypoints.get(waypointId);
        return active == null ? Optional.empty() : Optional.of(active.waypoint);
    }

    /**
     * Live targets by waypoint id: sampled every render tick so beams FOLLOW moving destinations
     * (wandering quest NPCs) instead of pointing at the coordinates captured at delivery time.
     * Suppliers must be safe to call from any thread (read a concurrent map, no entity access).
     */
    private final Map<String, java.util.function.Supplier<Location>> liveTargets = new ConcurrentHashMap<>();

    public void registerLiveTarget(String waypointId, java.util.function.Supplier<Location> target) {
        if (waypointId != null && !waypointId.isBlank() && target != null) {
            liveTargets.put(waypointId, target);
        }
    }

    public void unregisterLiveTarget(String waypointId) {
        if (waypointId != null) {
            liveTargets.remove(waypointId);
        }
    }

    /** Re-points an active waypoint at its live target when the destination has moved ≥0.75 blocks. */
    private void refreshLiveTarget(ActiveWaypoint active) {
        java.util.function.Supplier<Location> supplier = liveTargets.get(active.waypoint.id());
        if (supplier == null) {
            return;
        }
        Location live = supplier.get();
        if (live == null || live.getWorld() == null) {
            return;
        }
        Waypoint current = active.waypoint;
        if (!live.getWorld().getName().equals(current.worldName())) {
            // Target left this world — keep pointing at its last known spot here.
            return;
        }
        double dx = live.getX() - current.x();
        double dy = live.getY() - current.y();
        double dz = live.getZ() - current.z();
        if (dx * dx + dy * dy + dz * dz < 0.5625D) {
            return;
        }
        active.waypoint = new Waypoint(
                current.id(),
                current.worldName(),
                live.getX(),
                live.getY(),
                live.getZ(),
                current.label(),
                current.color(),
                current.autoClearBlocks(),
                current.hideWithinBlocks(),
                current.marker()
        );
    }

    /** Starts or replaces a waypoint for {@code player}. Multiple ids can be active at once. */
    public void navigate(Player player, Waypoint waypoint) {
        if (player == null || waypoint == null) {
            return;
        }
        if (!waypoint.worldName().equals(player.getWorld().getName())) {
            return;
        }
        PlayerNavigation session = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerNavigation());
        ActiveWaypoint previous = session.waypoints.remove(waypoint.id());
        if (previous != null) {
            destroyActive(previous);
        }
        ActiveWaypoint active = new ActiveWaypoint(waypoint);
        session.waypoints.put(waypoint.id(), active);
        ensureTicker(player, session);
        core.platformScheduler().runOnPlayer(player, () -> {
            if (!player.isOnline() || !isCurrent(player, session, active)) {
                return;
            }
            spawnVisuals(player, active);
        });
    }

    /** Convenience: navigate to a location with defaults. */
    public void navigate(Player player, String id, Location location, String label) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        navigate(player, Waypoint.of(id, location, label, Color.fromRGB(30, 255, 80)));
    }

    public void clear(Player player, String waypointId) {
        if (player == null || waypointId == null) {
            return;
        }
        PlayerNavigation session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        ActiveWaypoint removed = session.waypoints.remove(waypointId);
        if (removed != null) {
            destroyActive(removed);
        }
        if (session.waypoints.isEmpty()) {
            clearAll(player.getUniqueId());
        }
    }

    /** Clears every active waypoint whose id starts with {@code prefix} (e.g. {@code extract:}). */
    public void clearByPrefix(Player player, String prefix) {
        if (player == null || prefix == null || prefix.isBlank()) {
            return;
        }
        PlayerNavigation session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        List<String> toRemove = new ArrayList<>();
        for (String id : session.waypoints.keySet()) {
            if (id.startsWith(prefix)) {
                toRemove.add(id);
            }
        }
        for (String id : toRemove) {
            ActiveWaypoint removed = session.waypoints.remove(id);
            if (removed != null) {
                destroyActive(removed);
            }
        }
        if (session.waypoints.isEmpty()) {
            clearAll(player.getUniqueId());
        }
    }

    public void clearAll(Player player) {
        if (player == null) {
            return;
        }
        clearAll(player.getUniqueId());
    }

    public void clearAll(UUID playerId) {
        if (playerId == null) {
            return;
        }
        PlayerNavigation session = sessions.remove(playerId);
        if (session == null) {
            return;
        }
        if (session.task != null) {
            session.task.cancel();
            session.task = null;
        }
        for (ActiveWaypoint active : session.waypoints.values()) {
            destroyActive(active);
        }
        session.waypoints.clear();
    }

    public void shutdown() {
        for (UUID playerId : new ArrayList<>(sessions.keySet())) {
            clearAll(playerId);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clearAll(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        clearAll(event.getEntity().getUniqueId());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        PlayerNavigation session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        String worldName = player.getWorld().getName();
        Iterator<Map.Entry<String, ActiveWaypoint>> iterator = session.waypoints.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, ActiveWaypoint> entry = iterator.next();
            if (!entry.getValue().waypoint.worldName().equals(worldName)) {
                destroyActive(entry.getValue());
                iterator.remove();
            }
        }
        if (session.waypoints.isEmpty()) {
            clearAll(player.getUniqueId());
        }
    }

    private void ensureTicker(Player player, PlayerNavigation session) {
        if (session.task != null) {
            return;
        }
        session.task = core.platformScheduler().runOnPlayerTimer(player, () -> tick(player), 1L, 2L);
    }

    private void tick(Player player) {
        PlayerNavigation session = sessions.get(player.getUniqueId());
        if (session == null || !player.isOnline()) {
            clearAll(player.getUniqueId());
            return;
        }
        if (session.waypoints.isEmpty()) {
            clearAll(player.getUniqueId());
            return;
        }
        for (ActiveWaypoint active : session.waypoints.values()) {
            refreshLiveTarget(active);
            if (!active.waypoint.worldName().equals(player.getWorld().getName())) {
                continue;
            }
            double dist = horizontalDistance(player, active.waypoint);
            // Arrival clear must run before hide-suppression: a player already inside the hide
            // radius (teleport, spawn next to the NPC) would otherwise suppress forever and never clear.
            double clearAt = active.waypoint.autoClearBlocks();
            if (clearAt > 0.0D && dist <= clearAt) {
                clear(player, active.waypoint.id());
                continue;
            }
            double hideAt = active.waypoint.hideWithinBlocks();
            if (dist <= hideAt) {
                if (!active.suppressed) {
                    active.suppressed = true;
                    hideVisuals(active);
                }
                continue;
            }
            if (active.suppressed) {
                active.suppressed = false;
                core.platformScheduler().runOnPlayer(player, () -> {
                    if (player.isOnline() && isCurrent(player, session, active)) {
                        spawnVisuals(player, active);
                    }
                });
                continue;
            }
            updateActive(player, active);
        }
    }

    private void spawnVisuals(Player player, ActiveWaypoint active) {
        World world = player.getWorld();
        if (!active.waypoint.worldName().equals(world.getName()) || active.spawning) {
            return;
        }
        destroyActive(active);

        double minY = world.getMinHeight() + 2.0D;
        double maxY = world.getMaxHeight() - 0.5D;
        float height = (float) Math.max(16.0D, maxY - minY);
        active.beamHeightBlocks = height;
        active.beamBaseY = (float) minY;
        active.beamSegmentCount = beamSegmentCount(height);
        active.spawning = true;
        WaypointMarker marker = active.waypoint.marker();
        active.pendingSpawns.set(marker != null ? 3 : 2);

        Location beamSpawn = edgeOrTarget(player, active.waypoint, minY);
        double dist = horizontalDistance(player, active.waypoint);
        double elevation = active.waypoint.y() - player.getLocation().getY();
        Location hudAnchor = navAnchor(player, active.waypoint);
        Component beamGlyph = beamText(active.waypoint.color());
        float beamScaleX = BEAM_THICKNESS_BLOCKS / BEAM_GLYPH_WIDTH_BLOCKS;
        float beamScaleY = BEAM_SEGMENT_SCALE_Y;
        UUID playerId = player.getUniqueId();
        String waypointId = active.waypoint.id();
        float hudScale = scaleForDistance(dist);

        // Spawns must run on the region that owns the destination (Folia), not the player thread.
        core.platformScheduler().runAtLocation(beamSpawn, () -> {
            try {
                if (!stillActive(playerId, waypointId, active)) {
                    return;
                }
                float yaw = spinYaw();
                List<TextDisplay> pillars = new ArrayList<>(active.beamSegmentCount);
                for (int segment = 0; segment < active.beamSegmentCount; segment++) {
                    float segmentBaseY = active.beamBaseY + segment * BEAM_SEGMENT_STEP_BLOCKS;
                    Location segmentAt = new Location(
                            beamSpawn.getWorld(),
                            beamSpawn.getX(),
                            segmentBaseY,
                            beamSpawn.getZ()
                    );
                    pillars.add(spawnBeamPillar(segmentAt, beamGlyph, beamScaleX, beamScaleY, yaw));
                }
                active.beamSegments = pillars;
                Player online = core.getServer().getPlayer(playerId);
                if (online != null && online.isOnline()) {
                    core.platformScheduler().runOnPlayer(online, () -> {
                        if (!online.isOnline() || !stillActive(playerId, waypointId, active)) {
                            return;
                        }
                        for (TextDisplay pillar : pillars) {
                            if (pillar.isValid()) {
                                online.showEntity(core, pillar);
                            }
                        }
                    });
                }
            } catch (RuntimeException ex) {
                active.beamSegments = List.of();
                core.getLogger().warning("[Waypoint] Failed to spawn beam for " + waypointId + ": " + ex.getMessage());
            } finally {
                finishSpawn(active);
            }
        });

        core.platformScheduler().runAtLocation(hudAnchor, () -> {
            try {
                if (!stillActive(playerId, waypointId, active)) {
                    return;
                }
                TextDisplay hologram = hudAnchor.getWorld().spawn(hudAnchor, TextDisplay.class, entity -> {
                    entity.text(hologramText(active.waypoint, dist));
                    entity.setBillboard(Display.Billboard.CENTER);
                    entity.setVisibleByDefault(false);
                    entity.setSeeThrough(true);
                    entity.setShadowed(true);
                    entity.setDefaultBackground(true);
                    entity.setBackgroundColor(Color.fromARGB(160, 8, 12, 24));
                    entity.setLineWidth(200);
                    entity.setPersistent(false);
                    entity.setViewRange(DISPLAY_VIEW_RANGE);
                    entity.setInterpolationDuration(2);
                    entity.setTeleportDuration(1);
                    entity.setTransformation(hologramLabelTransform(hudScale));
                });
                active.hologram = hologram;
                Player online = core.getServer().getPlayer(playerId);
                if (online != null && online.isOnline()) {
                    core.platformScheduler().runOnPlayer(online, () -> {
                        if (online.isOnline() && hologram.isValid() && stillActive(playerId, waypointId, active)) {
                            online.showEntity(core, hologram);
                        }
                    });
                }
            } catch (RuntimeException ex) {
                active.hologram = null;
                core.getLogger().warning("[Waypoint] Failed to spawn hologram for " + waypointId + ": " + ex.getMessage());
            } finally {
                finishSpawn(active);
            }
        });

        if (marker == null) {
            return;
        }
        core.platformScheduler().runAtLocation(hudAnchor, () -> {
            try {
                if (!stillActive(playerId, waypointId, active)) {
                    return;
                }
                spawnMarker(hudAnchor, marker, active, hudScale, elevation);
                Player online = core.getServer().getPlayer(playerId);
                if (online != null && online.isOnline()) {
                    core.platformScheduler().runOnPlayer(online, () -> {
                        if (!online.isOnline() || !stillActive(playerId, waypointId, active)) {
                            return;
                        }
                        TextDisplay plate = active.markerPlate;
                        if (plate != null && plate.isValid()) {
                            online.showEntity(core, plate);
                        }
                        TextDisplay icon = active.markerIcon;
                        if (icon != null && icon.isValid()) {
                            online.showEntity(core, icon);
                        }
                    });
                }
            } catch (RuntimeException ex) {
                active.markerPlate = null;
                active.markerIcon = null;
                core.getLogger().warning("[Waypoint] Failed to spawn marker for " + waypointId + ": " + ex.getMessage());
            } finally {
                finishSpawn(active);
            }
        });
    }

    /** Spawns the marker plate (+ optional icon) at the HUD anchor; must run on the anchor's region. */
    private void spawnMarker(Location anchor, WaypointMarker marker, ActiveWaypoint active, float scale, double elevationDelta) {
        Component plateGlyph = markerPlateText(marker, active.waypoint.color());
        TextDisplay plate = anchor.getWorld().spawn(anchor, TextDisplay.class, entity -> {
            entity.text(plateGlyph);
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setSeeThrough(true);
            entity.setShadowed(false);
            entity.setDefaultBackground(false);
            entity.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            entity.setVisibleByDefault(false);
            entity.setPersistent(false);
            entity.setViewRange(DISPLAY_VIEW_RANGE);
            entity.setInterpolationDuration(2);
            entity.setTeleportDuration(1);
            entity.setBrightness(new Display.Brightness(15, 15));
            entity.setTransformation(markerPlateTransform(scale));
        });
        active.markerPlate = plate;
        Component centerText = markerCenterText(marker, elevationDelta);
        if (centerText == null || centerText.equals(Component.empty())) {
            active.markerIcon = null;
            return;
        }
        TextDisplay icon = anchor.getWorld().spawn(anchor, TextDisplay.class, entity -> {
            entity.text(centerText);
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setVisibleByDefault(false);
            entity.setSeeThrough(true);
            entity.setShadowed(false);
            entity.setDefaultBackground(false);
            entity.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            entity.setPersistent(false);
            entity.setViewRange(DISPLAY_VIEW_RANGE);
            entity.setInterpolationDuration(2);
            entity.setTeleportDuration(1);
            entity.setBrightness(new Display.Brightness(15, 15));
            entity.setTransformation(markerIconTransform(scale));
        });
        active.markerIcon = icon;
    }

    /** Plate centered {@code lift} above the anchor, facing the viewer (billboard CENTER). */
    private static Transformation markerPlateTransform(float scale) {
        float plateScale = scale * MARKER_PLATE_SCALE;
        return new Transformation(
                new Vector3f(0.0F, markerLift(scale), 0.0F),
                new Quaternionf(),
                new Vector3f(plateScale, plateScale, plateScale),
                new Quaternionf()
        );
    }

    /** Icon glyph recentered on the plate middle and nudged toward the viewer. */
    private static Transformation markerIconTransform(float scale) {
        float iconScale = scale * MARKER_ICON_SCALE;
        return new Transformation(
                new Vector3f(
                        0.0F,
                        markerLift(scale) - iconScale * MARKER_ICON_HALF_GLYPH,
                        MARKER_ICON_VIEW_OFFSET * scale
                ),
                new Quaternionf(),
                new Vector3f(iconScale, iconScale, iconScale),
                new Quaternionf()
        );
    }

    private static float markerLift(float scale) {
        return scale * MARKER_LIFT_PER_SCALE + MARKER_LIFT_BASE;
    }

    private static void finishSpawn(ActiveWaypoint active) {
        if (active.pendingSpawns.decrementAndGet() <= 0) {
            active.spawning = false;
        }
    }

    private void updateActive(Player player, ActiveWaypoint active) {
        if (!player.isOnline() || !active.waypoint.worldName().equals(player.getWorld().getName()) || active.spawning) {
            return;
        }
        double dist = horizontalDistance(player, active.waypoint);
        double elevation = active.waypoint.y() - player.getLocation().getY();
        Location beamCenter = edgeOrTarget(player, active.waypoint, active.beamBaseY);
        Location hudAnchor = navAnchor(player, active.waypoint);
        float scale = scaleForDistance(dist);

        TextDisplay beam = primaryBeamSegment(active);
        if (beam == null || !beam.isValid()) {
            spawnVisuals(player, active);
            return;
        }

        float yaw = spinYaw();
        float beamScaleX = BEAM_THICKNESS_BLOCKS / BEAM_GLYPH_WIDTH_BLOCKS;
        float beamScaleY = BEAM_SEGMENT_SCALE_Y;
        Transformation beamXf = beamSegmentTransform(beamScaleX, beamScaleY, yaw);
        for (int segment = 0; segment < active.beamSegmentCount; segment++) {
            float segmentBaseY = active.beamBaseY + segment * BEAM_SEGMENT_STEP_BLOCKS;
            Location segmentAt = new Location(
                    beamCenter.getWorld(),
                    beamCenter.getX(),
                    segmentBaseY,
                    beamCenter.getZ()
            );
            TextDisplay pillar = segment < active.beamSegments.size() ? active.beamSegments.get(segment) : null;
            if (pillar != null && pillar.isValid()) {
                TextDisplay resolved = pillar;
                core.platformScheduler().runAtEntity(resolved, () -> {
                    if (resolved.isValid()) {
                        resolved.setTransformation(beamXf);
                        resolved.teleportAsync(segmentAt);
                    }
                });
            }
        }

        TextDisplay hologram = active.hologram;
        if (hologram == null || !hologram.isValid()) {
            UUID playerId = player.getUniqueId();
            String waypointId = active.waypoint.id();
            Component text = hologramText(active.waypoint, dist);
            core.platformScheduler().runAtLocation(hudAnchor, () -> {
                if (!stillActive(playerId, waypointId, active)) {
                    return;
                }
                try {
                    TextDisplay spawned = hudAnchor.getWorld().spawn(hudAnchor, TextDisplay.class, entity -> {
                        entity.text(text);
                        entity.setBillboard(Display.Billboard.CENTER);
                        entity.setVisibleByDefault(false);
                        entity.setSeeThrough(true);
                        entity.setShadowed(true);
                        entity.setDefaultBackground(true);
                        entity.setBackgroundColor(Color.fromARGB(160, 8, 12, 24));
                        entity.setLineWidth(200);
                        entity.setPersistent(false);
                        entity.setViewRange(DISPLAY_VIEW_RANGE);
                        entity.setTransformation(hologramLabelTransform(scale));
                    });
                    active.hologram = spawned;
                    Player online = core.getServer().getPlayer(playerId);
                    if (online != null && online.isOnline()) {
                        core.platformScheduler().runOnPlayer(online, () -> {
                            if (online.isOnline() && spawned.isValid() && stillActive(playerId, waypointId, active)) {
                                online.showEntity(core, spawned);
                            }
                        });
                    }
                } catch (RuntimeException ignored) {
                    active.hologram = null;
                }
            });
            return;
        }

        Component text = hologramText(active.waypoint, dist);
        core.platformScheduler().runAtEntity(hologram, () -> {
            if (!hologram.isValid()) {
                return;
            }
            hologram.text(text);
            hologram.setInterpolationDelay(0);
            hologram.setTransformation(hologramLabelTransform(scale));
            if (hologram.getLocation().distanceSquared(hudAnchor) > ANCHOR_RETELEPORT_BLOCKS * ANCHOR_RETELEPORT_BLOCKS) {
                hologram.teleportAsync(hudAnchor);
            }
        });

        updateMarker(player, active, scale, hudAnchor, elevation);
    }

    private void updateMarker(Player player, ActiveWaypoint active, float scale, Location hudAnchor, double elevationDelta) {
        WaypointMarker marker = active.waypoint.marker();
        if (marker == null) {
            return;
        }
        TextDisplay plate = active.markerPlate;
        if (plate == null || !plate.isValid()) {
            TextDisplay staleIcon = active.markerIcon;
            active.markerIcon = null;
            if (staleIcon != null) {
                core.platformScheduler().runAtEntity(staleIcon, () -> {
                    if (staleIcon.isValid()) {
                        staleIcon.remove();
                    }
                });
            }
            UUID playerId = player.getUniqueId();
            String waypointId = active.waypoint.id();
            core.platformScheduler().runAtLocation(hudAnchor, () -> {
                if (!stillActive(playerId, waypointId, active)) {
                    return;
                }
                try {
                    spawnMarker(hudAnchor, marker, active, scale, elevationDelta);
                    Player online = core.getServer().getPlayer(playerId);
                    if (online != null && online.isOnline()) {
                        core.platformScheduler().runOnPlayer(online, () -> {
                            if (!online.isOnline() || !stillActive(playerId, waypointId, active)) {
                                return;
                            }
                            TextDisplay respawnedPlate = active.markerPlate;
                            if (respawnedPlate != null && respawnedPlate.isValid()) {
                                online.showEntity(core, respawnedPlate);
                            }
                            TextDisplay respawnedIcon = active.markerIcon;
                            if (respawnedIcon != null && respawnedIcon.isValid()) {
                                online.showEntity(core, respawnedIcon);
                            }
                        });
                    }
                } catch (RuntimeException ignored) {
                    active.markerPlate = null;
                    active.markerIcon = null;
                }
            });
            return;
        }
        if (plate.isValid()) {
            core.platformScheduler().runAtEntity(plate, () -> {
                if (plate.isValid()) {
                    plate.setInterpolationDelay(0);
                    plate.setTransformation(markerPlateTransform(scale));
                    if (plate.getLocation().distanceSquared(hudAnchor) > ANCHOR_RETELEPORT_BLOCKS * ANCHOR_RETELEPORT_BLOCKS) {
                        plate.teleportAsync(hudAnchor);
                    }
                }
            });
        }
        TextDisplay icon = active.markerIcon;
        Component centerText = markerCenterText(marker, elevationDelta);
        if (icon != null && icon.isValid()) {
            core.platformScheduler().runAtEntity(icon, () -> {
                if (icon.isValid()) {
                    icon.text(centerText);
                    icon.setInterpolationDelay(0);
                    icon.setTransformation(markerIconTransform(scale));
                    if (icon.getLocation().distanceSquared(hudAnchor) > ANCHOR_RETELEPORT_BLOCKS * ANCHOR_RETELEPORT_BLOCKS) {
                        icon.teleportAsync(hudAnchor);
                    }
                }
            });
        }
    }

    /**
     * Places the beam on the true target when close; otherwise leads the player by
     * {@link #BEAM_FOLLOW_BLOCKS} toward the destination so the pillar stays in view.
     */
    private Location edgeOrTarget(Player player, Waypoint waypoint, double baseY) {
        Location eye = player.getLocation();
        double dx = waypoint.x() - eye.getX();
        double dz = waypoint.z() - eye.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist <= BEAM_ANCHOR_BLOCKS || dist < 0.001D) {
            return new Location(player.getWorld(), waypoint.x(), baseY, waypoint.z());
        }
        double lead = Math.min(BEAM_FOLLOW_BLOCKS, Math.max(0.0D, dist - BEAM_ANCHOR_BLOCKS));
        double scale = lead / dist;
        return new Location(
                player.getWorld(),
                eye.getX() + dx * scale,
                baseY,
                eye.getZ() + dz * scale
        );
    }

    /**
     * HUD stack anchor: pinned {@link #MARKER_MIN_DISTANCE_BLOCKS} out along the
     * player→target ray while the target is farther than that; once the target comes
     * within the minimum it snaps onto the target itself (never past it). Always at the
     * WAYPOINT's own Y — static, so the stack no longer bobs with the viewer's eye level;
     * the UP/DOWN plate hint covers elevation.
     */
    private Location navAnchor(Player player, Waypoint waypoint) {
        Location loc = player.getLocation();
        double dx = waypoint.x() - loc.getX();
        double dz = waypoint.z() - loc.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist <= MARKER_MIN_DISTANCE_BLOCKS) {
            return new Location(player.getWorld(), waypoint.x(), waypoint.y(), waypoint.z());
        }
        double scale = MARKER_MIN_DISTANCE_BLOCKS / dist;
        return new Location(
                player.getWorld(),
                loc.getX() + dx * scale,
                waypoint.y(),
                loc.getZ() + dz * scale
        );
    }

    private static double horizontalDistance(Player player, Waypoint waypoint) {
        Location loc = player.getLocation();
        double dx = waypoint.x() - loc.getX();
        double dz = waypoint.z() - loc.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static float scaleForDistance(double distance) {
        double clamped = Math.max(HOLO_SCALE_NEAR, Math.min(HOLO_SCALE_FAR, distance));
        double t = (clamped - HOLO_SCALE_NEAR) / (HOLO_SCALE_FAR - HOLO_SCALE_NEAR);
        return (float) (HOLO_MIN_SCALE + t * (HOLO_MAX_SCALE - HOLO_MIN_SCALE));
    }

    /** Destination label with the live distance readout next to it. */
    private static Component hologramText(Waypoint waypoint, double distance) {
        return ServerTextUtil.miniMessageComponent(
                waypoint.label() + " <white><bold>" + Math.round(distance) + "m</bold></white>"
        );
    }

    /**
     * Plate center: UP/DOWN elevation hint (the anchor rides the target's Y now, so this is
     * the "which way vertically" cue); the marker icon when level with the target.
     */
    private static Component markerCenterText(WaypointMarker marker, double elevationDelta) {
        if (elevationDelta > ELEVATION_HINT_BLOCKS) {
            return ServerTextUtil.miniMessageComponent("<white><bold>UP</bold></white>");
        }
        if (elevationDelta < -ELEVATION_HINT_BLOCKS) {
            return ServerTextUtil.miniMessageComponent("<white><bold>DOWN</bold></white>");
        }
        if (marker.icon() != null) {
            return ServerTextUtil.miniMessageComponent(marker.icon());
        }
        return Component.empty();
    }

    private static Transformation hologramLabelTransform(float scale) {
        float labelScale = scale * HOLO_LABEL_TEXT_SCALE;
        return new Transformation(
                new Vector3f(0.0F, -(labelScale * HOLO_LABEL_DROP_PER_SCALE + HOLO_LABEL_DROP_BASE), 0.0F),
                new Quaternionf(),
                new Vector3f(labelScale, labelScale, labelScale),
                new Quaternionf()
        );
    }

    /** Shared Y spin so every stacked segment stays coplanar across respawns. */
    private static float spinYaw() {
        long now = System.currentTimeMillis() % BEAM_SPIN_PERIOD_MS;
        return (float) (now / (double) BEAM_SPIN_PERIOD_MS * Math.PI * 2.0D);
    }

    private static Transformation beamSegmentTransform(float scaleX, float scaleY, float yawRadians) {
        return new Transformation(
                new Vector3f(),
                new Quaternionf().rotationY(yawRadians),
                new Vector3f(scaleX, scaleY, 1.0F),
                new Quaternionf()
        );
    }

    /** One FIXED-billboard pillar quad; must run on the region owning {@code at}. */
    private static TextDisplay spawnBeamPillar(
            Location at, Component glyph, float scaleX, float scaleY, float yawRadians) {
        return at.getWorld().spawn(at, TextDisplay.class, entity -> {
            entity.text(glyph);
            // FIXED + shared Y rotation: stacked segments stay aligned (VERTICAL billboards
            // diverge under perspective). See-through keeps the beam visible through terrain.
            entity.setBillboard(Display.Billboard.FIXED);
            entity.setSeeThrough(true);
            entity.setShadowed(false);
            entity.setDefaultBackground(false);
            entity.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            entity.setVisibleByDefault(false);
            entity.setPersistent(false);
            entity.setInterpolationDuration(2);
            entity.setTeleportDuration(2);
            entity.setViewRange(DISPLAY_VIEW_RANGE);
            entity.setBrightness(new Display.Brightness(15, 15));
            entity.setTransformation(beamSegmentTransform(scaleX, scaleY, yawRadians));
        });
    }

    /** Beam pillar glyph tinted with the waypoint color (white texture × text color). */
    private static Component beamText(Color tint) {
        Color resolved = tint != null ? tint : Color.fromRGB(30, 255, 80);
        return Component.text(BEAM_GLYPH)
                .font(NAV_FONT)
                .color(net.kyori.adventure.text.format.TextColor.color(
                        resolved.getRed(), resolved.getGreen(), resolved.getBlue()));
    }

    /** Octagon plate glyph; {@link WaypointMarker#color()} wins, waypoint color is the fallback. */
    private static Component markerPlateText(WaypointMarker marker, Color waypointColor) {
        Color resolved = marker.color() != null ? marker.color()
                : waypointColor != null ? waypointColor
                : Color.fromRGB(30, 255, 80);
        return Component.text(MARKER_PLATE_GLYPH)
                .font(NAV_FONT)
                .color(net.kyori.adventure.text.format.TextColor.color(
                        resolved.getRed(), resolved.getGreen(), resolved.getBlue()));
    }

    private void hideVisuals(ActiveWaypoint active) {
        if (active == null) {
            return;
        }
        active.spawning = false;
        active.pendingSpawns.set(0);
        removeDisplays(active.beamSegments);
        active.beamSegments = List.of();
        removeDisplay(active.hologram);
        active.hologram = null;
        removeDisplay(active.markerPlate);
        active.markerPlate = null;
        removeDisplay(active.markerIcon);
        active.markerIcon = null;
    }

    private static int beamSegmentCount(float totalHeightBlocks) {
        return Math.min(
                BEAM_MAX_SEGMENTS,
                Math.max(1, (int) Math.ceil(totalHeightBlocks / BEAM_SEGMENT_STEP_BLOCKS))
        );
    }

    private static TextDisplay primaryBeamSegment(ActiveWaypoint active) {
        if (active.beamSegments == null || active.beamSegments.isEmpty()) {
            return null;
        }
        return active.beamSegments.get(0);
    }

    private void removeDisplays(List<TextDisplay> displays) {
        if (displays == null || displays.isEmpty()) {
            return;
        }
        for (TextDisplay display : displays) {
            removeDisplay(display);
        }
    }

    private void removeDisplay(TextDisplay display) {
        if (display == null) {
            return;
        }
        core.platformScheduler().runAtEntity(display, () -> {
            if (display.isValid()) {
                display.remove();
            }
        });
    }

    private void destroyActive(ActiveWaypoint active) {
        hideVisuals(active);
        active.suppressed = false;
    }

    private boolean isCurrent(Player player, PlayerNavigation session, ActiveWaypoint active) {
        PlayerNavigation current = sessions.get(player.getUniqueId());
        return current == session && current.waypoints.get(active.waypoint.id()) == active;
    }

    private boolean stillActive(UUID playerId, String waypointId, ActiveWaypoint active) {
        PlayerNavigation session = sessions.get(playerId);
        return session != null && session.waypoints.get(waypointId) == active;
    }

    private static final class PlayerNavigation {
        private final Map<String, ActiveWaypoint> waypoints = new ConcurrentHashMap<>();
        private PlatformTask task;
    }

    private static final class ActiveWaypoint {
        /** Mutable: live-target waypoints (moving NPCs) get their coordinates refreshed each tick. */
        private volatile Waypoint waypoint;
        private List<TextDisplay> beamSegments = List.of();
        private TextDisplay hologram;
        private volatile TextDisplay markerPlate;
        private volatile TextDisplay markerIcon;
        private float beamHeightBlocks = 320.0F;
        private float beamBaseY = -62.0F;
        private int beamSegmentCount = 1;
        private volatile boolean spawning;
        private volatile boolean suppressed;
        private final AtomicInteger pendingSpawns = new AtomicInteger();

        private ActiveWaypoint(Waypoint waypoint) {
            this.waypoint = waypoint;
        }
    }
}
