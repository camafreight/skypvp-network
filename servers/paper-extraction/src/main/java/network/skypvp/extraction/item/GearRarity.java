package network.skypvp.extraction.item;

/**
 * Infuse gear rarity tiers. Defense is a percentage reduction applied via named stat while equipped.
 */
public enum GearRarity {
    COMMON(0.10D, 1, 0, false, ArmorMark.MK1, 12.0D, 200.0D),
    UNCOMMON(0.15D, 2, 0, true, ArmorMark.MK3, 18.0D, 320.0D),
    RARE(0.20D, 3, 0, true, ArmorMark.MK4, 26.0D, 480.0D),
    EPIC(0.28D, 4, 1, true, ArmorMark.MK5, 36.0D, 700.0D),
    LEGENDARY(0.35D, 5, 2, true, ArmorMark.MK6, 50.0D, 1000.0D);

    public static final String DEFENSE_STAT_KEY = "extraction:defense_percent";

    private final double defensePercent;
    private final int moduleSockets;
    private final int overclockSockets;
    private final boolean shieldSlot;
    private final ArmorMark maxMark;
    private final double shieldCapacity;
    private final double shieldIntegrity;

    GearRarity(
            double defensePercent,
            int moduleSockets,
            int overclockSockets,
            boolean shieldSlot,
            ArmorMark maxMark,
            double shieldCapacity,
            double shieldIntegrity
    ) {
        this.defensePercent = defensePercent;
        this.moduleSockets = moduleSockets;
        this.overclockSockets = overclockSockets;
        this.shieldSlot = shieldSlot;
        this.maxMark = maxMark;
        this.shieldCapacity = shieldCapacity;
        this.shieldIntegrity = shieldIntegrity;
    }

    public double defensePercent() {
        return defensePercent;
    }

    /** Rechargeable shield buffer (points) restored to full by a shield recharger. */
    public double shieldCapacity() {
        return shieldCapacity;
    }

    /** Total damage this shield can absorb over its lifetime before it is permanently destroyed. */
    public double shieldIntegrity() {
        return shieldIntegrity;
    }

    public int moduleSockets() {
        return moduleSockets;
    }

    public int overclockSockets() {
        return overclockSockets;
    }

    public boolean hasShieldSlot() {
        return shieldSlot;
    }

    public ArmorMark maxMark() {
        return maxMark;
    }

    public String displayName() {
        return name().charAt(0) + name().substring(1).toLowerCase();
    }
}
