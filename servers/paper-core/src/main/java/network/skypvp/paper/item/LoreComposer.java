package network.skypvp.paper.item;

import net.kyori.adventure.text.Component;
import network.skypvp.paper.item.api.LiveItemContext;
import network.skypvp.paper.item.api.LoreSectionContributor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class LoreComposer {

    private final CustomItemRegistry registry;

    LoreComposer(CustomItemRegistry registry) {
        this.registry = registry;
    }

    List<Component> compose(LiveItemContext ctx, Player viewer) {
        Optional<LoreSectionContributor> contributor = registry.loreContributor(ctx.definition().typeId());
        if (contributor.isEmpty()) {
            return List.of();
        }
        List<Component> lines = new ArrayList<>(contributor.get().sections(ctx, viewer));
        return lines.isEmpty() ? List.of() : lines;
    }

    Optional<Component> displayName(LiveItemContext ctx, Player viewer) {
        return registry.loreContributor(ctx.definition().typeId())
                .flatMap(contributor -> contributor.displayName(ctx, viewer));
    }
}
