package network.skypvp.extraction.crafting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import network.skypvp.paper.gui.GuiCustomItemRequirement;
import network.skypvp.paper.item.CustomItemStacks;
import network.skypvp.paper.item.api.CustomItemService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Creates and identifies physical crafting material stacks. */
public final class CraftingMaterialItemFactory {

    private CraftingMaterialItemFactory() {
    }

    public static ItemStack create(
            CustomItemService service,
            CraftingConfigService config,
            String materialId,
            int amount
    ) {
        if (service == null || materialId == null || materialId.isBlank() || amount <= 0) {
            return null;
        }
        CraftingMaterialDefinition def = CraftingMaterialDefinition.byId(materialId, config.materials()).orElse(null);
        Material icon = def == null ? Material.PAPER : def.icon();
        String display = def == null ? materialId : def.displayName();
        CraftingMaterialItemPayload payload = new CraftingMaterialItemPayload(materialId.trim().toLowerCase(Locale.ROOT));
        ItemStack stack = service.create(CraftingMaterialItemDefinition.TYPE_ID, builder -> builder.payload(payload.encode()));
        if (stack != null && def != null) {
            stack.setType(def.icon());
            stack = service.refreshPresentation(stack, null);
        }
        if (stack != null) {
            // Per-material sprite: mat_<id> from the skypvp pack. Applied AFTER
            // refreshPresentation, which rebuilds meta from the (shared) definition and
            // would otherwise drop it. Materials share one CustomItemDefinition, so the
            // definition-level itemModel() hook can't vary per material id.
            org.bukkit.NamespacedKey model = new org.bukkit.NamespacedKey(
                    "skypvp", "mat_" + payload.materialId());
            stack.editMeta(meta -> meta.setItemModel(model));
            stack.setAmount(Math.min(64, amount));
        }
        return stack;
    }

    public static GuiCustomItemRequirement requirement(String materialId) {
        if (materialId == null || materialId.isBlank()) {
            throw new IllegalArgumentException("materialId required");
        }
        String normalized = materialId.trim().toLowerCase(Locale.ROOT);
        return GuiCustomItemRequirement.require(CraftingMaterialItemDefinition.TYPE_ID)
                .match(instance -> normalized.equals(
                        CraftingMaterialItemPayload.decode(instance.payloadCopy()).materialId()));
    }

    public static Optional<String> materialIdOf(CustomItemService service, ItemStack stack) {
        if (service == null || stack == null || stack.getType().isAir() || !service.isCustomItem(stack)) {
            return Optional.empty();
        }
        return service.resolve(stack)
                .filter(instance -> CraftingMaterialItemDefinition.TYPE_ID.equals(instance.typeId()))
                .map(instance -> CraftingMaterialItemPayload.decode(instance.payloadCopy()).materialId())
                .filter(id -> !id.isBlank());
    }

    public static boolean isMaterial(CustomItemService service, ItemStack stack, String materialId) {
        if (materialId == null || materialId.isBlank()) {
            return false;
        }
        return CustomItemStacks.matches(service, stack, requirement(materialId));
    }

    public static int countInInventory(CustomItemService service, Player player, String materialId) {
        if (service == null || player == null || materialId == null) {
            return 0;
        }
        int total = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (isMaterial(service, stack, materialId)) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    /** Removes up to {@code amount} matching material stacks from the player inventory. Returns removed count. */
    public static int takeFromInventory(CustomItemService service, Player player, String materialId, int amount) {
        if (service == null || player == null || materialId == null || amount <= 0) {
            return 0;
        }
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack stack = contents[slot];
            if (!isMaterial(service, stack, materialId)) {
                continue;
            }
            int take = Math.min(remaining, stack.getAmount());
            remaining -= take;
            if (stack.getAmount() - take <= 0) {
                contents[slot] = null;
            } else {
                ItemStack updated = stack.clone();
                updated.setAmount(stack.getAmount() - take);
                contents[slot] = updated;
            }
        }
        player.getInventory().setStorageContents(contents);
        return amount - remaining;
    }

    public static List<ItemStack> splitStacks(CustomItemService service, CraftingConfigService config, String materialId, int amount) {
        Objects.requireNonNull(service, "service");
        List<ItemStack> stacks = new ArrayList<>();
        int remaining = amount;
        while (remaining > 0) {
            int chunk = Math.min(64, remaining);
            ItemStack stack = create(service, config, materialId, chunk);
            if (stack == null) {
                break;
            }
            stacks.add(stack);
            remaining -= chunk;
        }
        return stacks;
    }
}
