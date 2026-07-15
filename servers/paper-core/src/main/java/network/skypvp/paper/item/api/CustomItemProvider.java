package network.skypvp.paper.item.api;

import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.Optional;

/**
 * Gamemode SPI: registers item types and hooks for one mode (extraction, lobby, etc.).
 */
public interface CustomItemProvider {

    String modeKey();

    Collection<CustomItemDefinition> definitions();

    Optional<CustomItemBehavior> behavior(CustomItemTypeId typeId);

    Optional<StatContributor> statContributor(CustomItemTypeId typeId);

    Optional<LoreSectionContributor> loreContributor(CustomItemTypeId typeId);

    default void register(Plugin plugin, CustomItemService service) {
        service.registerProvider(this);
    }
}
