package network.skypvp.extraction.gameplay;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import network.skypvp.extraction.config.BreachConfigService;
import network.skypvp.extraction.config.BreachLootEntry;
import network.skypvp.extraction.gameplay.ExtractionLootFactory;
import network.skypvp.extraction.gameplay.loot.BreachLootChestDisplayService;
import network.skypvp.extraction.gameplay.loot.BreachLootChestLayout;
import network.skypvp.extraction.gameplay.loot.BreachLootChestRegistry;
import network.skypvp.extraction.gameplay.loot.BreachLootChestState;
import network.skypvp.extraction.integration.WeaponMechanicsBridge;
import network.skypvp.extraction.model.BreachMapMeta;
import network.skypvp.extraction.world.McaChestScanner;
import network.skypvp.paper.platform.ServerPlatform;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.data.type.Chest.Type;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class BreachLootService {

    private static final int AUTO_DISCOVER_PADDING_BLOCKS = 80;
    private static final int CHUNK_ACTIVATIONS_PER_TICK = 4;

    private final JavaPlugin plugin;
    private final ServerPlatform scheduler;
    private final BreachConfigService configService;
    private final WeaponMechanicsBridge weaponMechanicsBridge;
    private final BreachLootChestRegistry chestRegistry;
    private final BreachLootChestDisplayService chestDisplayService;
    private final Logger logger;
    private volatile ExtractionLootFactory extractionLoot;
    private final Random random = new Random();
    private final ConcurrentHashMap<String, List<McaChestScanner.BlockPos>> templateChestCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, WorldLootPlan> activePlans = new ConcurrentHashMap<>();

    public BreachLootService(
            JavaPlugin plugin,
            ServerPlatform scheduler,
            BreachConfigService configService,
            WeaponMechanicsBridge weaponMechanicsBridge,
            BreachLootChestRegistry chestRegistry,
            BreachLootChestDisplayService chestDisplayService
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.configService = Objects.requireNonNull(configService, "configService");
        this.weaponMechanicsBridge = Objects.requireNonNull(weaponMechanicsBridge, "weaponMechanicsBridge");
        this.chestRegistry = Objects.requireNonNull(chestRegistry, "chestRegistry");
        this.chestDisplayService = Objects.requireNonNull(chestDisplayService, "chestDisplayService");
        this.logger = plugin.getLogger();
    }

    public BreachLootChestRegistry chestRegistry() {
        return chestRegistry;
    }

    public void bindExtractionLoot(ExtractionLootFactory factory) {
        this.extractionLoot = factory;
    }

    public void warmTemplateCaches() {
        this.scheduler.runAsync(() -> {
            int warmed = 0;
            for (BreachConfigService.BreachMapEntry entry : this.configService.enabledMapEntries()) {
                this.configService.mapMeta(entry.mapId()).ifPresent(meta -> {
                    Path regionDirectory = this.configService.mapTemplateRoot()
                            .resolve(entry.template())
                            .resolve("world")
                            .resolve("region");
                    if (!Files.isDirectory(regionDirectory)) {
                        this.logger.warning("[Breach] No region folder for template '" + entry.template()
                                + "' at " + regionDirectory);
                        return;
                    }
                    BreachMapMeta.ScanBounds chunkBounds = meta.gameplayChunkBounds(AUTO_DISCOVER_PADDING_BLOCKS);
                    McaChestScanner.BlockBounds blockBounds = McaChestScanner.BlockBounds.fromChunkBounds(chunkBounds);
                    try {
                        List<McaChestScanner.BlockPos> chests = McaChestScanner.scanChests(regionDirectory, blockBounds);
                        this.templateChestCache.put(entry.template(), List.copyOf(chests));
                        this.logger.info("[Breach] Pre-cached " + chests.size() + " chest(s) for template '"
                                + entry.template() + "' (" + meta.mapId() + ").");
                    } catch (Exception ex) {
                        this.logger.warning("[Breach] Failed to pre-cache chests for template '"
                                + entry.template() + "': " + ex.getMessage());
                    }
                });
                warmed++;
            }
            this.logger.info("[Breach] Chest cache warm-up finished for " + warmed + " enabled map(s).");
        });
    }

    public void populateMapLoot(World world, BreachMapMeta mapMeta, String templateId) {
        if (world == null || mapMeta == null) {
            return;
        }

        if (this.configService.enhancedLootChests()) {
            this.beginEnhancedMapLoot(world, mapMeta, templateId);
            return;
        }

        Set<Long> configuredBlocks = configuredBlockKeys(mapMeta);
        Set<Inventory> populatedInventories = new HashSet<>();
        int configuredPopulated = populateConfiguredChests(world, mapMeta, populatedInventories);
        int discoveredPopulated = populateCachedTemplateChests(world, templateId, configuredBlocks, populatedInventories);

        this.logger.info("[Breach] Populated loot on " + mapMeta.mapId() + ": "
                + configuredPopulated + " configured, "
                + discoveredPopulated + " cached chest(s).");
    }

    public void activateChunk(World world, int chunkX, int chunkZ) {
        if (world == null) {
            return;
        }
        WorldLootPlan plan = this.activePlans.get(world.getUID());
        if (plan == null) {
            return;
        }

        int activated = 0;
        List<ChestSpawn> spawns = plan.spawnsByChunk.get(chunkKey(chunkX, chunkZ));
        if (spawns != null) {
            for (ChestSpawn spawn : spawns) {
                if (this.activateEnhancedChest(world, spawn, plan)) {
                    activated++;
                }
            }
        }
        activated += this.scanChunkForPhysicalChests(world, chunkX, chunkZ, plan);

        if (activated > 0) {
            this.logger.fine("[Breach] Mapped " + activated + " loot chest(s) in chunk "
                    + chunkX + "," + chunkZ + " for world '" + world.getName() + "'.");
        }
    }

    public void invalidateWorldCache(World world) {
        if (world != null) {
            this.activePlans.remove(world.getUID());
            chestRegistry.clearWorld(world);
            chestDisplayService.clearWorld(world);
        }
    }

    /**
     * Force-loads and rolls loot for every planned enhanced chest so a standby breach is matchmaking-ready without
     * waiting for players to explore the map.
     */
    public void forceActivatePlannedLoot(World world, Runnable onComplete) {
        if (world == null) {
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }
        WorldLootPlan plan = this.activePlans.get(world.getUID());
        if (plan == null || plan.spawnsByChunk.isEmpty()) {
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }
        List<long[]> chunks = new ArrayList<>(plan.spawnsByChunk.size());
        for (long packed : plan.spawnsByChunk.keySet()) {
            chunks.add(new long[]{(int) (packed >> 32), (int) packed});
        }
        this.logger.info("[Breach] Force-activating " + plan.spawnsByKey.size() + " loot chest(s) across "
                + chunks.size() + " chunk(s) in '" + world.getName() + "'.");
        this.forceLoadChunkBatch(world, chunks, 0, () -> {
            this.activateChunkBatch(world, chunks, 0);
            if (onComplete != null) {
                int delayTicks = Math.max(2, (chunks.size() / CHUNK_ACTIVATIONS_PER_TICK) + 2);
                this.scheduler.runGlobalLater(onComplete, delayTicks);
            }
        });
    }

    private void forceLoadChunkBatch(World world, List<long[]> chunks, int startIndex, Runnable onComplete) {
        int endIndex = Math.min(startIndex + CHUNK_ACTIVATIONS_PER_TICK, chunks.size());
        for (int index = startIndex; index < endIndex; index++) {
            long[] coords = chunks.get(index);
            int chunkX = (int) coords[0];
            int chunkZ = (int) coords[1];
            this.scheduler.runAtChunk(world, chunkX, chunkZ, () -> {
                Chunk chunk = world.getChunkAt(chunkX, chunkZ);
                if (!chunk.isLoaded()) {
                    chunk.load(true);
                }
            });
        }
        if (endIndex < chunks.size()) {
            this.scheduler.runGlobalLater(() -> this.forceLoadChunkBatch(world, chunks, endIndex, onComplete), 1L);
        } else if (onComplete != null) {
            this.scheduler.runGlobalLater(onComplete, 2L);
        }
    }

    private void beginEnhancedMapLoot(World world, BreachMapMeta mapMeta, String templateId) {
        WorldLootPlan plan = new WorldLootPlan(mapMeta.mapId(), templateId, this.configService.defaultLootTier());
        Set<Long> configuredBlocks = configuredBlockKeys(mapMeta);

        for (BreachMapMeta.LootChest chestDef : mapMeta.lootChests()) {
            plan.register(
                    (int) Math.floor(chestDef.x()),
                    (int) Math.floor(chestDef.y()),
                    (int) Math.floor(chestDef.z()),
                    chestDef.tier(),
                    this.configService.placeConfiguredChestBlocks()
            );
        }

        if (this.configService.autoDiscoverChests()) {
            List<McaChestScanner.BlockPos> cached = this.templateChestCache.get(templateId);
            if (cached != null) {
                for (McaChestScanner.BlockPos pos : cached) {
                    long key = BreachLootChestRegistry.blockKey(pos.x(), pos.y(), pos.z());
                    if (configuredBlocks.contains(key)) {
                        continue;
                    }
                    plan.register(pos.x(), pos.y(), pos.z(), plan.defaultTier, false);
                }
            }
        }

        this.activePlans.put(world.getUID(), plan);
        this.scheduleChunkActivations(world, world.getLoadedChunks());

        this.logger.info("[Breach] Registered " + plan.spawnsByKey.size() + " enhanced loot chest(s) on "
                + mapMeta.mapId() + " (virtualized as chunks load).");
    }

    private void scheduleChunkActivations(World world, Chunk[] loadedChunks) {
        if (loadedChunks.length == 0) {
            return;
        }
        List<long[]> chunks = new ArrayList<>(loadedChunks.length);
        for (Chunk chunk : loadedChunks) {
            chunks.add(new long[]{chunk.getX(), chunk.getZ()});
        }
        this.scheduler.runGlobal(() -> this.activateChunkBatch(world, chunks, 0));
    }

    private void activateChunkBatch(World world, List<long[]> chunks, int startIndex) {
        int endIndex = Math.min(startIndex + CHUNK_ACTIVATIONS_PER_TICK, chunks.size());
        for (int index = startIndex; index < endIndex; index++) {
            long[] coords = chunks.get(index);
            int chunkX = (int) coords[0];
            int chunkZ = (int) coords[1];
            this.scheduler.runAtChunk(world, chunkX, chunkZ, () -> this.activateChunk(world, chunkX, chunkZ));
        }
        if (endIndex < chunks.size()) {
            this.scheduler.runGlobalLater(() -> this.activateChunkBatch(world, chunks, endIndex), 1L);
        }
    }

    private int scanChunkForPhysicalChests(World world, int chunkX, int chunkZ, WorldLootPlan plan) {
        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        int activated = 0;
        for (BlockState state : chunk.getTileEntities()) {
            if (!(state instanceof Chest)) {
                continue;
            }
            Location location = state.getLocation();
            long key = BreachLootChestRegistry.blockKey(location);
            if (plan.activated.contains(key) || plan.spawnsByKey.containsKey(key)) {
                continue;
            }
            if (this.shouldSkipChestHalf(location.getBlock())) {
                continue;
            }
            ChestSpawn spawn = new ChestSpawn(location.getBlockX(), location.getBlockY(), location.getBlockZ(), plan.defaultTier, false);
            if (this.activateEnhancedChest(world, spawn, plan)) {
                activated++;
            }
        }
        return activated;
    }

    private boolean activateEnhancedChest(World world, ChestSpawn spawn, WorldLootPlan plan) {
        long key = BreachLootChestRegistry.blockKey(spawn.x, spawn.y, spawn.z);
        if (!plan.activated.add(key)) {
            return false;
        }

        Location location = blockLocation(world, spawn.x, spawn.y, spawn.z);
        Block block = location.getBlock();
        if (!spawn.allowMissingBlock) {
            Material type = block.getType();
            if (type != Material.CHEST && type != Material.TRAPPED_CHEST) {
                plan.activated.remove(key);
                return false;
            }
            if (this.shouldSkipChestHalf(block)) {
                plan.activated.remove(key);
                return false;
            }
        }

        this.removePhysicalChest(location);
        this.chestDisplayService.ensureVirtualChest(location, spawn.tier);
        BreachLootChestState state = this.chestRegistry.getOrCreate(world, location, spawn.tier);
        state.populateIfEmpty(this.rollLootArray(spawn.tier));
        this.chestDisplayService.updateGlow(location, spawn.tier);
        return true;
    }

    private void removePhysicalChest(Location location) {
        Block block = location.getBlock();
        Material type = block.getType();
        if (type != Material.CHEST && type != Material.TRAPPED_CHEST) {
            return;
        }
        this.clearBlockInventory(block);
        Block partner = this.findDoubleChestPartner(block);
        if (partner != null) {
            this.clearBlockInventory(partner);
            partner.setType(Material.AIR, false);
        }
        block.setType(Material.AIR, false);
    }

    private Block findDoubleChestPartner(Block block) {
        if (!(block.getBlockData() instanceof org.bukkit.block.data.type.Chest chestData)) {
            return null;
        }
        Type chestType = chestData.getType();
        if (chestType == Type.SINGLE) {
            return null;
        }
        BlockFace face = chestData.getFacing();
        BlockFace offset = this.doubleChestOffset(face, chestType == Type.LEFT);
        if (offset == null) {
            return null;
        }
        Block partner = block.getRelative(offset);
        if (partner.getType() != block.getType() || !(partner.getBlockData() instanceof org.bukkit.block.data.type.Chest)) {
            return null;
        }
        return partner;
    }

    private BlockFace doubleChestOffset(BlockFace facing, boolean fromLeftHalf) {
        return switch (facing) {
            case NORTH -> fromLeftHalf ? BlockFace.WEST : BlockFace.EAST;
            case SOUTH -> fromLeftHalf ? BlockFace.EAST : BlockFace.WEST;
            case EAST -> fromLeftHalf ? BlockFace.NORTH : BlockFace.SOUTH;
            case WEST -> fromLeftHalf ? BlockFace.SOUTH : BlockFace.NORTH;
            default -> null;
        };
    }

    private boolean shouldSkipChestHalf(Block block) {
        if (!(block.getBlockData() instanceof org.bukkit.block.data.type.Chest chestData)) {
            return false;
        }
        return chestData.getType() == Type.LEFT;
    }

    private int populateCachedTemplateChests(
            World world,
            String templateId,
            Set<Long> configuredBlocks,
            Set<Inventory> populatedInventories
    ) {
        if (!this.configService.autoDiscoverChests()) {
            return 0;
        }
        if (templateId == null || templateId.isBlank()) {
            return 0;
        }
        List<McaChestScanner.BlockPos> cached = this.templateChestCache.get(templateId);
        if (cached == null || cached.isEmpty()) {
            return 0;
        }

        String defaultTier = this.configService.defaultLootTier();
        int populated = 0;
        for (McaChestScanner.BlockPos pos : cached) {
            long key = BreachLootChestRegistry.blockKey(pos.x(), pos.y(), pos.z());
            if (configuredBlocks.contains(key)) {
                continue;
            }
            Location location = blockLocation(world, pos.x(), pos.y(), pos.z());
            preloadChunk(world, location.getBlockX() >> 4, location.getBlockZ() >> 4);
            if (populateChestAt(location, defaultTier, populatedInventories)) {
                populated++;
            }
        }
        return populated;
    }

    private int populateConfiguredChests(
            World world,
            BreachMapMeta mapMeta,
            Set<Inventory> populatedInventories
    ) {
        int configuredPopulated = 0;
        for (BreachMapMeta.LootChest chestDef : mapMeta.lootChests()) {
            Location location = blockLocation(world, chestDef.x(), chestDef.y(), chestDef.z());
            preloadChunk(world, location.getBlockX() >> 4, location.getBlockZ() >> 4);
            if (populateChestAt(location, chestDef.tier(), populatedInventories)) {
                configuredPopulated++;
            }
        }
        return configuredPopulated;
    }

    private void preloadChunk(World world, int chunkX, int chunkZ) {
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            world.loadChunk(chunkX, chunkZ, false);
        }
    }

    private void clearBlockInventory(Block block) {
        BlockState state = block.getState();
        if (state instanceof Chest chest) {
            chest.getInventory().clear();
            chest.update(true, false);
        }
    }

    private boolean populateChestAt(Location location, String tier, Set<Inventory> populatedInventories) {
        if (location == null || location.getWorld() == null) {
            return false;
        }

        Block block = location.getBlock();
        Material type = block.getType();
        if (type != Material.CHEST && type != Material.TRAPPED_CHEST) {
            this.logger.warning("[Breach] Expected chest at "
                    + block.getX() + ", " + block.getY() + ", " + block.getZ()
                    + " in " + location.getWorld().getName()
                    + " but found " + type + ". Update meta.json lootChests coordinates.");
            return false;
        }

        if (this.shouldSkipChestHalf(block)) {
            return false;
        }

        BlockState state = block.getState();
        if (!(state instanceof Chest chest)) {
            return false;
        }

        Inventory inventory = chest.getInventory();
        if (!populatedInventories.add(inventory)) {
            return false;
        }

        inventory.clear();
        List<ItemStack> rolled = rollLoot(tier);
        for (int slot = 0; slot < rolled.size() && slot < inventory.getSize(); slot++) {
            ItemStack item = rolled.get(slot);
            if (item != null && !item.getType().isAir()) {
                inventory.setItem(slot, item);
            }
        }

        chest.update(true, false);
        return !rolled.isEmpty();
    }

    private ItemStack[] rollLootArray(String tier) {
        List<ItemStack> rolled = rollLoot(tier);
        ItemStack[] array = new ItemStack[BreachLootChestLayout.LOOT_SLOTS.length];
        for (int i = 0; i < rolled.size() && i < array.length; i++) {
            array[i] = rolled.get(i);
        }
        return array;
    }

    private List<ItemStack> rollLoot(String tier) {
        List<BreachLootEntry> table = this.configService.lootTable(tier);
        if (table.isEmpty()) {
            if (this.extractionLoot != null) {
                return this.extractionLoot.customItem("medic:bandage_rag", 4)
                        .map(stack -> List.of(stack))
                        .orElse(List.of(new ItemStack(Material.BREAD, 4)));
            }
            return List.of(new ItemStack(Material.BREAD, 4));
        }

        List<ItemStack> results = new ArrayList<>();
        for (BreachLootEntry entry : table) {
            if (this.random.nextDouble() <= entry.chance()) {
                entry.createItemStack(this.weaponMechanicsBridge, this.extractionLoot).ifPresent(results::add);
            }
        }

        if (results.isEmpty()) {
            for (BreachLootEntry entry : table) {
                var stack = entry.createItemStack(this.weaponMechanicsBridge, this.extractionLoot);
                if (stack.isPresent()) {
                    results.add(stack.get());
                    break;
                }
            }
        }

        if (results.isEmpty()) {
            if (this.extractionLoot != null) {
                this.extractionLoot.customItem("medic:bandage_rag", 4)
                        .ifPresentOrElse(results::add, () -> results.add(new ItemStack(Material.BREAD, 4)));
            } else {
                results.add(new ItemStack(Material.BREAD, 4));
            }
        }
        return results;
    }

    private static Location blockLocation(World world, double x, double y, double z) {
        return new Location(world, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    private Set<Long> configuredBlockKeys(BreachMapMeta mapMeta) {
        Set<Long> keys = new HashSet<>();
        for (BreachMapMeta.LootChest chest : mapMeta.lootChests()) {
            keys.add(BreachLootChestRegistry.blockKey(
                    (int) Math.floor(chest.x()),
                    (int) Math.floor(chest.y()),
                    (int) Math.floor(chest.z())
            ));
        }
        return keys;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private static final class ChestSpawn {
        private final int x;
        private final int y;
        private final int z;
        private final String tier;
        private final boolean allowMissingBlock;

        private ChestSpawn(int x, int y, int z, String tier, boolean allowMissingBlock) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.tier = tier;
            this.allowMissingBlock = allowMissingBlock;
        }
    }

    private static final class WorldLootPlan {
        private final String defaultTier;
        private final Map<Long, ChestSpawn> spawnsByKey = new HashMap<>();
        private final Map<Long, List<ChestSpawn>> spawnsByChunk = new HashMap<>();
        private final Set<Long> activated = ConcurrentHashMap.newKeySet();

        private WorldLootPlan(String mapId, String templateId, String defaultTier) {
            this.defaultTier = defaultTier;
        }

        private void register(int x, int y, int z, String tier, boolean allowMissingBlock) {
            long key = BreachLootChestRegistry.blockKey(x, y, z);
            if (this.spawnsByKey.containsKey(key)) {
                return;
            }
            ChestSpawn spawn = new ChestSpawn(x, y, z, tier, allowMissingBlock);
            this.spawnsByKey.put(key, spawn);
            long chunk = chunkKey(x >> 4, z >> 4);
            this.spawnsByChunk.computeIfAbsent(chunk, ignored -> new ArrayList<>()).add(spawn);
        }
    }
}
