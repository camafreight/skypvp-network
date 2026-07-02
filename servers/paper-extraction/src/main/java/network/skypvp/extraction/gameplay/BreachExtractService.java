package network.skypvp.extraction.gameplay;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import network.skypvp.extraction.config.BreachConfigService;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.engine.BreachInstance;
import network.skypvp.extraction.model.BreachState;
import network.skypvp.extraction.text.ExtractionTexts;
import network.skypvp.paper.platform.ServerPlatform;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import network.skypvp.paper.platform.PlatformTask;

public final class BreachExtractService {

    private final BreachConfigService configService;
    private final ServerPlatform scheduler;
    private final Map<UUID, Long> combatTaggedUntilMillis = new ConcurrentHashMap<>();
    private final Map<UUID, Long> extractStartMillis = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> extractBossBars = new ConcurrentHashMap<>();
    private PlatformTask tickTask;

    public BreachExtractService(BreachConfigService configService, ServerPlatform scheduler) {
        this.configService = Objects.requireNonNull(configService, "configService");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    public void start(JavaPlugin plugin, ServerPlatform scheduler, BreachEngine engine) {
        if (tickTask != null) {
            return;
        }
        tickTask = scheduler.runGlobalTimer(() -> tick(engine), 10L, 10L);
    }

    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        for (Map.Entry<UUID, BossBar> entry : extractBossBars.entrySet()) {
            Player player = pluginPlayer(entry.getKey());
            if (player != null) {
                player.hideBossBar(entry.getValue());
            }
        }
        extractBossBars.clear();
        extractStartMillis.clear();
        combatTaggedUntilMillis.clear();
    }

