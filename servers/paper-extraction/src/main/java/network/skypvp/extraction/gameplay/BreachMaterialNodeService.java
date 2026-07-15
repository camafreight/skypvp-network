package network.skypvp.extraction.gameplay;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import network.skypvp.extraction.crafting.CraftingConfigService;
import network.skypvp.extraction.crafting.CraftingMaterialItemFactory;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.gameplay.scrapper.ScrapperService;
import network.skypvp.extraction.model.BreachMapMeta;
import network.skypvp.paper.PaperCorePlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Passive salvage piles in breach maps ({@code materialNodes} in meta.json). Ready piles spawn visible material
 * item drops; players gather by walking nearby (auto-pickup range) or the items sit on the ground until collected.
 */
public final class BreachMaterialNodeService {

    private static final Material PILE_BLOCK = Material.RAW_GOLD_BLOCK;
    private static final double PICKUP_RADIUS = 1.75;

    private final PaperCorePlugin core;
    private final CraftingConfigService craftingConfig;
    private final BreachEngine breachEngine;
    private final ScrapperService scrapperService;
    private final Logger logger;
    private final Map<UUID, Map<String, NodeRuntime>> nodesByWorld = new ConcurrentHashMap<>();

    public BreachMaterialNodeService(
            JavaPlugin plugin,
            PaperCorePlugin core,
            CraftingConfigService craftingConfig,
            BreachEngine breachEngine,
            ScrapperService scrapperService
    ) {
        Objects.requireNonNull(plugin, "plugin");
        this.core = Objects.requireNonNull(core, "core");
        this.craftingConfig = Objects.requireNonNull(craftingConfig, "craftingConfig");
        this.breachEngine = Objects.requireNonNull(breachEngine, "breachEngine");
        this.scrapperService = Objects.requireNonNull(scrapperService, "scrapperService");
        this.logger = plugin.getLogger();
    }

    public void resetWorld(World world) {
        if (world != null) {
            nodesByWorld.remove(world.getUID());
        }
    }

    public void tick(World world, BreachMapMeta mapMeta, List<Player> viewers) {
        if (world == null || mapMeta == null || mapMeta.materialNodes().isEmpty()) {
            return;
        }
        Map<String, NodeRuntime> runtime = nodesByWorld.computeIfAbsent(world.getUID(), ignored -> new HashMap<>());
        long now = System.currentTimeMillis();
        for (BreachMapMeta.MaterialNode node : mapMeta.materialNodes()) {
            NodeRuntime state = runtime.computeIfAbsent(node.id(), ignored -> new NodeRuntime(node));
            if (state.ready && state.dropEntityId != null) {
                if (org.bukkit.Bukkit.getEntity(state.dropEntityId) == null) {
                    state.ready = false;
                    state.dropEntityId = null;
                    state.nextReadyAtMillis = now + node.respawnSeconds() * 1000L;
                }
            }
            if (!state.ready && now >= state.nextReadyAtMillis) {
                spawnPile(world, node, state);
            }
            if (state.ready && viewers != null) {
                showPileParticles(world, node, viewers);
                tryAutoGather(world, node, state, viewers);
            }
        }
    }

    private void spawnPile(World world, BreachMapMeta.MaterialNode node, NodeRuntime state) {
        ItemStack stack = CraftingMaterialItemFactory.create(
                core.customItemService(),
                craftingConfig,
                node.materialId(),
                node.amount()
        );
        if (stack == null || stack.getType().isAir()) {
            logger.warning("[Breach] Material node '" + node.id() + "' uses unknown material '" + node.materialId() + "'");
            state.nextReadyAtMillis = System.currentTimeMillis() + node.respawnSeconds() * 1000L;
            return;
        }
        Location dropAt = new Location(world, node.x() + 0.5, node.y() + 0.35, node.z() + 0.5);
        var dropped = world.dropItem(dropAt, stack);
        dropped.setPickupDelay(0);
        dropped.setVelocity(dropped.getVelocity().zero());
        state.ready = true;
        state.dropEntityId = dropped.getUniqueId();
    }

    private void showPileParticles(World world, BreachMapMeta.MaterialNode node, List<Player> viewers) {
        Location center = new Location(world, node.x() + 0.5, node.y() + 0.6, node.z() + 0.5);
        BlockData marker = PILE_BLOCK.createBlockData();
        for (Player viewer : viewers) {
            if (viewer == null || !viewer.isOnline() || !viewer.getWorld().equals(world)) {
                continue;
            }
            if (viewer.getLocation().distanceSquared(center) > 48.0 * 48.0) {
                continue;
            }
            viewer.spawnParticle(Particle.BLOCK, center, 3, 0.15, 0.05, 0.15, 0.0, marker, true);
        }
    }

    private void tryAutoGather(World world, BreachMapMeta.MaterialNode node, NodeRuntime state, List<Player> viewers) {
        if (state.dropEntityId == null) {
            return;
        }
        var entity = org.bukkit.Bukkit.getEntity(state.dropEntityId);
        if (entity == null) {
            return;
        }
        Location pile = entity.getLocation();
        for (Player player : viewers) {
            if (player == null || !player.isOnline() || !player.getWorld().equals(world)) {
                continue;
            }
            if (!scrapperService.isActiveInBreach(player)
                    && breachEngine.instanceFor(player).filter(i -> i.containsPlayer(player.getUniqueId())).isEmpty()) {
                continue;
            }
            if (player.getLocation().distanceSquared(pile) > node.gatherRadius() * node.gatherRadius()) {
                continue;
            }
            if (player.getLocation().distanceSquared(pile) <= PICKUP_RADIUS * PICKUP_RADIUS) {
                entity.remove();
                scrapperService.collect(player, node.amount());
                player.sendMessage(net.kyori.adventure.text.Component.text(
                        "Salvaged " + node.amount() + "x " + node.materialId().replace('_', ' '),
                        net.kyori.adventure.text.format.NamedTextColor.GRAY
                ));
                state.ready = false;
                state.dropEntityId = null;
                state.nextReadyAtMillis = System.currentTimeMillis() + node.respawnSeconds() * 1000L;
                return;
            }
        }
    }

    private static final class NodeRuntime {
        private final BreachMapMeta.MaterialNode node;
        private long nextReadyAtMillis;
        private boolean ready;
        private UUID dropEntityId;

        private NodeRuntime(BreachMapMeta.MaterialNode node) {
            this.node = node;
            this.nextReadyAtMillis = System.currentTimeMillis() + 5000L;
        }
    }
}
