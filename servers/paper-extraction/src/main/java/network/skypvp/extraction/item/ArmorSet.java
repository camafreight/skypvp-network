package network.skypvp.extraction.item;

import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Infuse armor set identity. Set bonuses stack with rarity defense and socketed modules.
 * Crafted at the armory workbench alongside a rarity tier.
 */
public enum ArmorSet {

    VANGUARD("vanguard", "Vanguard Set", NamedTextColor.GRAY,
            "Balanced frontline kit with extra plating.",
            GearRarity.DEFENSE_STAT_KEY, 0.05D,
            ExtractionStatKeys.STAMINA_MAX_MULT, 0.0D),
    STRIKER("striker", "Striker Set", NamedTextColor.WHITE,
            "Lightweight rig built for fast repositioning.",
            ExtractionStatKeys.MOVEMENT_SPEED_MULT, 0.08D,
            ExtractionStatKeys.STAMINA_DRAIN_MULT, -0.10D),
    MEDIC("medic", "Medic Set", NamedTextColor.GREEN,
            "Field-support plating with enhanced recovery.",
            ExtractionStatKeys.STAMINA_REGEN_MULT, 0.20D,
            GearRarity.DEFENSE_STAT_KEY, -0.03D),
    PHANTOM("phantom", "Phantom Set", NamedTextColor.DARK_AQUA,
            "Sleek rig favoring endurance over armor.",
            ExtractionStatKeys.STAMINA_MAX_MULT, 0.15D,
            GearRarity.DEFENSE_STAT_KEY, -0.05D);

    private final String id;
    private final String displayName;
    private final NamedTextColor color;
    private final String blurb;
    private final String bonusKeyA;
    private final double bonusAmountA;
    private final String bonusKeyB;
    private final double bonusAmountB;

    ArmorSet(
            String id,
            String displayName,
            NamedTextColor color,
            String blurb,
            String bonusKeyA,
            double bonusAmountA,
            String bonusKeyB,
            double bonusAmountB
    ) {
        this.id = id;
        this.displayName = displayName;
        this.color = color;
        this.blurb = blurb;
        this.bonusKeyA = bonusKeyA;
        this.bonusAmountA = bonusAmountA;
        this.bonusKeyB = bonusKeyB;
        this.bonusAmountB = bonusAmountB;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public NamedTextColor color() {
        return color;
    }

    public String blurb() {
        return blurb;
    }

    public String bonusKeyA() {
        return bonusKeyA;
    }

    public double bonusAmountA() {
        return bonusAmountA;
    }

    public String bonusKeyB() {
        return bonusKeyB;
    }

    public double bonusAmountB() {
        return bonusAmountB;
    }

    public static Optional<ArmorSet> byId(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (ArmorSet set : values()) {
            if (set.id.equals(normalized)) {
                return Optional.of(set);
            }
        }
        return Optional.empty();
    }

    public static ArmorSet parse(String raw, ArmorSet fallback) {
        return byId(raw).orElse(fallback == null ? VANGUARD : fallback);
    }
}
