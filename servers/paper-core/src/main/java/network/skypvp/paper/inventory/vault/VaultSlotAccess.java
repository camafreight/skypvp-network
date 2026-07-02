package network.skypvp.paper.inventory.vault;

public final class VaultSlotAccess {

    private VaultSlotAccess() {
    }

    public static int defaultUnlockedRows() {
        return VaultLayout.SLOTS_PER_PAGE / VaultLayout.SLOTS_PER_ROW;
    }

    public static int maxRows() {
        return VaultLayout.MAX_VAULT_SLOTS / VaultLayout.SLOTS_PER_ROW;
    }

    public static int depositableLimit(int unlockedRows) {
        int rows = Math.max(unlockedRows, defaultUnlockedRows());
        return Math.min(rows * VaultLayout.SLOTS_PER_ROW, VaultLayout.MAX_VAULT_SLOTS);
    }

    public static int rowForVaultIndex(int vaultIndex) {
        return vaultIndex / VaultLayout.SLOTS_PER_ROW;
    }

    public static boolean isDepositableIndex(int vaultIndex, int unlockedRows) {
        return vaultIndex >= 0
                && vaultIndex < VaultLayout.MAX_VAULT_SLOTS
                && vaultIndex < depositableLimit(unlockedRows);
    }

    /** The only row that can be purchased right now (immediate next row). */
    public static boolean isPurchasableRow(int rowIndex, int unlockedRows) {
        return rowIndex == unlockedRows && unlockedRows < maxRows();
    }

    public static int pageForVaultIndex(int vaultIndex) {
        return VaultLayout.scrollRowForVaultIndex(vaultIndex);
    }

    public static int clampUnlockedRows(int unlockedRows) {
        return Math.min(Math.max(unlockedRows, defaultUnlockedRows()), maxRows());
    }
}
