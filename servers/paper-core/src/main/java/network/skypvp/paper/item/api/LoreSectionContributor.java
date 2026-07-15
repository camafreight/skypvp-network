package network.skypvp.paper.item.api;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;

/**
 * Contributes lore lines for a custom item. Modes compose their own sections; core only orders contributors.
 */
public interface LoreSectionContributor {

    /** Lower values render earlier. */
    default int order() {
        return 100;
    }

    List<Component> sections(LiveItemContext ctx, Player viewer);

    default Optional<Component> displayName(LiveItemContext ctx, Player viewer) {
        return Optional.empty();
    }
}
