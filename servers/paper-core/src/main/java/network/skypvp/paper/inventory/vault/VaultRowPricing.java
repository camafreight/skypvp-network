package network.skypvp.paper.inventory.vault;

import network.skypvp.shared.currency.CurrencyFormat;

/** Procedural coin cost for unlocking the next vault row (one row at a time). */
public final class VaultRowPricing {

    private static final int BASE_PRICE = 500;
    private static final double GROWTH = 1.32;

    private VaultRowPricing() {
    }

    /**
     * Coins required to unlock {@code rowIndex} (0-based). Rows below {@link VaultSlotAccess#defaultUnlockedRows()}
     * are free and return {@code 0}.
     */
    public static long priceForRow(int rowIndex) {
        int firstPaidRow = VaultSlotAccess.defaultUnlockedRows();
        if (rowIndex < firstPaidRow) {
            return 0L;
        }
        int tier = rowIndex - firstPaidRow;
        return (long) Math.ceil(BASE_PRICE * Math.pow(GROWTH, tier));
    }

    public static String formatCoins(long amount) {
        return CurrencyFormat.formatCoins(Math.max(0L, amount));
    }
}
