package network.skypvp.extraction.item;

import network.skypvp.extraction.crafting.CraftingMaterialItemDefinition;
import network.skypvp.extraction.crafting.CraftingMaterialLoreContributor;
import network.skypvp.extraction.crafting.ItemConfigOverrides;
import network.skypvp.paper.item.api.CustomItemBehavior;
import network.skypvp.paper.item.api.CustomItemDefinition;
import network.skypvp.paper.item.api.CustomItemProvider;
import network.skypvp.paper.item.api.CustomItemService;
import network.skypvp.paper.item.api.CustomItemTypeId;
import network.skypvp.paper.item.api.LoreSectionContributor;
import network.skypvp.paper.item.api.StatContributor;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.FoodProperties;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;
import org.bukkit.Color;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public final class ExtractionCustomItemProvider implements CustomItemProvider {

    private static final InfuseChestplateDefinition INFUSE_CHESTPLATE = new InfuseChestplateDefinition();
    private static final InfuseHelmetDefinition INFUSE_HELMET = new InfuseHelmetDefinition();
    private static final InfuseLeggingsDefinition INFUSE_LEGGINGS = new InfuseLeggingsDefinition();
    private static final InfuseBootsDefinition INFUSE_BOOTS = new InfuseBootsDefinition();
    private static final ShieldModuleDefinition SHIELD_MODULE = new ShieldModuleDefinition();
    private static final ShieldRechargerDefinition SHIELD_RECHARGER = new ShieldRechargerDefinition();
    private static final ShieldRepairKitDefinition SHIELD_REPAIR_KIT = new ShieldRepairKitDefinition();
    private static final CraftingMaterialItemDefinition CRAFTING_MATERIAL = new CraftingMaterialItemDefinition();
    private static final BlueprintReceiptDefinition BLUEPRINT_RECEIPT = new BlueprintReceiptDefinition();
    private static final FlightRecorderDefinition FLIGHT_RECORDER = new FlightRecorderDefinition();
    private static final BackpackDefinition BACKPACK = new BackpackDefinition();
    private static final List<ArmorModuleDefinition> ARMOR_MODULES = Arrays.stream(ArmorModuleType.values())
            .map(ArmorModuleDefinition::new)
            .toList();
    private static final List<MedicConsumableDefinition> MEDIC_ITEMS = Arrays.stream(MedicConsumableType.values())
            .map(MedicConsumableDefinition::new)
            .toList();
    private static final InfuseArmorLoreContributor ARMOR_LORE = new InfuseArmorLoreContributor();
    private static final InfuseArmorStatContributor ARMOR_STATS = new InfuseArmorStatContributor();
    private static final ShieldModuleLoreContributor SHIELD_LORE = new ShieldModuleLoreContributor();
    private static final ShieldRechargerLoreContributor RECHARGER_LORE = new ShieldRechargerLoreContributor();
    private static final ShieldRepairKitLoreContributor REPAIR_KIT_LORE = new ShieldRepairKitLoreContributor();
    private static final ArmorModuleLoreContributor MODULE_LORE = new ArmorModuleLoreContributor();
    private static final MedicConsumableLoreContributor MEDIC_LORE = new MedicConsumableLoreContributor();
    private static final BlueprintReceiptLoreContributor BLUEPRINT_RECEIPT_LORE = new BlueprintReceiptLoreContributor();
    private static final FlightRecorderLoreContributor FLIGHT_RECORDER_LORE = new FlightRecorderLoreContributor();
    private static final BackpackLoreContributor BACKPACK_LORE = new BackpackLoreContributor();
    private static final CraftingMaterialLoreContributor CRAFTING_MATERIAL_LORE = new CraftingMaterialLoreContributor();

    @Override
    public String modeKey() {
        return "extraction";
    }

    @Override
    public Collection<CustomItemDefinition> definitions() {
        List<CustomItemDefinition> all = new ArrayList<>();
        all.add(INFUSE_CHESTPLATE);
        all.add(INFUSE_HELMET);
        all.add(INFUSE_LEGGINGS);
        all.add(INFUSE_BOOTS);
        all.add(SHIELD_MODULE);
        all.add(SHIELD_RECHARGER);
        all.add(SHIELD_REPAIR_KIT);
        all.add(CRAFTING_MATERIAL);
        all.add(BLUEPRINT_RECEIPT);
        all.add(FLIGHT_RECORDER);
        all.add(BACKPACK);
        all.addAll(ARMOR_MODULES);
        all.addAll(MEDIC_ITEMS);
        return all;
    }

    @Override
    public Optional<CustomItemBehavior> behavior(CustomItemTypeId typeId) {
        return Optional.empty();
    }

    @Override
    public Optional<StatContributor> statContributor(CustomItemTypeId typeId) {
        if (InfuseArmorPiece.isInfuseArmorTypeId(typeId)) {
            return Optional.of(ARMOR_STATS);
        }
        return Optional.empty();
    }

    @Override
    public Optional<LoreSectionContributor> loreContributor(CustomItemTypeId typeId) {
        if (InfuseArmorPiece.isInfuseArmorTypeId(typeId)) {
            return Optional.of(ARMOR_LORE);
        }
        if (SHIELD_MODULE.typeId().equals(typeId)) {
            return Optional.of(SHIELD_LORE);
        }
        if (SHIELD_RECHARGER.typeId().equals(typeId)) {
            return Optional.of(RECHARGER_LORE);
        }
        if (BACKPACK.typeId().equals(typeId)) {
            return Optional.of(BACKPACK_LORE);
        }
        if (SHIELD_REPAIR_KIT.typeId().equals(typeId)) {
            return Optional.of(REPAIR_KIT_LORE);
        }
        if (ArmorModuleType.isModuleTypeId(typeId)) {
            return Optional.of(MODULE_LORE);
        }
        if (MedicConsumableType.isMedicTypeId(typeId)) {
            return Optional.of(MEDIC_LORE);
        }
        if (BLUEPRINT_RECEIPT.typeId().equals(typeId)) {
            return Optional.of(BLUEPRINT_RECEIPT_LORE);
        }
        if (FLIGHT_RECORDER.typeId().equals(typeId)) {
            return Optional.of(FLIGHT_RECORDER_LORE);
        }
        if (CRAFTING_MATERIAL.typeId().equals(typeId)) {
            return Optional.of(CRAFTING_MATERIAL_LORE);
        }
        return Optional.empty();
    }

    /** Equipment asset (armor layers + wings-layer cape) worn by legendary infuse pieces. */
    private static final net.kyori.adventure.key.Key LEGENDARY_EQUIPMENT_ASSET =
            net.kyori.adventure.key.Key.key("skypvp", "infuse_legendary");

    public static ItemStack createInfuseArmor(CustomItemService service, InfuseArmorPiece piece, GearRarity rarity, ArmorSet set) {
        InfuseArmorPayload payload = InfuseArmorPayload.forPiece(piece, rarity, set);
        ItemStack stack = service.create(piece.typeId(), builder -> builder.payload(payload.encode()));
        applyLegendaryEquipmentLook(stack, piece, rarity);
        return stack;
    }

    public static ItemStack createInfuseChestplate(CustomItemService service, GearRarity rarity) {
        InfuseArmorPayload payload = InfuseArmorPayload.defaults(rarity);
        ItemStack stack = service.create(InfuseChestplateDefinition.TYPE_ID, builder -> builder.payload(payload.encode()));
        applyLegendaryEquipmentLook(stack, InfuseArmorPiece.CHESTPLATE, rarity);
        return stack;
    }

    public static ItemStack createInfuseChestplate(CustomItemService service, GearRarity rarity, ArmorMark mark) {
        InfuseArmorPayload payload = InfuseArmorPayload.defaults(rarity).withMark(mark);
        ItemStack stack = service.create(InfuseChestplateDefinition.TYPE_ID, builder -> builder.payload(payload.encode()));
        applyLegendaryEquipmentLook(stack, InfuseArmorPiece.CHESTPLATE, rarity);
        return stack;
    }

    public static ItemStack createInfuseChestplate(CustomItemService service, GearRarity rarity, ArmorMark mark, ArmorSet set) {
        InfuseArmorPayload payload = InfuseArmorPayload.defaults(rarity).withMark(mark).withArmorSet(set);
        ItemStack stack = service.create(InfuseChestplateDefinition.TYPE_ID, builder -> builder.payload(payload.encode()));
        applyLegendaryEquipmentLook(stack, InfuseArmorPiece.CHESTPLATE, rarity);
        return stack;
    }

    /**
     * Legendary pieces wear the custom "Aetherforged" equipment model — humanoid armor
     * layers plus a wings-layer cape (generated by Generate-InfuseLegendaryEquipment.ps1).
     * Lower rarities keep the vanilla netherite look, so the skin doubles as a status flex.
     */
    public static void applyLegendaryEquipmentLook(ItemStack stack, InfuseArmorPiece piece, GearRarity rarity) {
        if (stack == null || piece == null || rarity != GearRarity.LEGENDARY) {
            return;
        }
        org.bukkit.inventory.EquipmentSlot slot = switch (piece) {
            case HELMET -> org.bukkit.inventory.EquipmentSlot.HEAD;
            case CHESTPLATE -> org.bukkit.inventory.EquipmentSlot.CHEST;
            case LEGGINGS -> org.bukkit.inventory.EquipmentSlot.LEGS;
            case BOOTS -> org.bukkit.inventory.EquipmentSlot.FEET;
        };
        stack.setData(
                DataComponentTypes.EQUIPPABLE,
                io.papermc.paper.datacomponent.item.Equippable.equippable(slot)
                        .assetId(LEGENDARY_EQUIPMENT_ASSET)
                        .build()
        );
    }

    /** Whether {@code stack} already wears the legendary equipment asset. */
    public static boolean hasLegendaryEquipmentLook(ItemStack stack) {
        if (stack == null) {
            return false;
        }
        io.papermc.paper.datacomponent.item.Equippable equippable = stack.getData(DataComponentTypes.EQUIPPABLE);
        return equippable != null && LEGENDARY_EQUIPMENT_ASSET.equals(equippable.assetId());
    }

    public static ItemStack createMedicConsumable(CustomItemService service, MedicConsumableType type) {
        ItemStack item = service.create(type.typeId(), builder -> {
        });
        if (item != null && type.isHealing()) {
            applyHealingConsumable(item, type);
        }
        if (item != null) {
            // Stackable in raid inventories; cap is config-driven (stats.stackSize, default 8).
            item.setData(DataComponentTypes.MAX_STACK_SIZE, ItemConfigOverrides.medicStackSize(type));
        }
        return item;
    }

    /** Makes healing supplies eatable so activation waits for the vanilla consume cast. */
    public static void applyHealingConsumable(ItemStack item, MedicConsumableType type) {
        if (item == null || type == null || !type.isHealing()) {
            return;
        }
        float seconds = ItemConfigOverrides.medicConsumeSeconds(type);
        item.setData(
                DataComponentTypes.FOOD,
                FoodProperties.food()
                        .nutrition(0)
                        .saturation(0.0F)
                        .canAlwaysEat(true)
                        .build()
        );
        item.setData(
                DataComponentTypes.CONSUMABLE,
                Consumable.consumable()
                        .consumeSeconds(seconds)
                        .animation(ItemUseAnimation.EAT)
                        .hasConsumeParticles(false)
                        .build()
        );
    }

    public static ItemStack createShieldModule(CustomItemService service, GearRarity rarity) {
        ShieldModulePayload payload = ShieldModulePayload.defaults(rarity);
        return service.create(ShieldModuleDefinition.TYPE_ID, builder -> builder.payload(payload.encode()));
    }

    public static ItemStack createShieldModule(CustomItemService service, GearRarity rarity, String variantId) {
        ShieldModulePayload payload = new ShieldModulePayload(rarity, variantId);
        return service.create(ShieldModuleDefinition.TYPE_ID, builder -> builder.payload(payload.encode()));
    }

    public static ItemStack createArmorModule(CustomItemService service, ArmorModuleType type) {
        return service.create(type.typeId(), builder -> {
        });
    }

    /** Mints a tier-N raid backpack wearing the default skin. */
    public static ItemStack createBackpack(CustomItemService service, int tier) {
        return createBackpack(service, tier, BackpackSkins.DEFAULT_ID);
    }

    /** Mints a tier-N raid backpack with the {@code skypvp:backpack_t<n>_<skin>} pack model. */
    public static ItemStack createBackpack(CustomItemService service, int tier, String skinId) {
        int clamped = Math.max(1, Math.min(BackpackDefinition.MAX_TIER, tier));
        String skin = BackpackSkins.byId(skinId).id();
        BackpackPayload payload = BackpackPayload.empty(clamped).withSkin(skin);
        ItemStack item = service.create(BackpackDefinition.TYPE_ID, builder -> builder.payload(payload.encode()));
        if (item != null) {
            // Per-tier+skin sprite; the shared definition-level itemModel() hook can't vary by payload.
            item.editMeta(meta -> meta.setItemModel(BackpackSkins.modelKey(clamped, skin)));
        }
        return item;
    }

    public static ItemStack createShieldRecharger(CustomItemService service, RechargerTier tier) {
        ShieldRechargerPayload payload = ShieldRechargerPayload.of(tier);
        ItemStack item = service.create(ShieldRechargerDefinition.TYPE_ID, builder -> builder.payload(payload.encode()));
        if (item != null && item.getItemMeta() instanceof PotionMeta potionMeta) {
            potionMeta.setColor(Color.fromRGB(0, 200, 255));
            item.setItemMeta(potionMeta);
        }
        if (item != null) {
            // Per-tier sprite (window color + pip count) from the skypvp pack; the shared
            // definition-level itemModel() hook can't vary by payload tier.
            org.bukkit.NamespacedKey model = shieldRechargerModel(payload.tier());
            item.editMeta(meta -> meta.setItemModel(model));
        }
        return item;
    }

    /** {@code skypvp:shield_recharger_<tier>} — generated by Generate-CraftingItemArt.ps1. */
    public static org.bukkit.NamespacedKey shieldRechargerModel(RechargerTier tier) {
        RechargerTier resolved = tier == null ? RechargerTier.FIELD : tier;
        return new org.bukkit.NamespacedKey(
                "skypvp", "shield_recharger_" + resolved.name().toLowerCase(java.util.Locale.ROOT));
    }

    public static ItemStack createShieldRepairKit(CustomItemService service) {
        return service.create(ShieldRepairKitDefinition.TYPE_ID, builder -> {
        });
    }

    public static ItemStack createBlueprintReceipt(CustomItemService service, String blueprintId) {
        BlueprintReceiptPayload payload = BlueprintReceiptPayload.of(blueprintId);
        return service.create(BlueprintReceiptDefinition.TYPE_ID, builder -> builder.payload(payload.encode()));
    }

    public static ItemStack createFlightRecorder(CustomItemService service) {
        return service.create(FlightRecorderDefinition.TYPE_ID, builder -> {
        });
    }

    public static boolean isFlightRecorder(CustomItemService service, ItemStack stack) {
        if (service == null || stack == null || stack.getType().isAir()) {
            return false;
        }
        return service.resolve(stack)
                .map(instance -> FlightRecorderDefinition.TYPE_ID.equals(instance.typeId()))
                .orElse(false);
    }

    public static boolean hasFlightRecorder(CustomItemService service, org.bukkit.entity.Player player) {
        if (service == null || player == null) {
            return false;
        }
        for (ItemStack stack : player.getInventory().getContents()) {
            if (isFlightRecorder(service, stack)) {
                return true;
            }
        }
        return false;
    }

    /** Removes one flight recorder from the player's inventory. */
    public static boolean consumeFlightRecorder(CustomItemService service, org.bukkit.entity.Player player) {
        if (service == null || player == null) {
            return false;
        }
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (!isFlightRecorder(service, stack)) {
                continue;
            }
            int amount = stack.getAmount();
            if (amount <= 1) {
                player.getInventory().setItem(i, null);
            } else {
                stack.setAmount(amount - 1);
            }
            return true;
        }
        return false;
    }
}
