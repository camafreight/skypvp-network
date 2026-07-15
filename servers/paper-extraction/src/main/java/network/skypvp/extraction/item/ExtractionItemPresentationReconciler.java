package network.skypvp.extraction.item;

import java.util.Locale;
import java.util.Optional;
import java.util.function.UnaryOperator;
import network.skypvp.extraction.crafting.CraftingConfigService;
import network.skypvp.extraction.crafting.CraftingMaterialDefinition;
import network.skypvp.extraction.crafting.CraftingMaterialItemDefinition;
import network.skypvp.extraction.crafting.CraftingMaterialItemPayload;
import network.skypvp.paper.item.api.CustomItemInstance;
import network.skypvp.paper.item.api.CustomItemService;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Modernizes persisted custom item stacks whose PRESENTATION predates the current build:
 * crafting materials, medic consumables, and shield rechargers get their current display
 * {@link Material} and {@code skypvp:*} item model re-applied (plus a name/lore refresh)
 * while payload, instance identity, and amount are preserved.
 *
 * <p>Registered with {@link CustomItemService#registerReconciler}; runs wherever stored
 * stacks re-enter the world — vault decode, material stash decode, and the join-time
 * inventory sweep. This is what upgrades stashes full of stacks minted before an item's
 * material or art changed.
 */
public final class ExtractionItemPresentationReconciler implements UnaryOperator<ItemStack> {

    private final CustomItemService itemService;
    private final CraftingConfigService craftingConfig;

    public ExtractionItemPresentationReconciler(CustomItemService itemService, CraftingConfigService craftingConfig) {
        this.itemService = itemService;
        this.craftingConfig = craftingConfig;
    }

    @Override
    public ItemStack apply(ItemStack stack) {
        if (stack == null || itemService == null) {
            return null;
        }
        Optional<CustomItemInstance> resolved = itemService.resolve(stack);
        if (resolved.isEmpty()) {
            return null;
        }
        CustomItemInstance instance = resolved.get();

        // Legendary infuse armor minted before the Aetherforged equipment model shipped:
        // re-apply the EQUIPPABLE asset (worn-model + cape), not a material/item-model swap.
        Optional<InfuseArmorPiece> armorPiece = InfuseArmorPiece.byTypeId(instance.typeId());
        if (armorPiece.isPresent()) {
            InfuseArmorPayload payload = InfuseArmorPayload.decode(instance.payloadCopy());
            if (payload.rarity() != GearRarity.LEGENDARY
                    || ExtractionCustomItemProvider.hasLegendaryEquipmentLook(stack)) {
                return null;
            }
            ItemStack updated = stack.clone();
            ExtractionCustomItemProvider.applyLegendaryEquipmentLook(updated, armorPiece.get(), payload.rarity());
            return updated;
        }

        Material expectedType;
        NamespacedKey expectedModel;
        if (CraftingMaterialItemDefinition.TYPE_ID.equals(instance.typeId())) {
            String materialId = CraftingMaterialItemPayload.decode(instance.payloadCopy()).materialId();
            if (materialId == null || materialId.isBlank() || craftingConfig == null) {
                return null;
            }
            String normalized = materialId.trim().toLowerCase(Locale.ROOT);
            CraftingMaterialDefinition def =
                    CraftingMaterialDefinition.byId(normalized, craftingConfig.materials()).orElse(null);
            if (def == null) {
                return null;
            }
            expectedType = def.icon();
            expectedModel = new NamespacedKey("skypvp", "mat_" + normalized);
        } else if (ShieldRechargerDefinition.TYPE_ID.equals(instance.typeId())) {
            RechargerTier tier = ShieldRechargerPayload.decode(instance.payloadCopy()).tier();
            expectedType = Material.POTION;
            expectedModel = ExtractionCustomItemProvider.shieldRechargerModel(tier);
        } else if (ShieldModuleDefinition.TYPE_ID.equals(instance.typeId())) {
            expectedType = Material.HEAVY_CORE;
            expectedModel = new NamespacedKey("skypvp", "shield_module");
        } else if (BlueprintReceiptDefinition.TYPE_ID.equals(instance.typeId())) {
            expectedType = Material.PAPER;
            expectedModel = new NamespacedKey("skypvp", "blueprint");
        } else if (BackpackDefinition.TYPE_ID.equals(instance.typeId())) {
            // Migrates legacy BUNDLE packs → inert PAPER (so offhand no longer steals gun fire),
            // and re-applies skin models after payload changes.
            BackpackPayload payload = BackpackPayload.decode(instance.payloadCopy());
            expectedType = Material.PAPER;
            expectedModel = BackpackSkins.modelKey(payload.tier(), payload.skin());
        } else {
            MedicConsumableType medic = medicTypeOf(instance);
            if (medic == null) {
                return null;
            }
            expectedType = medic.material();
            expectedModel = new NamespacedKey("skypvp", "medic_" + medic.id());
        }

        if (isCurrent(stack, expectedType, expectedModel)) {
            return null;
        }
        ItemStack updated = stack.clone();
        updated.setType(expectedType);
        NamespacedKey model = expectedModel;
        updated.editMeta(meta -> meta.setItemModel(model));
        ItemStack refreshed = itemService.refreshPresentation(updated, null);
        if (refreshed != null) {
            updated = refreshed;
        }
        updated.setAmount(stack.getAmount());
        return updated;
    }

    private static boolean isCurrent(ItemStack stack, Material expectedType, NamespacedKey expectedModel) {
        if (stack.getType() != expectedType) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta != null && meta.hasItemModel() && expectedModel.equals(meta.getItemModel());
    }

    private static MedicConsumableType medicTypeOf(CustomItemInstance instance) {
        for (MedicConsumableType type : MedicConsumableType.values()) {
            if (type.typeId().equals(instance.typeId())) {
                return type;
            }
        }
        return null;
    }
}
