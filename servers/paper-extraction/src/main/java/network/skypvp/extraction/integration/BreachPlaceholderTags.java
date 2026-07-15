package network.skypvp.extraction.integration;

import java.util.Locale;
import network.skypvp.extraction.config.BreachConfigService;
import network.skypvp.extraction.engine.BreachInstance;
import network.skypvp.extraction.gameplay.loot.BreachLootChestRegistry;
import network.skypvp.extraction.text.ExtractionTexts;
import network.skypvp.extraction.model.BreachState;

public final class BreachPlaceholderTags {

    private BreachPlaceholderTags() {
    }

    static String phaseKey(BreachInstance instance, BreachConfigService config) {
        return switch (instance.state()) {
            case WAITING -> "waiting";
            case STARTING -> "starting";
            case ENDING -> "ending";
            case RESETTING -> "resetting";
            case TOXIC -> "toxic";
            case ACTIVE -> activePhaseKey(instance, config);
        };
    }

    public static String phaseLabel(BreachInstance instance, BreachConfigService config) {
        return phaseLabel(instance, config, ExtractionTexts.defaultLocale());
    }

    public static String phaseLabel(BreachInstance instance, BreachConfigService config, String locale) {
        return ExtractionTexts.text(
                "extraction.phase." + phaseKey(instance, config),
                locale
        );
    }

    static String lootStateKey(double percentRemaining) {
        if (percentRemaining >= 80.0D) {
            return "plenty";
        }
        if (percentRemaining >= 60.0D) {
            return "high";
        }
        if (percentRemaining >= 40.0D) {
            return "moderate";
        }
        if (percentRemaining >= 20.0D) {
            return "low";
        }
        if (percentRemaining >= 5.0D) {
            return "scarce";
        }
        return "barren";
    }

    public static String lootStateLabel(double percentRemaining) {
        return lootStateLabel(percentRemaining, ExtractionTexts.defaultLocale());
    }

    public static String lootStateLabel(double percentRemaining, String locale) {
        return ExtractionTexts.text(
                "extraction.loot." + lootStateKey(percentRemaining),
                locale
        );
    }

    public static double lootPercent(BreachLootChestRegistry.WorldLootStats stats) {
        if (stats == null) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(100.0D, stats.percentRemaining()));
    }

    public static String formatPercent(double value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }

    static String formatPercentDetailed(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    static String formatDuration(int seconds) {
        int safe = Math.max(0, seconds);
        int hours = safe / 3600;
        int minutes = (safe % 3600) / 60;
        int remainder = safe % 60;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, remainder);
        }
        return String.format(Locale.ROOT, "%d:%02d", minutes, remainder);
    }

    public static int timeProgressPercent(BreachInstance instance) {
        int duration = Math.max(1, instance.durationSeconds());
        int elapsed = Math.max(0, duration - Math.max(0, instance.remainingSeconds()));
        return (int) Math.round(100.0D * elapsed / duration);
    }

    public static int timeRemainingPercent(BreachInstance instance) {
        int duration = Math.max(1, instance.durationSeconds());
        int remaining = Math.max(0, instance.remainingSeconds());
        return (int) Math.round(100.0D * remaining / duration);
    }

    private static String activePhaseKey(BreachInstance instance, BreachConfigService config) {
        int remaining = Math.max(0, instance.remainingSeconds());
        int closingSoon = Math.max(1, config.extractClosingSoonSeconds());
        if (remaining <= closingSoon) {
            return "closing";
        }
        double ratio = (double) remaining / Math.max(1, instance.durationSeconds());
        if (ratio >= 0.75D) {
            return "early";
        }
        if (ratio >= 0.50D) {
            return "mid";
        }
        if (ratio >= 0.25D) {
            return "late";
        }
        return "final";
    }
}
