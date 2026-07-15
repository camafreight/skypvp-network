package network.skypvp.extraction.gameplay;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import network.skypvp.extraction.engine.BreachInstance;
import network.skypvp.extraction.model.BreachMapMeta;
import network.skypvp.paper.platform.ServerPlatform;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;

public final class BreachSpawnSafety {

    private static final double PLAYER_THREAT_RADIUS = 48.0D;
    private static final double MOB_THREAT_RADIUS = 24.0D;
    private static final double GUNFIRE_THREAT_RADIUS = 64.0D;
    private static final long GUNFIRE_WINDOW_MS = 15_000L;
    private static final int BLOCK_UNSAFE_PENALTY = 50;
    private static final int NEARBY_PLAYER_PENALTY = 12;
    // Must decisively beat base-rating gaps: at 6/mob, a spawn with a +10 safetyRating edge
    // (plus the preferred bonus) still won with two gunners standing on it.
    private static final int NEARBY_MOB_PENALTY = 15;
    private static final int GUNFIRE_PENALTY = 8;
    private static final int PREFERRED_BONUS = 3;

    private BreachSpawnSafety() {
    }

    public record SpawnEvaluation(
            int totalScore,
            int baseRating,
            int blockPenalty,
            int playerPenalty,
            int mobPenalty,
            int gunfirePenalty,
            int nearbyPlayers,
            int nearbyHostiles,
            int recentGunfire
    ) {
    }

    /**
     * Picks the least dangerous spawn point and returns its RAW location — callers must
     * {@link #snapToGround} it on the region that owns the chosen point before teleporting.
     *
     * <p>Threading: this runs on ONE region thread (the deploy anchor), but candidate spawn points can
     * belong to other Folia regions where live entity/block reads throw. Mob threat therefore comes from
     * {@link BreachMobSpawnService#countAliveNear}, which only reads static meta coordinates and
     * concurrent alive-counts; live {@code getNearbyEntities} scans and block checks run only for
     * region-owned candidates. Swallowing the off-region exceptions instead used to score every remote
     * spawn as threat-free, which deployed raiders on top of gunner patrols.
     */
    public static Location resolveSpawn(
            World world,
            BreachMapMeta mapMeta,
            UUID playerId,
            UUID partyId,
            BreachInstance instance,
            BreachGunfireTracker gunfireTracker,
            BreachMobSpawnService mobSpawnService,
            ServerPlatform scheduler,
            ConcurrentHashMap<UUID, Location> partySpawnLocations,
            Logger logger
    ) {
        Objects.requireNonNull(world, "world");
        List<BreachMapMeta.SpawnPoint> spawns = mapMeta == null ? List.of() : mapMeta.spawnPoints();
        if (spawns.isEmpty()) {
            return world.getSpawnLocation();
        }
        if (partyId != null) {
            Location shared = partySpawnLocations.get(partyId);
            if (shared != null && shared.getWorld().equals(world)) {
                return shared.clone();
            }
        }

        BreachMapMeta.SpawnPoint preferred = spawns.get(Math.floorMod(playerId.hashCode(), spawns.size()));
        BreachMapMeta.SpawnPoint chosen = chooseSpawnPoint(
                world, mapMeta, preferred, instance, playerId, partyId, gunfireTracker, mobSpawnService, scheduler, logger);
        return toLocation(world, chosen);
    }

    private static BreachMapMeta.SpawnPoint chooseSpawnPoint(
            World world,
            BreachMapMeta mapMeta,
            BreachMapMeta.SpawnPoint preferred,
            BreachInstance instance,
            UUID deployingPlayerId,
            UUID partyId,
            BreachGunfireTracker gunfireTracker,
            BreachMobSpawnService mobSpawnService,
            ServerPlatform scheduler,
            Logger logger
    ) {
        BreachMapMeta.SpawnPoint best = null;
        SpawnEvaluation bestEvaluation = null;
        int bestScore = Integer.MIN_VALUE;
        for (BreachMapMeta.SpawnPoint spawn : mapMeta.spawnPoints()) {
            SpawnEvaluation evaluation = evaluate(
                    world, mapMeta, spawn, instance, deployingPlayerId, partyId, gunfireTracker, mobSpawnService, scheduler);
            int score = evaluation.totalScore() + (spawn.equals(preferred) ? PREFERRED_BONUS : 0);
            if (best == null
                    || score > bestScore
                    || (score == bestScore && spawn.safetyRating() > best.safetyRating())) {
                best = spawn;
                bestEvaluation = evaluation;
                bestScore = score;
            }
        }
        if (best == null) {
            return preferred;
        }
        if (logger != null) {
            logger.info("[Breach] Deploy spawn '" + best.id() + "' for " + deployingPlayerId
                    + " score=" + bestScore
                    + " (base=" + bestEvaluation.baseRating()
                    + ", mobs=" + bestEvaluation.nearbyHostiles() + "/-" + bestEvaluation.mobPenalty()
                    + ", players=" + bestEvaluation.nearbyPlayers() + "/-" + bestEvaluation.playerPenalty()
                    + ", gunfire=" + bestEvaluation.recentGunfire() + "/-" + bestEvaluation.gunfirePenalty()
                    + ", block=-" + bestEvaluation.blockPenalty() + ")");
        }
        return best;
    }

