package network.skypvp.extraction.item;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import network.skypvp.paper.item.api.LiveItemContext;
import network.skypvp.paper.item.api.LoreSectionContributor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;

final class ShieldRechargerLoreContributor implements LoreSectionContributor {

    @Override
    public int order() {
        return 10;
    }

    @Override
    public Optional<Component> displayName(LiveItemContext ctx, Player viewer) {
        RechargerTier tier = tier(ctx);
        return Optional.of(Component.text(tier.displayName(), NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
    }

    @Override
    public List<Component> sections(LiveItemContext ctx, Player viewer) {
        RechargerTier tier = tier(ctx);
        return List.of(
                Component.text("Drinkable", NamedTextColor.DARK_AQUA).decoration(TextDecoration.ITALIC, false),
                Component.text("Restores: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                        .append(Component.text(tier.amountLabel(), NamedTextColor.AQUA)),
                Component.text("Delivery: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                        .append(Component.text(tier.rateLabel(), NamedTextColor.AQUA)),
                Component.empty(),
                Component.text("Drink to recharge your", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text(tier.instant()
                                ? "Infuse shield instantly."
                                : "Infuse shield over time.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Cannot repair a destroyed shield.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
        );
    }

    private static RechargerTier tier(LiveItemContext ctx) {
        if (ctx == null || ctx.instance() == null) {
            return RechargerTier.FIELD;
        }
        return ShieldRechargerPayload.decode(ctx.instance().payloadCopy()).tier();
    }
}
