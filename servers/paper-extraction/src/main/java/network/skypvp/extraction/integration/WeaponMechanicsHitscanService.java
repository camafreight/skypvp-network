package network.skypvp.extraction.integration;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import network.skypvp.extraction.config.HitscanSettings;
import network.skypvp.paper.platform.ServerPlatform;
import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

/**
 * Converts WeaponMechanics bullet weapons into single-tick ray hits while preserving WM damage,
 * spread, pierce, and dropoff via {@code WeaponProjectile.updatePosition()}.
 *
 * <p>Combat resolves inline on the {@code WeaponShootEvent} region thread the same tick the shot fires so
 * raycasts test against targets at their current position. Tracers and impact cosmetics use a separate
 * deferred visual pipeline.
 */
public final class WeaponMechanicsHitscanService implements Listener {

    private static final String SHOOT_EVENT = "me.deecaad.weaponmechanics.weapon.weaponevents.WeaponShootEvent";

    @FunctionalInterface
    private interface ProjectileTickGuard {
        boolean pauseTicking(Object projectile);
    }

    private final Logger logger;
    private final HitscanSettings settings;
    private final HitscanVisualQueue visualQueue;
    private final Optional<ProjectileTickGuard> tickGuard;
    private final Method getProjectile;
    private final Method getWeaponTitle;
    private final Method getBukkitLocation;
    private final Method getNormalizedMotion;
    private final Method setMotion;
    private final Method updatePosition;
    private final Method remove;
    private final Method getProjectileSettings;
    private final Method getMaximumTravelDistance;

    private WeaponMechanicsHitscanService(
            Logger logger,
            HitscanSettings settings,
            ServerPlatform scheduler,
            HitscanVisualQueue visualQueue,
            Optional<ProjectileTickGuard> tickGuard,
            Method getProjectile,
            Method getWeaponTitle,
            Method getBukkitLocation,
            Method getNormalizedMotion,
            Method setMotion,
            Method updatePosition,
            Method remove,
            Method getProjectileSettings,
            Method getMaximumTravelDistance
    ) {
        this.logger = logger;
        this.settings = settings;
        this.visualQueue = visualQueue;
        this.tickGuard = tickGuard;
        this.getProjectile = getProjectile;
        this.getWeaponTitle = getWeaponTitle;
        this.getBukkitLocation = getBukkitLocation;
        this.getNormalizedMotion = getNormalizedMotion;
        this.setMotion = setMotion;
        this.updatePosition = updatePosition;
        this.remove = remove;
        this.getProjectileSettings = getProjectileSettings;
        this.getMaximumTravelDistance = getMaximumTravelDistance;
    }

    public static void register(
            JavaPlugin plugin,
            HitscanSettings settings,
            WeaponMechanicsBridge bridge,
            ServerPlatform scheduler
    ) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(scheduler, "scheduler");
        if (bridge == null || !bridge.isAvailable() || !settings.enabled()) {
            return;
        }

        Logger logger = plugin.getLogger();
        WeaponMechanicsHitscanService service = tryCreate(logger, settings, plugin, scheduler);
        if (service == null) {
            logger.warning("[Breach] WeaponMechanics hitscan could not initialize; using WM projectiles.");
            return;
        }

