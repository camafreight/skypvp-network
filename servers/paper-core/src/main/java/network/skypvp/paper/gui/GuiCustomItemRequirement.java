package network.skypvp.paper.gui;

import java.util.Objects;
import java.util.function.Predicate;
import network.skypvp.paper.item.api.CustomItemInstance;
import network.skypvp.paper.item.api.CustomItemTypeId;

/**
 * Describes a physical custom-item requirement for workstation deposit slots. Use
 * {@link #require(CustomItemTypeId)} as the entry point, optionally narrow with {@link #match(Predicate)}
 * and {@link #amount(int)}, then validate stacks via {@link GuiDepositRequirements}.
 */
public final class GuiCustomItemRequirement {

    private final CustomItemTypeId typeId;
    private final Predicate<CustomItemInstance> instanceMatch;
    private final int minimumAmount;

    private GuiCustomItemRequirement(CustomItemTypeId typeId, Predicate<CustomItemInstance> instanceMatch, int minimumAmount) {
        this.typeId = Objects.requireNonNull(typeId, "typeId");
        this.instanceMatch = Objects.requireNonNull(instanceMatch, "instanceMatch");
        this.minimumAmount = Math.max(1, minimumAmount);
    }

    /** Requires any stack of the given custom item type (payload ignored). */
    public static GuiCustomItemRequirement require(CustomItemTypeId typeId) {
        return new GuiCustomItemRequirement(typeId, instance -> true, 1);
    }

    /** Narrows the requirement to instances whose payload passes {@code predicate}. */
    public GuiCustomItemRequirement match(Predicate<CustomItemInstance> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return new GuiCustomItemRequirement(this.typeId, this.instanceMatch.and(predicate), this.minimumAmount);
    }

    /** Minimum stack amount that must be deposited in a bound slot for the requirement to be met. */
    public GuiCustomItemRequirement amount(int minimumAmount) {
        return new GuiCustomItemRequirement(this.typeId, this.instanceMatch, minimumAmount);
    }

    public CustomItemTypeId typeId() {
        return this.typeId;
    }

    public int minimumAmount() {
        return this.minimumAmount;
    }

    public boolean matches(CustomItemInstance instance) {
        return instance != null
                && this.typeId.equals(instance.typeId())
                && this.instanceMatch.test(instance);
    }
}
