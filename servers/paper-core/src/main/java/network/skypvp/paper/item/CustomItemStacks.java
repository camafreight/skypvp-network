package network.skypvp.paper.item;

import java.util.Objects;
import java.util.Optional;
import network.skypvp.paper.gui.GuiCustomItemRequirement;
import network.skypvp.paper.item.api.CustomItemDefinition;
import network.skypvp.paper.item.api.CustomItemInstance;
import network.skypvp.paper.item.api.CustomItemService;
import network.skypvp.paper.item.api.CustomItemTypeId;
import org.bukkit.inventory.ItemStack;

/** Canonical stack matching helpers for custom items in menus and workbenches. */
public final class CustomItemStacks {

    private CustomItemStacks() {
    }

    public static boolean isType(CustomItemService service, ItemStack stack, CustomItemTypeId typeId) {
        return matches(service, stack, GuiCustomItemRequirement.require(typeId));
    }

    public static boolean matches(CustomItemService service, ItemStack stack, GuiCustomItemRequirement requirement) {
        return resolveMatching(service, stack, requirement).isPresent();
    }

    public static Optional<CustomItemInstance> resolveMatching(
            CustomItemService service,
            ItemStack stack,
            GuiCustomItemRequirement requirement
    ) {
        Objects.requireNonNull(requirement, "requirement");
        if (service == null || stack == null || stack.getType().isAir() || !service.isCustomItem(stack)) {
            return Optional.empty();
        }
        return service.resolve(stack).filter(requirement::matches);
    }

    /** Returns {@code stack.getAmount()} when stacks match; otherwise {@code 0}. */
    public static int depositedAmount(CustomItemService service, ItemStack stack, GuiCustomItemRequirement requirement) {
        return resolveMatching(service, stack, requirement).map(ignored -> stack.getAmount()).orElse(0);
    }

    /** Whether two custom stacks can merge in a player inventory (ignores amount). */
    public static boolean canMerge(CustomItemService service, ItemStack left, ItemStack right) {
        if (service == null || left == null || right == null || left.getType().isAir() || right.getType().isAir()) {
            return false;
        }
        if (!service.isCustomItem(left) || !service.isCustomItem(right)) {
            return false;
        }
        Optional<CustomItemInstance> leftInstance = service.resolve(left);
        Optional<CustomItemInstance> rightInstance = service.resolve(right);
        if (leftInstance.isEmpty() || rightInstance.isEmpty()) {
            return false;
        }
        CustomItemInstance a = leftInstance.get();
        CustomItemInstance b = rightInstance.get();
        if (!a.typeId().equals(b.typeId()) || a.schemaVersion() != b.schemaVersion()) {
            return false;
        }
        if (!java.util.Arrays.equals(a.payloadCopy(), b.payloadCopy())) {
            return false;
        }
        Optional<CustomItemDefinition> definition = service.definition(a.typeId());
        if (definition.isPresent() && definition.get().stackable()) {
            return left.isSimilar(right) || sameStackableIdentity(a, b);
        }
        return a.instanceId().equals(b.instanceId()) && left.isSimilar(right);
    }

    private static boolean sameStackableIdentity(CustomItemInstance left, CustomItemInstance right) {
        return left.typeId().equals(right.typeId())
                && left.schemaVersion() == right.schemaVersion()
                && java.util.Arrays.equals(left.payloadCopy(), right.payloadCopy());
    }
}
