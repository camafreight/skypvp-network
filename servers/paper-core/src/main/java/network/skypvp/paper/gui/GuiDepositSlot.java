package network.skypvp.paper.gui;

import java.util.Objects;

/** Binds a raw inventory slot to a {@link GuiCustomItemRequirement} for deposit workbenches. */
public record GuiDepositSlot(int slot, GuiCustomItemRequirement requirement) {

    public GuiDepositSlot {
        if (slot < 0) {
            throw new IllegalArgumentException("slot must be non-negative");
        }
        Objects.requireNonNull(requirement, "requirement");
    }
}
