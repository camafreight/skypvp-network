package network.skypvp.paper.item.api;

import java.util.List;

/**
 * Supplies equip-time stat effects for a custom item type.
 */
public interface StatContributor {

    List<CustomStatEffect> effects(LiveItemContext ctx);
}