    public void clearPlayer(Player player) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        extractStartMillis.remove(playerId);
        hideExtractBossBar(player);
    }

    /** Removes the combat-log tag, e.g. after a player is eliminated so the ghost is no longer "in combat". */
    public void clearCombat(Player player) {
        if (player == null) {
            return;
        }
        combatTaggedUntilMillis.remove(player.getUniqueId());
    }

    public void tagCombat(Player player) {
        if (player == null) {
            return;
        }
        long until = System.currentTimeMillis() + configService.combatTagSeconds() * 1000L;
        combatTaggedUntilMillis.put(player.getUniqueId(), until);
        if (extractStartMillis.containsKey(player.getUniqueId())) {
            cancelExtract(player, "extraction.extract.cancelled.entered_combat");
        }
    }

    public boolean isCombatTagged(Player player) {
        if (player == null) {
            return true;
        }
        Long until = combatTaggedUntilMillis.get(player.getUniqueId());
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() >= until) {
            combatTaggedUntilMillis.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    public int combatTagRemainingSeconds(Player player) {
        Long until = combatTaggedUntilMillis.get(player.getUniqueId());
        if (until == null) {
            return 0;
        }
        long remaining = until - System.currentTimeMillis();
        if (remaining <= 0) {
            combatTaggedUntilMillis.remove(player.getUniqueId());
            return 0;
        }
        return (int) Math.ceil(remaining / 1000.0);
    }

    public boolean isExtracting(Player player) {
        return player != null && extractStartMillis.containsKey(player.getUniqueId());
    }

    public void onMovedInExtractZone(Player player, BreachInstance instance) {
        if (player == null || instance == null) {
            return;
        }
        if (instance.state() != BreachState.ACTIVE || instance.hasExtracted(player.getUniqueId())) {
            clearPlayer(player);
            return;
        }
        if (isCombatTagged(player)) {
            extractStartMillis.remove(player.getUniqueId());
            hideExtractBossBar(player);
            int tagSeconds = combatTagRemainingSeconds(player);
            player.sendActionBar(ExtractionTexts.miniMessage(
                    player,
                    "extraction.extract.actionbar.combat_tagged",
                    tagSeconds
            ));
            return;
        }
        extractStartMillis.putIfAbsent(player.getUniqueId(), System.currentTimeMillis());
    }

    public void onLeftExtractZone(Player player) {
        if (player == null) {
            return;
        }
        if (extractStartMillis.remove(player.getUniqueId()) != null) {
            hideExtractBossBar(player);
            player.sendActionBar(ExtractionTexts.miniMessage(player, "extraction.extract.actionbar.left_zone"));
        }
    }

    private void tick(BreachEngine engine) {
        if (extractStartMillis.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long dwellMillis = configService.extractDwellSeconds() * 1000L;

        for (BreachInstance instance : engine.activeInstances()) {
            if (instance.state() != BreachState.ACTIVE) {
                continue;
            }
            World world = instance.world();
            if (world == null) {
                continue;
            }
            Location anchor = world.getSpawnLocation();
            this.scheduler.runAtLocation(anchor, () -> tickInstanceExtracts(engine, instance, now, dwellMillis));
        }
    }

    private void tickInstanceExtracts(BreachEngine engine, BreachInstance instance, long now, long dwellMillis) {
        for (UUID playerId : instance.participantsSnapshot()) {
            if (!extractStartMillis.containsKey(playerId)) {
                continue;
            }
            Player player = pluginPlayer(playerId);
            if (player == null || !player.isOnline()) {
                extractStartMillis.remove(playerId);
                continue;
            }
            this.scheduler.runOnPlayer(player, () -> this.tickPlayerExtract(engine, instance, player, now, dwellMillis));
        }
    }

    private void tickPlayerExtract(
            BreachEngine engine,
            BreachInstance instance,
            Player player,
            long now,
            long dwellMillis
    ) {
        UUID playerId = player.getUniqueId();
        if (instance.hasExtracted(playerId)) {
            clearPlayer(player);
            return;
        }

        Long start = extractStartMillis.get(playerId);
        if (start == null) {
            return;
        }

        if (!instance.isInExtractZone(player.getLocation())) {
            onLeftExtractZone(player);
            return;
        }

        if (isCombatTagged(player)) {
            cancelExtract(player, "extraction.extract.cancelled.combat_tagged");
            return;
        }

        long elapsed = now - start;
        if (elapsed >= dwellMillis) {
            extractStartMillis.remove(playerId);
            hideExtractBossBar(player);
            engine.completeExtract(player, instance);
            return;
        }

        float progress = Math.max(0.0F, Math.min(1.0F, (float) elapsed / dwellMillis));
        int remainingSeconds = (int) Math.ceil((dwellMillis - elapsed) / 1000.0);
        updateExtractUi(player, progress, remainingSeconds);
    }

    private void updateExtractUi(Player player, float progress, int remainingSeconds) {
        Component title = ExtractionTexts.miniMessage(
                player,
                "extraction.extract.bossbar.extracting",
                remainingSeconds
        );
        BossBar bar = extractBossBars.computeIfAbsent(player.getUniqueId(), ignored -> {
            BossBar created = BossBar.bossBar(
                    title,
                    progress,
                    BossBar.Color.GREEN,
                    BossBar.Overlay.PROGRESS
            );
            player.showBossBar(created);
            return created;
        });
        bar.name(title);
        bar.progress(progress);
        bar.color(BossBar.Color.GREEN);

        player.sendActionBar(ExtractionTexts.miniMessage(
                player,
                "extraction.extract.actionbar.extracting",
                remainingSeconds
        ));
    }

    private void cancelExtract(Player player, String catalogKey) {
        extractStartMillis.remove(player.getUniqueId());
        hideExtractBossBar(player);
        if (catalogKey != null && !catalogKey.isBlank()) {
            player.sendMessage(ExtractionTexts.miniMessage(player, catalogKey));
        }
    }

    private void hideExtractBossBar(Player player) {
        BossBar bar = extractBossBars.remove(player.getUniqueId());
        if (bar != null) {
            player.hideBossBar(bar);
        }
    }

    private Player pluginPlayer(UUID playerId) {
        return org.bukkit.Bukkit.getPlayer(playerId);
    }
}
