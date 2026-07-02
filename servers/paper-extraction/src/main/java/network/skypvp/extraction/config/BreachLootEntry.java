package network.skypvp.extraction.config;

import java.util.Optional;
import network.skypvp.extraction.integration.WeaponMechanicsBridge;
import network.skypvp.paper.library.ItemStackCodec;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public sealed interface BreachLootEntry permits
        BreachLootEntry.MaterialEntry,
        BreachLootEntry.CodecEntry,
        BreachLootEntry.WeaponMechanicsEntry {

    int amount();

    double chance();

    Optional<ItemStack> createItemStack(WeaponMechanicsBridge weaponMechanicsBridge);

    record MaterialEntry(Material material, int amount, double chance) implements BreachLootEntry {
        @Override
        public Optional<ItemStack> createItemStack(WeaponMechanicsBridge weaponMechanicsBridge) {
            return Optional.of(new ItemStack(material, amount));
        }
    }

    record CodecEntry(String payload, int amount, double chance) implements BreachLootEntry {
        @Override
        public Optional<ItemStack> createItemStack(WeaponMechanicsBridge weaponMechanicsBridge) {
            try {
                ItemStack decoded = ItemStackCodec.decode(payload);
                decoded.setAmount(amount);
                return Optional.of(decoded);
            } catch (RuntimeException ignored) {
                return Optional.empty();
            }
        }
    }

    record WeaponMechanicsEntry(String weaponTitle, int amount, double chance) implements BreachLootEntry {
        @Override
        public Optional<ItemStack> createItemStack(WeaponMechanicsBridge weaponMechanicsBridge) {
            return weaponMechanicsBridge.generateWeapon(weaponTitle, amount);
        }
    }
}
