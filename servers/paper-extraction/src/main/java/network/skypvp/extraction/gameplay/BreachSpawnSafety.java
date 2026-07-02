package network.skypvp.extraction.gameplay;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.extraction.engine.BreachInstance;
import network.skypvp.extraction.model.BreachMapMeta;
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
    private static final int NEARBY_MOB_PENALTY = 6;
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

    public static Location resolveSpawn(
            World world,
            List<BreachMapMeta.SpawnPoint> spawns,
            UUID playerId,
            UUID partyId,
            BreachInstance instance,
            BreachGunfireTracker gunfireTracker,
            ConcurrentHashMap<UUID, Location> partySpawnLocations
    ) {
        Objects.requireNonNull(world, "world");
        if (spawns == null || spawns.isEmpty()) {
            return world.getSpawnLocation();
        }
        if (partyId != null) {
            Location shared = partySpawnLocations.get(partyId);
            if (shared != null && shared.getWorld().equals(world)) {
                return shared.clone();
            }
        }

        BreachMapMeta.SpawnPoint preferred = spawns.get(Math.floorMod(playerId.hashCode(), spawns.size()));
        BreachMapMeta.SpawnPoint chosen = chooseSpawnPoint(world, spawns, preferred, instance, playerId, partyId, gunfireTracker);
        Location location = toLocation(world, chosen);

        if (partyId != null) {
            partySpawnLocations.putIfAbsent(partyId, location.clone());
            return partySpawnLocations.get(partyId).clone();
        }
        return location;
    }

    private static BreachMapMeta.SpawnPoint chooseSpawnPoint(
            World world,
            List<BreachMapMeta.SpawnPoint> spawns,
            BreachMapMeta.SpawnPoint preferred,
            BreachInstance instance,
            UUID deployingPlayerId,
            UUID partyId,
            BreachGunfireTracker gunfireTracker
    ) {
        return spawns.stream()
                .max(Comparator
                        .<BreachMapMeta.SpawnPoint>comparingInt(spawn -> evaluate(
                                world,
                                spawn,
                                instance,
                                deployingPlayerId,
                                partyId,
                                gunfireTracker).totalScore()
                                + (spawn.equals(preferred) ? PREFERRED_BONUS : 0))
                        .thenComparingInt(BreachMapMeta.SpawnPoint::safetyRating))
                .orElse(preferred);
    }

    static SpawnEvaluation evaluate(
            World world,
            BreachMapMeta.SpawnPoint spawn,
            BreachInstance instance,
            UUID deployingPlayerId,
            UUID partyId,
            BreachGunfireTracker gunfireTracker
    ) {
        int baseRating = spawn.safetyRating();
        int blockPenalty = isBlockSafe(world, spawn) ? 0 : BLOCK_UNSAFE_PENALTY;
        Location center = toLocation(world, spawn);
        int nearbyPlayers = countNearbyRaiders(instance, center, PLAYER_THREAT_RADIUS, deployingPlayerId, partyId);
        int playerPenalty = nearbyPlayers * NEARBY_PLAYER_PENALTY;
        int nearbyHostiles = countHostileMobs(world, center, MOB_THREAT_RADIUS);
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

    private static int countHostileMobs(World world, Location center, double radiusBlocks) {
        try {
            int count = 0;
            for (Entity entity : world.getNearbyEntities(center, radiusBlocks, radiusBlocks, radiusBlocks)) {
                if (entity instanceof Monster) {
                    count++;
                }
            }
            return count;
        } catch (RuntimeException ignored) {
            return 0;
        }
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
}
