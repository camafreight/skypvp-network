package network.skypvp.paper.platform;

import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.scheduler.BukkitTask;

/**
 * Cancellable repeating task handle that works on both Paper and Folia.
 */
public final class PlatformTask {

    private final AtomicBoolean cancelled;
    private BukkitTask paperTask;

    PlatformTask(AtomicBoolean cancelled) {
        this.cancelled = cancelled;
    }

    void bindPaperTask(BukkitTask paperTask) {
        this.paperTask = paperTask;
    }

    public void cancel() {
        this.cancelled.set(true);
        if (this.paperTask != null) {
            this.paperTask.cancel();
        }
    }

    public boolean isCancelled() {
        return this.cancelled.get();
    }
}
