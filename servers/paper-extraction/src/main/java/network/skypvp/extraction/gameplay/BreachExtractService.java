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
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.platform.PlatformTask;
import network.skypvp.paper.platform.ServerPlatform;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Extract-zone dwell timer. Progress feedback uses a dedicated boss bar plus {@link ExtractFeedback}
 * titles/SFX — it does not override the vitals action-bar HUD.
 */
public final class BreachExtractService {

    private final PaperCorePlugin core;
    private final BreachConfigService configService;
    private final ServerPlatform scheduler;
    private final Map<UUID, Long> combatTaggedUntilMillis = new ConcurrentHashMap<>();
    private final Map<UUID, Long> extractStartMillis = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastCountdownSeconds = new ConcurrentHashMap<>();
    private PlatformTask tickTask;

    public BreachExtractService(
            PaperCorePlugin core,
            BreachConfigService configService,
            ServerPlatform scheduler
    ) {
        this.core = Objects.requireNonNull(core, "core");
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
        extractStartMillis.clear();
        lastCountdownSeconds.clear();
        combatTaggedUntilMillis.clear();
    }

    public void clearPlayer(Player player) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        extractStartMillis.remove(playerId);
        lastCountdownSeconds.remove(playerId);
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
            cancelExtract(player, "extraction.extract.cancelled.entered_combat", true);
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

    /**
     * Dwell progress 0..1, or -1 when the player is not extracting. The extract UI rides
     * the shared provider boss bar ({@code BreachHudProvider#bossBar}); this service no
     * longer shows a bar of its own — the two bars used to stack and shoved the balance
     * readout aside.
     */
    public float extractProgress(Player player) {
        Long start = player == null ? null : extractStartMillis.get(player.getUniqueId());
        if (start == null) {
            return -1.0F;
        }
        long dwellMillis = Math.max(1L, configService.extractDwellSeconds() * 1000L);
        return Math.max(0.0F, Math.min(1.0F, (System.currentTimeMillis() - start) / (float) dwellMillis));
    }

    /** Seconds until extraction completes, or 0 when not extracting. */
    public int extractRemainingSeconds(Player player) {
        Long start = player == null ? null : extractStartMillis.get(player.getUniqueId());
        if (start == null) {
            return 0;
        }
        long dwellMillis = Math.max(1L, configService.extractDwellSeconds() * 1000L);
        long remaining = dwellMillis - (System.currentTimeMillis() - start);
        return Math.max(0, (int) Math.ceil(remaining / 1000.0));
    }

    public void onMovedInExtractZone(Player player, BreachInstance instance) {
        if (player == null || instance == null) {
            return;
        }
        if (instance.state() != BreachState.ACTIVE || instance.hasExtracted(player.getUniqueId())) {
            clearPlayer(player);
            return;
        }
        if (!instance.isInOpenExtractZone(player.getLocation())) {
            boolean wasExtracting = extractStartMillis.remove(player.getUniqueId()) != null;
            lastCountdownSeconds.remove(player.getUniqueId());
            if (wasExtracting) {
                ExtractFeedback.cancelled(core, player, "extraction.title.extract_cancelled_zone_closed");
            }
            return;
        }
        if (isCombatTagged(player)) {
            extractStartMillis.remove(player.getUniqueId());
            lastCountdownSeconds.remove(player.getUniqueId());
            return;
        }
        Long previous = extractStartMillis.putIfAbsent(player.getUniqueId(), System.currentTimeMillis());
        if (previous == null) {
            ExtractFeedback.entered(core, scheduler, player);
            int dwellSeconds = Math.max(1, configService.extractDwellSeconds());
            lastCountdownSeconds.put(player.getUniqueId(), dwellSeconds);
            ExtractFeedback.countdownSecond(core, scheduler, player, dwellSeconds);
        }
    }

