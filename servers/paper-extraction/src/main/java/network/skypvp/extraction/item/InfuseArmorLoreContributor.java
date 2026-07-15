package network.skypvp.extraction.item;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import network.skypvp.extraction.crafting.ItemConfigOverrides;
import network.skypvp.extraction.text.ExtractionTexts;
import network.skypvp.paper.item.api.LiveItemContext;
import network.skypvp.paper.item.api.LoreSectionContributor;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

final class InfuseArmorLoreContributor implements LoreSectionContributor {

    private static final int SHIELD_BAR_SEGMENTS = 10;

    @Override
    public int order() {
        return 10;
    }

    @Override
    public Optional<Component> displayName(LiveItemContext ctx, Player viewer) {
        InfuseArmorPayload payload = InfuseArmorPayload.decode(ctx.instance().payloadCopy());
        InfuseArmorPiece piece = InfuseArmorPiece.byTypeId(ctx.definition().typeId()).orElse(InfuseArmorPiece.CHESTPLATE);
        String display = ItemConfigOverrides.displayName(ctx.definition().typeId()).orElse(piece.displayName());
        return Optional.of(Component.text(display, rarityColor(payload.rarity()))
                .decoration(TextDecoration.ITALIC, false));
    }

    @Override
    public List<Component> sections(LiveItemContext ctx, Player viewer) {
        InfuseArmorPayload payload = InfuseArmorPayload.decode(ctx.instance().payloadCopy());
        GearRarity rarity = payload.rarity();
        ArmorMark mark = payload.mark() == null ? ArmorMark.MK1 : payload.mark();
        InfuseArmorPiece piece = InfuseArmorPiece.byTypeId(ctx.definition().typeId()).orElse(InfuseArmorPiece.CHESTPLATE);

        List<Entry> entries = new ArrayList<>();
        entries.add(plain(Component.text(rarity.displayName(), rarityColor(rarity))));
        entries.add(plain(Component.text(mark.displayName(), NamedTextColor.AQUA)));
        ArmorSet set = payload.armorSet() == null ? ArmorSet.VANGUARD : payload.armorSet();
        entries.add(plain(Component.text(set.displayName(), set.color())));
        for (String line : ArmorSetBonusTexts.miniMessageLines(set, piece.setBonusShare())) {
            entries.add(plain(ExtractionTexts.miniMessageTemplate(line, ExtractionTexts.locale(viewer))));
        }
        entries.add(blank());
        double defense = rarity.defensePercent() * piece.defenseShare()
                * ItemConfigOverrides.defenseMultiplier(ctx.definition().typeId());
        entries.add(plain(Component.text("Defense: " + formatPercent(defense), NamedTextColor.GRAY)));

        if (piece.isChestplate()) {
            appendModuleLines(entries, payload);
            appendModuleSummary(entries, payload);
            appendShieldSection(entries, payload, mark);
            appendOverclockLine(entries, payload);
        } else {
            appendModuleLines(entries, payload);
            appendModuleSummary(entries, payload);
        }

        return centerFlagged(entries);
    }

    /** Always list every module socket ("Module 1: Empty" / "Module 1: <name>"); no redundant socket-count line. */
    private static void appendModuleLines(List<Entry> entries, InfuseArmorPayload payload) {
        List<String> modules = payload.moduleSockets();
        if (modules.isEmpty()) {
            return;
        }
        entries.add(blank());
        for (int i = 0; i < modules.size(); i++) {
            Optional<ArmorModuleType> installed = ArmorModuleType.byId(modules.get(i));
            if (installed.isPresent()) {
                ArmorModuleType type = installed.get();
                entries.add(plain(label("Module " + (i + 1) + ": ", NamedTextColor.DARK_AQUA)
                        .append(Component.text(type.displayName(), type.color())
                                .decoration(TextDecoration.ITALIC, false))));
            } else {
                entries.add(plain(Component.text("Module " + (i + 1) + ": Empty", NamedTextColor.DARK_GRAY)));
            }
        }
    }

