package network.skypvp.extraction.integration;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.logging.Logger;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.gameplay.BreachGunfireTracker;
import network.skypvp.extraction.gameplay.BreachLobbyProtection;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.java.JavaPlugin;

public final class ExtractionWeaponLobbyGuard implements Listener {

    private static final String[] EVENT_CLASS_NAMES = {
            "me.deecaad.weaponmechanics.events.WeaponShootEvent",
            "me.deecaad.weaponmechanics.events.WeaponReloadEvent",
            "me.deecaad.weaponmechanics.events.WeaponScopeEvent"
    };

    private final BreachEngine engine;

    public ExtractionWeaponLobbyGuard(BreachEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    public static void register(JavaPlugin plugin, BreachEngine engine, WeaponMechanicsBridge weaponMechanicsBridge) {
        register(plugin, engine, weaponMechanicsBridge, null);
    }

    public static void register(
            JavaPlugin plugin,
            BreachEngine engine,
            WeaponMechanicsBridge weaponMechanicsBridge,
            BreachGunfireTracker gunfireTracker
    ) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(engine, "engine");
        if (weaponMechanicsBridge == null || !weaponMechanicsBridge.isAvailable()) {
            return;
        }

        ExtractionWeaponLobbyGuard guard = new ExtractionWeaponLobbyGuard(engine);
        Logger logger = plugin.getLogger();
        int registered = 0;

        for (String className : EVENT_CLASS_NAMES) {
            if (registerEvent(plugin, guard, logger, className)) {
                registered++;
            }
        }

        if (registered > 0) {
            logger.info("[Breach] Weapon discharge blocked in extraction lobby (" + registered + " WeaponMechanics events).");
        }
        registerGunfireTracking(plugin, gunfireTracker, logger);
    }

    private static void registerGunfireTracking(JavaPlugin plugin, BreachGunfireTracker tracker, Logger logger) {
        if (tracker == null) {
            return;
        }
        try {
            Class<? extends Event> eventClass = eventClass("me.deecaad.weaponmechanics.events.WeaponShootEvent");
            Method getPlayer = eventClass.getMethod("getPlayer");
            EventExecutor executor = (listener, event) -> {
                if (!eventClass.isInstance(event)) {
                    return;
                }
                try {
                    Object shooter = getPlayer.invoke(event);
                    if (shooter instanceof Player player) {
                        tracker.record(player.getEyeLocation(), player.getUniqueId());
                    }
                } catch (ReflectiveOperationException ignored) {
                }
            };
            plugin.getServer().getPluginManager().registerEvent(
                    eventClass,
                    new ExtractionWeaponLobbyGuard(null),
                    EventPriority.MONITOR,
                    executor,
                    plugin,
                    true
            );
            logger.info("[Breach] Gunfire tracking enabled for raid spawn scoring.");
        } catch (ReflectiveOperationException ex) {
            logger.fine("[Breach] Gunfire tracking unavailable: " + ex.getMessage());
        }
    }

    private static boolean registerEvent(
            JavaPlugin plugin,
            ExtractionWeaponLobbyGuard guard,
            Logger logger,
            String className
    ) {
        try {
            Class<? extends Event> eventClass = eventClass(className);
            Method getPlayer = eventClass.getMethod("getPlayer");
            Method setCancelled = Event.class.getMethod("setCancelled", boolean.class);
            EventExecutor executor = (listener, event) -> {
                if (!eventClass.isInstance(event)) {
                    return;
                }
                try {
                    Object shooter = getPlayer.invoke(event);
                    if (!(shooter instanceof Player player)) {
                        return;
                    }
                    if (BreachLobbyProtection.isLobbySafe(guard.engine, player)) {
                        setCancelled.invoke(event, true);
                    }
                } catch (ReflectiveOperationException ignored) {
                }
            };
            plugin.getServer().getPluginManager().registerEvent(
                    eventClass,
                    guard,
                    EventPriority.HIGH,
                    executor,
                    plugin,
                    true
            );
            return true;
        } catch (ReflectiveOperationException ex) {
            logger.fine("[Breach] WeaponMechanics event unavailable: " + className);
            return false;
        }
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
