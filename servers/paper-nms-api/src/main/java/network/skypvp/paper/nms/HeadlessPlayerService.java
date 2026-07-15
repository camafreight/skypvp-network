package network.skypvp.paper.nms;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Spawns and manages "headless" real players: genuine server-side player entities that remain in the
 * server's online player list with a silent, connectionless network handler. Because they are real, online
 * {@code Player} objects they flow through every existing gameplay system unchanged (damage events, shield
 * absorption, elimination math, WeaponMechanics hitscan, tab list, etc.) — the disconnected player is simply
 * "hung" in the world awaiting reconnection instead of being removed.
 *
 * <p>The single implementation ({@code network.skypvp.paper.nms.impl.NmsHeadlessPlayerService}) lives in the
 * paperweight {@code servers:paper-nms} module and is loaded reflectively by paper-core so the rest of the
 * network can keep compiling against paper-api only. {@link NoopHeadlessPlayerService} is the fallback when
 * NMS wiring is unavailable.
 */
public interface HeadlessPlayerService {

    /** True when the NMS backend initialised successfully and headless players can be spawned. */
    boolean isAvailable();

    /**
     * Hangs the given player's existing {@code ServerPlayer} online after disconnect (Variant 3): the same object is
     * revived if vanilla removal already ran, its network handler is swapped for an inert fake connection, and it
     * stays in the online player list. Must run on the player's Folia region thread (typically deferred to the tick
     * after {@code PlayerQuitEvent}).
     *
     * @return true if the existing body was hung successfully.
     */
    boolean hang(Player player);

    /**
     * Tries {@link #hang(Player)} first; if that fails, falls back to {@link #spawn(HeadlessPlayerSpec)} (Variant 2).
     */
    default boolean hangOrSpawn(Player player, HeadlessPlayerSpec fallbackSpec) {
        if (player != null && this.hang(player)) {
            return true;
        }
        return fallbackSpec != null && this.spawn(fallbackSpec);
    }

    /**
     * Spawns a fresh connectionless online player using the given profile/uuid, equipped and health-set (Variant 2
     * fallback). The uuid SHOULD match the real (now-disconnected) player so that {@code Bukkit.getPlayer(uuid)}
     * resolves to the headless body and all uuid-keyed gameplay continues to work.
     *
     * @return true if the headless player was created and added to the online player list.
     */
    boolean spawn(HeadlessPlayerSpec spec);

    /** True if a headless player with this id currently exists. */
    boolean exists(UUID id);

    /** True if the given online player id is one of our headless bodies (not a genuine connected client). */
    boolean isHeadless(UUID id);

    /** Reads the current live state (location, health, inventory) of a headless body without removing it. */
    Optional<HeadlessSnapshot> capture(UUID id);

    /** Captures the current live state and then despawns/removes the headless body. */
    Optional<HeadlessSnapshot> removeAndCapture(UUID id);

    /** Despawns and removes the headless body. Returns true if one was present. */
    boolean remove(UUID id);

    /** True when this uuid still occupies a slot in the server player list (blocks real login). */
    default boolean occupiesLoginSlot(UUID id) {
        return this.exists(id) || (id != null && org.bukkit.Bukkit.getPlayer(id) != null);
    }

    /**
     * Forcefully clears a hung body from the player list. Intended for pre-login eviction when a normal remove was not
     * enough to free the uuid slot.
     */
    boolean forceClearLoginSlot(UUID id);

    /**
     * Hands back the body's live state for the resume-after-reconnect path. If the vanilla duplicate-login kick
     * already despawned the body (the normal seamless flow), this returns the state captured at that moment without
     * touching the world. If the body is somehow still online, it is evicted on its Folia region thread first (may
     * block the caller briefly).
     */
    Optional<HeadlessSnapshot> evictForLogin(UUID id, Location regionHint);

    /** Snapshot of all active headless body ids. */
    Set<UUID> activeIds();

    /** Removes every headless body (plugin shutdown). */
    void shutdown();
}