        try {
            Class<? extends Event> eventClass = eventClass(SHOOT_EVENT);
            EventExecutor executor = (listener, event) -> {
                if (!eventClass.isInstance(event)) {
                    return;
                }
                service.onWeaponShoot(event);
            };
            plugin.getServer().getPluginManager().registerEvent(
                    eventClass,
                    service,
                    EventPriority.LOW,
                    executor,
                    plugin,
                    true
            );
            plugin.getServer().getPluginManager().registerEvents(new Listener() {
                @org.bukkit.event.EventHandler
                public void onDisable(org.bukkit.event.server.PluginDisableEvent disableEvent) {
                    if (!disableEvent.getPlugin().equals(plugin)) {
                        return;
                    }
                    service.shutdown();
                }
            }, plugin);

            logger.info("[Breach] WeaponMechanics hitscan enabled (inline region combat, deferred/budgeted visuals).");
        } catch (ReflectiveOperationException ex) {
            logger.warning("[Breach] Failed to register WeaponMechanics hitscan: " + ex.getMessage());
        }
    }

    private static WeaponMechanicsHitscanService tryCreate(
            Logger logger,
            HitscanSettings settings,
            JavaPlugin plugin,
            ServerPlatform scheduler
    ) {
        try {
            Class<?> projectileClass = Class.forName(
                    "me.deecaad.weaponmechanics.weapon.projectile.weaponprojectile.WeaponProjectile"
            );
            Class<?> projectileSettingsClass = Class.forName(
                    "me.deecaad.weaponmechanics.weapon.projectile.weaponprojectile.ProjectileSettings"
            );
            Class<?> shootEventClass = Class.forName(SHOOT_EVENT);

            Method getProjectile = shootEventClass.getMethod("getProjectile");
            Method getWeaponTitle = shootEventClass.getMethod("getWeaponTitle");
            Method getBukkitLocation = projectileClass.getMethod("getBukkitLocation");
            Method getNormalizedMotion = projectileClass.getMethod("getNormalizedMotion");
            Method setMotion = projectileClass.getMethod("setMotion", Vector.class);
            Method updatePosition = projectileClass.getMethod("updatePosition");
            Method remove = projectileClass.getMethod("remove");
            Method getProjectileSettings = projectileClass.getMethod("getProjectileSettings");
            Method getMaximumTravelDistance = projectileSettingsClass.getMethod("getMaximumTravelDistance");

            HitscanLaserBeamRenderer laserRenderer = new HitscanLaserBeamRenderer(scheduler, settings);
            HitscanLaserDebugService.register(laserRenderer, settings);
            HitscanVisualQueue visualQueue = new HitscanVisualQueue(
                    plugin,
                    scheduler,
                    settings,
                    new HitscanVisualRenderer(settings, laserRenderer)
            );

            return new WeaponMechanicsHitscanService(
                    logger,
                    settings,
                    scheduler,
                    visualQueue,
                    createTickGuard(logger),
                    getProjectile,
                    getWeaponTitle,
                    getBukkitLocation,
                    getNormalizedMotion,
                    setMotion,
                    updatePosition,
                    remove,
                    getProjectileSettings,
                    getMaximumTravelDistance
            );
        } catch (ReflectiveOperationException ex) {
            logger.log(Level.FINE, "[Breach] WeaponMechanics hitscan reflection init failed", ex);
            return null;
        }
    }

    private static Optional<ProjectileTickGuard> createTickGuard(Logger logger) {
        Optional<ProjectileTickGuard> cancelGuard = tryCancelGuard();
        if (cancelGuard.isPresent()) {
            return cancelGuard;
        }
        Optional<ProjectileTickGuard> runnableGuard = tryRunnableRemoveGuard();
        if (runnableGuard.isPresent()) {
            return runnableGuard;
        }
        logger.fine("[Breach] Hitscan could not pause WM projectile ticks; WM may still advance one tick before removal.");
        return Optional.empty();
    }

    private static Optional<ProjectileTickGuard> tryCancelGuard() {
        try {
            Class<?> projectileClass = Class.forName("me.deecaad.weaponmechanics.weapon.projectile.AProjectile");
            Method cancel = projectileClass.getMethod("cancel");
            return Optional.of(projectile -> {
                try {
                    cancel.invoke(projectile);
                    return true;
                } catch (ReflectiveOperationException ex) {
                    return false;
                }
            });
        } catch (ReflectiveOperationException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<ProjectileTickGuard> tryRunnableRemoveGuard() {
        try {
            Class<?> wmClass = Class.forName("me.deecaad.weaponmechanics.WeaponMechanics");
            Class<?> projectileClass = Class.forName("me.deecaad.weaponmechanics.weapon.projectile.AProjectile");
            Class<?> runnableClass = Class.forName("me.deecaad.weaponmechanics.weapon.projectile.ProjectilesRunnable");
            Method getInstance = wmClass.getMethod("getInstance");
            Method getRunnable = wmClass.getMethod("getProjectilesRunnable");
            Method remove = runnableClass.getMethod("remove", projectileClass);
            Object runnable = getRunnable.invoke(getInstance.invoke(null));
            return Optional.of(projectile -> {
                try {
                    remove.invoke(runnable, projectile);
                    return true;
                } catch (ReflectiveOperationException ex) {
                    return false;
                }
            });
        } catch (ReflectiveOperationException ignored) {
            return Optional.empty();
        }
    }

    private void onWeaponShoot(Object event) {
        try {
            String weaponTitle = (String) getWeaponTitle.invoke(event);
            if (!settings.usesHitscan(weaponTitle)) {
                return;
            }

            Object projectile = getProjectile.invoke(event);
            if (projectile == null) {
                return;
            }

            Location start = ((Location) getBukkitLocation.invoke(projectile)).clone();
            Vector direction = (Vector) getNormalizedMotion.invoke(projectile);
            if (direction == null || direction.lengthSquared() < 1.0E-8) {
                remove.invoke(projectile);
                return;
            }

            if (start.getWorld() == null) {
                return;
            }

            pauseProjectileTicking(projectile);

            // WeaponShootEvent fires on the shooter's region thread. Scheduling runAtLocation here would defer the
            // ray to the next region tick on Folia and make moving targets feel unhittable.
            resolveCombat(new HitscanCombatJob(projectile, start, direction, resolveRange(projectile)));
        } catch (ReflectiveOperationException ex) {
            logger.log(Level.FINE, "[Breach] WeaponMechanics hitscan shot failed", ex);
        }
    }

    private void pauseProjectileTicking(Object projectile) {
        tickGuard.ifPresent(guard -> guard.pauseTicking(projectile));
    }

    private void resolveCombat(HitscanCombatJob job) {
        Object projectile = job.projectile();
        if (projectile == null) {
            return;
        }

        try {
            Location start = job.start();
            Vector direction = job.direction();
            double range = job.range();

            setMotion.invoke(projectile, direction.clone().multiply(range));
            updatePosition.invoke(projectile);
            Location end = ((Location) getBukkitLocation.invoke(projectile)).clone();
            remove.invoke(projectile);

            if (start.getWorld() == null) {
                return;
            }

            HitscanVisualJob visualJob = HitscanVisualJob.create(
                    start,
                    end,
                    direction,
                    start.distance(end),
                    range,
                    settings.usesLaserTracer() ? 0.0 : settings.tracerSpacingBlocks(),
                    settings.usesLaserTracer() ? 0 : settings.maxTracerPoints()
            );
            visualQueue.enqueueAsync(visualJob);
        } catch (ReflectiveOperationException ex) {
            logger.log(Level.FINE, "[Breach] WeaponMechanics hitscan combat failed", ex);
            try {
                remove.invoke(projectile);
            } catch (ReflectiveOperationException ignored) {
            }
        }
    }

    private double resolveRange(Object projectile) throws ReflectiveOperationException {
        Object projectileSettings = getProjectileSettings.invoke(projectile);
        if (projectileSettings != null) {
            Object configured = getMaximumTravelDistance.invoke(projectileSettings);
            if (configured instanceof Number number) {
                double value = number.doubleValue();
                if (value > 0.0) {
                    return value;
                }
            }
        }
        return settings.maxRangeBlocks();
    }

    private void shutdown() {
        HandlerList.unregisterAll(this);
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Event> eventClass(String className) throws ClassNotFoundException {
        Class<?> loaded = Class.forName(className);
        if (!Event.class.isAssignableFrom(loaded)) {
            throw new ClassNotFoundException(className + " is not a Bukkit event");
        }
        return (Class<? extends Event>) loaded;
    }
}
