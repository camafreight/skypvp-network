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

final class BlueprintReceiptLoreContributor implements LoreSectionContributor {

    @Override
    public int order() {
        return 10;
    }

    @Override
    public Optional<Component> displayName(LiveItemContext ctx, Player viewer) {
        String recipe = BlueprintReceiptPayload.decode(ctx.instance().payloadCopy()).blueprintId();
        String label = recipe.isBlank() ? "Unknown Blueprint" : formatRecipeName(recipe);
        return Optional.of(Component.text(label + " Blueprint", NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
    }

    @Override
    public List<Component> sections(LiveItemContext ctx, Player viewer) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("Crafting Schematic", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lines.add(Component.empty());
        lines.add(Component.text("Right-click to study and unlock", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lines.add(Component.text("this recipe at the armory workbench.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lines.add(Component.empty());
        lines.add(Component.text("Usable in the hub or after extracting.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        return lines;
    }

    private static String formatRecipeName(String blueprintId) {
        String[] parts = blueprintId.split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1).toLowerCase());
            }
        }
        return builder.isEmpty() ? blueprintId : builder.toString();
    }
}