    public void onLeftExtractZone(Player player) {
        if (player == null) {
            return;
        }
        if (extractStartMillis.remove(player.getUniqueId()) != null) {
            lastCountdownSeconds.remove(player.getUniqueId());
            ExtractFeedback.leftZone(core, player);
        }
    }

    private void tick(BreachEngine engine) {
        this.scanExtractZoneEntries(engine);
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
            // Coordination across all raiders (they span many regions): global thread, with
            // per-player mutations dispatched via runOnPlayer inside. The old spawn-anchor hop
            // added a scheduling bounce and loaded the busiest region with map-wide scans.
            tickInstanceExtracts(engine, instance, now, dwellMillis);
        }
    }

    /**
     * Starts extraction for raiders already standing in the extract zone when their combat tag expires (movement is
     * not required). Idempotent via {@link #onMovedInExtractZone}'s {@code putIfAbsent}.
     */
    private void scanExtractZoneEntries(BreachEngine engine) {
        for (BreachInstance instance : engine.activeInstances()) {
            if (instance.state() != BreachState.ACTIVE) {
                continue;
            }
            World world = instance.world();
            if (world == null) {
                continue;
            }
            // Same as tickInstanceExtracts: tolerated reads + runOnPlayer dispatch — no region hop.
            this.scanInstanceExtractZoneEntries(instance);
        }
    }

    private void scanInstanceExtractZoneEntries(BreachInstance instance) {
        for (UUID playerId : instance.participantsSnapshot()) {
            if (instance.hasExtracted(playerId) || extractStartMillis.containsKey(playerId)) {
                continue;
            }
            Player player = pluginPlayer(playerId);
            if (player == null || !player.isOnline()) {
                continue;
            }
            if (!instance.isInOpenExtractZone(player.getLocation()) || isCombatTagged(player)) {
                continue;
            }
            this.scheduler.runOnPlayer(player, () -> onMovedInExtractZone(player, instance));
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
                lastCountdownSeconds.remove(playerId);
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

        if (!instance.isInOpenExtractZone(player.getLocation())) {
            onLeftExtractZone(player);
            return;
        }

        if (isCombatTagged(player)) {
            cancelExtract(player, "extraction.extract.cancelled.combat_tagged", true);
            return;
        }

        long elapsed = now - start;
        if (elapsed >= dwellMillis) {
            extractStartMillis.remove(playerId);
            lastCountdownSeconds.remove(playerId);
            engine.completeExtract(player, instance);
            return;
        }

        // Boss bar rendering happens in BreachHudProvider#bossBar (single shared bar with
        // fixed sections); this tick only drives the per-second countdown feedback.
        int remainingSeconds = (int) Math.ceil((dwellMillis - elapsed) / 1000.0);
        Integer lastShown = lastCountdownSeconds.put(playerId, remainingSeconds);
        if (lastShown == null || lastShown.intValue() != remainingSeconds) {
            ExtractFeedback.countdownSecond(core, scheduler, player, remainingSeconds);
        }
    }

    private void cancelExtract(Player player, String chatCatalogKey, boolean withFeedback) {
        extractStartMillis.remove(player.getUniqueId());
        lastCountdownSeconds.remove(player.getUniqueId());
        if (chatCatalogKey != null && !chatCatalogKey.isBlank()) {
            player.sendMessage(ExtractionTexts.miniMessage(player, chatCatalogKey));
        }
        if (withFeedback) {
            String titleReason = switch (chatCatalogKey == null ? "" : chatCatalogKey) {
                case "extraction.extract.cancelled.entered_combat" ->
                        "extraction.title.extract_cancelled_combat";
                case "extraction.extract.cancelled.combat_tagged" ->
                        "extraction.title.extract_cancelled_combat";
                default -> "extraction.title.extract_cancelled_subtitle";
            };
            ExtractFeedback.cancelled(core, player, titleReason);
        }
    }

    private Player pluginPlayer(UUID playerId) {
        return org.bukkit.Bukkit.getPlayer(playerId);
    }
}
