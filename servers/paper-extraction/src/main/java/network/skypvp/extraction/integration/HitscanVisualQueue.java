package network.skypvp.extraction.integration;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import network.skypvp.extraction.config.HitscanSettings;
import network.skypvp.paper.platform.ServerPlatform;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

/** Batches deferred hitscan cosmetics off the WeaponShootEvent hot path. */
final class HitscanVisualQueue {

    private final JavaPlugin plugin;
    private final ServerPlatform scheduler;
    private final HitscanSettings settings;
    private final HitscanVisualRenderer renderer;
    private final Logger logger;
    private final ConcurrentLinkedQueue<HitscanVisualJob> pending = new ConcurrentLinkedQueue<>();
    private final AtomicInteger dropped = new AtomicInteger();

    HitscanVisualQueue(
            JavaPlugin plugin,
            ServerPlatform scheduler,
            HitscanSettings settings,
            HitscanVisualRenderer renderer
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.logger = plugin.getLogger();
        this.scheduler.runGlobalTimer(this::drain, 1L, 1L);
    }

    void enqueue(HitscanVisualJob job) {
        if (job == null) {
            return;
        }
        if (pending.size() >= settings.visualQueueCapacity()) {
            pending.poll();
            int drops = dropped.incrementAndGet();
            if (drops == 1 || drops % 500 == 0) {
                this.logger.warning("[Breach] Hitscan visual queue saturated; dropping oldest cosmetic jobs.");
            }
        }
        pending.offer(job);
    }

    void enqueueAsync(HitscanVisualJob job) {
        if (job == null) {
            return;
        }
        if (!settings.asyncVisualPrep()) {
            enqueue(job);
            return;
        }
        this.scheduler.runAsync(() -> enqueue(job));
    }

    private void drain() {
        int budget = settings.maxVisualJobsPerTick();
        for (int i = 0; i < budget; i++) {
            HitscanVisualJob job = pending.poll();
            if (job == null) {
                return;
            }
            World world = Bukkit.getWorld(job.worldId());
            if (world == null) {
                continue;
            }
            long defer = settings.visualDeferTicks();
            if (defer <= 0L) {
                this.scheduler.runAtChunk(world, job.chunkX(), job.chunkZ(), () -> this.renderer.render(world, job));
            } else {
                this.scheduler.runAtChunkLater(
                        world,
                        job.chunkX(),
                        job.chunkZ(),
                        () -> this.renderer.render(world, job),
                        defer
                );
            }
        }
    }
}
