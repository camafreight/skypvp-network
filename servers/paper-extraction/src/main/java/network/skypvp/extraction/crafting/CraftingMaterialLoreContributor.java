package network.skypvp.extraction.crafting;

import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import network.skypvp.paper.item.api.LiveItemContext;
import network.skypvp.paper.item.api.LoreSectionContributor;
import org.bukkit.entity.Player;

/** Lore for physical crafting material stacks. */
public final class CraftingMaterialLoreContributor implements LoreSectionContributor {

    private static volatile CraftingConfigService config;

    public static void bind(CraftingConfigService service) {
        config = service;
    }

    @Override
    public int order() {
        return 5;
    }

    @Override
    public Optional<Component> displayName(LiveItemContext ctx, Player viewer) {
        CraftingMaterialItemPayload payload = CraftingMaterialItemPayload.decode(ctx.instance().payloadCopy());
        if (payload.materialId().isBlank()) {
            return Optional.empty();
        }
        String label = CraftingMaterialDefinition.byId(payload.materialId(), catalog())
                .map(CraftingMaterialDefinition::displayName)
                .orElse(payload.materialId());
        return Optional.of(Component.text(label, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
    }

    @Override
    public List<Component> sections(LiveItemContext ctx, Player viewer) {
        CraftingMaterialItemPayload payload = CraftingMaterialItemPayload.decode(ctx.instance().payloadCopy());
        if (payload.materialId().isBlank()) {
            return List.of();
        }
        String description = CraftingMaterialDefinition.byId(payload.materialId(), catalog())
                .map(CraftingMaterialDefinition::description)
                .orElse("");
        if (description.isBlank()) {
            return List.of(Component.text("Crafting material", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        return List.of(Component.text(description, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
    }

    private static Iterable<CraftingMaterialDefinition> catalog() {
        return config == null ? List.of() : config.materials();
    }
}
