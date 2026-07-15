package network.skypvp.extraction.item;

import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.item.api.CustomItemInstance;
import network.skypvp.paper.item.api.CustomItemService;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/**
 * Applies the socketed shield buffer to incoming breach damage. The shield absorbs damage <em>after</em> Infuse
 * defense reduction and <em>before</em> health, depleting its rechargeable buffer and accumulating lifetime damage
 * toward permanent destruction.
 */
public final class ShieldCombatService {

    public record ShieldOutcome(
            boolean present,
            boolean active,
            double absorbed,
            boolean depletedThisHit,
            boolean destroyedThisHit,
            double currentPoints,
            double maxPoints,
            double lifetimeAbsorbed,
            double integrity,
            boolean destroyed,
            GearRarity rarity,
            String variantLabel
    ) {
        public static ShieldOutcome none() {
            return new ShieldOutcome(false, false, 0.0D, false, false,
                    0.0D, 0.0D, 0.0D, 0.0D, false, null, null);
        }

        public boolean logWorthy() {
            return present;
        }
    }

    private ShieldCombatService() {
    }

    public static ShieldOutcome absorb(PaperCorePlugin core, Player victim, EntityDamageEvent event) {
        if (core == null || core.customItemService() == null || victim == null || event == null) {
            return ShieldOutcome.none();
        }
        CustomItemService service = core.customItemService();
        ItemStack chest = victim.getInventory().getChestplate();
        if (!InfuseArmorMutator.isInfuseChestplate(service, chest)) {
            return ShieldOutcome.none();
        }
        Optional<CustomItemInstance> instanceOpt = service.resolve(chest);
        if (instanceOpt.isEmpty()) {
            return ShieldOutcome.none();
        }
        InfuseArmorPayload armor = InfuseArmorPayload.decode(instanceOpt.get().payloadCopy());
        Optional<ShieldSocketReference> refOpt = ShieldSocketReference.decode(armor.shieldModule());
        if (refOpt.isEmpty()) {
            return ShieldOutcome.none();
        }
        ShieldSocketReference shield = refOpt.get();

        ArmorMark mark = armor.mark() == null ? ArmorMark.MK1 : armor.mark();
        boolean markCompatible = mark.isAtLeast(ArmorMark.requiredForShield(shield.shieldRarity()));

        if (shield.destroyed() || !markCompatible || !shield.isActive()) {
            return present(shield, 0.0D, false, false);
        }

        double incoming = Math.max(0.0D, event.getFinalDamage());
        if (incoming <= 0.0D) {
            return present(shield, 0.0D, false, false);
        }

        double byBuffer = Math.min(incoming, shield.currentPoints());
        double byIntegrity = Math.min(byBuffer, shield.remainingIntegrity());
        double absorbed = Math.max(0.0D, byIntegrity);
        if (absorbed <= 0.0D) {
            return present(shield, 0.0D, false, false);
        }

        double newPoints = Math.max(0.0D, shield.currentPoints() - absorbed);
        double newLifetime = shield.lifetimeAbsorbed() + absorbed;
        boolean destroyedNow = newLifetime >= shield.integrity();
        if (destroyedNow) {
            newPoints = 0.0D;
        }
        boolean depletedNow = !destroyedNow && newPoints <= 0.0D;

        ShieldSocketReference updatedShield = shield.withState(newPoints, newLifetime, destroyedNow);
        InfuseArmorPayload updatedArmor = armor.withShield(updatedShield);
        ItemStack updatedChest = service.updatePayload(chest, ignored -> updatedArmor.encode());
        victim.getInventory().setChestplate(updatedChest);

        ExtractionCombatDefense.scaleDamageToFinal(event, Math.max(0.0D, incoming - absorbed));

        if (destroyedNow) {
            ShieldFeedback.destroyed(victim);
        } else if (depletedNow) {
            ShieldFeedback.depleted(victim);
        } else {
            ShieldFeedback.absorb(victim, absorbed);
        }

        return new ShieldOutcome(
                true,
                true,
                absorbed,
                depletedNow,
                destroyedNow,
                newPoints,
                updatedShield.maxPoints(),
                newLifetime,
                updatedShield.integrity(),
                destroyedNow,
                updatedShield.shieldRarity(),
                updatedShield.variantDisplay()
        );
    }

    private static ShieldOutcome present(ShieldSocketReference shield, double absorbed, boolean depleted, boolean destroyed) {
        return new ShieldOutcome(
                true,
                shield.isActive() && !shield.destroyed(),
                absorbed,
                depleted,
                destroyed,
                shield.currentPoints(),
                shield.maxPoints(),
                shield.lifetimeAbsorbed(),
                shield.integrity(),
                shield.destroyed(),
                shield.shieldRarity(),
                shield.variantDisplay()
        );
    }

    /** Read-only summary of the currently socketed shield, for status/debug commands. */
    public static Optional<ShieldSocketReference> equippedShield(PaperCorePlugin core, Player player) {
        if (core == null || core.customItemService() == null || player == null) {
            return Optional.empty();
        }
        CustomItemService service = core.customItemService();
        ItemStack chest = player.getInventory().getChestplate();
        if (!InfuseArmorMutator.isInfuseChestplate(service, chest)) {
            return Optional.empty();
        }
        return service.resolve(chest)
                .map(instance -> InfuseArmorPayload.decode(instance.payloadCopy()))
                .flatMap(payload -> ShieldSocketReference.decode(payload.shieldModule()));
    }
}
