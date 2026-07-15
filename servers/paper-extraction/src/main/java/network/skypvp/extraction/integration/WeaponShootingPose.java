package network.skypvp.extraction.integration;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ChargedProjectiles;
import io.papermc.paper.datacomponent.item.CustomModelData;
import java.util.Locale;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Vanilla {@code CROSSBOW_HOLD} cannot be forced by a pose/metadata packet — the client only
 * picks that arm pose when it believes the entity holds a <em>charged crossbow</em>.
 * There is no server-writable ArmPose field.
 *
 * <p>So the only vanilla-compatible approach is a packet-only cosmetic item: same
 * {@code custom_model_data} / {@code item_model} as the real FEATHER gun, but type CROSSBOW
 * + charged projectiles. Viewers need {@code assets/minecraft/items/crossbow.json} mirroring
 * feather gun models or they will see a vanilla crossbow.
 */
public final class WeaponShootingPose {

    private static final Set<String> NON_FIREARM_TITLES = Set.of(
            "combat_knife",
            "stim",
            "grenade",
            "semtex",
            "flashbang",
            "cluster_grenade",
            "airstrike",
            "sky_torch"
    );

    private WeaponShootingPose() {
    }

    public static boolean usesFirearmHoldPose(ItemStack stack, WeaponMechanicsBridge weaponBridge) {
        if (stack == null || stack.getType().isAir() || weaponBridge == null || !weaponBridge.isAvailable()) {
            return false;
        }
        if (!weaponBridge.isWeaponItem(stack)) {
            return false;
        }
        String title = weaponBridge.weaponTitle(stack).orElse("").trim().toLowerCase(Locale.ROOT);
        return !title.isEmpty() && !NON_FIREARM_TITLES.contains(title);
    }

    /**
     * Packet-only clone for equipment spoof. Preserves model components via {@link ItemStack#withType}.
     */
    public static ItemStack cosmeticChargedCrossbow(ItemStack real, WeaponMechanicsBridge weaponBridge) {
        if (!usesFirearmHoldPose(real, weaponBridge)) {
            return null;
        }
        // Capture model bits before type change (setType drops them; withType keeps meta).
        CustomModelData cmd = real.getData(DataComponentTypes.CUSTOM_MODEL_DATA);
        net.kyori.adventure.key.Key itemModel = real.getData(DataComponentTypes.ITEM_MODEL);
        Integer legacyCmd = null;
        if (real.hasItemMeta() && real.getItemMeta().hasCustomModelData()) {
            legacyCmd = real.getItemMeta().getCustomModelData();
        }

        ItemStack cosmetic = real.getType() == Material.CROSSBOW
                ? real.clone()
                : real.clone().withType(Material.CROSSBOW);

        if (cmd != null) {
            cosmetic.setData(DataComponentTypes.CUSTOM_MODEL_DATA, cmd);
        } else if (legacyCmd != null) {
            final int cmdValue = legacyCmd;
            cosmetic.editMeta(meta -> meta.setCustomModelData(cmdValue));
        }
        if (itemModel != null) {
            cosmetic.setData(DataComponentTypes.ITEM_MODEL, itemModel);
        }

        cosmetic.setData(
                DataComponentTypes.CHARGED_PROJECTILES,
                ChargedProjectiles.chargedProjectiles()
                        .add(ItemStack.of(Material.ARROW))
                        .build()
        );
        return cosmetic;
    }
}
