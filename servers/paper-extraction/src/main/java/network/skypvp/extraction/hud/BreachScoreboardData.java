package network.skypvp.extraction.hud;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.platform.PlatformTask;
import network.skypvp.paper.platform.ServerPlatform;
import network.skypvp.paper.repository.PlayerCurrencyRepository;
import network.skypvp.paper.repository.PlayerStatsRepository;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Periodically caches per-player lifetime stats and currency for the breach sidebar so the
 * per-tick scoreboard render never performs a blocking DB read.
 */
public final class BreachScoreboardData {

    public static final Snapshot EMPTY = new Snapshot(0L, 0L, 0L, 0L, 0L);

    private final PaperCorePlugin core;
    private final ServerPlatform scheduler;
    private final Map<UUID, Snapshot> cache = new ConcurrentHashMap<>();
    private PlatformTask task;

    public BreachScoreboardData(PaperCorePlugin core) {
        this.core = core;
        this.scheduler = core.platformScheduler();
    }

    public void start() {
        if (this.task == null) {
            this.task = this.scheduler.runGlobalTimer(this::refreshAll, 20L, 60L);
        }
    }

    public void stop() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
        this.cache.clear();
    }

    public Snapshot get(UUID playerId) {
        return this.cache.getOrDefault(playerId, EMPTY);
    }

    private void refreshAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            this.scheduler.runAsync(() -> this.refreshPlayer(id));
        }
        this.cache.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    private void refreshPlayer(UUID id) {
        long extractions = 0L;
        long kills = 0L;
        long deaths = 0L;
        long gold = 0L;
        long coins = 0L;

        PlayerStatsRepository statsRepo = this.core.playerStatsRepository();
        if (statsRepo != null) {
            Optional<PlayerStatsRepository.PlayerStats> stats = statsRepo.getStats(id);
            if (stats.isPresent()) {
                PlayerStatsRepository.PlayerStats s = stats.get();
                extractions = s.extractions();
                kills = s.kills();
                deaths = s.deaths();
            }
        }

        PlayerCurrencyRepository currencyRepo = this.core.playerCurrencyRepository();
        if (currencyRepo != null) {
            Optional<PlayerCurrencyRepository.Balance> balance = currencyRepo.getBalance(id);
            if (balance.isPresent()) {
                gold = balance.get().gold();
                coins = balance.get().coins();
            }
        }

        this.cache.put(id, new Snapshot(extractions, kills, deaths, gold, coins));
    }

    public static record Snapshot(long extractions, long kills, long deaths, long gold, long coins) {
    }
}
