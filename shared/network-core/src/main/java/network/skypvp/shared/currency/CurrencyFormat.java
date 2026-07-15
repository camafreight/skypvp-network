package network.skypvp.shared.currency;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Formats currency balances and large quantities for display.
 * Supports values from {@code 1} through {@link Long#MAX_VALUE} and beyond via {@link BigInteger}.
 */
public final class CurrencyFormat {

    /** How a balance or quantity should be rendered. */
    public enum Mode {
        /** Comma-separated integer: {@code 1,234,567}. */
        GROUPED,
        /** Suffix compact: {@code 1.2K} … {@code 9.2Qi} (scales for larger values). */
        COMPACT,
        /** Plain digits with no grouping: {@code 1234567}. */
        PLAIN,
        /** Plain below 1K, compact from 1K upward (material stacks). */
        QUANTITY
    }

    private static final Locale US = Locale.US;
    private static final NumberFormat GROUPED_FORMAT = NumberFormat.getIntegerInstance(US);
    private static final String[] COMPACT_SUFFIXES = {
            "", "K", "M", "B", "T", "Qa", "Qi", "Sx", "Sp", "Oc", "No", "Dc"
    };
    private static final BigInteger THOUSAND = BigInteger.valueOf(1_000L);

    private CurrencyFormat() {
    }

    /** Default grouped format suitable for coin balances and exact prices. */
    public static String formatCoins(long amount) {
        return format(amount, Mode.GROUPED);
    }

    /** Default grouped format suitable for gold balances and exact prices. */
    public static String formatGold(long amount) {
        return format(amount, Mode.GROUPED);
    }

    /** Compact format for space-constrained UI (HUD bars, capacity summaries). */
    public static String formatCompact(long amount) {
        return format(amount, Mode.COMPACT);
    }

    /** Material quantity display: plain below 1K, compact otherwise. */
    public static String formatQuantity(long amount) {
        return format(amount, Mode.QUANTITY);
    }

    public static String format(long amount) {
        return format(amount, Mode.GROUPED);
    }

    public static String format(long amount, Mode mode) {
        if (mode == null) {
            mode = Mode.GROUPED;
        }
        return switch (mode) {
            case GROUPED -> grouped(amount);
            case COMPACT -> compact(amount);
            case PLAIN -> Long.toString(amount);
            case QUANTITY -> amount < 1_000L ? Long.toString(amount) : compact(amount);
        };
    }

    public static String format(BigInteger amount, Mode mode) {
        if (amount == null) {
            return "0";
        }
        if (mode == null) {
            mode = Mode.GROUPED;
        }
        if (amount.bitLength() <= 63) {
            return format(amount.longValue(), mode);
        }
        return switch (mode) {
            case GROUPED -> grouped(amount);
            case COMPACT -> compact(amount);
            case PLAIN -> amount.toString();
            case QUANTITY -> amount.compareTo(THOUSAND) < 0 ? amount.toString() : compact(amount);
        };
    }

    /** Whether plain text matches a stash quantity lore line ({@code x123}, {@code x1.5K}, {@code x1,234}). */
    public static boolean isQuantityLoreLine(String plainText) {
        if (plainText == null) {
            return false;
        }
        return plainText.trim().matches("x(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d)?(?:[KMBTQaQiSxSpOcNoDc]+)?");
    }

    private static String grouped(long amount) {
        return GROUPED_FORMAT.format(amount);
    }

    private static String grouped(BigInteger amount) {
        String raw = amount.abs().toString();
        StringBuilder grouped = new StringBuilder(raw.length() + raw.length() / 3);
        int len = raw.length();
        for (int i = 0; i < len; i++) {
            if (i > 0 && (len - i) % 3 == 0) {
                grouped.append(',');
            }
            grouped.append(raw.charAt(i));
        }
        if (amount.signum() < 0) {
            grouped.insert(0, '-');
        }
        return grouped.toString();
    }

    private static String compact(long amount) {
        if (amount == 0L) {
            return "0";
        }
        if (amount == Long.MIN_VALUE) {
            return compact(BigInteger.valueOf(amount));
        }
        if (amount < 0L) {
            return "-" + compact(-amount);
        }
        if (amount < 1_000L) {
            return Long.toString(amount);
        }
        int maxTier = Math.min((Long.toString(amount).length() - 1) / 3, COMPACT_SUFFIXES.length - 1);
        int tier = 0;
        long divisor = 1L;
        while (tier < maxTier) {
            long nextDivisor = divisor * 1_000L;
            if (nextDivisor <= 0L) {
                break;
            }
            divisor = nextDivisor;
            tier++;
        }
        double scaled = amount / (double) divisor;
        return String.format(US, "%.1f%s", scaled, COMPACT_SUFFIXES[tier]);
    }

    private static String compact(BigInteger amount) {
        if (amount.signum() == 0) {
            return "0";
        }
        BigInteger abs = amount.abs();
        if (abs.compareTo(THOUSAND) < 0) {
            return amount.toString();
        }
        int tier = 0;
        BigInteger divisor = BigInteger.ONE;
        while (abs.compareTo(divisor.multiply(THOUSAND)) >= 0 && tier < COMPACT_SUFFIXES.length - 1) {
            divisor = divisor.multiply(THOUSAND);
            tier++;
        }
        BigDecimal scaled = new BigDecimal(abs).divide(new BigDecimal(divisor), 1, RoundingMode.HALF_UP);
        String formatted = scaled.stripTrailingZeros().toPlainString();
        if (!formatted.contains(".")) {
            formatted = scaled.setScale(1, RoundingMode.HALF_UP).toPlainString();
        }
        if (amount.signum() < 0) {
            formatted = "-" + formatted;
        }
        return formatted + COMPACT_SUFFIXES[tier];
    }
}
