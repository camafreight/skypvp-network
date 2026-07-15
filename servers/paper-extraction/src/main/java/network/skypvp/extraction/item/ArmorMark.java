package network.skypvp.extraction.item;

import java.util.Locale;

/**
 * Infuse armor upgrade tier (Mk). Higher marks unlock higher-tier shield modules in the dedicated shield socket.
 */
public enum ArmorMark {
    MK1(1),
    MK2(2),
    MK3(3),
    MK4(4),
    MK5(5),
    MK6(6);

    private final int level;

    ArmorMark(int level) {
        this.level = level;
    }

    public int level() {
        return level;
    }

    public boolean isAtLeast(ArmorMark required) {
        return required != null && this.level >= required.level;
    }

    /** Minimum armor mark required to socket a shield of the given rarity. */
    public static ArmorMark requiredForShield(GearRarity shieldRarity) {
        if (shieldRarity == null) {
            return MK2;
        }
        int index = shieldRarity.ordinal() + 1;
        ArmorMark[] values = values();
        if (index >= values.length) {
            return MK6;
        }
        return values[index];
    }

    public static ArmorMark parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return MK1;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (!normalized.startsWith("MK")) {
            normalized = "MK" + normalized;
        }
        for (ArmorMark mark : values()) {
            if (mark.name().equals(normalized)) {
                return mark;
            }
        }
        try {
            int level = Integer.parseInt(normalized.replace("MK", ""));
            for (ArmorMark mark : values()) {
                if (mark.level == level) {
                    return mark;
                }
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        throw new IllegalArgumentException("Unknown armor mark: " + raw);
    }

    public String displayName() {
        return "Mk " + toRoman(level);
    }

    private static String toRoman(int value) {
        return switch (value) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            default -> Integer.toString(value);
        };
    }
}
