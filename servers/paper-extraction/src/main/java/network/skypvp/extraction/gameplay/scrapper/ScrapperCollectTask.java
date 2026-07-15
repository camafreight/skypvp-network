package network.skypvp.extraction.gameplay.scrapper;

import java.util.Objects;
import network.skypvp.paper.platform.PlatformTask;
import network.skypvp.paper.platform.ServerPlatform;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Global ticker that passively collects scrapper materials for active breach raiders. */
public final class ScrapperCollectTask {

    private static final long INTERVAL_TICKS = 40L;

    private final JavaPlugin plugin;
    private final ServerPlatform scheduler;
    private final ScrapperService scrapperService;
    private PlatformTask task;

    public ScrapperCollectTask(JavaPlugin plugin, ServerPlatform scheduler, ScrapperService scrapperService) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.scrapperService = Objects.requireNonNull(scrapperService, "scrapperService");
    }

    public void start() {
        if (this.task != null) {
            return;
        }
        this.task = scheduler.runGlobalTimer(this::tick, INTERVAL_TICKS, INTERVAL_TICKS);
    }

    public void stop() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player == null || !player.isOnline()) {
                continue;
            }
            scheduler.runOnPlayer(player, () -> scrapperService.tickPassiveCollection(player));
        }
    }
}
