package network.skypvp.extraction.config;

import java.util.Optional;
import network.skypvp.extraction.gameplay.ExtractionLootFactory;
import network.skypvp.extraction.integration.WeaponMechanicsBridge;
import network.skypvp.paper.library.ItemStackCodec;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public sealed interface BreachLootEntry permits
        BreachLootEntry.MaterialEntry,
        BreachLootEntry.CodecEntry,
        BreachLootEntry.WeaponMechanicsEntry,
        BreachLootEntry.BlueprintReceiptEntry,
        BreachLootEntry.CustomItemEntry {

    int amount();

    double chance();

    Optional<ItemStack> createItemStack(WeaponMechanicsBridge weaponMechanicsBridge, ExtractionLootFactory extractionLoot);

    record MaterialEntry(Material material, int amount, double chance) implements BreachLootEntry {
        @Override
        public Optional<ItemStack> createItemStack(WeaponMechanicsBridge weaponMechanicsBridge, ExtractionLootFactory extractionLoot) {
            return Optional.of(new ItemStack(material, amount));
        }
    }

    record CodecEntry(String payload, int amount, double chance) implements BreachLootEntry {
        @Override
        public Optional<ItemStack> createItemStack(WeaponMechanicsBridge weaponMechanicsBridge, ExtractionLootFactory extractionLoot) {
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
        public Optional<ItemStack> createItemStack(WeaponMechanicsBridge weaponMechanicsBridge, ExtractionLootFactory extractionLoot) {
            return weaponMechanicsBridge.generateWeapon(weaponTitle, amount);
        }
    }

    record BlueprintReceiptEntry(String blueprintId, int amount, double chance) implements BreachLootEntry {
        @Override
        public Optional<ItemStack> createItemStack(WeaponMechanicsBridge weaponMechanicsBridge, ExtractionLootFactory extractionLoot) {
            if (extractionLoot == null) {
                return Optional.empty();
            }
            return extractionLoot.blueprintReceipt(blueprintId, amount);
        }
    }

    /** SkyPvP extraction custom item resolved by {@link ExtractionLootFactory#customItem(String, int)}. */
    record CustomItemEntry(String itemSpec, int amount, double chance) implements BreachLootEntry {
        @Override
        public Optional<ItemStack> createItemStack(WeaponMechanicsBridge weaponMechanicsBridge, ExtractionLootFactory extractionLoot) {
            if (extractionLoot == null) {
                return Optional.empty();
            }
            return extractionLoot.customItem(itemSpec, amount);
        }
    }
}
