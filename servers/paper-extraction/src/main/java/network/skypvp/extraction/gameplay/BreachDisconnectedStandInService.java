package network.skypvp.extraction.gameplay;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.nms.HeadlessPlayerService;
import network.skypvp.paper.nms.HeadlessPlayerSpec;
import network.skypvp.paper.nms.HeadlessSnapshot;
import network.skypvp.paper.platform.ServerPlatform;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Keeps a disconnected mid-raid raider online as a real headless {@link Player} body (Variant 3: the same
 * {@code ServerPlayer} object is hung with a fake connection; Variant 2 fresh spawn is the fallback). The body takes
 * real damage, depletes shields, and fires {@link org.bukkit.event.entity.PlayerDeathEvent} when killed.
 *
 * <p>Deliberately NO visual marker (no label, no tab flag): revealing that a raider is AFK would give away their
 * position and defenselessness in an extraction gamemode.
 *
 * <p>Reconnect handoff (Folia): Folia removed vanilla's "kick the old player and wait" duplicate-login flow — instead
 * {@code ServerConfigurationPacketListenerImpl.handleConfigurationFinished} REJECTS the reconnecting client with
 * "You logged in from another location" while the uuid still occupies the player list. The body is therefore evicted
 * from {@code AsyncPlayerConnectionConfigureEvent} via {@link #evictForReconnect(UUID)} — the last plugin-blockable
 * configuration task, after registry sync and right before spawn preparation — which blocks until the despawn
 * finished and stashes the live state (health/position/inventory) for {@link #consumeReconnectCapture(UUID)}. The
 * body is only gone for the final ~1 network round-trip of the reconnect — the tightest handoff Folia allows without
 * patching the server itself.
 *
 * <p>Gear stays escrowed in the persistent RAID inventory container; reconnect reloads it untouched. Only headless
 * death (or grace expiry) converts the escrow into a lootable corpse.
 */
public final class BreachDisconnectedStandInService {

    private final JavaPlugin plugin;
    private final ServerPlatform scheduler;
    private final PaperCorePlugin core;
    private final Map<UUID, StandIn> standInsByOwner = new ConcurrentHashMap<>();
    /** Live body state captured by the pre-login eviction, consumed by the join-time resume. */
    private final Map<UUID, HeadlessSnapshot> reconnectCaptures = new ConcurrentHashMap<>();

    public BreachDisconnectedStandInService(JavaPlugin plugin, ServerPlatform scheduler, PaperCorePlugin core) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.core = Objects.requireNonNull(core, "core");
    }

    public void start() {
        // No periodic work: bodies are event-driven and intentionally unmarked.
    }

    public void shutdown() {
        for (UUID ownerId : this.standInsByOwner.keySet().toArray(UUID[]::new)) {
            this.remove(ownerId);
        }
        this.reconnectCaptures.clear();
    }

    /** Removes every stand-in tied to a recycled or shut-down breach instance. */
    public void removeForInstance(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            return;
        }
        for (UUID ownerId : this.standInsByOwner.keySet().toArray(UUID[]::new)) {
            StandIn standIn = this.standInsByOwner.get(ownerId);
            if (standIn != null && instanceId.equals(standIn.instanceId())) {
                this.remove(ownerId);
            }
        }
    }

    /**
     * Hangs the disconnecting raider online at their current location. Must be invoked during {@code PlayerQuitEvent}
     * while the {@code Player} reference is still valid; the actual NMS hang is deferred to the next tick on the region
     * thread so vanilla quit removal can finish first.
     */
    public void spawn(Player owner, String instanceId, ItemStack[] lootSnapshot) {
        if (owner == null || owner.getWorld() == null) {
            return;
        }
        HeadlessPlayerService headless = this.headlessService();
        if (headless == null || !headless.isAvailable()) {
            this.plugin.getLogger().warning("[Disconnected] Headless player backend unavailable; cannot hang " + owner.getName());
            return;
        }

        UUID ownerId = owner.getUniqueId();
        this.remove(ownerId);

        Location location = owner.getLocation().clone();
        String ownerName = owner.getName();
        String textureValue = null;
        String textureSignature = null;
        PlayerProfile profile = owner.getPlayerProfile();
        for (ProfileProperty property : profile.getProperties()) {
            if ("textures".equals(property.getName())) {
                textureValue = property.getValue();
                textureSignature = property.getSignature();
                break;
            }
        }

        ItemStack[] contentsClone = cloneContents(owner.getInventory().getContents());
        HeadlessPlayerSpec fallback = new HeadlessPlayerSpec(
                ownerId,
                ownerName,
                textureValue,
                textureSignature,
                location.clone(),
                Math.max(1.0D, owner.getHealth()),
                BreachPlayerVitality.RAID_MAX_HEALTH,
                contentsClone);

        StandIn standIn = new StandIn(
                ownerId,
                ownerName,
                instanceId,
                location.getWorld().getUID(),
                location.clone(),
                lootSnapshot,
                textureValue,
                textureSignature,
                System.currentTimeMillis());
        this.standInsByOwner.put(ownerId, standIn);

        Player ownerRef = owner;
        Runnable hangTask = () -> {
            if (!this.standInsByOwner.containsKey(ownerId)) {
                return;
            }
            // Instant-rejoin race: the real player is already back online — no body needed, the join
            // resume path handles the rest.
            Player current = Bukkit.getPlayer(ownerId);
            if (current != null && current.isOnline() && !headless.isHeadless(ownerId)) {
                return;
            }
            if (!headless.hangOrSpawn(ownerRef, fallback)) {
                this.standInsByOwner.remove(ownerId);
                this.plugin.getLogger().warning("[Disconnected] Failed to hang/spawn headless body for " + ownerName);
                return;
            }
            this.plugin.getLogger().info("[Disconnected] Hung headless body for " + ownerName + " in " + instanceId
                    + " at " + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ());
        };
        // Wait for Folia global connection cleanup before hanging the body on its region thread.
        this.scheduler.runGlobalLater(() -> this.scheduler.runAtLocation(location, hangTask), 1L);
    }

    /**
     * Despawns the hung body so the reconnecting owner passes Folia's config-phase duplicate-uuid check. Must be
     * called from a login/configuration hook that completes BEFORE {@code handleConfigurationFinished} runs
     * (currently {@code AsyncPlayerConnectionConfigureEvent}, whose thread is safe to block); Folia rejects the NEW
     * connection with "You logged in from another location" while the uuid is still in the player list. Never
     * throws: on failure the login simply bounces and can be retried.
     */
    public void evictForReconnect(UUID ownerId) {
        if (ownerId == null) {
            return;
        }
        HeadlessPlayerService headless = this.headlessService();
        if (headless == null) {
            return;
        }
        if (!headless.isHeadless(ownerId) && !this.standInsByOwner.containsKey(ownerId)) {
            return;
        }
        try {
            StandIn standIn = this.standInsByOwner.get(ownerId);
            Location hint = standIn != null ? standIn.location() : null;
            headless.evictForLogin(ownerId, hint)
                    .ifPresent(snapshot -> this.reconnectCaptures.put(ownerId, snapshot));
            if (headless.isHeadless(ownerId) && headless.occupiesLoginSlot(ownerId)) {
                headless.forceClearLoginSlot(ownerId);
            }
            boolean slotFree = !headless.occupiesLoginSlot(ownerId);
            this.plugin.getLogger().info("[Disconnected] Evicted hung body for reconnecting owner " + ownerId
                    + " (slot free: " + slotFree + ")");
        } catch (RuntimeException exception) {
            this.plugin.getLogger().warning("[Disconnected] Reconnect eviction failed for " + ownerId + ": "
                    + exception.getMessage());
        }
    }

    /**
     * Live state of the body at the moment it was evicted — stored by {@link #evictForReconnect(UUID)}, or (Paper
     * backends only) captured by the duplicate-login kick hook. Falls back to evicting a still-online body.
     */
    public Optional<HeadlessSnapshot> consumeReconnectCapture(UUID ownerId) {
        if (ownerId == null) {
            return Optional.empty();
        }
        HeadlessSnapshot local = this.reconnectCaptures.remove(ownerId);
        if (local != null) {
            return Optional.of(local);
        }
        HeadlessPlayerService headless = this.headlessService();
        if (headless == null) {
            return Optional.empty();
        }
        StandIn standIn = this.standInsByOwner.get(ownerId);
        Location hint = standIn != null ? standIn.location() : null;
        return headless.evictForLogin(ownerId, hint);
    }

    /** True if the given online player is a hung AFK raider body. */
    public boolean isHeadlessBody(Player player) {
        if (player == null) {
            return false;
        }
        HeadlessPlayerService headless = this.headlessService();
        return headless != null
                && headless.isHeadless(player.getUniqueId())
                && this.standInsByOwner.containsKey(player.getUniqueId());
    }

    public Optional<StandIn> byOwner(UUID ownerId) {
        return ownerId == null ? Optional.empty() : Optional.ofNullable(this.standInsByOwner.get(ownerId));
    }

    public boolean hasStandIn(UUID ownerId) {
        return ownerId != null && this.standInsByOwner.containsKey(ownerId);
    }

    /** Despawns the hung body and forgets the stand-in. Returns the removed record, if any. */
    public Optional<StandIn> remove(UUID ownerId) {
        if (ownerId != null) {
            // Resume consumes its capture before calling remove; anything left here is stale (elimination, reset).
            this.reconnectCaptures.remove(ownerId);
        }
        StandIn standIn = ownerId == null ? null : this.standInsByOwner.remove(ownerId);
        if (standIn == null) {
            return Optional.empty();
        }
        HeadlessPlayerService headless = this.headlessService();
        if (headless != null) {
            headless.remove(ownerId);
        }
        return Optional.of(standIn);
    }

    private HeadlessPlayerService headlessService() {
        return this.core == null ? null : this.core.headlessPlayerService();
    }

    private static ItemStack[] cloneContents(ItemStack[] contents) {
        if (contents == null) {
            return new ItemStack[0];
        }
        ItemStack[] cloned = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            cloned[i] = stack == null ? null : stack.clone();
        }
        return cloned;
    }

    /** Immutable record describing one active AFK stand-in. */
    public record StandIn(
            UUID ownerId,
            String ownerName,
            String instanceId,
            UUID worldId,
            Location location,
            ItemStack[] lootSnapshot,
            String textureValue,
            String textureSignature,
            long disconnectedSinceMillis
    ) {
    }
}
