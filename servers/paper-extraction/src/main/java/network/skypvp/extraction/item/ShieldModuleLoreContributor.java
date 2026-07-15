package network.skypvp.extraction.item;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import network.skypvp.paper.item.api.LiveItemContext;
import network.skypvp.paper.item.api.LoreSectionContributor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class ShieldModuleLoreContributor implements LoreSectionContributor {

    @Override
    public int order() {
        return 10;
    }

    @Override
    public Optional<Component> displayName(LiveItemContext ctx, Player viewer) {
        ShieldModulePayload payload = ShieldModulePayload.decode(ctx.instance().payloadCopy());
        return Optional.of(Component.text(payload.variantId().substring(0, 1).toUpperCase()
                        + payload.variantId().substring(1) + " Shield Module",
                rarityColor(payload.shieldRarity()))
                .decoration(TextDecoration.ITALIC, false));
    }

    @Override
    public List<Component> sections(LiveItemContext ctx, Player viewer) {
        ShieldModulePayload payload = ShieldModulePayload.decode(ctx.instance().payloadCopy());
        ArmorMark required = ArmorMark.requiredForShield(payload.shieldRarity());
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text(payload.shieldRarity().displayName(), rarityColor(payload.shieldRarity()))
                .decoration(TextDecoration.ITALIC, false));
        lines.add(Component.empty());
        lines.add(Component.text("Dedicated shield module", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lines.add(Component.text("Requires armor " + required.displayName() + "+", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lines.add(Component.text("Install via Infuse shield socket only", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        return lines;
    }

    private static NamedTextColor rarityColor(GearRarity rarity) {
        return switch (rarity) {
            case COMMON -> NamedTextColor.WHITE;
            case UNCOMMON -> NamedTextColor.GREEN;
            case RARE -> NamedTextColor.BLUE;
            case EPIC -> NamedTextColor.LIGHT_PURPLE;
            case LEGENDARY -> NamedTextColor.GOLD;
        };
    }
}
