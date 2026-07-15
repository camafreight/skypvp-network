package network.skypvp.extraction.gameplay;

import java.lang.reflect.Method;
import java.util.Locale;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Tones down WeaponMechanics damage dealt by Ruins gunner NPCs.
 *
 * <p>AI uses {@code WeaponMechanicsAPI.shoot} which skips weapon spread and can stack shotgun
 * pellets on one ray. This listener keeps player loot guns unchanged while making NPC spray
 * survivable.
 */
public final class RuinsGunnerDamageService implements Listener {

    private final JavaPlugin plugin;

    public RuinsGunnerDamageService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void registerWeaponMechanics() {
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Event> eventClass = (Class<? extends Event>) Class.forName(
                    "me.deecaad.weaponmechanics.weapon.weaponevents.WeaponDamageEntityEvent"
            );
            Method getShooter = eventClass.getMethod("getShooter");
            Method getVictim = findMethod(eventClass, "getVictim", "getEntity", "getTarget");
            Method getWeaponTitle = findMethod(eventClass, "getWeaponTitle", "getWeapon");
            Method getPoint = findMethod(eventClass, "getPoint");
            Method getDamage = findMethod(eventClass, "getFinalDamage", "getDamage");
            Method setDamage = findMethod(eventClass, "setDamage", "setFinalDamage");
            if (getDamage == null || setDamage == null) {
                plugin.getLogger().warning("[Breach] Ruins gunner damage scaling unavailable (no damage accessors).");
                return;
            }

            EventExecutor executor = (listener, event) -> {
                if (!eventClass.isInstance(event)) {
                    return;
                }
                try {
                    Object shooterObj = getShooter.invoke(event);
                    if (!(shooterObj instanceof LivingEntity shooter) || shooter instanceof Player) {
                        return;
                    }
                    String mobType = ruinsGunnerType(shooter);
                    if (mobType == null) {
                        return;
                    }
                    if (getVictim != null) {
                        Object victimObj = getVictim.invoke(event);
                        if (!(victimObj instanceof Player)) {
                            return;
                        }
                    }

                    double current = toDouble(getDamage.invoke(event));
                    if (current <= 0.0D) {
                        return;
                    }

                    String weaponTitle = "";
                    if (getWeaponTitle != null) {
                        Object titleObj = getWeaponTitle.invoke(event);
                        weaponTitle = titleObj == null ? "" : titleObj.toString();
                    }

                    double factor = damageFactor(mobType, weaponTitle);
                    boolean head = false;
                    if (getPoint != null) {
                        Object point = getPoint.invoke(event);
                        head = point != null && "HEAD".equalsIgnoreCase(point.toString());
                    }
                    if (head) {
                        // Strip most of the WM +50% head bonus for NPCs.
                        factor *= 0.70D;
                    }

                    double scaled = current * factor;
                    if (setDamage.getParameterCount() == 1) {
                        Class<?> param = setDamage.getParameterTypes()[0];
                        if (param == double.class || param == Double.class) {
                            setDamage.invoke(event, scaled);
                        } else if (param == float.class || param == Float.class) {
                            setDamage.invoke(event, (float) scaled);
                        }
                    }
                } catch (ReflectiveOperationException ignored) {
                }
            };

            plugin.getServer().getPluginManager().registerEvent(
                    eventClass,
                    this,
                    EventPriority.HIGH,
                    executor,
                    plugin,
                    true
            );
            plugin.getLogger().info("[Breach] Ruins gunner NPC damage scaling wired to WeaponMechanics.");
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().fine("[Breach] WeaponMechanics damage event unavailable for NPC scaling.");
        }
    }

    private static String ruinsGunnerType(LivingEntity entity) {
        if (entity == null || RaiderGunnerKeys.mobTypeKey() == null) {
            return null;
        }
        String type = entity.getPersistentDataContainer().get(
                RaiderGunnerKeys.mobTypeKey(),
                PersistentDataType.STRING
        );
        if (type == null || type.isBlank()) {
            return null;
        }
        return type.toLowerCase(Locale.ROOT);
    }

    static double damageFactor(String mobType, String weaponTitle) {
        String weapon = weaponTitle == null ? "" : weaponTitle.toLowerCase(Locale.ROOT);
        boolean shotgun = weapon.contains("origin_12") || weapon.contains("shotgun") || weapon.contains("dp12");
        if (shotgun || "ruinsbreacher".equals(mobType)) {
            // WM fires ~10 pellets on one AI vector — scale so a full connect is ~8-12 HP, not 100+.
            return 0.09D;
        }
        return switch (mobType == null ? "" : mobType) {
            case "ruinssmgunner" -> 0.42D;
            case "ruinsrifleman" -> 0.48D;
            case "ruinspistolgunner" -> 0.55D;
            default -> 0.45D;
        };
    }

    private static Method findMethod(Class<?> type, String... names) {
        for (String name : names) {
            try {
                return type.getMethod(name);
            } catch (NoSuchMethodException ignored) {
            }
            for (Method method : type.getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() <= 1) {
                    return method;
                }
            }
        }
        return null;
    }

    private static double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return 0.0D;
    }
}
