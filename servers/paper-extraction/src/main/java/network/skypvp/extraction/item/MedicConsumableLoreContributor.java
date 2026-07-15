package network.skypvp.extraction.item;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import network.skypvp.extraction.text.ExtractionTexts;
import network.skypvp.extraction.crafting.ItemConfigOverrides;
import network.skypvp.paper.item.api.LiveItemContext;
import network.skypvp.paper.item.api.LoreSectionContributor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class MedicConsumableLoreContributor implements LoreSectionContributor {

    @Override
    public int order() {
        return 10;
    }

    @Override
    public java.util.Optional<Component> displayName(LiveItemContext ctx, Player viewer) {
        return MedicConsumableType.byTypeId(ctx.definition().typeId())
                .map(type -> {
                    String name = ItemConfigOverrides.displayName(type.typeId()).orElse(type.displayName());
                    return Component.text(name, type.color()).decoration(TextDecoration.ITALIC, false);
                });
    }

    @Override
    public List<Component> sections(LiveItemContext ctx, Player viewer) {
        return MedicConsumableType.byTypeId(ctx.definition().typeId())
                .map(type -> linesFor(type, ExtractionTexts.locale(viewer)))
                .orElse(List.of());
    }

    private List<Component> linesFor(MedicConsumableType type, String locale) {
        List<Component> lore = new ArrayList<>();
        String blurb = ItemConfigOverrides.blurb(type.typeId()).orElse(type.blurb());
        lore.add(line(blurb, NamedTextColor.GRAY));
        lore.add(Component.empty());
        for (String statLine : type.statMiniMessageLines()) {
            if (statLine.isBlank()) {
                lore.add(Component.empty());
            } else {
                lore.add(ExtractionTexts.miniMessageTemplate(statLine, locale));
            }
        }
        lore.add(Component.empty());
        lore.add(line("Right-click to use in a raid.", NamedTextColor.DARK_GRAY));
        return lore;
    }

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
}
