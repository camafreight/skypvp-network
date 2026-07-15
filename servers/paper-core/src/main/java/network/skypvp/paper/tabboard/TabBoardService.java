package network.skypvp.paper.tabboard;

import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import network.skypvp.paper.library.packet.PacketEventsBridge;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;

/**
 * Injects the per-viewer 4x20 tab-board grid via PacketEvents.
 *
 * <p>Each apply diffs against the viewer's cached cells and sends at most three batched
 * packets: one remove (stale cells), one add (new cells, carrying skins), and one update
 * (cells whose text or latency changed). Unchanged cells cost no traffic.</p>
 */
public final class TabBoardService implements Listener {

    private static final EnumSet<WrapperPlayServerPlayerInfoUpdate.Action> ADD_ACTIONS = EnumSet.of(
            WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
            WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED,
            WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LATENCY,
            WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_DISPLAY_NAME,
            WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE
    );
    private static final EnumSet<WrapperPlayServerPlayerInfoUpdate.Action> UPDATE_ACTIONS = EnumSet.of(
            WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LATENCY,
            WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_DISPLAY_NAME
    );

    private final Plugin plugin;

    /** Last cell state sent to a viewer, keyed by fake profile id. */
    private record CachedCell(Component displayName, int latencyMs) {
    }

    private final Map<UUID, Map<UUID, CachedCell>> viewerCells = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> realPlayersHidden = new ConcurrentHashMap<>();
    /** Bound by the plugin: schedules a prompt board re-apply for one viewer after a client reset. */
    private volatile java.util.function.Consumer<Player> reapplyHook;

    public TabBoardService(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Wires the callback used to re-apply the board right after respawn/world-change resets. */
    public void bindReapply(java.util.function.Consumer<Player> hook) {
        this.reapplyHook = hook;
    }

    /** {@code true} when the packet layer required for fake tab rows is present and loaded. */
    public static boolean isOperational() {
        return PacketEventsBridge.isAvailable();
    }

    public void apply(Player viewer, TabBoardSpec spec) {
        if (viewer == null || !viewer.isOnline() || !isOperational()) {
            return;
        }
        TabBoardSpec safe = spec == null ? TabBoardSpec.empty() : spec;
        Map<UUID, CachedCell> previous = viewerCells.getOrDefault(viewer.getUniqueId(), Map.of());
        Map<UUID, CachedCell> next = new HashMap<>(safe.entries().size() * 2);

        List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> adds = new ArrayList<>();
        List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> updates = new ArrayList<>();
        for (TabBoardEntry entry : safe.entries()) {
            CachedCell cell = new CachedCell(entry.tabDisplayName(), Math.max(0, entry.latencyMs()));
            next.put(entry.profileId(), cell);
            CachedCell before = previous.get(entry.profileId());
            if (before == null) {
                adds.add(playerInfo(entry));
            } else if (!before.equals(cell)) {
                updates.add(playerInfo(entry));
            }
        }

        List<UUID> stale = new ArrayList<>();
        for (UUID profileId : previous.keySet()) {
            if (!next.containsKey(profileId)) {
                stale.add(profileId);
            }
        }

        if (!stale.isEmpty()) {
            PacketEventsBridge.send(viewer, new WrapperPlayServerPlayerInfoRemove(stale),
                    plugin.getLogger(), "tab-board-remove");
        }
        if (!adds.isEmpty()) {
            PacketEventsBridge.send(viewer, new WrapperPlayServerPlayerInfoUpdate(ADD_ACTIONS, adds),
                    plugin.getLogger(), "tab-board-add");
        }
        if (!updates.isEmpty()) {
            PacketEventsBridge.send(viewer, new WrapperPlayServerPlayerInfoUpdate(UPDATE_ACTIONS, updates),
                    plugin.getLogger(), "tab-board-update");
        }
        viewerCells.put(viewer.getUniqueId(), next);
        hideRealPlayers(viewer);
    }

    private static WrapperPlayServerPlayerInfoUpdate.PlayerInfo playerInfo(TabBoardEntry entry) {
        UserProfile profile = new UserProfile(entry.profileId(), entry.profileName(), List.of());
        if (entry.hasTexture()) {
            profile.setTextureProperties(List.of(new TextureProperty(
                    "textures",
                    entry.textureValue(),
                    entry.textureSignature() == null ? "" : entry.textureSignature()
            )));
        }
        return new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                profile,
                entry.listed(),
                Math.max(0, entry.latencyMs()),
                GameMode.SURVIVAL,
                entry.tabDisplayName(),
                null
        );
    }

    /** Hides vanilla tab rows so only the tab-board canvas is visible to this viewer. */
    public void hideRealPlayers(Player viewer) {
        if (viewer == null) {
            return;
        }
        // Idempotent: newcomers are handled by onJoin, and re-sending UPDATE_LISTED for
        // every online player on every refresh is pure packet waste. A client reset
        // (respawn/world change) clears this flag via invalidateClientState.
        // showPlayer after hidePlayer (extraction tab scoping) also re-lists real rows —
        // callers must {@link #rehideRealPlayersIfBoardActive} after those mutations.
        if (Boolean.TRUE.equals(realPlayersHidden.get(viewer.getUniqueId()))) {
            return;
        }
        setAllRealPlayersListed(viewer, false);
        realPlayersHidden.put(viewer.getUniqueId(), true);
    }