    /**
     * Aggregated net effect of every installed module (numbered sockets + overclock), one line per stat with an
     * up/down arrow and the summed value, e.g. {@code ▲ Speed: +26%} / {@code ▼ Knockback Resistance: -25%}.
     * Sits directly under the module list so players read their build's totals at a glance.
     */
    private static void appendModuleSummary(List<Entry> entries, InfuseArmorPayload payload) {
        List<ArmorModuleType> installed = new ArrayList<>();
        for (String id : payload.moduleSockets()) {
            ArmorModuleType.byId(id).ifPresent(installed::add);
        }
        ArmorModuleType.byId(payload.overclockModule()).ifPresent(installed::add);
        if (installed.isEmpty()) {
            return;
        }

        // Sum each stat across all modules; two effects on the same stat cancel/stack. Insertion order is preserved
        // so the summary reads in the order stats first appear across the loadout.
        LinkedHashMap<String, Agg> totals = new LinkedHashMap<>();
        for (ArmorModuleType type : installed) {
            for (ArmorModuleType.ModuleEffect effect : type.effects()) {
                StatDisplay display = displayFor(effect);
                Agg agg = totals.computeIfAbsent(display.name(), key -> new Agg(display.name(), display.percent()));
                agg.sum += effect.amount();
            }
        }

        List<Component> lines = new ArrayList<>();
        for (Agg agg : totals.values()) {
            if (Math.abs(agg.sum) < 1.0E-9) {
                continue;
            }
            lines.add(summaryLine(agg));
        }
        if (lines.isEmpty()) {
            return;
        }
        entries.add(blank());
        for (Component line : lines) {
            entries.add(plain(line));
        }
    }

    private static Component summaryLine(Agg agg) {
        boolean positive = agg.sum >= 0.0D;
        NamedTextColor color = positive ? NamedTextColor.GREEN : NamedTextColor.RED;
        String arrow = positive ? "\u25B2 " : "\u25BC ";
        String value;
        if (agg.percent) {
            long pct = Math.round(Math.abs(agg.sum) * 100.0D);
            value = (positive ? "+" : "-") + pct + "%";
        } else {
            value = (positive ? "+" : "-") + trimNumber(Math.abs(agg.sum));
        }
        return label(arrow, color)
                .append(label(agg.name + ": ", NamedTextColor.GRAY))
                .append(label(value, color));
    }

    private static StatDisplay displayFor(ArmorModuleType.ModuleEffect effect) {
        if (effect.isNamed()) {
            if (GearRarity.DEFENSE_STAT_KEY.equals(effect.namedKey())) {
                return new StatDisplay("Defense", true);
            }
            if (ExtractionStatKeys.STAMINA_MAX_MULT.equals(effect.namedKey())) {
                return new StatDisplay("Stamina Pool", true);
            }
            if (ExtractionStatKeys.STAMINA_REGEN_MULT.equals(effect.namedKey())) {
                return new StatDisplay("Stamina Regen", true);
            }
            if (ExtractionStatKeys.STAMINA_DRAIN_MULT.equals(effect.namedKey())) {
                return new StatDisplay("Stamina Drain", true);
            }
            return new StatDisplay(prettify(effect.namedKey()), true);
        }
        Attribute attr = effect.attribute();
        if (attr == Attribute.MOVEMENT_SPEED) {
            return new StatDisplay("Speed", true);
        }
        if (attr == Attribute.ATTACK_SPEED) {
            return new StatDisplay("Attack Speed", true);
        }
        if (attr == Attribute.ATTACK_DAMAGE) {
            return new StatDisplay("Attack Damage", false);
        }
        if (attr == Attribute.KNOCKBACK_RESISTANCE) {
            return new StatDisplay("Knockback Resistance", true);
        }
        if (attr == Attribute.MAX_HEALTH) {
            return new StatDisplay("Max Health", false);
        }
        boolean percent = effect.operation() == AttributeModifier.Operation.MULTIPLY_SCALAR_1
                || effect.operation() == AttributeModifier.Operation.ADD_SCALAR;
        return new StatDisplay(prettify(String.valueOf(attr)), percent);
    }

