package network.skypvp.extraction.gameplay.loot;

import java.util.Objects;
import network.skypvp.extraction.config.BreachConfigService;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.library.NpcLibrary;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Barrel;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

public final class BreachLootChestDisplayService {

    public static final String PROP_TYPE = "BREACH_LOOT_CHEST";

    private static final Transformation CLOSED_CHEST_TRANSFORM = new Transformation(
            new Vector3f(-0.5F, 0.0F, -0.5F),
            new AxisAngle4f(0.0F, 0.0F, 0.0F, 1.0F),
            new Vector3f(1.0F, 1.0F, 1.0F),
            new AxisAngle4f(0.0F, 0.0F, 0.0F, 1.0F)
    );

    private static final Transformation OPENED_CHEST_TRANSFORM = CLOSED_CHEST_TRANSFORM;

    private final PaperCorePlugin core;
    private final BreachConfigService configService;
    private final BreachLootChestRegistry registry;

    public BreachLootChestDisplayService(
            PaperCorePlugin core,
            BreachConfigService configService,
            BreachLootChestRegistry registry
    ) {
        this.core = Objects.requireNonNull(core, "core");
        this.configService = Objects.requireNonNull(configService, "configService");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public void ensureVirtualChest(Location blockLocation, String tier) {
        if (blockLocation == null || blockLocation.getWorld() == null || !configService.enhancedLootChests()) {
            return;
        }
        World world = blockLocation.getWorld();
        long anchorKey = BreachLootChestRegistry.blockKey(blockLocation);
        String id = propId(world, anchorKey);
        NpcLibrary npcLibrary = core.npcLibrary();
        if (!npcLibrary.ensureBlockProp(id)) {
            BreachConfigService.LootChestFx fx = configService.lootChestFx(tier);
            npcLibrary.spawnBlockProp(
                    id,
                    blockLocation,
                    Material.CHEST,
                    PROP_TYPE,
                    anchorKey,
                    true,
                    fx.glowColor()
            );
        }
        refreshAppearance(blockLocation);
    }

    public void refreshAppearance(Location blockLocation) {
        if (blockLocation == null || blockLocation.getWorld() == null || !configService.enhancedLootChests()) {
            return;
        }
        World world = blockLocation.getWorld();
        long anchorKey = BreachLootChestRegistry.blockKey(blockLocation);
        String id = propId(world, anchorKey);
        NpcLibrary npcLibrary = core.npcLibrary();

        var stateOpt = registry.find(world, blockLocation);
        if (stateOpt.isEmpty()) {
            return;
        }
        BreachLootChestState state = stateOpt.get();
        if (state.isEmpty()) {
            npcLibrary.removeBlockProp(id);
            return;
        }

        if (!npcLibrary.ensureBlockProp(id)) {
            BreachConfigService.LootChestFx fx = configService.lootChestFx(state.tier());
            npcLibrary.spawnBlockProp(
                    id,
                    blockLocation,
                    Material.CHEST,
                    PROP_TYPE,
                    anchorKey,
                    !state.opened(),
                    state.opened() ? null : fx.glowColor()
            );
        }

        BlockData blockData = state.opened() ? openedChestBlockData() : chestBlockData();
        boolean glow = !state.opened();
        String glowColor = glow ? configService.lootChestFx(state.tier()).glowColor() : null;
        Transformation transformation = state.opened() ? OPENED_CHEST_TRANSFORM : CLOSED_CHEST_TRANSFORM;
        npcLibrary.updateBlockPropAppearance(id, blockData, glow, glowColor, transformation);
    }

    public void updateGlow(Location blockLocation, String tier) {
        refreshAppearance(blockLocation);
    }

    public void clearWorld(World world) {
        if (world != null) {
            core.npcLibrary().removeBlockPropsForWorld(world, propPrefix(world));
        }
    }

    public static String propId(World world, long anchorKey) {
        return propPrefix(world) + anchorKey;
    }

    private static String propPrefix(World world) {
        return "breach_chest_" + world.getUID() + "_";
    }

    private static BlockData chestBlockData() {
        return Material.CHEST.createBlockData();
    }

    private static BlockData openedChestBlockData() {
        Barrel barrel = (Barrel) Material.BARREL.createBlockData();
        barrel.setOpen(true);
        return barrel;
    }
}