    /**
     * Re-sends {@code UPDATE_LISTED=false} for every real player when the tab-board owns this
     * viewer's tab. Required after {@code Player#showPlayer}: Bukkit re-lists the shown player
     * client-side while our idempotent hide flag would otherwise skip the fix forever.
     */
    public void rehideRealPlayersIfBoardActive(Player viewer) {
        if (viewer == null || !viewer.isOnline() || !isBoardActive(viewer.getUniqueId())) {
            return;
        }
        realPlayersHidden.remove(viewer.getUniqueId());
        hideRealPlayers(viewer);
    }

    /**
     * Forgets everything we believe the viewer's client has (fake cells, hidden real rows)
     * WITHOUT sending packets. The next {@link #apply} then performs a full resend — the
     * recovery path for client-visible resets (respawn, world transfer, plugin interference)
     * where the diff cache would otherwise skip "already sent" state forever.
     */
    public void invalidateClientState(Player viewer) {
        if (viewer == null) {
            return;
        }
        viewerCells.remove(viewer.getUniqueId());
        realPlayersHidden.remove(viewer.getUniqueId());
    }

    public void restoreRealPlayers(Player viewer) {
        if (viewer == null || !PacketEventsBridge.isAvailable()) {
            return;
        }
        if (!Boolean.TRUE.equals(realPlayersHidden.remove(viewer.getUniqueId()))) {
            return;
        }
        setAllRealPlayersListed(viewer, true);
    }

    private void setAllRealPlayersListed(Player viewer, boolean listed) {
        if (viewer == null || !viewer.isOnline() || !PacketEventsBridge.isAvailable()) {
            return;
        }
        List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> infos = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online == null || !online.isOnline()) {
                continue;
            }
            infos.add(new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                    new UserProfile(online.getUniqueId(), profileName(online.getName()), List.of()),
                    listed,
                    0,
                    GameMode.SURVIVAL,
                    Component.empty(),
                    null
            ));
        }
        if (infos.isEmpty()) {
            return;
        }
        WrapperPlayServerPlayerInfoUpdate packet = new WrapperPlayServerPlayerInfoUpdate(
                EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED),
                infos
        );
        PacketEventsBridge.send(viewer, packet, plugin.getLogger(), "tab-board-listed");
    }

    public boolean isBoardActive(UUID viewerId) {
        Map<UUID, CachedCell> cells = viewerCells.get(viewerId);
        return cells != null && !cells.isEmpty();
    }

    public void clear(Player viewer) {
        if (viewer == null) {
            return;
        }
        restoreRealPlayers(viewer);
        Map<UUID, CachedCell> previous = viewerCells.remove(viewer.getUniqueId());
        if (previous != null && !previous.isEmpty() && viewer.isOnline() && PacketEventsBridge.isAvailable()) {
            PacketEventsBridge.send(viewer, new WrapperPlayServerPlayerInfoRemove(new ArrayList<>(previous.keySet())),
                    plugin.getLogger(), "tab-board-remove");
        }
    }

    /** Stable fake profile id for a logical tab-board cell (grid position + occupant). */
    public static UUID profileId(String namespace, String key) {
        return UUID.nameUUIDFromBytes((namespace + ":" + key).getBytes(StandardCharsets.UTF_8));
    }

    /** Valid 16-char profile name for packet tab entries. */
    public static String profileName(String seed) {
        String sanitized = seed == null ? "row" : seed.replaceAll("[^a-zA-Z0-9_]", "");
        if (sanitized.isEmpty()) {
            sanitized = "row";
        }
        if (sanitized.length() > 16) {
            sanitized = sanitized.substring(0, 16);
        }
        return sanitized;
    }

    /** Patches listed state for a single real player as seen by one viewer. */
    public void setListed(Player viewer, UUID profileId, String profileName, boolean listed) {
        if (viewer == null || profileId == null || !viewer.isOnline() || !PacketEventsBridge.isAvailable()) {
            return;
        }
        UserProfile profile = new UserProfile(profileId, profileName(profileName), List.of());
        WrapperPlayServerPlayerInfoUpdate.PlayerInfo info = new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                profile,
                listed,
                0,
                GameMode.SURVIVAL,
                Component.empty(),
                null
        );
        WrapperPlayServerPlayerInfoUpdate packet = new WrapperPlayServerPlayerInfoUpdate(
                EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED),
                info
        );
        PacketEventsBridge.send(viewer, packet, plugin.getLogger(), "tab-board-listed");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player joined = event.getPlayer();
        if (joined == null) {
            return;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer == null || viewer.equals(joined) || !isBoardActive(viewer.getUniqueId())) {
                continue;
            }
            setListed(viewer, joined.getUniqueId(), joined.getName(), false);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (player != null) {
            // The client is gone: drop caches without sending restore/remove packets.
            viewerCells.remove(player.getUniqueId());
            realPlayersHidden.remove(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        handleClientReset(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        handleClientReset(event.getPlayer());
    }

    /**
     * Respawns and world transfers can reset or reorder client tab state (and give other
     * plugins a window to re-list rows). If this viewer had an active board, forget the
     * sent-state cache and ask the plugin to re-apply the full board promptly.
     */
    private void handleClientReset(Player viewer) {
        if (viewer == null || !isBoardActive(viewer.getUniqueId())) {
            return;
        }
        invalidateClientState(viewer);
        java.util.function.Consumer<Player> hook = reapplyHook;
        if (hook != null) {
            hook.accept(viewer);
        }
    }
}
