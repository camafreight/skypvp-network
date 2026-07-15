package network.skypvp.extraction.integration;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class WeaponMechanicsBridge {

    private final Logger logger;
    private final boolean present;
    private final Method generateWeapon;
    private final Method getWeaponHandler;
    private final Method getEntityWrapper;
    private final Method getShootHandler;
    private final Method shootWithoutTrigger;
    private final Method isDualWieldingWeapons;
    private final Object startRightClickTrigger;

    public WeaponMechanicsBridge(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        this.logger = plugin.getLogger();
        Method weaponGenerator = null;
        Method weaponHandlerAccessor = null;
        Method entityWrapperAccessor = null;
        Method shootHandlerAccessor = null;
        Method shootWithoutTriggerMethod = null;
        Method dualWieldCheck = null;
        Object rightClickHeldTrigger = null;
        boolean available = false;

        try {
            Class<?> apiClass = Class.forName("me.deecaad.weaponmechanics.WeaponMechanicsAPI");
            weaponGenerator = apiClass.getMethod("generateWeapon", String.class);

            Class<?> wmClass = Class.forName("me.deecaad.weaponmechanics.WeaponMechanics");
            weaponHandlerAccessor = wmClass.getMethod("getWeaponHandler");
            entityWrapperAccessor = wmClass.getMethod("getEntityWrapper", org.bukkit.entity.LivingEntity.class);

            Class<?> weaponHandlerClass = Class.forName("me.deecaad.weaponmechanics.weapon.WeaponHandler");
            shootHandlerAccessor = weaponHandlerClass.getMethod("getShootHandler");

            Class<?> shootHandlerClass = Class.forName("me.deecaad.weaponmechanics.weapon.shoot.ShootHandler");
            Class<?> entityWrapperClass = Class.forName("me.deecaad.weaponmechanics.wrappers.EntityWrapper");
            Class<?> triggerTypeClass = Class.forName("me.deecaad.weaponmechanics.weapon.trigger.TriggerType");
            shootWithoutTriggerMethod = shootHandlerClass.getMethod(
                    "shootWithoutTrigger",
                    entityWrapperClass,
                    String.class,
                    ItemStack.class,
                    EquipmentSlot.class,
                    triggerTypeClass,
                    boolean.class
            );
            dualWieldCheck = entityWrapperClass.getMethod("isDualWieldingWeapons");
            // WM 4.3.1 names the held-right-click trigger RIGHT_CLICK (no START_ prefix).
            rightClickHeldTrigger = Enum.valueOf((Class<Enum>) triggerTypeClass, "RIGHT_CLICK");

            available = true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // RuntimeException too: a renamed enum constant throws IllegalArgumentException,
            // and an escaping throwable here aborts the whole plugin enable (2026-07-14 outage).
            available = false;
        }

        this.present = available;
        this.generateWeapon = weaponGenerator;
        this.getWeaponHandler = weaponHandlerAccessor;
        this.getEntityWrapper = entityWrapperAccessor;
        this.getShootHandler = shootHandlerAccessor;
        this.shootWithoutTrigger = shootWithoutTriggerMethod;
        this.isDualWieldingWeapons = dualWieldCheck;
        this.startRightClickTrigger = rightClickHeldTrigger;

        if (present) {
            logger.info("[Breach] WeaponMechanics bridge enabled.");
        } else {
            logger.info("[Breach] WeaponMechanics not present; wm loot entries will be skipped.");
        }
    }

    public Optional<ItemStack> generateWeapon(String weaponTitle, int amount) {
        if (!present || weaponTitle == null || weaponTitle.isBlank() || generateWeapon == null) {
            return Optional.empty();
        }

        try {
            Object result = generateWeapon.invoke(null, weaponTitle.trim());
            if (!(result instanceof ItemStack itemStack)) {
                return Optional.empty();
            }
            itemStack.setAmount(Math.max(1, amount));
            return Optional.of(itemStack);
        } catch (ReflectiveOperationException ex) {
            logger.warning("[Breach] Failed to generate WeaponMechanics weapon '"
                    + weaponTitle.trim() + "': " + ex.getMessage());
            return Optional.empty();
        }
    }

    public boolean isAvailable() {
        return present;
    }

    /**
     * Fires one shot through WeaponMechanics' validated shoot path (ammo, reload, delays,
     * permissions, dual-wield rules). Returns false when WM would refuse the shot.
     */
    public boolean tryShootWithoutTrigger(Player player, String weaponTitle, ItemStack weaponStack) {
        if (!present || player == null || weaponTitle == null || weaponTitle.isBlank()
                || weaponStack == null || weaponStack.getType().isAir()
                || shootWithoutTrigger == null) {
            return false;
        }
        try {
            Class<?> wmClass = Class.forName("me.deecaad.weaponmechanics.WeaponMechanics");
            Object wm = wmClass.getMethod("getInstance").invoke(null);
            Object entityWrapper = getEntityWrapper.invoke(wm, player);
            if (entityWrapper == null) {
                return false;
            }
            Object weaponHandler = getWeaponHandler.invoke(wm);
            Object shootHandler = getShootHandler.invoke(weaponHandler);
            boolean dualWield = (Boolean) isDualWieldingWeapons.invoke(entityWrapper);
            Object result = shootWithoutTrigger.invoke(
                    shootHandler,
                    entityWrapper,
                    weaponTitle.trim(),
                    weaponStack,
                    EquipmentSlot.HAND,
                    startRightClickTrigger,
                    dualWield
            );
            return result instanceof Boolean bool && bool;
        } catch (ReflectiveOperationException ex) {
            logger.log(Level.FINE, "[Breach] WeaponMechanics shootWithoutTrigger failed for "
                    + weaponTitle.trim(), ex);
            return false;
        }
    }

    /** True when WeaponMechanics currently has this player mid-reload. */
    public boolean isReloading(org.bukkit.entity.Player player) {
        if (!present || player == null) {
            return false;
        }
        return invokeBooleanOnEntity("me.deecaad.weaponmechanics.WeaponMechanicsAPI", "isReloading", player);
    }

    public Optional<String> weaponTitle(ItemStack itemStack) {
        if (!present || itemStack == null || itemStack.getType().isAir()) {
            return Optional.empty();
        }
        try {
            Class<?> apiClass = Class.forName("me.deecaad.weaponmechanics.WeaponMechanicsAPI");
            Object title = apiClass.getMethod("getWeaponTitle", ItemStack.class).invoke(null, itemStack);
            if (title instanceof String weaponTitle && !weaponTitle.isBlank()) {
                return Optional.of(weaponTitle.trim());
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return Optional.empty();
    }

    public boolean isWeaponItem(ItemStack itemStack) {
        if (!present || itemStack == null || itemStack.getType().isAir()) {
            return false;
        }
        try {
            Class<?> apiClass = Class.forName("me.deecaad.weaponmechanics.WeaponMechanicsAPI");
            try {
                Object title = apiClass.getMethod("getWeaponTitle", ItemStack.class).invoke(null, itemStack);
                if (title instanceof String weaponTitle) {
                    return !weaponTitle.isBlank();
                }
            } catch (ReflectiveOperationException ignored) {
            }
            Object result = apiClass.getMethod("isWeapon", ItemStack.class).invoke(null, itemStack);
            return result instanceof Boolean bool && bool;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    /**
     * Reads held-weapon ammo/reload/scope state for HUD display.
     * Returns {@link WeaponHudSnapshot#empty()} when WeaponMechanics is unavailable or the player has no weapon.
     */
    public WeaponHudSnapshot readHeldWeaponStatus(org.bukkit.entity.Player player) {
        if (!present || player == null) {
            return WeaponHudSnapshot.empty();
        }
        org.bukkit.inventory.ItemStack held = player.getInventory().getItemInMainHand();
        if (!isWeaponItem(held)) {
            return WeaponHudSnapshot.empty();
        }
        String weaponTitle = resolveWeaponTitle(held);
        if (weaponTitle == null || weaponTitle.isBlank()) {
            return WeaponHudSnapshot.empty();
        }
        int ammoInClip = readAmmoInClip(held, weaponTitle);
        int clipSize = readMagazineSize(weaponTitle);
        boolean reloading = invokeBooleanOnEntity("me.deecaad.weaponmechanics.WeaponMechanicsAPI", "isReloading", player);
        boolean scoping = invokeBooleanOnEntity("me.deecaad.weaponmechanics.WeaponMechanicsAPI", "isScoping", player);
        if (!scoping) {
            double scopeLevel = invokeDoubleOnEntity("me.deecaad.weaponmechanics.WeaponMechanicsAPI", "getScopeLevel", player);
            scoping = scopeLevel > 0.0D;
        }
        int selectiveFire = readSelectiveFire(held);
        return new WeaponHudSnapshot(weaponTitle, ammoInClip, clipSize, reloading, scoping, selectiveFire);
    }

    private String resolveWeaponTitle(org.bukkit.inventory.ItemStack itemStack) {
        try {
            Class<?> apiClass = Class.forName("me.deecaad.weaponmechanics.WeaponMechanicsAPI");
            Object title = apiClass.getMethod("getWeaponTitle", org.bukkit.inventory.ItemStack.class).invoke(null, itemStack);
            if (title instanceof String weaponTitle && !weaponTitle.isBlank()) {
                return weaponTitle.trim();
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    private int readAmmoInClip(org.bukkit.inventory.ItemStack itemStack, String weaponTitle) {
        try {
            Class<?> wmClass = Class.forName("me.deecaad.weaponmechanics.WeaponMechanics");
            Object instance = wmClass.getMethod("getInstance").invoke(null);
            Object weaponHandler = wmClass.getMethod("getWeaponHandler").invoke(instance);
            Object reloadHandler = weaponHandler.getClass().getMethod("getReloadHandler").invoke(weaponHandler);
            Object value = reloadHandler.getClass()
                    .getMethod("getAmmoLeft", org.bukkit.inventory.ItemStack.class, String.class)
                    .invoke(reloadHandler, itemStack, weaponTitle);
            if (value instanceof Integer ammo) {
                return ammo;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return readAmmoLeftTag(itemStack);
    }

    private int readMagazineSize(String weaponTitle) {
        try {
            Class<?> wmClass = Class.forName("me.deecaad.weaponmechanics.WeaponMechanics");
            Object instance = wmClass.getMethod("getInstance").invoke(null);
            Object configs = wmClass.getMethod("getWeaponConfigurations").invoke(instance);
            Object value = configs.getClass().getMethod("getInt", String.class)
                    .invoke(configs, weaponTitle + ".Reload.Magazine_Size");
            if (value instanceof Integer size && size > 0) {
                return size;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return -1;
    }

    private int readAmmoLeftTag(org.bukkit.inventory.ItemStack itemStack) {
        try {
            Class<?> tagClass = Class.forName("me.deecaad.weaponmechanics.utils.CustomTag");
            Object ammoLeftTag = Enum.valueOf((Class<? extends Enum>) tagClass, "AMMO_LEFT");
            Object value = tagClass.getMethod("getInteger", org.bukkit.inventory.ItemStack.class).invoke(ammoLeftTag, itemStack);
            if (value instanceof Integer ammo) {
                return Math.max(0, ammo);
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return -1;
    }

    private int readSelectiveFire(org.bukkit.inventory.ItemStack itemStack) {
        try {
            Class<?> tagClass = Class.forName("me.deecaad.weaponmechanics.utils.CustomTag");
            Object tag = Enum.valueOf((Class<? extends Enum>) tagClass, "SELECTIVE_FIRE");
            if (!(Boolean) tagClass.getMethod("hasInteger", org.bukkit.inventory.ItemStack.class).invoke(tag, itemStack)) {
                return -1;
            }
            Object value = tagClass.getMethod("getInteger", org.bukkit.inventory.ItemStack.class).invoke(tag, itemStack);
            return value instanceof Integer mode ? mode : -1;
        } catch (ReflectiveOperationException ignored) {
            return -1;
        }
    }

    private boolean invokeBooleanOnEntity(String className, String methodName, org.bukkit.entity.LivingEntity entity) {
        try {
            Class<?> apiClass = Class.forName(className);
            Object result = apiClass.getMethod(methodName, org.bukkit.entity.LivingEntity.class).invoke(null, entity);
            return result instanceof Boolean bool && bool;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private double invokeDoubleOnEntity(String className, String methodName, org.bukkit.entity.LivingEntity entity) {
        try {
            Class<?> apiClass = Class.forName(className);
            Object result = apiClass.getMethod(methodName, org.bukkit.entity.LivingEntity.class).invoke(null, entity);
            return result instanceof Number number ? number.doubleValue() : 0.0D;
        } catch (ReflectiveOperationException ignored) {
            return 0.0D;
        }
    }

    public record WeaponHudSnapshot(
            String weaponTitle,
            int ammoInClip,
            int clipSize,
            boolean reloading,
            boolean scoping,
            int selectiveFire
    ) {
        public static WeaponHudSnapshot empty() {
            return new WeaponHudSnapshot("", -1, -1, false, false, -1);
        }

        public boolean hasWeapon() {
            return weaponTitle != null && !weaponTitle.isBlank();
        }

        public String displayName() {
            if (!hasWeapon()) {
                return "";
            }
            return weaponTitle.replace('_', ' ');
        }

        public String formattedClipAmmo() {
            if (ammoInClip < 0) {
                return "?";
            }
            if (clipSize > 0) {
                return ammoInClip + "/" + clipSize;
            }
            return String.valueOf(ammoInClip);
        }
    }
}
