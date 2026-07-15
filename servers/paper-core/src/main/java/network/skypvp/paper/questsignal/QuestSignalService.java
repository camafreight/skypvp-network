package network.skypvp.paper.questsignal;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.waypoint.Waypoint;
import network.skypvp.shared.ServerTextUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Modular "quest signals": static quests register a {@link QuestSignalProvider}; when a player
 * enters the provider's world (join or world change) the provider is evaluated and, if it has
 * something pending, the service delivers a chat CTA, optional floating NPC shout board, and/or
 * a navigator waypoint (via {@link network.skypvp.paper.waypoint.WaypointNavigatorService}).
 *
 * <p>Convention: waypoints started by a signal use id {@link #waypointIdFor(String)}
 * ({@code quest:<quest-id>}), so quests can be completed/cleared uniformly.
 *
 * <p>Folia-safe: evaluation and delivery run on the player's region thread, delayed a moment
 * after entry so hub spawn teleports settle first.
 */
public final class QuestSignalService implements Listener {

    /** Ticks after world entry before providers run — lets join teleports/warm-ups settle. */
    private static final long ENTRY_SETTLE_TICKS = 40L;

    private final PaperCorePlugin core;
    private final Map<String, QuestSignalProvider> providers = new ConcurrentHashMap<>();
    /** Quest ids that already shouted this session (per player) — skip repeating board/chat on re-entry. */
    private final Map<UUID, Set<String>> shoutedThisSession = new ConcurrentHashMap<>();

    public QuestSignalService(PaperCorePlugin core) {
        this.core = Objects.requireNonNull(core, "core");
        core.getServer().getPluginManager().registerEvents(this, core);
    }

    /** Waypoint id used for quest {@code questId} deliveries: {@code quest:<quest-id>}. */
    public static String waypointIdFor(String questId) {
        return "quest:" + questId;
    }

    /** Registers (or replaces) the provider for its quest id. */
    public void register(QuestSignalProvider provider) {
        Objects.requireNonNull(provider, "provider");
        providers.put(normalize(provider.questId()), provider);
    }

    public void unregister(String questId) {
        if (questId != null) {
            providers.remove(normalize(questId));
        }
    }

    /** Re-evaluates every provider matching the player's current world (e.g. after quest state changed). */
    public void refresh(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        core.platformScheduler().runOnPlayer(player, () -> evaluateAll(player));
    }

    /**
     * Clears the quest's navigator waypoint and ends any active floating shout for the player.
     * Call when the player completed/collected.
     */
    public void complete(Player player, String questId) {
        if (player == null || questId == null) {
            return;
        }
        String normalized = normalize(questId);
        if (core.waypointNavigator() != null) {
            core.waypointNavigator().clear(player, waypointIdFor(normalized));
        }
        if (core.questDialogueService() != null) {
            core.questDialogueService().shouts().end(player);
        }
        Set<String> shouted = shoutedThisSession.get(player.getUniqueId());
        if (shouted != null) {
            shouted.remove(normalized);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        scheduleEvaluation(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        scheduleEvaluation(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        shoutedThisSession.remove(event.getPlayer().getUniqueId());
    }

    private void scheduleEvaluation(Player player) {
        if (providers.isEmpty()) {
            return;
        }
        core.platformScheduler().runOnPlayerLater(player, () -> {
            if (player.isOnline()) {
                evaluateAll(player);
            }
        }, ENTRY_SETTLE_TICKS);
    }

    private void evaluateAll(Player player) {
        String worldName = player.getWorld().getName();
        for (QuestSignalProvider provider : providers.values()) {
            if (!worldName.equalsIgnoreCase(provider.worldName())) {
                continue;
            }
            Optional<QuestSignalDelivery> delivery;
            try {
                delivery = provider.evaluate(player);
            } catch (RuntimeException ex) {
                core.getLogger().warning("[QuestSignal] Provider " + provider.questId() + " failed: " + ex.getMessage());
                continue;
            }
            delivery.ifPresent(resolved -> deliver(player, provider, resolved));
        }
    }

    private void deliver(Player player, QuestSignalProvider provider, QuestSignalDelivery delivery) {
        String questKey = normalize(provider.questId());
        String waypointId = waypointIdFor(questKey);
        boolean alreadyNavigating = core.waypointNavigator() != null
                && core.waypointNavigator().isNavigating(player.getUniqueId(), waypointId);
        Waypoint waypoint = delivery.waypoint();
        if (waypoint != null && core.waypointNavigator() != null) {
            core.waypointNavigator().navigate(player, waypoint);
        }

        Set<String> shouted = shoutedThisSession.computeIfAbsent(
                player.getUniqueId(),
                ignored -> ConcurrentHashMap.newKeySet()
        );
        boolean alreadyShouted = shouted.contains(questKey);
        // Re-entering while the same signal is still live shouldn't repeat shout/chat.
        if (!alreadyNavigating && !alreadyShouted) {
            if (delivery.chatMiniMessage() != null) {
                player.sendMessage(ServerTextUtil.miniMessageComponent(delivery.chatMiniMessage()));
            }
            if (delivery.hasShout() && core.questDialogueService() != null) {
                org.bukkit.Location anchor = waypoint != null
                        ? waypoint.toLocation(player.getWorld())
                        : null;
                String speaker = delivery.npcDisplayName() != null
                        ? delivery.npcDisplayName()
                        : provider.questId();
                core.questDialogueService().shouts().shout(player, anchor, speaker, delivery.shoutLines());
            }
            shouted.add(questKey);
        }
    }

    private static String normalize(String questId) {
        return questId == null ? "" : questId.trim().toLowerCase(Locale.ROOT);
    }
}
