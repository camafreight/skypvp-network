package network.skypvp.extraction.gameplay.loot;

import java.util.List;
import java.util.Objects;
import network.skypvp.extraction.config.BreachConfigService;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.engine.BreachInstance;
import network.skypvp.extraction.model.BreachState;
import network.skypvp.paper.platform.ServerPlatform;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class BreachLootChestAmbienceService {

    private final BreachConfigService configService;
    private final BreachLootChestRegistry registry;
    private final BreachLootChestDisplayService displayService;
    private boolean started;

    public BreachLootChestAmbienceService(
            BreachConfigService configService,
            BreachLootChestRegistry registry,
            BreachLootChestDisplayService displayService
    ) {
        this.configService = Objects.requireNonNull(configService, "configService");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.displayService = Objects.requireNonNull(displayService, "displayService");
    }

    public void start(JavaPlugin plugin, ServerPlatform scheduler, BreachEngine engine) {
        if (started || !configService.enhancedLootChests()) {
            return;
        }
        started = true;
        long interval = configService.lootChestAmbientIntervalTicks();
        // Ambience touches chest block props and world particles; schedule per active breach world instead of global.
        scheduler.runGlobalTimer(() -> tick(engine, scheduler), interval, interval);
    }

    private void tick(BreachEngine engine, ServerPlatform scheduler) {
        if (!configService.enhancedLootChests()) {
            return;
        }
        for (BreachInstance instance : engine.activeInstances()) {
            if (instance.state() != BreachState.ACTIVE) {
                continue;
            }
            World world = instance.world();
            if (world == null) {
                continue;
            }
            Location anchor = world.getSpawnLocation();
            scheduler.runAtLocation(anchor, () -> tickInstance(instance, scheduler));
        }
    }

    private void tickInstance(BreachInstance instance, ServerPlatform scheduler) {
        World world = instance.world();
        if (world == null || instance.state() != BreachState.ACTIVE) {
            return;
        }
        double radius = configService.lootChestAmbientRadius();
        double radiusSquared = radius * radius;
        List<Location> chests = registry.activeChestsWithLoot(world);
        if (chests.isEmpty()) {
            return;
        }
        List<Player> viewers = instance.participantsSnapshot().stream()
                .map(Bukkit::getPlayer)
                .filter(player -> player != null && player.isOnline() && player.getWorld().equals(world))
                .toList();
        if (viewers.isEmpty()) {
            return;
        }
        for (Location chestLocation : chests) {
            tickChest(scheduler, world, chestLocation, viewers, radiusSquared);
        }
    }

    private void tickChest(
            ServerPlatform scheduler,
            World world,
            Location chestLocation,
            List<Player> viewers,
            double radiusSquared
    ) {
        Location center = chestLocation.clone().add(0.5, 0.85, 0.5);
        registry.find(world, chestLocation).ifPresent(state -> {
            if (state.opened()) {
                return;
            }
            BreachConfigService.LootChestFx fx = configService.lootChestFx(state.tier());
            boolean anyNearby = false;
            for (Player player : viewers) {
                if (player.getLocation().distanceSquared(center) <= radiusSquared) {
                    anyNearby = true;
                    scheduler.runOnPlayer(player, () -> player.playSound(center, fx.sound(), fx.volume(), fx.pitch()));
                }
            }
            if (!anyNearby) {
                return;
            }
            scheduler.runAtLocation(center, () -> {
                displayService.refreshAppearance(chestLocation);
                Particle particle = fx.particle();
                world.spawnParticle(
                        particle,
                        center,
                        fx.particleCount(),
                        0.35,
                        0.25,
                        0.35,
                        fx.particleSpeed()
                );
            });
        });
    }
}
