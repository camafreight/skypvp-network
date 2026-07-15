package network.skypvp.extraction.item;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import network.skypvp.paper.item.api.LiveItemContext;
import network.skypvp.paper.item.api.LoreSectionContributor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;

final class ShieldRepairKitLoreContributor implements LoreSectionContributor {

    @Override
    public int order() {
        return 10;
    }

    @Override
    public Optional<Component> displayName(LiveItemContext ctx, Player viewer) {
        return Optional.of(Component.text("Field Shield Repair Kit", NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
    }

    @Override
    public List<Component> sections(LiveItemContext ctx, Player viewer) {
        return List.of(
                Component.text("Rare Field Consumable", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("Use during an active breach to", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("repair a destroyed or broken", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("shield on your worn chestplate.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("Does not refill shield buffer.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Use a recharger for that.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
        );
    }
}
