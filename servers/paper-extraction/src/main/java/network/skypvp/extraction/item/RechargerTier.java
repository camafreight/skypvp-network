package network.skypvp.extraction.item;

import java.util.Locale;

/**
 * Tier of a shield recharger item. The recharger — not the shield — determines how fast the shield's buffer refills.
 * Higher tiers regenerate faster; the top tier restores instantly.
 */
public enum RechargerTier {
    FIELD("Field Recharger", 8.0D, 2.0D, false),
    TACTICAL("Tactical Recharger", 15.0D, 4.0D, false),
    MILITARY("Military Recharger", 25.0D, 8.0D, false),
    QUANTUM("Quantum Recharger", 40.0D, 0.0D, true);

    private final String displayName;
    private final double rechargeAmount;
    private final double pointsPerSecond;
    private final boolean instant;

    RechargerTier(String displayName, double rechargeAmount, double pointsPerSecond, boolean instant) {
        this.displayName = displayName;
        this.rechargeAmount = rechargeAmount;
        this.pointsPerSecond = pointsPerSecond;
        this.instant = instant;
    }

    public String displayName() {
        return displayName;
    }

    /** Total shield points this recharger restores per use (capped at the shield's remaining capacity). */
    public double rechargeAmount() {
        return rechargeAmount;
    }

    /** Shield points restored per second while the charge is delivering. Ignored when {@link #instant()} is true. */
    public double pointsPerSecond() {
        return pointsPerSecond;
    }

    public boolean instant() {
        return instant;
    }

    public String amountLabel() {
        return String.format(Locale.ROOT, "%.0f pts", rechargeAmount);
    }

    public String rateLabel() {
        return instant ? "Instant" : String.format(Locale.ROOT, "%.1f pts/s", pointsPerSecond);
    }

    public static RechargerTier fromId(String raw) {
        if (raw == null || raw.isBlank()) {
            return FIELD;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return FIELD;
        }
    }
}
