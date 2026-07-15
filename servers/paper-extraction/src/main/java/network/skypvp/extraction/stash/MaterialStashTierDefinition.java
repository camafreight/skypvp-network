package network.skypvp.extraction.stash;

/** One material stash capacity tier (slots + total item capacity). */
public record MaterialStashTierDefinition(
        int tier,
        String name,
        int maxCapacity,
        int maxSlots,
        long upgradeCoins,
        long upgradeGold
) {

    public MaterialStashTierDefinition {
        if (tier < 1) {
            throw new IllegalArgumentException("tier must be >= 1");
        }
        if (name == null || name.isBlank()) {
            name = "Tier " + tier;
        }
        maxCapacity = Math.max(1, maxCapacity);
        maxSlots = Math.max(1, maxSlots);
        upgradeCoins = Math.max(0L, upgradeCoins);
        upgradeGold = Math.max(0L, upgradeGold);
    }

    public boolean hasUpgradeCost() {
        return upgradeCoins > 0L || upgradeGold > 0L;
    }
}
