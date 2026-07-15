package network.skypvp.paper.item.api;

/**
 * Optional per-type lifecycle hooks. Core fires equip/unequip events; behaviors may add side effects.
 */
public interface CustomItemBehavior {

    default void onEquip(LiveItemContext ctx) {
    }

    default void onUnequip(LiveItemContext ctx) {
    }

    default void onRefresh(LiveItemContext ctx) {
    }
}
