package network.skypvp.extraction.item;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import network.skypvp.extraction.crafting.ItemConfigOverrides;
import network.skypvp.extraction.gameplay.BreachPlayerVitality;
import network.skypvp.extraction.gameplay.BreachStaminaService;

/** Lore/stat lines for medic items, honoring optional JSON overrides. */
public final class MedicConsumableTexts {

    private MedicConsumableTexts() {
    }

    public static List<String> statMiniMessageLines(MedicConsumableType type) {
        List<String> custom = ItemConfigOverrides.loreLines(type.typeId());
        if (!custom.isEmpty()) {
            return List.copyOf(custom);
        }

        List<String> lines = new ArrayList<>();
        lines.add("<dark_gray>" + type.categoryLabel());
        lines.add("");

        double healAmount = ItemConfigOverrides.medicHeal(type);
        double staminaRestore = ItemConfigOverrides.medicStaminaRestore(type);
        long drainFreezeMillis = ItemConfigOverrides.medicDrainFreezeMillis(type);
        long regenBoostMillis = ItemConfigOverrides.medicRegenBoostMillis(type);

        if (healAmount > 0.0D) {
            int heal = (int) Math.round(healAmount);
            int healPct = percentOf(healAmount, BreachPlayerVitality.RAID_MAX_HEALTH);
            double chunk = ItemConfigOverrides.medicHealChunk(type);
            long intervalTicks = ItemConfigOverrides.medicHealChunkIntervalTicks(type);
            float consumeSeconds = ItemConfigOverrides.medicConsumeSeconds(type);
            lines.add("<green>+" + heal + " HP <gray>(" + healPct + "% of " + (int) BreachPlayerVitality.RAID_MAX_HEALTH + " raid max)");
            lines.add("<gray>Eat " + formatDuration((long) (consumeSeconds * 1000.0F))
                    + ", then +" + formatHp(chunk) + " HP every " + formatDuration(intervalTicks * 50L));
            if (healAmount > BreachPlayerVitality.RAID_MAX_HEALTH) {
                lines.add("<gray>Caps at raid max health");
            }
        }
        if (staminaRestore > 0.0D) {
            int stamina = (int) Math.round(staminaRestore);
            int staminaPct = percentOf(staminaRestore, BreachStaminaService.BASE_MAX);
            lines.add("<yellow>+" + stamina + " stamina <gray>(" + staminaPct + "% of " + (int) BreachStaminaService.BASE_MAX + " base pool)");
        }
        if (drainFreezeMillis > 0L) {
            lines.add("<aqua>Sprint drain disabled for " + formatDuration(drainFreezeMillis));
            if (type == MedicConsumableType.STAMINA_STABILIZER) {
                lines.add("<gray>Passive stamina regen continues");
            }
        }
        if (regenBoostMillis > 0L) {
            lines.add("<light_purple>+" + regenBoostPercent() + "% stamina regen for " + formatDuration(regenBoostMillis));
            lines.add("<gray>Sprint drain -" + sprintDrainReductionPercent() + "% while boosted");
        }
        return List.copyOf(lines);
    }

    private static int regenBoostPercent() {
        return 50;
    }

    private static int sprintDrainReductionPercent() {
        return 65;
    }

    private static int percentOf(double value, double total) {
        if (total <= 0.0D) {
            return 0;
        }
        return (int) Math.round((value / total) * 100.0D);
    }

    private static String formatDuration(long millis) {
        if (millis <= 0L) {
            return "0s";
        }
        double seconds = millis / 1000.0D;
        if (millis % 1000L == 0L) {
            return (millis / 1000L) + "s";
        }
        return String.format(Locale.US, "%.1fs", seconds);
    }

    private static String formatHp(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.05D) {
            return String.format(Locale.US, "%.0f", value);
        }
        return String.format(Locale.US, "%.1f", value);
    }
}
