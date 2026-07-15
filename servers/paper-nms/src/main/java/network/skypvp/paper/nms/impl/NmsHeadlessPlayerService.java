package network.skypvp.paper.nms.impl;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import network.skypvp.paper.nms.HeadlessPlayerService;
import network.skypvp.paper.nms.HeadlessPlayerSpec;
import network.skypvp.paper.nms.HeadlessSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * NMS-backed {@link HeadlessPlayerService} for Folia/Paper 1.21.11. Prefers Variant 3 ({@link #hang}) which keeps the
 * original {@link ServerPlayer} object online; {@link #spawn} is the Variant 2 fallback when hang cannot succeed.
 */
public final class NmsHeadlessPlayerService implements HeadlessPlayerService {

    private final Plugin plugin;
    private final MinecraftServer server;
    private final PlayerList playerList;
    private final boolean available;
    private final Set<UUID> headlessIds = ConcurrentHashMap.newKeySet();
    /** State captured when a body was despawned by the vanilla duplicate-login kick before evictForLogin ran. */
    private final Map<UUID, HeadlessSnapshot> pendingEvictionCaptures = new ConcurrentHashMap<>();

    public NmsHeadlessPlayerService(Plugin plugin) {
        this.plugin = plugin;
        MinecraftServer resolvedServer = null;
        PlayerList resolvedList = null;
        boolean ok = false;
        try {
            resolvedServer = ((CraftServer) Bukkit.getServer()).getServer();
            resolvedList = resolvedServer.getPlayerList();
            ok = resolvedList != null;
        } catch (Throwable ignored) {
            ok = false;
        }
        this.server = resolvedServer;
        this.playerList = resolvedList;
        this.available = ok;
    }

    @Override
    public boolean isAvailable() {
        return this.available;
    }

    @Override
    public boolean hang(Player player) {
        if (!this.available || player == null) {
            return false;
        }
        UUID id = player.getUniqueId();
        if (this.headlessIds.contains(id)) {
            ServerPlayer existing = this.playerList.getPlayer(id);
            if (existing != null) {
                HeadlessPlayerRegionTasks.run(this.plugin, player.getLocation(), () -> HeadlessPlayerPlacement.ensureCombatReady(existing));
            }
            return existing != null;
        }
        Location location = player.getLocation();
        Boolean hung = HeadlessPlayerRegionTasks.call(this.plugin, location, () -> {
            ServerPlayer handle = ((CraftPlayer) player).getHandle();
            if (!HeadlessPlayerPlacement.hang(this.server, this.playerList, handle, () -> this.onForcedKick(id))) {
                return Boolean.FALSE;
            }
            this.headlessIds.add(id);
            return Boolean.TRUE;
        });
        return Boolean.TRUE.equals(hung);
    }

    /**
     * The vanilla duplicate-login flow kicked this hung body because its real owner is logging back in. The login
     * thread polls {@code WAITING_FOR_DUPE_DISCONNECT} until the uuid leaves the player list, so despawn immediately
     * (region-safe, non-blocking) and stash the live state for the resume path.
     */
    private void onForcedKick(UUID id) {
        ServerPlayer player = this.playerList.getPlayer(id);
        if (player == null) {
            this.headlessIds.remove(id);
            return;
        }
        Location location = player.getBukkitEntity().getLocation();
        HeadlessPlayerRegionTasks.run(this.plugin, location, () -> {
            ServerPlayer live = this.playerList.getPlayer(id);
            if (live == null) {
                this.headlessIds.remove(id);
                return;
            }
            try {
                this.snapshotOnRegion(id, live).ifPresent(snapshot -> this.pendingEvictionCaptures.put(id, snapshot));
            } catch (RuntimeException exception) {
                this.plugin.getLogger().warning("[Headless] State capture failed for " + id + ": " + exception.getMessage());
            }
            try {
                this.despawnOnRegion(id, live);
            } finally {
                this.headlessIds.remove(id);
            }
            this.plugin.getLogger().info("[Headless] Duplicate-login kick evicted hung body for " + id);
        });
    }

    @Override
    public boolean spawn(HeadlessPlayerSpec spec) {
        if (!this.available || spec == null || this.headlessIds.contains(spec.id())) {
            return false;
        }
        Location location = spec.location();
        UUID id = spec.id();
        ServerPlayer created = HeadlessPlayerRegionTasks.call(this.plugin, location, () -> {
            ServerPlayer player = HeadlessPlayerPlacement.spawn(this.server, this.playerList, spec, () -> this.onForcedKick(id));
            if (player != null) {
                this.headlessIds.add(spec.id());
            }
            return player;
        });
        return created != null;
    }

    @Override
    public boolean exists(UUID id) {
        return id != null && this.playerList.getPlayer(id) != null;
    }

    @Override
    public boolean isHeadless(UUID id) {
        return id != null && this.headlessIds.contains(id);
    }

    @Override
    public boolean occupiesLoginSlot(UUID id) {
        return id != null && this.playerList.getPlayer(id) != null;
    }

    @Override
    public boolean forceClearLoginSlot(UUID id) {
        if (!this.available || id == null) {
            return true;
        }
        ServerPlayer player = this.playerList.getPlayer(id);
        if (player == null) {
            this.headlessIds.remove(id);
            return true;
        }
        Location location = player.getBukkitEntity().getLocation();
        HeadlessPlayerRegionTasks.call(this.plugin, location, () -> {
            this.despawnOnRegion(id, this.playerList.getPlayer(id));
            return null;
        });
        this.headlessIds.remove(id);
        boolean cleared = this.playerList.getPlayer(id) == null;
        if (!cleared) {
            // NEVER fall back to a global/off-region despawn here: tearing the body down off its
            // owning region half-removes the connection and Folia's next tickConnections halts the
            // whole server (this crashed the extraction pod). Leaving the slot occupied is safe —
            // the vanilla duplicate-login flow keeps polling and the owner's next attempt retries.
            org.bukkit.Bukkit.getLogger().warning("[Headless] Login slot for " + id
                    + " still occupied after region despawn; deferring to the duplicate-login retry.");
        }
        return cleared;
    }

    @Override
    public Optional<HeadlessSnapshot> capture(UUID id) {
        if (!this.available || id == null || !this.headlessIds.contains(id)) {
            return Optional.empty();
        }
        ServerPlayer player = this.playerList.getPlayer(id);
        if (player == null) {
            return Optional.empty();
        }
        return HeadlessPlayerRegionTasks.call(this.plugin, player.getBukkitEntity().getLocation(), () -> this.snapshotOnRegion(id, player));
    }

    @Override
    public Optional<HeadlessSnapshot> removeAndCapture(UUID id) {
        if (!this.available || id == null || !this.headlessIds.contains(id)) {
            return Optional.empty();
        }
        ServerPlayer player = this.playerList.getPlayer(id);
        if (player == null) {
            this.headlessIds.remove(id);
            return Optional.empty();
        }
        return HeadlessPlayerRegionTasks.call(this.plugin, player.getBukkitEntity().getLocation(), () -> {
            Optional<HeadlessSnapshot> snapshot = this.snapshotOnRegion(id, player);
            this.despawnOnRegion(id, player);
            return snapshot;
        });
    }

    @Override
    public Optional<HeadlessSnapshot> evictForLogin(UUID id, Location regionHint) {
        if (!this.available || id == null) {
            return Optional.empty();
        }
        HeadlessSnapshot pendingCapture = this.pendingEvictionCaptures.remove(id);
        if (pendingCapture != null) {
            return Optional.of(pendingCapture);
        }
        if (!this.headlessIds.contains(id)) {
            return Optional.empty();
        }
        ServerPlayer player = this.playerList.getPlayer(id);
        if (player == null) {
            this.headlessIds.remove(id);
            return Optional.empty();
        }
        Location location = regionHint != null && regionHint.getWorld() != null
                ? regionHint
                : player.getBukkitEntity().getLocation();
        return HeadlessPlayerRegionTasks.call(this.plugin, location, () -> {
            ServerPlayer live = this.playerList.getPlayer(id);
            if (live == null || !this.headlessIds.contains(id)) {
                this.headlessIds.remove(id);
                return Optional.<HeadlessSnapshot>empty();
            }
            Optional<HeadlessSnapshot> snapshot = this.snapshotOnRegion(id, live);
            this.despawnOnRegion(id, live);
            return snapshot;
        });
    }

    @Override
    public boolean remove(UUID id) {
        if (id != null) {
            this.pendingEvictionCaptures.remove(id);
        }
        if (!this.available || id == null || !this.headlessIds.contains(id)) {
            return false;
        }
        ServerPlayer player = this.playerList.getPlayer(id);
        if (player == null) {
            this.headlessIds.remove(id);
            return false;
        }
        return Boolean.TRUE.equals(HeadlessPlayerRegionTasks.call(this.plugin, player.getBukkitEntity().getLocation(), () -> {
            ServerPlayer live = this.playerList.getPlayer(id);
            if (live == null) {
                this.headlessIds.remove(id);
                return Boolean.FALSE;
            }
            this.despawnOnRegion(id, live);
            return Boolean.TRUE;
        }));
    }

    @Override
    public Set<UUID> activeIds() {
        return Set.copyOf(this.headlessIds);
    }

    @Override
    public void shutdown() {
        for (UUID id : Set.copyOf(this.headlessIds)) {
            this.remove(id);
        }
    }

    private void despawnOnRegion(UUID id, ServerPlayer player) {
        if (player == null) {
            this.headlessIds.remove(id);
            return;
        }
        if (!HeadlessPlayerPlacement.manualDespawn(this.playerList, player)) {
            // The body moved regions between the location snapshot and this task — chase it once
            // on its CURRENT owning region. manualDespawn refuses off-region teardown outright.
            HeadlessPlayerRegionTasks.run(this.plugin, player.getBukkitEntity().getLocation(), () -> {
                ServerPlayer chased = this.playerList.getPlayer(id);
                if (chased != null) {
                    HeadlessPlayerPlacement.manualDespawn(this.playerList, chased);
                }
                this.headlessIds.remove(id);
            });
            return;
        }
        this.headlessIds.remove(id);
    }

    private Optional<HeadlessSnapshot> snapshotOnRegion(UUID id, ServerPlayer player) {
        HeadlessPlayerPlacement.ensureCombatReady(player);
        var craft = player.getBukkitEntity();
        ItemStack[] contents = craft.getInventory().getContents();
        ItemStack[] cloned = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            cloned[i] = stack == null ? null : stack.clone();
        }
        return Optional.of(new HeadlessSnapshot(
                id,
                craft.getLocation(),
                craft.getHealth(),
                cloned));
    }
}
