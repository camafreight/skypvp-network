package network.skypvp.extraction.gameplay.loot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

public final class BreachLootChestRegistry {

    private final Map<UUID, Map<Long, BreachLootChestState>> chestsByWorld = new ConcurrentHashMap<>();

    public BreachLootChestState getOrCreate(World world, Location location, String tier) {
        UUID worldId = world.getUID();
        long key = blockKey(location);
        return chestsByWorld
                .computeIfAbsent(worldId, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(key, ignored -> new BreachLootChestState(tier));
    }

    public Optional<BreachLootChestState> find(World world, Location location) {
        if (world == null || location == null) {
            return Optional.empty();
        }
        Map<Long, BreachLootChestState> worldChests = chestsByWorld.get(world.getUID());
        if (worldChests == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(worldChests.get(blockKey(location)));
    }

    public boolean isRegistered(World world, Location location) {
        return find(world, location).isPresent();
    }

    public List<Location> activeChestsWithLoot(World world) {
        Map<Long, BreachLootChestState> worldChests = chestsByWorld.get(world.getUID());
        if (worldChests == null || worldChests.isEmpty()) {
            return List.of();
        }
        List<Location> locations = new ArrayList<>();
        for (Map.Entry<Long, BreachLootChestState> entry : worldChests.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            locations.add(fromBlockKey(world, entry.getKey()));
        }
        return locations;
    }

    public void clearWorld(World world) {
        if (world != null) {
            chestsByWorld.remove(world.getUID());
        }
    }

    public WorldLootStats aggregateStatsForWorld(World world) {
        if (world == null) {
            return WorldLootStats.empty();
        }
        Map<Long, BreachLootChestState> worldChests = chestsByWorld.get(world.getUID());
        if (worldChests == null || worldChests.isEmpty()) {
            return WorldLootStats.empty();
        }
        int slotsPerChest = BreachLootChestLayout.LOOT_SLOTS.length;
        int registeredChests = worldChests.size();
        int chestsWithLoot = 0;
        int itemSlotsTotal = 0;
        int itemSlotsRemaining = 0;
        for (BreachLootChestState state : worldChests.values()) {
            itemSlotsTotal += slotsPerChest;
            int filled = countFilledSlots(state);
            itemSlotsRemaining += filled;
            if (filled > 0) {
                chestsWithLoot++;
            }
        }
        double percentRemaining = itemSlotsTotal <= 0
                ? 0.0D
                : (100.0D * itemSlotsRemaining) / itemSlotsTotal;
        return new WorldLootStats(
                registeredChests,
                chestsWithLoot,
                itemSlotsTotal,
                itemSlotsRemaining,
                percentRemaining
        );
    }

    private static int countFilledSlots(BreachLootChestState state) {
        int filled = 0;
        for (ItemStack item : state.lootSnapshot()) {
            if (item != null && !item.getType().isAir()) {
                filled++;
            }
        }
        return filled;
    }

    public record WorldLootStats(
            int registeredChests,
            int chestsWithLoot,
            int itemSlotsTotal,
            int itemSlotsRemaining,
            double percentRemaining
    ) {
        public static WorldLootStats empty() {
            return new WorldLootStats(0, 0, 0, 0, 0.0D);
        }

        public double chestPercentRemaining() {
            if (registeredChests <= 0) {
                return 0.0D;
            }
            return (100.0D * chestsWithLoot) / registeredChests;
        }
    }

    public static long blockKey(Location location) {
        return blockKey(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public static long blockKey(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) y & 0xFFFL) << 26 | ((long) z & 0x3FFFFFFL);
    }

    private static Location fromBlockKey(World world, long key) {
        int x = (int) (key >> 38 & 0x3FFFFFFL);
        int y = (int) (key >> 26 & 0xFFF);
        int z = (int) (key & 0x3FFFFFFL);
        if (x >= 1 << 25) {
            x -= 1 << 26;
        }
        if (z >= 1 << 25) {
            z -= 1 << 26;
        }
        return new Location(world, x, y, z);
    }
}
