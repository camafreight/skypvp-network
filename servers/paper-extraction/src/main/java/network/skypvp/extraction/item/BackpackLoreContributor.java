package network.skypvp.extraction.item;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import network.skypvp.paper.item.api.LiveItemContext;
import network.skypvp.paper.item.api.LoreSectionContributor;
import org.bukkit.entity.Player;

/** Hover card for raid backpacks: tier, unlocked rows, fill state, and usage hints. */
final class BackpackLoreContributor implements LoreSectionContributor {

    static final TextColor[] TIER_COLORS = {
            TextColor.color(0x9d9d9d),  // T1 — scavenger gray
            TextColor.color(0x4fc46f),  // T2 — field green
            TextColor.color(0x4f9dfc),  // T3 — operator blue
            TextColor.color(0xc44ffc)   // T4 — breacher violet
    };
    static final String[] TIER_NAMES = {"Scavenger", "Field", "Operator", "Breacher"};

    @Override
    public int order() {
        return 10;
    }

    @Override
    public Optional<Component> displayName(LiveItemContext ctx, Player viewer) {
        BackpackPayload payload = BackpackPayload.decode(ctx.instance().payloadCopy());
        int tier = payload.tier();
        return Optional.of(Component.text(TIER_NAMES[tier - 1] + " Backpack", TIER_COLORS[tier - 1])
                .decorate(TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
    }

    @Override
    public List<Component> sections(LiveItemContext ctx, Player viewer) {
        BackpackPayload payload = BackpackPayload.decode(ctx.instance().payloadCopy());
        int tier = payload.tier();
        long stored = payload.contents().stream().filter(item -> item != null && !item.getType().isAir()).count();
        TextColor accent = TIER_COLORS[tier - 1];

        List<Component> lines = new ArrayList<>();
        lines.add(plain("Raid Storage — Tier " + tier + " / " + BackpackDefinition.MAX_TIER, NamedTextColor.DARK_GRAY));
        lines.add(Component.empty());
        lines.add(Component.text()
                .append(Component.text("Rows unlocked ", NamedTextColor.GRAY))
                .append(rowPips(tier, accent))
                .append(Component.text("  " + payload.unlockedRows() + "×9", accent))
                .build().decoration(TextDecoration.ITALIC, false));
        lines.add(plainMixed("Slots used ", stored + " / " + payload.capacity(), accent));
        BackpackSkins.Skin skin = BackpackSkins.byId(payload.skin());
        lines.add(plainMixed("Skin ", skin.displayName(), skin.color()));
        if (tier >= BackpackDefinition.MAX_TIER) {
            lines.add(plain("Fully expanded — hotbar row included.", NamedTextColor.DARK_GRAY));
        } else {
            lines.add(plain("Tier " + (tier + 1) + " unlocks one more row.", NamedTextColor.DARK_GRAY));
        }
        lines.add(Component.empty());
        lines.add(plain("Wear it in your offhand slot, then", NamedTextColor.GRAY));
        lines.add(plainMixed("press ", "[F]", NamedTextColor.YELLOW, " to open or stow the pack."));
        lines.add(plain("Loot picked up while open goes straight in.", NamedTextColor.GRAY));
        lines.add(Component.empty());
        lines.add(plain("play.skyclub.gg", TextColor.color(0xffd700)));
        return lines;
    }

    private static Component rowPips(int tier, TextColor accent) {
        var builder = Component.text().decoration(TextDecoration.ITALIC, false);
        for (int row = 1; row <= BackpackDefinition.MAX_TIER; row++) {
            builder.append(Component.text("■", row <= tier ? accent : NamedTextColor.DARK_GRAY));
        }
        return builder.build();
    }

    private static Component plain(String text, TextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static Component plainMixed(String gray, String highlight, TextColor highlightColor) {
        return Component.text()
                .append(Component.text(gray, NamedTextColor.GRAY))
                .append(Component.text(highlight, highlightColor))
                .build().decoration(TextDecoration.ITALIC, false);
    }

    private static Component plainMixed(String gray, String highlight, TextColor highlightColor, String tail) {
        return Component.text()
                .append(Component.text(gray, NamedTextColor.GRAY))
                .append(Component.text(highlight, highlightColor))
                .append(Component.text(tail, NamedTextColor.GRAY))
                .build().decoration(TextDecoration.ITALIC, false);
    }
}
