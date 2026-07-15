package network.skypvp.extraction.item;

import network.skypvp.paper.item.api.CustomItemService;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.List;
import java.util.Optional;

public final class InfuseArmorMutator {

    private InfuseArmorMutator() {
    }

    public static Optional<ItemStack> findInfuseChestplate(CustomItemService service, Player player) {
        if (service == null || player == null) {
            return Optional.empty();
        }
        ItemStack chest = player.getInventory().getChestplate();
        if (isInfuseChestplate(service, chest)) {
            return Optional.of(chest);
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (isInfuseChestplate(service, hand)) {
            return Optional.of(hand);
        }
        return Optional.empty();
    }

    public static boolean isInfuseChestplate(CustomItemService service, ItemStack stack) {
        return isInfuseArmorPiece(service, stack, InfuseArmorPiece.CHESTPLATE);
    }

    public static boolean isInfuseArmor(CustomItemService service, ItemStack stack) {
        if (service == null || stack == null || stack.getType().isAir() || !service.isCustomItem(stack)) {
            return false;
        }
        return service.resolve(stack).flatMap(instance -> InfuseArmorPiece.byTypeId(instance.typeId())).isPresent();
    }

    public static boolean isInfuseArmorPiece(CustomItemService service, ItemStack stack, InfuseArmorPiece piece) {
        if (service == null || stack == null || piece == null || stack.getType().isAir() || !service.isCustomItem(stack)) {
            return false;
        }
        return service.resolve(stack)
                .map(instance -> piece.typeId().equals(instance.typeId()))
                .orElse(false);
    }

    public static Optional<InfuseArmorPiece> armorPieceOf(CustomItemService service, ItemStack stack) {
        if (!isInfuseArmor(service, stack)) {
            return Optional.empty();
        }
        return service.resolve(stack).flatMap(instance -> InfuseArmorPiece.byTypeId(instance.typeId()));
    }

    public static boolean isArmorModule(CustomItemService service, ItemStack stack) {
        return moduleTypeOf(service, stack).isPresent();
    }

    public static Optional<ArmorModuleType> moduleTypeOf(CustomItemService service, ItemStack stack) {
        if (service == null || stack == null || stack.getType().isAir() || !service.isCustomItem(stack)) {
            return Optional.empty();
        }
        return service.resolve(stack).flatMap(instance -> ArmorModuleType.byTypeId(instance.typeId()));
    }

    /** Result of a module/overclock socket mutation on a specific armor stack (for GUI-driven edits). */
    public record ModuleMutation(boolean success, String message, ItemStack updatedArmor) {
        public static ModuleMutation failure(String message, ItemStack armorStack) {
            return new ModuleMutation(false, message, armorStack);
        }

        public static ModuleMutation success(ItemStack updatedArmor) {
            return new ModuleMutation(true, null, updatedArmor);
        }
    }

    public static ModuleMutation installModule(CustomItemService service, ItemStack armorStack, int socketIndex, ArmorModuleType type) {
        Optional<InfuseArmorPiece> pieceOpt = armorPieceOf(service, armorStack);
        if (pieceOpt.isEmpty()) {
            return ModuleMutation.failure("Place Infuse armor first.", armorStack);
        }
        InfuseArmorPiece piece = pieceOpt.get();
        if (type == null || type.overclock()) {
            return ModuleMutation.failure("That module cannot go in a module socket.", armorStack);
        }
        InfuseArmorPayload payload = decodePayload(service, armorStack);
        String conflict = ModuleSlotRules.conflictReason(payload, piece, type);
        if (conflict != null) {
            return ModuleMutation.failure(conflict, armorStack);
        }
        int sockets = payload.rarity().moduleSockets();
        if (socketIndex < 0 || socketIndex >= sockets) {
            return ModuleMutation.failure("This armor has no module socket " + (socketIndex + 1) + ".", armorStack);
        }
        String existing = payload.moduleSockets().get(socketIndex);
        if (existing != null && !existing.isBlank()) {
            return ModuleMutation.failure("That module socket is already filled.", armorStack);
        }
        InfuseArmorPayload updated = payload.withModule(socketIndex, type.id());
        return ModuleMutation.success(service.updatePayload(armorStack, ignored -> updated.encode()));
    }

    public static ModuleMutation removeModule(CustomItemService service, ItemStack armorStack, int socketIndex) {
        Optional<InfuseArmorPiece> pieceOpt = armorPieceOf(service, armorStack);
        if (pieceOpt.isEmpty()) {
            return ModuleMutation.failure("Place Infuse armor first.", armorStack);
        }
        InfuseArmorPayload payload = decodePayload(service, armorStack);
        List<String> modules = payload.moduleSockets();
        if (socketIndex < 0 || socketIndex >= modules.size()
                || modules.get(socketIndex) == null || modules.get(socketIndex).isBlank()) {
            return ModuleMutation.failure("That module socket is empty.", armorStack);
        }
        InfuseArmorPayload updated = payload.withoutModule(socketIndex);
        return ModuleMutation.success(service.updatePayload(armorStack, ignored -> updated.encode()));
    }

    public static ModuleMutation installOverclock(CustomItemService service, ItemStack armorStack, ArmorModuleType type) {
        if (!isInfuseChestplate(service, armorStack)) {
            return ModuleMutation.failure("Place an Infuse chestplate first.", armorStack);
        }
        if (type == null || !type.overclock()) {
            return ModuleMutation.failure("Only overclock modules fit the overclock socket.", armorStack);
        }
        InfuseArmorPayload payload = decodePayload(service, armorStack);
        if (payload.rarity().overclockSockets() <= 0) {
            return ModuleMutation.failure("This armor rarity has no overclock socket.", armorStack);
        }
        if (payload.overclockModule() != null && !payload.overclockModule().isBlank()) {
            return ModuleMutation.failure("The overclock socket is already filled.", armorStack);
        }
        InfuseArmorPayload updated = payload.withOverclock(type.id());
        return ModuleMutation.success(service.updatePayload(armorStack, ignored -> updated.encode()));
    }

    public static ModuleMutation removeOverclock(CustomItemService service, ItemStack armorStack) {
        if (!isInfuseChestplate(service, armorStack)) {
            return ModuleMutation.failure("Place an Infuse chestplate first.", armorStack);
        }
        InfuseArmorPayload payload = decodePayload(service, armorStack);
        if (payload.overclockModule() == null || payload.overclockModule().isBlank()) {
            return ModuleMutation.failure("The overclock socket is empty.", armorStack);
        }
        InfuseArmorPayload updated = payload.withoutOverclock();
        return ModuleMutation.success(service.updatePayload(armorStack, ignored -> updated.encode()));
    }

    public static ModuleMutation installPieceModule(
            CustomItemService service,
            ItemStack armorStack,
            InfuseArmorPiece piece,
            ArmorModuleType type
    ) {
        if (!isInfuseArmorPiece(service, armorStack, piece)) {
            return ModuleMutation.failure("Place an Infuse " + piece.label() + " first.", armorStack);
        }
        if (piece.isChestplate()) {
            return ModuleMutation.failure("Use the chestplate infuse station for chest modules.", armorStack);
        }
        if (type == null || type.overclock()) {
            return ModuleMutation.failure("That module cannot go in a piece socket.", armorStack);
        }
        InfuseArmorPayload payload = decodePayload(service, armorStack);
        if (payload.pieceModule() != null && !payload.pieceModule().isBlank()) {
            return ModuleMutation.failure("That module socket is already filled.", armorStack);
        }
        String conflict = ModuleSlotRules.conflictReason(payload, piece, type);
        if (conflict != null) {
            return ModuleMutation.failure(conflict, armorStack);
        }
        InfuseArmorPayload updated = payload.withPieceModule(type.id());
        return ModuleMutation.success(service.updatePayload(armorStack, ignored -> updated.encode()));
    }

    public static ModuleMutation removePieceModule(CustomItemService service, ItemStack armorStack, InfuseArmorPiece piece) {
        if (!isInfuseArmorPiece(service, armorStack, piece)) {
            return ModuleMutation.failure("Place an Infuse " + piece.label() + " first.", armorStack);
        }
        InfuseArmorPayload payload = decodePayload(service, armorStack);
        if (payload.pieceModule() == null || payload.pieceModule().isBlank()) {
            return ModuleMutation.failure("That module socket is empty.", armorStack);
        }
        InfuseArmorPayload updated = payload.withoutPieceModule();
        return ModuleMutation.success(service.updatePayload(armorStack, ignored -> updated.encode()));
    }

    /** In-raid field repair for a destroyed or critically broken socketed shield (repair kit consumable). */
    public enum FieldRepairOutcome {
        NO_CHESTPLATE,
        NO_SHIELD,
        ALREADY_OK,
        REPAIRED
    }

    public static FieldRepairOutcome fieldRepairSocketedShield(CustomItemService service, Player player) {
        Optional<ItemStack> chestOpt = findInfuseChestplate(service, player);
        if (chestOpt.isEmpty()) {
            return FieldRepairOutcome.NO_CHESTPLATE;
        }
        ItemStack chest = chestOpt.get();
        InfuseArmorPayload payload = decodePayload(service, chest);
        Optional<ShieldSocketReference> refOpt = ShieldSocketReference.decode(
                payload.shieldModule() == null ? "" : payload.shieldModule());
        if (refOpt.isEmpty()) {
            return FieldRepairOutcome.NO_SHIELD;
        }
        ShieldSocketReference ref = refOpt.get();
        boolean needsRepair = ref.destroyed()
                || ref.remainingIntegrity() < ref.integrity() * 0.15D;
        if (!needsRepair) {
            return FieldRepairOutcome.ALREADY_OK;
        }
        double restoredPoints = Math.max(ref.maxPoints() * 0.35D, ref.maxPoints() * 0.15D);
        ShieldSocketReference repaired = ref.withState(restoredPoints, ref.lifetimeAbsorbed() * 0.85D, false);
        InfuseArmorPayload updated = payload.withShield(repaired);
        ItemStack updatedArmor = service.updatePayload(chest, ignored -> updated.encode());
        writeArmorStack(player, chest, updatedArmor);
        service.scanPlayerEquipment(player);
        return FieldRepairOutcome.REPAIRED;
    }

    /** Result of installing a shield onto a specific armor stack (GUI-driven). */
    public record ShieldMutation(boolean success, String message, ItemStack updatedArmor) {
        static ShieldMutation failure(String message, ItemStack armorStack) {
            return new ShieldMutation(false, message, armorStack);
        }
    }

    /** Result of removing a shield from a specific armor stack, carrying the removed shield's identity. */
    public record ShieldClickResult(boolean success, String message, ItemStack updatedArmor, GearRarity shieldRarity, String variantId) {
        static ShieldClickResult failure(String message, ItemStack armorStack) {
            return new ShieldClickResult(false, message, armorStack, null, null);
        }
    }

    public static ShieldMutation installShieldToStack(CustomItemService service, ItemStack armorStack, ItemStack shieldModuleStack) {
        if (!isInfuseChestplate(service, armorStack)) {
            return ShieldMutation.failure("Place an Infuse chestplate first.", armorStack);
        }
        if (!isShieldModule(service, shieldModuleStack)) {
            return ShieldMutation.failure("That is not a shield module.", armorStack);
        }
        InfuseArmorPayload armorPayload = decodePayload(service, armorStack);
        ShieldModulePayload modulePayload = ShieldModulePayload.decode(
                service.resolve(shieldModuleStack).orElseThrow().payloadCopy());
        ShieldSlotRules.Result validation = ShieldSlotRules.validateSocket(armorPayload, modulePayload);
        if (!(validation instanceof ShieldSlotRules.Result.Success success)) {
            return ShieldMutation.failure(((ShieldSlotRules.Result.Failure) validation).message(), armorStack);
        }
        InfuseArmorPayload updated = armorPayload.withShield(success.socketed());
        return new ShieldMutation(true, null, service.updatePayload(armorStack, ignored -> updated.encode()));
    }

    public static ShieldClickResult uninstallShieldToStack(CustomItemService service, ItemStack armorStack) {
        if (!isInfuseChestplate(service, armorStack)) {
            return ShieldClickResult.failure("Place an Infuse chestplate first.", armorStack);
        }
        InfuseArmorPayload payload = decodePayload(service, armorStack);
        Optional<ShieldSocketReference> ref = ShieldSocketReference.decode(
                payload.shieldModule() == null ? "" : payload.shieldModule());
        if (ref.isEmpty()) {
            return ShieldClickResult.failure("No shield module is socketed.", armorStack);
        }
        InfuseArmorPayload updated = payload.withoutShield();
        ItemStack updatedArmor = service.updatePayload(armorStack, ignored -> updated.encode());
        return new ShieldClickResult(true, null, updatedArmor, ref.get().shieldRarity(), ref.get().variantId());
    }

    private static InfuseArmorPayload decodePayload(CustomItemService service, ItemStack armorStack) {
        return InfuseArmorPayload.decode(service.resolve(armorStack).orElseThrow().payloadCopy());
    }

    public static boolean isShieldModule(CustomItemService service, ItemStack stack) {
        return service != null
                && stack != null
                && !stack.getType().isAir()
                && service.isCustomItem(stack)
                && service.resolve(stack)
                .map(instance -> ShieldModuleDefinition.TYPE_ID.equals(instance.typeId()))
                .orElse(false);
    }

    public static boolean isShieldRecharger(CustomItemService service, ItemStack stack) {
        return service != null
                && stack != null
                && !stack.getType().isAir()
                && service.isCustomItem(stack)
                && service.resolve(stack)
                .map(instance -> ShieldRechargerDefinition.TYPE_ID.equals(instance.typeId()))
                .orElse(false);
    }

    public static boolean isShieldRepairKit(CustomItemService service, ItemStack stack) {
        return service != null
                && stack != null
                && !stack.getType().isAir()
                && service.isCustomItem(stack)
                && service.resolve(stack)
                .map(instance -> ShieldRepairKitDefinition.TYPE_ID.equals(instance.typeId()))
                .orElse(false);
    }

    public static ShieldSlotRules.Result socketShield(
            CustomItemService service,
            Player player,
            ItemStack armorStack,
            ItemStack shieldModuleStack
    ) {
        if (!isInfuseChestplate(service, armorStack)) {
            return new ShieldSlotRules.Result.Failure("Hold or wear an Infuse chestplate.");
        }
        if (!isShieldModule(service, shieldModuleStack)) {
            return new ShieldSlotRules.Result.Failure("Hold a shield module in your main hand.");
        }
        InfuseArmorPayload armorPayload = InfuseArmorPayload.decode(
                service.resolve(armorStack).orElseThrow().payloadCopy()
        );
        ShieldModulePayload modulePayload = ShieldModulePayload.decode(
                service.resolve(shieldModuleStack).orElseThrow().payloadCopy()
        );
        ShieldSlotRules.Result validation = ShieldSlotRules.validateSocket(armorPayload, modulePayload);
        if (!(validation instanceof ShieldSlotRules.Result.Success success)) {
            return validation;
        }
        InfuseArmorPayload updated = armorPayload.withShield(success.socketed());
        ItemStack updatedArmor = service.updatePayload(armorStack, ignored -> updated.encode());
        writeArmorStack(player, armorStack, updatedArmor);
        shieldModuleStack.setAmount(shieldModuleStack.getAmount() - 1);
        service.scanPlayerEquipment(player);
        return validation;
    }

    public static ShieldSlotRules.Result setMark(CustomItemService service, Player player, ItemStack armorStack, ArmorMark mark) {
        if (!isInfuseArmor(service, armorStack)) {
            return new ShieldSlotRules.Result.Failure("Hold or wear Infuse armor.");
        }
        InfuseArmorPayload armorPayload = InfuseArmorPayload.decode(
                service.resolve(armorStack).orElseThrow().payloadCopy()
        );
        ShieldSlotRules.Result validation = ShieldSlotRules.validateMarkUpgrade(armorPayload, mark);
        if (!(validation instanceof ShieldSlotRules.Result.Success)) {
            return validation;
        }
        InfuseArmorPayload updated = armorPayload.withMark(mark);
        ItemStack updatedArmor = service.updatePayload(armorStack, ignored -> updated.encode());
        writeArmorStack(player, armorStack, updatedArmor);
        service.scanPlayerEquipment(player);
        return new ShieldSlotRules.Result.Success(null);
    }

    public static ShieldSlotRules.Result destroyShield(CustomItemService service, Player player, ItemStack armorStack) {
        if (!isInfuseChestplate(service, armorStack)) {
            return new ShieldSlotRules.Result.Failure("Wear or hold an Infuse chestplate.");
        }
        InfuseArmorPayload armorPayload = InfuseArmorPayload.decode(
                service.resolve(armorStack).orElseThrow().payloadCopy()
        );
        Optional<ShieldSocketReference> refOpt = ShieldSocketReference.decode(armorPayload.shieldModule());
        if (refOpt.isEmpty()) {
            return new ShieldSlotRules.Result.Failure("No shield module is socketed.");
        }
        ShieldSocketReference ref = refOpt.get();
        ShieldSocketReference destroyed = ref.withState(0.0D, ref.integrity(), true);
        InfuseArmorPayload updated = armorPayload.withShield(destroyed);
        ItemStack updatedArmor = service.updatePayload(armorStack, ignored -> updated.encode());
        writeArmorStack(player, armorStack, updatedArmor);
        service.scanPlayerEquipment(player);
        return new ShieldSlotRules.Result.Success(destroyed);
    }

    /** Result of repairing a destroyed or depleted shield socketed in armor (armory workbench). */
    public record RepairMutation(boolean success, String message, ItemStack updatedArmor, long coinCost) {
        public static RepairMutation failure(String message, ItemStack armorStack) {
            return new RepairMutation(false, message, armorStack, 0L);
        }

        public static RepairMutation success(ItemStack updatedArmor, long coinCost) {
            return new RepairMutation(true, null, updatedArmor, coinCost);
        }
    }

    public static RepairMutation repairShield(CustomItemService service, ItemStack stack) {
        if (isShieldModule(service, stack)) {
            return repairStandaloneShieldModule(service, stack);
        }
        if (!isInfuseChestplate(service, stack)) {
            return RepairMutation.failure("Place an Infuse chestplate or shield module.", stack);
        }
        InfuseArmorPayload payload = decodePayload(service, stack);
        Optional<ShieldSocketReference> refOpt = ShieldSocketReference.decode(
                payload.shieldModule() == null ? "" : payload.shieldModule());
        if (refOpt.isEmpty()) {
            return RepairMutation.failure("No shield module is socketed.", stack);
        }
        ShieldSocketReference ref = refOpt.get();
        boolean needsRepair = ref.destroyed()
                || ref.isDepleted()
                || ref.remainingIntegrity() < ref.integrity() * 0.99D;
        if (!needsRepair) {
            return RepairMutation.failure("This shield is already in good condition.", stack);
        }
        ShieldSocketReference repaired = ShieldSocketReference.fresh(ref.shieldRarity(), ref.variantId());
        InfuseArmorPayload updated = payload.withShield(repaired);
        ItemStack updatedArmor = service.updatePayload(stack, ignored -> updated.encode());
        return RepairMutation.success(updatedArmor, repairCostFor(ref.shieldRarity()));
    }

    private static RepairMutation repairStandaloneShieldModule(CustomItemService service, ItemStack shieldStack) {
        ShieldModulePayload payload = ShieldModulePayload.decode(
                service.resolve(shieldStack).orElseThrow().payloadCopy());
        ItemStack refreshed = ExtractionCustomItemProvider.createShieldModule(service, payload.shieldRarity(), payload.variantId());
        refreshed.setAmount(shieldStack.getAmount());
        return RepairMutation.success(refreshed, repairCostFor(payload.shieldRarity()));
    }

    public static long repairCostFor(GearRarity rarity) {
        if (rarity == null) {
            return 200L;
        }
        return switch (rarity) {
            case COMMON -> 200L;
            case UNCOMMON -> 350L;
            case RARE -> 550L;
            case EPIC -> 800L;
            case LEGENDARY -> 1200L;
        };
    }

    public static ShieldSlotRules.Result clearShield(CustomItemService service, Player player, ItemStack armorStack) {
        if (!isInfuseChestplate(service, armorStack)) {
            return new ShieldSlotRules.Result.Failure("Hold or wear an Infuse chestplate.");
        }
        InfuseArmorPayload armorPayload = InfuseArmorPayload.decode(
                service.resolve(armorStack).orElseThrow().payloadCopy()
        );
        if (armorPayload.shieldModule() == null || armorPayload.shieldModule().isBlank()) {
            return new ShieldSlotRules.Result.Failure("No shield module is socketed.");
        }
        Optional<ShieldSocketReference> socketed = ShieldSocketReference.decode(armorPayload.shieldModule());
        InfuseArmorPayload updated = armorPayload.withoutShield();
        ItemStack updatedArmor = service.updatePayload(armorStack, ignored -> updated.encode());
        writeArmorStack(player, armorStack, updatedArmor);
        service.scanPlayerEquipment(player);
        return new ShieldSlotRules.Result.Success(socketed.orElse(null));
    }

    static void writeArmorStack(Player player, ItemStack previous, ItemStack updated) {
        if (player == null || previous == null || updated == null) {
            return;
        }
        if (writeOpenInventoryStack(player, previous, updated)) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        if (matches(inventory.getChestplate(), previous)) {
            inventory.setChestplate(updated);
            return;
        }
        if (matches(inventory.getHelmet(), previous)) {
            inventory.setHelmet(updated);
            return;
        }
        if (matches(inventory.getLeggings(), previous)) {
            inventory.setLeggings(updated);
            return;
        }
        if (matches(inventory.getBoots(), previous)) {
            inventory.setBoots(updated);
            return;
        }
        if (matches(inventory.getItemInMainHand(), previous)) {
            inventory.setItemInMainHand(updated);
            return;
        }
        if (matches(inventory.getItemInOffHand(), previous)) {
            inventory.setItemInOffHand(updated);
        }
    }

    /** Workstation GUIs keep the anchor item in the open top inventory, not the player inventory. */
    private static boolean writeOpenInventoryStack(Player player, ItemStack previous, ItemStack updated) {
        if (player.getOpenInventory() == null) {
            return false;
        }
        org.bukkit.inventory.Inventory top = player.getOpenInventory().getTopInventory();
        for (int slot = 0; slot < top.getSize(); slot++) {
            if (matches(top.getItem(slot), previous)) {
                top.setItem(slot, updated);
                player.updateInventory();
                return true;
            }
        }
        return false;
    }

    private static boolean matches(ItemStack slotItem, ItemStack previous) {
        return slotItem != null && previous != null && (slotItem == previous || slotItem.equals(previous));
    }
}
