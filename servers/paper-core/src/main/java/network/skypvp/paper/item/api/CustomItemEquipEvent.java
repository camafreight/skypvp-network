package network.skypvp.paper.item.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;

public class CustomItemEquipEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final EquipmentSlotGroup slot;
    private final CustomItemInstance instance;
    private final CustomItemDefinition definition;
    private final ItemStack stack;
    private boolean cancelled;

    public CustomItemEquipEvent(
            Player player,
            EquipmentSlotGroup slot,
            CustomItemInstance instance,
            CustomItemDefinition definition,
            ItemStack stack
    ) {
        super(player);
        this.slot = Objects.requireNonNull(slot);
        this.instance = Objects.requireNonNull(instance);
        this.definition = Objects.requireNonNull(definition);
        this.stack = Objects.requireNonNull(stack);
    }

    public EquipmentSlotGroup slot() {
        return slot;
    }

    public CustomItemInstance instance() {
        return instance;
    }

    public CustomItemDefinition definition() {
        return definition;
    }

    public ItemStack stack() {
        return stack.clone();
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
