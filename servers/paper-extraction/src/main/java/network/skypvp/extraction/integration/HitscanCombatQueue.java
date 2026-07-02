package network.skypvp.extraction.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;
import network.skypvp.extraction.config.HitscanSettings;
import network.skypvp.paper.platform.ServerPlatform;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Drains hitscan combat jobs on a small worker pool and schedules each ray onto the correct region
 * thread. Shoot listeners only enqueue here so the WeaponShootEvent path stays thin.
 */
final class HitscanCombatQueue implements AutoCloseable {

    private final ServerPlatform scheduler;
    private final HitscanSettings settings;
    private final Consumer<HitscanCombatJob> processor;
    private final Logger logger;
    private final ConcurrentLinkedQueue<HitscanCombatJob> pending = new ConcurrentLinkedQueue<>();
    private final AtomicInteger dropped = new AtomicInteger();
    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean running = true;

    HitscanCombatQueue(
            JavaPlugin plugin,
            ServerPlatform scheduler,
            HitscanSettings settings,
            Consumer<HitscanCombatJob> processor
    ) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.processor = Objects.requireNonNull(processor, "processor");
        this.logger = plugin.getLogger();

        int threads = settings.combatDispatchThreads();
        if (threads <= 0) {
            return;
        }

        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(this::workerLoop, "skypvp-hitscan-combat-" + i);
            worker.setDaemon(true);
            worker.start();
            workers.add(worker);
        }
    }

    void enqueueOrDispatch(HitscanCombatJob job) {
        if (job == null || !running) {
            return;
        }
        if (settings.combatDispatchThreads() <= 0) {
            dispatch(job);
            return;
        }

        int capacity = settings.combatQueueCapacity();
        while (pending.size() >= capacity) {
            HitscanCombatJob evicted = pending.poll();
            if (evicted == null) {
                break;
            }
            int drops = dropped.incrementAndGet();
            if (drops == 1 || drops % 500 == 0) {
                logger.warning("[Breach] Hitscan combat queue saturated; dropping oldest combat jobs.");
            }
        }
        pending.offer(job);
    }

    private void workerLoop() {
        while (running) {
            HitscanCombatJob job = pending.poll();
            if (job == null) {
                try {
                    Thread.sleep(1L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            dispatch(job);
        }
    }

    private void dispatch(HitscanCombatJob job) {
        Location start = job.start();
        Runnable task = () -> processor.accept(job);
        long defer = settings.combatDeferTicks();
        if (defer <= 0L) {
            scheduler.runAtLocation(start, task);
            return;
        }
        scheduler.runAtChunkLater(
                start.getWorld(),
                start.getBlockX() >> 4,
                start.getBlockZ() >> 4,
                task,
                defer
        );
    }

    @Override
    public void close() {
        running = false;
        for (Thread worker : workers) {
            worker.interrupt();
        }
        pending.clear();
    }
}