    private static String trimNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 1.0E-9) {
            return Long.toString(Math.round(value));
        }
        return Double.toString(Math.round(value * 10.0D) / 10.0D);
    }

    private static String prettify(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Stat";
        }
        String cleaned = raw.substring(raw.indexOf(':') + 1)
                .replace("generic.", "")
                .replace('.', ' ')
                .replace('_', ' ')
                .trim();
        StringBuilder sb = new StringBuilder();
        for (String part : cleaned.split(" ")) {
            if (part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0)))
                    .append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.length() == 0 ? "Stat" : sb.toString();
    }

    private record StatDisplay(String name, boolean percent) {
    }

    private static final class Agg {
        private final String name;
        private final boolean percent;
        private double sum;

        private Agg(String name, boolean percent) {
            this.name = name;
            this.percent = percent;
        }
    }

    private static void appendShieldSection(List<Entry> entries, InfuseArmorPayload payload, ArmorMark mark) {
        if (!payload.rarity().hasShieldSlot()) {
            return;
        }
        entries.add(blank());
        Optional<ShieldSocketReference> socketed = ShieldSocketReference.decode(
                payload.shieldModule() == null ? "" : payload.shieldModule());
        if (socketed.isEmpty()) {
            if (mark.level() < ArmorMark.MK2.level()) {
                entries.add(plain(Component.text("Shield Socket: Locked (" + ArmorMark.MK2.displayName() + "+ required)",
                        NamedTextColor.DARK_GRAY)));
            } else {
                entries.add(plain(Component.text("Shield Socket: Empty", NamedTextColor.GOLD)));
            }
            return;
        }

        ShieldSocketReference shield = socketed.get();
        ArmorMark required = ArmorMark.requiredForShield(shield.shieldRarity());
        boolean compatible = mark.isAtLeast(required);
        NamedTextColor nameColor = compatible ? NamedTextColor.GOLD : NamedTextColor.RED;

        // Name centered, then the progress bar + percentage centered directly under it (scoreboard-style).
        entries.add(centered(Component.text(shield.displayLabel(), nameColor)));
        if (shield.destroyed()) {
            entries.add(centered(Component.text("SHIELD DOWN", NamedTextColor.RED)
                    .decoration(TextDecoration.BOLD, true)));
        } else {
            entries.add(centered(shieldBar(shield)));
        }
        if (!compatible) {
            entries.add(centered(Component.text("Incompatible - requires " + required.displayName(), NamedTextColor.RED)));
        }
    }

    private static void appendPieceModuleLine(List<Entry> entries, InfuseArmorPayload payload) {
        entries.add(blank());
        Optional<ArmorModuleType> installed = ArmorModuleType.byId(payload.pieceModule());
        if (installed.isPresent()) {
            ArmorModuleType type = installed.get();
            entries.add(plain(label("Module: ", NamedTextColor.DARK_AQUA)
                    .append(Component.text(type.displayName(), type.color())
                            .decoration(TextDecoration.ITALIC, false))));
        } else {
            entries.add(plain(Component.text("Module: Empty", NamedTextColor.DARK_GRAY)));
        }
    }

    private static void appendOverclockLine(List<Entry> entries, InfuseArmorPayload payload) {
        if (payload.rarity().overclockSockets() <= 0) {
            return;
        }
        entries.add(blank());
        Optional<ArmorModuleType> installed = ArmorModuleType.byId(payload.overclockModule());
        if (installed.isPresent()) {
            ArmorModuleType type = installed.get();
            entries.add(plain(label("Overclock: ", NamedTextColor.LIGHT_PURPLE)
                    .append(Component.text(type.displayName(), type.color())
                            .decoration(TextDecoration.ITALIC, false))));
        } else {
            entries.add(plain(Component.text("Overclock: Empty", NamedTextColor.LIGHT_PURPLE)));
        }
    }

    /** Builds {@code [██████░░░░] 62%}: aqua filled, dark-gray unfilled, aqua percentage. */
    private static Component shieldBar(ShieldSocketReference shield) {
        double max = shield.maxPoints();
        double ratio = max <= 0.0D ? 0.0D : Math.max(0.0D, Math.min(1.0D, shield.currentPoints() / max));
        int filled = (int) Math.round(SHIELD_BAR_SEGMENTS * ratio);
        if (filled == 0 && ratio > 0.0D) {
            filled = 1;
        }
        if (filled > SHIELD_BAR_SEGMENTS) {
            filled = SHIELD_BAR_SEGMENTS;
        }
        int empty = SHIELD_BAR_SEGMENTS - filled;
        int percent = (int) Math.round(ratio * 100.0D);
        return label("[", NamedTextColor.DARK_GRAY)
                .append(label("\u2588".repeat(filled), NamedTextColor.AQUA))
                .append(label("\u2588".repeat(empty), NamedTextColor.DARK_GRAY))
                .append(label("] ", NamedTextColor.DARK_GRAY))
                .append(label(percent + "%", NamedTextColor.AQUA));
    }

    /**
     * Centers any {@link Entry} flagged for centering relative to the widest line in the block, mirroring the
     * scoreboard's centering approach so the shield name and its bar sit under each other and centered.
     */
    private static List<Component> centerFlagged(List<Entry> entries) {
        int reference = 1;
        for (Entry entry : entries) {
            if (entry.component != null) {
                reference = Math.max(reference, ExtractionTexts.componentVisibleWidth(entry.component));
            }
        }
        List<Component> lines = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            if (entry.component == null) {
                lines.add(Component.empty());
            } else if (entry.center) {
                lines.add(ExtractionTexts.centerComponent(entry.component, reference));
            } else {
                lines.add(entry.component);
            }
        }
        return lines;
    }

    private static Component label(String text, TextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static Entry plain(Component component) {
        return new Entry(component.decoration(TextDecoration.ITALIC, false), false);
    }

    private static Entry centered(Component component) {
        return new Entry(component.decoration(TextDecoration.ITALIC, false), true);
    }

    private static Entry blank() {
        return new Entry(null, false);
    }

    private record Entry(Component component, boolean center) {
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

    private static String formatPercent(double value) {
        return Math.round(value * 100.0D) + "%";
    }
}
