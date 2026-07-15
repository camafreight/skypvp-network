package network.skypvp.extraction.item;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import network.skypvp.extraction.crafting.ItemConfigOverrides;
import network.skypvp.paper.item.api.LiveItemContext;
import network.skypvp.paper.item.api.LoreSectionContributor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Shared lore for every {@link ArmorModuleType} physical item: name, category, and the module's pros (green) and
 * cons (red).
 */
final class ArmorModuleLoreContributor implements LoreSectionContributor {

    @Override
    public int order() {
        return 10;
    }

    @Override
    public Optional<Component> displayName(LiveItemContext ctx, Player viewer) {
        return ArmorModuleType.byTypeId(ctx.definition().typeId())
                .map(type -> {
                    String name = ItemConfigOverrides.displayName(type.typeId()).orElse(type.displayName());
                    return Component.text(name, type.color()).decoration(TextDecoration.ITALIC, false);
                });
    }

    @Override
    public List<Component> sections(LiveItemContext ctx, Player viewer) {
        Optional<ArmorModuleType> resolved = ArmorModuleType.byTypeId(ctx.definition().typeId());
        if (resolved.isEmpty()) {
            return List.of();
        }
        ArmorModuleType type = resolved.get();
        List<Component> lines = new ArrayList<>();
        lines.add(line(type.overclock() ? "Overclock Module" : "Armor Module", NamedTextColor.GRAY));
        lines.add(Component.empty());
        List<ItemConfigOverrides.ModuleEffectLine> overrides = ItemConfigOverrides.moduleEffectLines(type.typeId());
        if (!overrides.isEmpty()) {
            for (ItemConfigOverrides.ModuleEffectLine effect : overrides) {
                boolean positive = effect.positive();
                String prefix = positive ? "\u25B2 +" : "\u25BC -";
                NamedTextColor color = positive ? NamedTextColor.GREEN : NamedTextColor.RED;
                lines.add(line(prefix + effect.label(), color));
            }
        } else {
            for (ArmorModuleType.ModuleEffect effect : type.effects()) {
                boolean positive = effect.positive();
                String prefix = positive ? "\u25B2 +" : "\u25BC -";
                NamedTextColor color = positive ? NamedTextColor.GREEN : NamedTextColor.RED;
                lines.add(line(prefix + effect.label(), color));
            }
        }
        List<String> extraLore = ItemConfigOverrides.loreLines(type.typeId());
        if (!extraLore.isEmpty()) {
            lines.add(Component.empty());
            String locale = network.skypvp.extraction.text.ExtractionTexts.locale(viewer);
            for (String line : extraLore) {
                lines.add(network.skypvp.extraction.text.ExtractionTexts.miniMessageTemplate(line, locale));
            }
        }
        lines.add(Component.empty());
        String pieces = type.compatiblePieces().stream()
                .map(InfuseArmorPiece::label)
                .reduce((a, b) -> a + " / " + b)
                .orElse("Chestplate");
        lines.add(line("Compatible: " + pieces, NamedTextColor.DARK_AQUA));
        if (!type.conflictsWith().isEmpty()) {
            List<String> conflictNames = type.conflictsWith().stream()
                    .map(id -> ArmorModuleType.byId(id).map(ArmorModuleType::displayName).orElse(id))
                    .toList();
            lines.add(line("Conflicts: " + String.join(", ", conflictNames), NamedTextColor.DARK_RED));
        }
        lines.add(Component.empty());
        lines.add(line(type.overclock()
                ? "Install in the Overclock socket (chestplate)"
                : "Install in a Module socket", NamedTextColor.DARK_GRAY));
        return lines;
    }

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
}
