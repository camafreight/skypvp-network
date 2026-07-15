package network.skypvp.paper.gui;

import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.bukkit.inventory.ItemStack;

/**
 * A modifier socket on a {@link GuiWorkstationMenu}: a virtual slot that installs an offered item into the menu's
 * anchor item, or removes the current modifier and hands an item back to the player.
 *
 * <p>The framework owns every cursor/inventory operation. Implementors describe only the visual and the domain
 * transform:</p>
 * <ul>
 *   <li>{@link Builder#accepts(Predicate)} — which cursor/shift item may be installed.</li>
 *   <li>{@link Builder#onInstall(Function)} — given a single offered item, return the updated anchor (or a rejection).</li>
 *   <li>{@link Builder#onRemove(Supplier)} — produce the updated anchor plus the item to hand back.</li>
 * </ul>
 */
public final class GuiWorkstationSocket {

    private final ItemStack icon;
    private final boolean filled;
    private final Predicate<ItemStack> accepts;
    private final Function<ItemStack, InstallOutcome> installer;
    private final Supplier<RemoveOutcome> remover;

    private GuiWorkstationSocket(Builder builder) {
        this.icon = builder.icon;
        this.filled = builder.filled;
        this.accepts = builder.accepts;
        this.installer = builder.installer;
        this.remover = builder.remover;
    }

    public static Builder builder(ItemStack icon) {
        return new Builder(icon);
    }

    ItemStack icon() {
        return this.icon;
    }

    boolean filled() {
        return this.filled;
    }

    boolean installable() {
        return this.installer != null;
    }

    boolean removable() {
        return this.remover != null;
    }

    boolean accepts(ItemStack stack) {
        return this.accepts != null && stack != null && this.accepts.test(stack);
    }

    InstallOutcome install(ItemStack offered) {
        return this.installer == null ? InstallOutcome.reject(null) : this.installer.apply(offered);
    }

    RemoveOutcome remove() {
        return this.remover == null ? RemoveOutcome.fail(null) : this.remover.get();
    }

    public static final class Builder {
        private final ItemStack icon;
        private boolean filled;
        private Predicate<ItemStack> accepts;
        private Function<ItemStack, InstallOutcome> installer;
        private Supplier<RemoveOutcome> remover;

        private Builder(ItemStack icon) {
            this.icon = icon;
        }

        public Builder filled(boolean filled) {
            this.filled = filled;
            return this;
        }

        public Builder accepts(Predicate<ItemStack> accepts) {
            this.accepts = accepts;
            return this;
        }

        public Builder onInstall(Function<ItemStack, InstallOutcome> installer) {
            this.installer = installer;
            return this;
        }

        public Builder onRemove(Supplier<RemoveOutcome> remover) {
            this.remover = remover;
            return this;
        }

        public GuiWorkstationSocket build() {
            return new GuiWorkstationSocket(this);
        }
    }

    /**
     * Result of an install attempt. On {@link #accept(ItemStack)} the framework consumes exactly one offered item and
     * stores {@code updatedAnchor}; on {@link #reject(String)} nothing is consumed and the message (if any) is shown.
     */
    public record InstallOutcome(boolean accepted, ItemStack updatedAnchor, String message) {
        public static InstallOutcome accept(ItemStack updatedAnchor) {
            return new InstallOutcome(true, updatedAnchor, null);
        }

        public static InstallOutcome reject(String message) {
            return new InstallOutcome(false, null, message);
        }
    }

    /**
     * Result of a remove attempt. On {@link #success(ItemStack, ItemStack)} the framework stores {@code updatedAnchor}
     * and returns {@code returned} to the player; on {@link #fail(String)} nothing changes.
     */
    public record RemoveOutcome(boolean success, ItemStack updatedAnchor, ItemStack returned, String message) {
        public static RemoveOutcome success(ItemStack updatedAnchor, ItemStack returned) {
            return new RemoveOutcome(true, updatedAnchor, returned, null);
        }

        public static RemoveOutcome fail(String message) {
            return new RemoveOutcome(false, null, null, message);
        }
    }
}