    static SpawnEvaluation evaluate(
            World world,
            BreachMapMeta mapMeta,
            BreachMapMeta.SpawnPoint spawn,
            BreachInstance instance,
            UUID deployingPlayerId,
            UUID partyId,
            BreachGunfireTracker gunfireTracker,
            BreachMobSpawnService mobSpawnService,
            ServerPlatform scheduler
    ) {
        int baseRating = spawn.safetyRating();
        Location center = toLocation(world, spawn);
        boolean regionOwned = scheduler == null || scheduler.isOwnedByCurrentRegion(center);
        // Block reads off the owning Folia region throw; treat unreachable candidates as neutral
        // (no penalty) rather than pretending we verified them.
        int blockPenalty = regionOwned && !isBlockSafe(world, spawn) ? BLOCK_UNSAFE_PENALTY : 0;
        int nearbyPlayers = countNearbyRaiders(instance, center, PLAYER_THREAT_RADIUS, deployingPlayerId, partyId);
        int playerPenalty = nearbyPlayers * NEARBY_PLAYER_PENALTY;
        int nearbyHostiles = countHostileMobs(world, mapMeta, center, MOB_THREAT_RADIUS, mobSpawnService, regionOwned);
        int mobPenalty = nearbyHostiles * NEARBY_MOB_PENALTY;
        int recentGunfire = gunfireTracker == null
                ? 0
                : gunfireTracker.countRecent(center, GUNFIRE_THREAT_RADIUS, GUNFIRE_WINDOW_MS);
        int gunfirePenalty = recentGunfire * GUNFIRE_PENALTY;
        int totalScore = clampScore(baseRating - blockPenalty - playerPenalty - mobPenalty - gunfirePenalty);
        return new SpawnEvaluation(
                totalScore,
                baseRating,
                blockPenalty,
                playerPenalty,
                mobPenalty,
                gunfirePenalty,
                nearbyPlayers,
                nearbyHostiles,
                recentGunfire
        );
    }

