package network.skypvp.extraction.integration;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.library.packet.PacketEventsBridge;
import network.skypvp.paper.platform.PlatformTask;
import network.skypvp.paper.platform.ServerPlatform;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Owns automatic fire cadence for players on Folia.
 *
 * <p>WeaponMechanics {@code Fully_Automatic} uses EntityScheduler fixed-rate tasks that
 * stall when the shooter crosses a region border — mid-spray freezes. This service drives
 * auto fire from a <strong>global heartbeat → cached chunk region</strong> dispatch (same
 * pattern as gunner AI).</p>
 *
 * <p>Trigger hold is driven only by right-click / USE_ITEM and cleared on RELEASE_USE_ITEM.
 * WeaponShootEvent must never refresh hold — our own shots would loop forever.
 * Follow-up shots call {@link WeaponMechanicsBridge#tryShootWithoutTrigger} so ammo, reload,
 * dual-wield, and delay gates match WM's normal interact path (never the raw API shoot bypass).</p>
 */
public final class PlayerFullAutoService implements Listener {

    /**
     * Auto stops if no fresh right-click / USE_ITEM arrives within this window.
     * Must not be refreshed by WeaponShootEvent (that caused infinite spray).
     */
    private static final long HOLD_GRACE_MS = 220L;
    private static final String SHOOT_EVENT = "me.deecaad.weaponmechanics.weapon.weaponevents.WeaponShootEvent";

    /** Shots per second for weapons we take over (Fully_Automatic disabled in YAML). */
    private static final Map<String, Integer> AUTO_RATES = Map.ofEntries(
            Map.entry("ak_47", 10),
            Map.entry("m4a1", 14),
            Map.entry("aug", 13),
            Map.entry("stg44", 9),
            Map.entry("fn_fal", 9),
            Map.entry("fr_5.56", 13),
            Map.entry("laser_carbine", 12),
            Map.entry("uzi", 20),
            Map.entry("mg34", 10)
    );

    private final JavaPlugin plugin;
    private final WeaponMechanicsBridge weapons;
    private final ServerPlatform platform;
    private final Logger logger;
    private final Map<UUID, AutoSession> sessions = new ConcurrentHashMap<>();
    private final Method getWeaponTitleFromEvent;
    private PlatformTask drainTask;
    private PacketListenerAbstract packetListener;

    private PlayerFullAutoService(
            JavaPlugin plugin,
            PaperCorePlugin core,
            WeaponMechanicsBridge weapons,
            Method getWeaponTitleFromEvent
    ) {
        this.plugin = plugin;
        this.weapons = weapons;
        this.platform = core.platformScheduler();
        this.logger = plugin.getLogger();
        this.getWeaponTitleFromEvent = getWeaponTitleFromEvent;
    }

    public static void register(JavaPlugin plugin, PaperCorePlugin core, WeaponMechanicsBridge weapons) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(core, "core");
        Objects.requireNonNull(weapons, "weapons");
        if (!weapons.isAvailable() || core.platformScheduler() == null) {
            return;
        }
        Method getWeaponTitle;
        try {
            Class<?> shootEvent = Class.forName(SHOOT_EVENT);
            getWeaponTitle = shootEvent.getMethod("getWeaponTitle");
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().warning("[Breach] Player full-auto service unavailable: " + ex.getMessage());
            return;
        }
        PlayerFullAutoService service = new PlayerFullAutoService(plugin, core, weapons, getWeaponTitle);
        plugin.getServer().getPluginManager().registerEvents(service, plugin);
        service.registerShootListener();
        service.registerPacketHoldListener();
        service.drainTask = core.platformScheduler().runGlobalTimer(service::drain, 1L, 1L);
        plugin.getLogger().info("[Breach] Player full-auto cadence owned by SkyPvP (global→region; WM Fully_Automatic disabled).");
    }

    public void shutdown() {
        if (this.drainTask != null) {
            this.drainTask.cancel();
            this.drainTask = null;
        }
        if (this.packetListener != null && PacketEventsBridge.isAvailable()) {
            try {
                PacketEvents.getAPI().getEventManager().unregisterListener(this.packetListener);
            } catch (RuntimeException ignored) {
            }
            this.packetListener = null;
        }
        this.sessions.clear();
    }

    private void registerShootListener() {
        try {
            Class<? extends org.bukkit.event.Event> type =
                    Class.forName(SHOOT_EVENT).asSubclass(org.bukkit.event.Event.class);
            EventExecutor executor = (listener, event) -> onWeaponShoot(event);
            plugin.getServer().getPluginManager().registerEvent(
                    type, this, EventPriority.MONITOR, executor, plugin, true);
        } catch (ReflectiveOperationException ex) {
            logger.log(Level.WARNING, "[Breach] Failed to listen for WeaponShootEvent (full-auto)", ex);
        }
    }

    private void registerPacketHoldListener() {
        if (!PacketEventsBridge.isAvailable()) {
            return;
        }
        this.packetListener = new PacketListenerAbstract(PacketListenerPriority.MONITOR) {
            @Override
            public void onPacketReceive(PacketReceiveEvent event) {
                Object playerObj = event.getPlayer();
                if (!(playerObj instanceof Player player)) {
                    return;
                }
                if (event.getPacketType() == PacketType.Play.Client.USE_ITEM) {
                    pressTrigger(player);
                    return;
                }
                if (event.getPacketType() != PacketType.Play.Client.PLAYER_DIGGING) {
                    return;
                }
                try {
                    WrapperPlayClientPlayerDigging digging = new WrapperPlayClientPlayerDigging(event);
                    DiggingAction action = digging.getAction();
                    if (action == DiggingAction.RELEASE_USE_ITEM
                            || action == DiggingAction.DROP_ITEM
                            || action == DiggingAction.DROP_ITEM_STACK) {
                        releaseTrigger(player.getUniqueId());
                    } else if (action == DiggingAction.SWAP_ITEM_WITH_OFFHAND
                            && !isReservedBackpackOffhand(player)) {
                        // F with a real offhand swap ends spray; backpack toggle must not.
                        releaseTrigger(player.getUniqueId());
                    }
                } catch (RuntimeException ignored) {
                    // Wrapper version mismatch — fall back to stale timeout.
                }
            }
        };
        PacketEvents.getAPI().getEventManager().registerListener(this.packetListener);
    }

    /**
     * WM / our API shots must not extend hold. Owned shots are ignored; player first-shot only
     * credits cadence if the trigger is already down from interact/USE_ITEM.
     */
    private void onWeaponShoot(Object event) {
        try {
            Object titleObj = getWeaponTitleFromEvent.invoke(event);
            if (!(titleObj instanceof String title) || title.isBlank()) {
                return;
            }
            if (!isAutoWeapon(title)) {
                return;
            }
            Player player = resolveShooter(event);
            if (player == null || !player.isOnline()) {
                return;
            }
            AutoSession session = sessions.get(player.getUniqueId());
            if (session == null) {
                return;
            }
            if (session.ownedShotDepth.get() > 0) {
                return;
            }
            if (!session.triggerHeld) {
                return;
            }
            // Credit the player/WM first shot so we don't double-fire on the same tick.
            session.lastShotTick = currentTick();
            session.weaponTitle = title.trim();
        } catch (ReflectiveOperationException ex) {
            logger.log(Level.FINE, "[Breach] full-auto shoot hook failed", ex);
        }
    }

    private static Player resolveShooter(Object event) throws ReflectiveOperationException {
        try {
            Method getShooter = event.getClass().getMethod("getShooter");
            Object shooter = getShooter.invoke(event);
            if (shooter instanceof Player player) {
                return player;
            }
        } catch (NoSuchMethodException ignored) {
        }
        try {
            Method getPlayer = event.getClass().getMethod("getPlayer");
            Object shooter = getPlayer.invoke(event);
            if (shooter instanceof Player player) {
                return player;
            }
        } catch (NoSuchMethodException ignored) {
        }
        return null;
    }

    // ignoreCancelled=false: WM often cancels the interact; we still need the hold signal.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!event.getAction().isRightClick()) {
            return;
        }
        pressTrigger(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        sessions.remove(event.getEntity().getUniqueId());
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onSwap(PlayerItemHeldEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onOffhand(PlayerSwapHandItemsEvent event) {
        // Backpack owns F (cancelled at LOWEST) — ignoreCancelled already skips that path.
        // Reserved pack slot must never end spray even if a plugin re-fires an uncancelled swap.
        if (isReservedBackpackOffhand(event.getPlayer())) {
            return;
        }
        sessions.remove(event.getPlayer().getUniqueId());
    }

    private void pressTrigger(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        String title = weapons.weaponTitle(held).orElse(null);
        if (title == null || !isAutoWeapon(title)) {
            releaseTrigger(player.getUniqueId());
            return;
        }
        // Do not start/extend spray while WM is reloading.
        if (weapons.isReloading(player)) {
            return;
        }
        LocationChunk chunk = LocationChunk.from(player);
        long tickNow = currentTick();
        long now = System.currentTimeMillis();
        sessions.compute(player.getUniqueId(), (id, previous) -> {
            int rate = AUTO_RATES.getOrDefault(title.toLowerCase(Locale.ROOT), 10);
            long periodTicks = Math.max(1L, Math.round(20.0D / (double) rate));
            if (previous == null) {
                AutoSession created = new AutoSession(
                        title, periodTicks, chunk, now, tickNow, new AtomicInteger(), new AtomicInteger());
                created.triggerHeld = true;
                return created;
            }
            previous.weaponTitle = title;
            previous.periodTicks = periodTicks;
            previous.triggerHeld = true;
            previous.lastHeldMs = now;
            if (chunk != null) {
                previous.chunk = chunk;
            }
            return previous;
        });
    }

    private void releaseTrigger(UUID playerId) {
        AutoSession session = sessions.get(playerId);
        if (session != null) {
            session.triggerHeld = false;
            sessions.remove(playerId, session);
        }
    }

    /** Offhand is the reserved backpack / NO BACKPACK slot — F toggles pack UI, not a real swap. */
    private static boolean isReservedBackpackOffhand(Player player) {
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand == null || offhand.getType().isAir() || !offhand.hasItemMeta()) {
            return false;
        }
        var model = offhand.getItemMeta().getItemModel();
        return model != null
                && "skypvp".equals(model.getNamespace())
                && model.getKey() != null
                && model.getKey().startsWith("backpack");
    }

    private void drain() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, AutoSession> entry : sessions.entrySet()) {
            UUID id = entry.getKey();
            AutoSession session = entry.getValue();
            if (!session.triggerHeld || now - session.lastHeldMs > HOLD_GRACE_MS) {
                sessions.remove(id, session);
                continue;
            }
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                sessions.remove(id, session);
                continue;
            }
            int generation = session.dispatchGeneration.incrementAndGet();
            LocationChunk chunk = session.chunk;
            if (chunk == null || chunk.worldId == null) {
                continue;
            }
            World world = Bukkit.getWorld(chunk.worldId);
            if (world == null) {
                continue;
            }
            platform.runAtChunk(world, chunk.chunkX, chunk.chunkZ, () -> {
                if (session.dispatchGeneration.get() != generation) {
                    return;
                }
                Player online = Bukkit.getPlayer(id);
                if (online == null || !online.isOnline()) {
                    return;
                }
                if (!platform.isOwnedByCurrentRegion(online)) {
                    platform.runAtEntity(online, () -> fireIfDue(online, session));
                    return;
                }
                fireIfDue(online, session);
            });
        }
    }

    private void fireIfDue(Player player, AutoSession session) {
        if (!session.triggerHeld) {
            sessions.remove(player.getUniqueId(), session);
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        String heldTitle = weapons.weaponTitle(held).orElse(null);
        if (heldTitle == null || !heldTitle.equalsIgnoreCase(session.weaponTitle)) {
            sessions.remove(player.getUniqueId(), session);
            return;
        }
        if (weapons.isReloading(player)) {
            LocationChunk chunk = LocationChunk.from(player);
            if (chunk != null) {
                session.chunk = chunk;
            }
            return;
        }
        long tickNow = currentTick();
        if (tickNow - session.lastShotTick < session.periodTicks) {
            LocationChunk chunk = LocationChunk.from(player);
            if (chunk != null) {
                session.chunk = chunk;
            }
            return;
        }
        session.ownedShotDepth.incrementAndGet();
        try {
            boolean fired = weapons.tryShootWithoutTrigger(player, session.weaponTitle, held);
            if (!fired) {
                return;
            }
            session.lastShotTick = tickNow;
            LocationChunk chunk = LocationChunk.from(player);
            if (chunk != null) {
                session.chunk = chunk;
            }
        } catch (RuntimeException ex) {
            logger.log(Level.FINE, "[Breach] full-auto fire failed for " + player.getName(), ex);
        } finally {
            session.ownedShotDepth.decrementAndGet();
        }
    }

    private static boolean isAutoWeapon(String title) {
        return title != null && AUTO_RATES.containsKey(title.toLowerCase(Locale.ROOT));
    }

    private static long currentTick() {
        try {
            return Bukkit.getServer().getCurrentTick();
        } catch (IllegalStateException | NoSuchMethodError ignored) {
            // Folia: packet threads are not region-ticking; getCurrentTick throws and was
            // aborting pressTrigger so full-auto never armed mid-fight.
            return System.currentTimeMillis() / 50L;
        }
    }

    private static final class AutoSession {
        private String weaponTitle;
        private long periodTicks;
        private volatile LocationChunk chunk;
        private volatile boolean triggerHeld;
        private volatile long lastHeldMs;
        private volatile long lastShotTick;
        private final AtomicInteger dispatchGeneration;
        private final AtomicInteger ownedShotDepth;

        private AutoSession(
                String weaponTitle,
                long periodTicks,
                LocationChunk chunk,
                long lastHeldMs,
                long lastShotTick,
                AtomicInteger dispatchGeneration,
                AtomicInteger ownedShotDepth
        ) {
            this.weaponTitle = weaponTitle;
            this.periodTicks = periodTicks;
            this.chunk = chunk;
            this.triggerHeld = false;
            this.lastHeldMs = lastHeldMs;
            this.lastShotTick = lastShotTick;
            this.dispatchGeneration = dispatchGeneration;
            this.ownedShotDepth = ownedShotDepth;
        }
    }

    private static final class LocationChunk {
        private final UUID worldId;
        private final int chunkX;
        private final int chunkZ;

        private LocationChunk(UUID worldId, int chunkX, int chunkZ) {
            this.worldId = worldId;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        private static LocationChunk from(Player player) {
            try {
                var loc = player.getLocation();
                if (loc.getWorld() == null) {
                    return null;
                }
                return new LocationChunk(
                        loc.getWorld().getUID(),
                        loc.getBlockX() >> 4,
                        loc.getBlockZ() >> 4
                );
            } catch (IllegalStateException foreignRegion) {
                return null;
            }
        }
    }
}
