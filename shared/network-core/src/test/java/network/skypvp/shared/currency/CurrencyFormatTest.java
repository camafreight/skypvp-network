package network.skypvp.shared.currency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class CurrencyFormatTest {

    @Test
    void groupedFormatsWithCommas() {
        assertEquals("1", CurrencyFormat.formatCoins(1L));
        assertEquals("999", CurrencyFormat.formatCoins(999L));
        assertEquals("1,234", CurrencyFormat.formatCoins(1_234L));
        assertEquals("12,345,678", CurrencyFormat.formatCoins(12_345_678L));
    }

    @Test
    void compactScalesThroughQuintillion() {
        assertEquals("999", CurrencyFormat.formatCompact(999L));
        assertEquals("1.0K", CurrencyFormat.formatCompact(1_000L));
        assertEquals("1.5K", CurrencyFormat.formatCompact(1_500L));
        assertEquals("1.0M", CurrencyFormat.formatCompact(1_000_000L));
        assertEquals("2.5B", CurrencyFormat.formatCompact(2_500_000_000L));
        assertEquals("3.0T", CurrencyFormat.formatCompact(3_000_000_000_000L));
        assertEquals("4.0Qa", CurrencyFormat.formatCompact(4_000_000_000_000_000L));
        assertEquals("9.2Qi", CurrencyFormat.formatCompact(9_223_372_036_854_775_807L));
    }

    @Test
    void quantityUsesPlainThenCompact() {
        assertEquals("500", CurrencyFormat.formatQuantity(500L));
        assertEquals("1.0K", CurrencyFormat.formatQuantity(1_000L));
        assertEquals("1.0M", CurrencyFormat.formatQuantity(1_000_000L));
    }

    @Test
    void bigIntegerGroupedAndCompact() {
        BigInteger huge = new BigInteger("123456789012345678901234567890");
        assertEquals("123,456,789,012,345,678,901,234,567,890", CurrencyFormat.format(huge, CurrencyFormat.Mode.GROUPED));
        assertTrue(CurrencyFormat.format(huge, CurrencyFormat.Mode.COMPACT).endsWith("Oc"));
    }

    @Test
    void detectsQuantityLoreLines() {
        assertTrue(CurrencyFormat.isQuantityLoreLine("x500"));
        assertTrue(CurrencyFormat.isQuantityLoreLine("x1.5K"));
        assertTrue(CurrencyFormat.isQuantityLoreLine("x1,234"));
        assertTrue(CurrencyFormat.isQuantityLoreLine("x9.2Qi"));
    }
}
