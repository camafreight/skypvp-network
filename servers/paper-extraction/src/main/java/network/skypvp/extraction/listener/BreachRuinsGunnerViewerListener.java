package network.skypvp.extraction.listener;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.gameplay.BreachRuinsMobNametagService;
import network.skypvp.extraction.gameplay.BreachRuinsRaiderAiService;
import network.skypvp.paper.PaperCorePlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Re-syncs Ruins gunner disguises and nametags when raiders load mobs after death, spectator, or reconnect. */
public final class BreachRuinsGunnerViewerListener implements Listener {

    private static final long CHUNK_RESYNC_COOLDOWN_MS = 750L;
    private static final double RESYNC_RADIUS_SQ = 72.0D * 72.0D;

    private final PaperCorePlugin core;
    private final BreachEngine breachEngine;
    private final BreachRuinsRaiderAiService raiderAiService;
    private final BreachRuinsMobNametagService nametagService;
    private final Map<UUID, Long> lastChunkResyncAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastChunkKey = new ConcurrentHashMap<>();

    public BreachRuinsGunnerViewerListener(
            PaperCorePlugin core,
            BreachEngine breachEngine,
            BreachRuinsRaiderAiService raiderAiService,
            BreachRuinsMobNametagService nametagService
    ) {
        this.core = Objects.requireNonNull(core, "core");
        this.breachEngine = Objects.requireNonNull(breachEngine, "breachEngine");
        this.raiderAiService = Objects.requireNonNull(raiderAiService, "raiderAiService");
        this.nametagService = Objects.requireNonNull(nametagService, "nametagService");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        lastChunkResyncAt.remove(playerId);
        lastChunkKey.remove(playerId);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        scheduleViewerSync(event.getPlayer(), 10L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRespawn(PlayerRespawnEvent event) {
        scheduleViewerSync(event.getPlayer(), 8L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        scheduleViewerSync(event.getPlayer(), 6L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        scheduleViewerSync(event.getPlayer(), 6L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) {
            return;
        }
        if (event.getFrom().getChunk().equals(event.getTo().getChunk())) {
            return;
        }
        Player player = event.getPlayer();
        if (!breachEngine.instanceFor(player).isPresent()) {
            return;
        }
        long chunkKey = packChunk(event.getTo().getChunk().getX(), event.getTo().getChunk().getZ());
        Long previous = lastChunkKey.put(player.getUniqueId(), chunkKey);
        if (previous != null && previous == chunkKey) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastChunkResyncAt.getOrDefault(player.getUniqueId(), 0L) < CHUNK_RESYNC_COOLDOWN_MS) {
            return;
        }
        lastChunkResyncAt.put(player.getUniqueId(), now);
        scheduleViewerSync(player, 2L);
    }

    private void scheduleViewerSync(Player player, long delayTicks) {
        if (player == null || core.platformScheduler() == null) {
            return;
        }
        core.platformScheduler().runGlobalLater(() -> syncViewer(player), delayTicks);
    }

    private void syncViewer(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (!breachEngine.instanceFor(player).isPresent()) {
            return;
        }
        raiderAiService.syncViewer(player, RESYNC_RADIUS_SQ);
        nametagService.syncViewer(player, RESYNC_RADIUS_SQ);
    }

    private static long packChunk(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }
}
