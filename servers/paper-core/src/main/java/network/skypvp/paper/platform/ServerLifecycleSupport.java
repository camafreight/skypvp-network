package network.skypvp.paper.platform;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/**
 * Hybrid Paper/Folia server-lifecycle gate.
 *
 * <p>Paper order: STARTUP plugins → worlds → POSTWORLD plugins → {@link ServerLoadEvent}({@code STARTUP})
 * → accept connections. Folia additionally fires {@code RegionizedServerInitEvent} during delayed init,
 * before parallel region ticking.
 *
 * <p>This support waits for {@code ServerLoadEvent.STARTUP} on both runtimes, and on Folia also waits for
 * {@code RegionizedServerInitEvent}, then invokes {@code onPlatformReady} once on the global scheduler.
 * That is the earliest safe point where all plugins have finished {@code onEnable} and worlds are loaded;
 * gameplay joinable still needs spawn/decoration/mode latches on top.
 */
public final class ServerLifecycleSupport implements Listener {

    private final Plugin plugin;
    private final ServerPlatform platform;
    private final Logger logger;
    private final Consumer<ServerLifecyclePhase> onPlatformReady;
    private final AtomicBoolean serverLoadSeen = new AtomicBoolean(false);
    private final AtomicBoolean foliaInitSeen = new AtomicBoolean(false);
    private final AtomicBoolean readyFired = new AtomicBoolean(false);
    private final boolean folia;
    private final AtomicBoolean foliaInitHookRegistered = new AtomicBoolean(false);

    public enum ServerLifecyclePhase {
        /** Plugins enabled + worlds loaded (+ Folia region init when applicable). */
        PLATFORM_READY
    }

    private ServerLifecycleSupport(
            Plugin plugin,
            ServerPlatform platform,
            Consumer<ServerLifecyclePhase> onPlatformReady,
            boolean folia
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.logger = plugin.getLogger();
        this.onPlatformReady = Objects.requireNonNull(onPlatformReady, "onPlatformReady");
        this.folia = folia;
        if (!folia) {
            // Paper has no separate region-init event; treat as already satisfied.
            this.foliaInitSeen.set(true);
        }
    }

    /**
     * Registers hybrid lifecycle listeners and returns the support instance.
     */
    public static ServerLifecycleSupport register(
            Plugin plugin,
            ServerPlatform platform,
            Consumer<ServerLifecyclePhase> onPlatformReady
    ) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(onPlatformReady, "onPlatformReady");

        boolean folia = platform.isFolia();
        ServerLifecycleSupport support = new ServerLifecycleSupport(plugin, platform, onPlatformReady, folia);

        PluginManager pluginManager = plugin.getServer().getPluginManager();
        pluginManager.registerEvents(support, plugin);

        if (folia) {
            boolean foliaHook = support.registerFoliaRegionInitListener(pluginManager);
            support.foliaInitHookRegistered.set(foliaHook);
            if (!foliaHook) {
                // Folia detected but event class missing — do not block forever.
                support.logger.warning(
                        "[Lifecycle] Folia detected but RegionizedServerInitEvent unavailable; "
                                + "proceeding after ServerLoadEvent only."
                );
                support.foliaInitSeen.set(true);
            }
        }

        support.logger.info(
                "[Lifecycle] Hybrid ready-gate registered (folia="
                        + folia
                        + ", regionInitHook="
                        + support.foliaInitHookRegistered.get()
                        + ")."
        );
        return support;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerLoad(ServerLoadEvent event) {
        if (event.getType() != ServerLoadEvent.LoadType.STARTUP) {
            return;
        }
        if (!this.serverLoadSeen.compareAndSet(false, true)) {
            return;
        }
        this.logger.info("[Lifecycle] ServerLoadEvent(STARTUP) — plugins enabled and worlds loaded.");
        this.tryFireReady();
    }

    private boolean registerFoliaRegionInitListener(PluginManager pluginManager) {
        try {
            Class<?> eventClass = Class.forName("io.papermc.paper.threadedregions.RegionizedServerInitEvent");
            pluginManager.registerEvent(
                    eventClass.asSubclass(org.bukkit.event.Event.class),
                    this,
                    EventPriority.MONITOR,
                    (listener, event) -> {
                        if (!this.foliaInitSeen.compareAndSet(false, true)) {
                            return;
                        }
                        this.logger.info(
                                "[Lifecycle] RegionizedServerInitEvent — Folia delayed init complete "
                                        + "(before parallel region ticks)."
                        );
                        this.tryFireReady();
                    },
                    this.plugin,
                    true
            );
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        } catch (Throwable ex) {
            this.logger.log(Level.WARNING, "[Lifecycle] Failed to register RegionizedServerInitEvent hook", ex);
            return false;
        }
    }

    private void tryFireReady() {
        if (!this.serverLoadSeen.get()) {
            return;
        }
        if (this.folia && !this.foliaInitSeen.get()) {
            this.logger.info("[Lifecycle] Waiting for Folia RegionizedServerInitEvent before platform-ready.");
            return;
        }
        if (!this.readyFired.compareAndSet(false, true)) {
            return;
        }
        this.platform.runGlobal(() -> {
            this.logger.info("[Lifecycle] Platform ready — handing off to WorldState joinable pipeline.");
            this.onPlatformReady.accept(ServerLifecyclePhase.PLATFORM_READY);
        });
    }

    public boolean isPlatformReady() {
        return this.readyFired.get();
    }

    /** Exposed for tests / diagnostics. */
    public boolean awaitsFoliaInit() {
        return this.folia && this.foliaInitHookRegistered.get() && !this.foliaInitSeen.get();
    }
}
