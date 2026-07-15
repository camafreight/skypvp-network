package network.skypvp.paper.nms.impl;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

/** Folia-safe dispatch onto a world's region thread. */
final class HeadlessPlayerRegionTasks {

    private static final long REGION_WAIT_MS = 3_000L;

    private HeadlessPlayerRegionTasks() {
    }

    static void run(Plugin plugin, Location location, Runnable task) {
        if (location == null || location.getWorld() == null) {
            task.run();
            return;
        }
        if (Bukkit.isOwnedByCurrentRegion(location)) {
            task.run();
            return;
        }
        Bukkit.getRegionScheduler().run(
                plugin,
                location.getWorld(),
                location.getBlockX() >> 4,
                location.getBlockZ() >> 4,
                scheduled -> task.run());
    }

    static <T> T call(Plugin plugin, Location location, Supplier<T> task) {
        if (location == null || location.getWorld() == null || Bukkit.isOwnedByCurrentRegion(location)) {
            return task.get();
        }
        AtomicReference<T> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Bukkit.getRegionScheduler().run(
                plugin,
                location.getWorld(),
                location.getBlockX() >> 4,
                location.getBlockZ() >> 4,
                scheduled -> {
                    try {
                        result.set(task.get());
                    } finally {
                        latch.countDown();
                    }
                });
        await(latch);
        return result.get();
    }

    static void callGlobal(Plugin plugin, Runnable task) {
        callGlobal(plugin, () -> {
            task.run();
            return null;
        });
    }

    static <T> T callGlobal(Plugin plugin, Supplier<T> task) {
        if (Bukkit.isGlobalTickThread()) {
            return task.get();
        }
        AtomicReference<T> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Bukkit.getGlobalRegionScheduler().run(plugin, scheduled -> {
            try {
                result.set(task.get());
            } finally {
                latch.countDown();
            }
        });
        await(latch);
        return result.get();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(REGION_WAIT_MS, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Timed out waiting for headless player region task");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for headless player region task", interrupted);
        }
    }
}
