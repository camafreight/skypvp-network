package network.skypvp.paper.tabboard;

/**
 * Vanilla tab-list grid geometry. The client renders up to 80 listed entries and picks the
 * smallest column count whose rows fit in 20, filling top-to-bottom then left-to-right
 * (index {@code i} lands at column {@code i / rows}, row {@code i % rows}). Publishing exactly
 * {@link #CELL_COUNT} entries therefore pins the layout to a full 4x20 grid where every cell is
 * its own entry with its own head icon and ping bars.
 */
public final class TabBoardLayout {

    public static final int ROWS = 20;
    public static final int COLS = 4;
    public static final int CELL_COUNT = ROWS * COLS;

    /** Fixed text width per cell so the grid never jitters as content changes. */
    public static final int CELL_TEXT_WIDTH_PIXELS = 120;

    private TabBoardLayout() {
    }

    /** Column-major cell index matching the vanilla fill order. */
    public static int cellIndex(int col, int row) {
        int safeCol = Math.max(0, Math.min(COLS - 1, col));
        int safeRow = Math.max(0, Math.min(ROWS - 1, row));
        return safeCol * ROWS + safeRow;
    }

    /**
     * Profile name used purely as the client-side sort key ("00".."79"); fixed two digits keep
     * lexicographic order equal to numeric order.
     */
    public static String sortName(int col, int row) {
        return String.format("%02d", cellIndex(col, row));
    }
}
