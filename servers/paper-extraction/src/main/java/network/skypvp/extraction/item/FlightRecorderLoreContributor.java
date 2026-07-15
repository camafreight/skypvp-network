package network.skypvp.extraction.item;

import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import network.skypvp.paper.item.api.LiveItemContext;
import network.skypvp.paper.item.api.LoreSectionContributor;
import org.bukkit.entity.Player;

final class FlightRecorderLoreContributor implements LoreSectionContributor {

    @Override
    public int order() {
        return 10;
    }

    @Override
    public Optional<Component> displayName(LiveItemContext ctx, Player viewer) {
        return Optional.of(Component.text("Flight Recorder", NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
    }

    @Override
    public List<Component> sections(LiveItemContext ctx, Player viewer) {
        return List.of(
                Component.text("Quest Item", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("Black-box from a downed skiff.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Return it to the Stranded Pilot", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("in the extraction hub.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
        );
    }
}
