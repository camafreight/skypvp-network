package network.skypvp.extraction.integration;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class WeaponMechanicsBridge {

    private final Logger logger;
    private final boolean present;
    private final Method generateWeapon;

    public WeaponMechanicsBridge(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        this.logger = plugin.getLogger();
        Method weaponGenerator = null;
        boolean available = false;

        try {
            Class<?> apiClass = Class.forName("me.deecaad.weaponmechanics.WeaponMechanicsAPI");
            weaponGenerator = apiClass.getMethod("generateWeapon", String.class);
            available = true;
        } catch (ReflectiveOperationException ignored) {
            available = false;
        }

        this.present = available;
        this.generateWeapon = weaponGenerator;

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