    private static int countNearbyRaiders(
            BreachInstance instance,
            Location center,
            double radiusBlocks,
            UUID deployingPlayerId,
            UUID partyId
    ) {
        if (instance == null || center.getWorld() == null) {
            return 0;
        }
        try {
            World world = center.getWorld();
            double radiusSquared = radiusBlocks * radiusBlocks;
            Set<UUID> partyMembers = partyId == null ? Set.of() : instance.partyMembers(partyId);
            int count = 0;
            for (UUID participantId : instance.participantIdsSnapshot()) {
                if (participantId.equals(deployingPlayerId)) {
                    continue;
                }
                if (partyMembers.contains(participantId)) {
                    continue;
                }
                if (instance.isPendingJoin(participantId)
                        || instance.isEliminated(participantId)
                        || instance.hasExtracted(participantId)) {
                    continue;
                }
                Player player = Bukkit.getPlayer(participantId);
                if (player == null || !player.isOnline() || !player.getWorld().equals(world)) {
                    continue;
                }
                if (player.getLocation().distanceSquared(center) <= radiusSquared) {
                    count++;
                }
            }
            return count;
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    /**
     * Mob threat = max of the cross-region-safe tracker count (live mobs anchored to static spawn
     * points from map meta) and, when the candidate's region is owned by the current thread, a live
     * {@link Monster} scan that also catches mobs which wandered or are chasing someone.
     */
    private static int countHostileMobs(
            World world,
            BreachMapMeta mapMeta,
            Location center,
            double radiusBlocks,
            BreachMobSpawnService mobSpawnService,
            boolean regionOwned
    ) {
        int tracked = mobSpawnService == null
                ? 0
                : mobSpawnService.countAliveNear(world, mapMeta, center.getX(), center.getY(), center.getZ(), radiusBlocks);
        int live = 0;
        if (regionOwned) {
            try {
                for (Entity entity : world.getNearbyEntities(center, radiusBlocks, radiusBlocks, radiusBlocks)) {
                    if (entity instanceof Monster) {
                        live++;
                    }
                }
            } catch (RuntimeException ignored) {
                live = 0;
            }
        }
        return Math.max(tracked, live);
    }

    /**
     * True when the player's head/eye is inside a solid, occluding block — the actual condition Minecraft uses to
     * deal {@code SUFFOCATION} damage. Used as a glitcher guard so the suffocation rescue only fires when a raider is
     * genuinely encased (buried in blocks), not when a client is momentarily clipping or faking the state to abuse
     * the free teleport.
     */
    public static boolean isHeadEncased(Player player) {
        try {
            Location eye = player.getEyeLocation();
            World world = eye.getWorld();
            if (world == null) {
                return false;
            }
            Material eyeType = world.getBlockAt(eye.getBlockX(), eye.getBlockY(), eye.getBlockZ()).getType();
            return eyeType.isSolid() && eyeType.isOccluding() && eyeType != Material.BARRIER;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Scans straight up from {@code origin} for the nearest standable surface: a 2-block passable column (feet + head
     * clear, no hazards) sitting on a solid, non-hazard floor. Returns the first such spot at or above the origin,
     * within {@code maxScan} blocks. Used to lift a suffocating raider out of the wall onto the nearest surface above
     * them. Block reads assume the caller is on the region owning {@code origin} (e.g. the entity's damage handler).
     */
    public static Optional<Location> findStandableSurfaceAbove(Location origin, int maxScan) {
        if (origin == null) {
            return Optional.empty();
        }
        World world = origin.getWorld();
        if (world == null) {
            return Optional.empty();
        }
        try {
            int x = origin.getBlockX();
            int z = origin.getBlockZ();
            int startY = Math.max(world.getMinHeight() + 1, origin.getBlockY());
            int limit = Math.min(world.getMaxHeight() - 2, startY + Math.max(1, maxScan));
            for (int y = startY; y <= limit; y++) {
                if (isStandableFloor(world, x, y - 1, z)
                        && isPassable(world, x, y, z)
                        && isPassable(world, x, y + 1, z)
                        && !isDangerous(world, x, y, z)
                        && !isDangerous(world, x, y + 1, z)) {
                    return Optional.of(new Location(
                            world,
                            x + 0.5D,
                            y,
                            z + 0.5D,
                            origin.getYaw(),
                            origin.getPitch()
                    ));
                }
            }
            return Optional.empty();
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static boolean isStandableFloor(World world, int x, int y, int z) {
        Material type = world.getBlockAt(x, y, z).getType();
        return type.isSolid() && type != Material.BARRIER && !isDangerous(world, x, y, z);
    }

    static boolean isBlockSafe(World world, BreachMapMeta.SpawnPoint spawn) {
        try {
            int blockX = (int) Math.floor(spawn.x());
            int blockZ = (int) Math.floor(spawn.z());
            int feetY = (int) Math.floor(spawn.y());
            int headY = feetY + 1;
            if (feetY < world.getMinHeight() || headY >= world.getMaxHeight()) {
                return false;
            }
            if (!isPassable(world, blockX, feetY, blockZ) || !isPassable(world, blockX, headY, blockZ)) {
                return false;
            }
            Material below = world.getBlockAt(blockX, feetY - 1, blockZ).getType();
            if (!below.isSolid() || below == Material.BARRIER) {
                return false;
            }
            return !isDangerous(world, blockX, feetY, blockZ) && !isDangerous(world, blockX, headY, blockZ);
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private static boolean isPassable(World world, int x, int y, int z) {
        Material type = world.getBlockAt(x, y, z).getType();
        return type.isAir() || !type.isSolid();
    }

    private static boolean isDangerous(World world, int x, int y, int z) {
        Material type = world.getBlockAt(x, y, z).getType();
        return type == Material.LAVA
                || type == Material.FIRE
                || type == Material.SOUL_FIRE
                || type == Material.MAGMA_BLOCK
                || type == Material.CACTUS
                || type == Material.SWEET_BERRY_BUSH
                || type == Material.WITHER_ROSE;
    }

    private static int clampScore(int score) {
        return Math.max(0, Math.min(100, score));
    }

    private static Location toLocation(World world, BreachMapMeta.SpawnPoint spawn) {
        return new Location(world, spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch());
    }

    /** Down first (out of roof slabs, into interiors), then slightly up for buried points. */
    private static final int[] GROUND_SNAP_ORDER = {0, -1, -2, -3, -4, -5, -6, 1, 2, 3, 4};

    /**
     * Snaps a hand-captured spawn Y to the nearest real standable column. Teleporting to a Y
     * inside a roof slab makes vanilla push the player up ON TOP of the roof; a Y floating
     * above a terrace drops them onto it. Preferring downward keeps spawns inside buildings.
     * Reads blocks — call on the region thread owning {@code configured} (idempotent, so
     * re-snapping a cached party spawn is harmless).
     */
    public static Location snapToGround(World world, Location configured) {
        try {
            int x = configured.getBlockX();
            int z = configured.getBlockZ();
            int baseY = configured.getBlockY();
            for (int dy : GROUND_SNAP_ORDER) {
                int y = baseY + dy;
                if (y <= world.getMinHeight() || y >= world.getMaxHeight() - 1) {
                    continue;
                }
                if (isPassable(world, x, y, z)
                        && isPassable(world, x, y + 1, z)
                        && isStandableFloor(world, x, y - 1, z)
                        && !isDangerous(world, x, y, z)
                        && !isDangerous(world, x, y + 1, z)) {
                    if (y == baseY) {
                        return configured;
                    }
                    return new Location(
                            world,
                            configured.getX(),
                            y,
                            configured.getZ(),
                            configured.getYaw(),
                            configured.getPitch()
                    );
                }
            }
            return configured;
        } catch (RuntimeException ignored) {
            return configured;
        }
    }
}
