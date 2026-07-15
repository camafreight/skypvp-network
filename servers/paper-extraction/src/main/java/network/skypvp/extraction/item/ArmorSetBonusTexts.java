package network.skypvp.extraction.item;

import java.util.ArrayList;
import java.util.List;

/** MiniMessage lines describing armor set bonuses for lore and craft previews. */
public final class ArmorSetBonusTexts {

    private ArmorSetBonusTexts() {
    }

    public static List<String> miniMessageLines(ArmorSet set, double share) {
        if (set == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        lines.add("<dark_gray>Set Bonuses <gray>(" + formatShare(share) + " per piece)");
        if (set.bonusAmountA() != 0.0D) {
            lines.add(formatBonus(set.bonusKeyA(), set.bonusAmountA() * share));
        }
        if (set.bonusAmountB() != 0.0D) {
            lines.add(formatBonus(set.bonusKeyB(), set.bonusAmountB() * share));
        }
        if (set.blurb() != null && !set.blurb().isBlank()) {
            lines.add("<gray>" + set.blurb());
        }
        return List.copyOf(lines);
    }

    private static String formatShare(double share) {
        int pct = (int) Math.round(share * 100.0D);
        return pct + "%";
    }

    private static String formatBonus(String key, double amount) {
        String label = labelFor(key);
        String sign = amount >= 0.0D ? "+" : "";
        int pct = (int) Math.round(Math.abs(amount) * 100.0D);
        String color = amount >= 0.0D ? "<green>" : "<red>";
        return color + sign + pct + "% " + label;
    }

    private static String labelFor(String key) {
        if (key == null) {
            return "Unknown";
        }
        if (GearRarity.DEFENSE_STAT_KEY.equals(key)) {
            return "Defense";
        }
        if (ExtractionStatKeys.STAMINA_MAX_MULT.equals(key)) {
            return "Stamina Pool";
        }
        if (ExtractionStatKeys.STAMINA_REGEN_MULT.equals(key)) {
            return "Stamina Regen";
        }
        if (ExtractionStatKeys.STAMINA_DRAIN_MULT.equals(key)) {
            return amountLabelDrain(key);
        }
        if (ExtractionStatKeys.MOVEMENT_SPEED_MULT.equals(key)) {
            return "Movement Speed";
        }
        return key;
    }

    private static String amountLabelDrain(String key) {
        return "Stamina Drain";
    }
}
