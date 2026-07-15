package network.skypvp.extraction.hud;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import network.skypvp.shared.ServerTextUtil;

/**
 * Boss-bar title line with FIXED-WIDTH SECTIONS. Every section occupies a constant pixel
 * footprint — content is drawn inside it and the cursor is then moved (with positive or
 * negative space glyphs) to the section's exact end — so changing amounts, localized status
 * text, or swapping game states (cycle timer ↔ extract dwell ↔ joining) can never shove the
 * balance readout around. Total advance is normalized to zero, pinning the layout to the
 * screen-center column.
 *
 * <pre>
 *   [ GOLD 46px ][ COINS 46px ]  gap  [ boss bar -91..+91 ]
 *                                     [   CENTER 160px status box (over the bar)   ]
 * </pre>
 */
public final class BreachBalanceLine {

    /** Fixed footprint of each balance section (icon 11 + gap 2 + up to ~31px of digits). */
    private static final int BALANCE_SECTION_WIDTH = 46;
    /** Balance sections end just left of the boss bar's left end (bar spans -91..+91). */
    private static final int BALANCE_RIGHT_EDGE = -100;
    /** Status title renders centered inside a fixed box straddling the bar. */
    private static final int CENTER_SECTION_HALF_WIDTH = 80;

    private static final int ICON_TEXT_GAP = 2;

    private static final TextColor GOLD_COLOR = TextColor.color(0xFFC04D);
    private static final TextColor COIN_COLOR = TextColor.color(0xFFEE88);

    private BreachBalanceLine() {
    }

    /**
     * Composes the boss-bar name from fixed sections. With a status title (in breach), the
     * two balance sections sit left of the bar and the title is centered in its own fixed
     * box; without one (hub), the balance sections themselves straddle the center.
     */
    public static Component compose(Component centerTitle, String goldAmount, String coinsAmount) {
        boolean hasTitle = centerTitle != null && !Component.empty().equals(centerTitle);
        int goldStart = hasTitle
                ? BALANCE_RIGHT_EDGE - 2 * BALANCE_SECTION_WIDTH
                : -BALANCE_SECTION_WIDTH;
        int coinsStart = goldStart + BALANCE_SECTION_WIDTH;

        TextComponent.Builder line = Component.text();
        Cursor cursor = new Cursor(line);

        balanceSection(cursor, goldStart, BreachHudFont.ICON_GOLD, GOLD_COLOR,
                goldAmount == null ? "0" : goldAmount);
        balanceSection(cursor, coinsStart, BreachHudFont.ICON_COIN, COIN_COLOR,
                coinsAmount == null ? "0" : coinsAmount);

        if (hasTitle) {
            // Center the title within its fixed box; the closing moveTo lands on the box
            // edge no matter how wide the localized text measured, so the section's total
            // advance is constant even when the measurement over- or under-shoots.
            int titleWidth = Math.max(0, ServerTextUtil.componentVisibleWidth(centerTitle));
            int boxWidth = 2 * CENTER_SECTION_HALF_WIDTH;
            int inset = Math.max(0, (boxWidth - titleWidth) / 2);
            cursor.moveTo(-CENTER_SECTION_HALF_WIDTH + inset);
            line.append(centerTitle);
            cursor.advance(titleWidth);
            cursor.moveTo(CENTER_SECTION_HALF_WIDTH);
        }

        cursor.moveTo(0);
        return line.build();
    }

    /** One fixed-width balance section: icon + amount, then land exactly on the section end. */
    private static void balanceSection(Cursor cursor, int startX, char icon, TextColor color, String amount) {
        Component text = Component.text(amount, color);
        int textWidth = Math.max(0, ServerTextUtil.componentVisibleWidth(text));
        cursor.moveTo(startX);
        cursor.glyph(icon, color);
        cursor.moveTo(startX + BreachHudFont.ICON_ADVANCE + ICON_TEXT_GAP);
        cursor.line.append(text);
        cursor.advance(textWidth);
        cursor.moveTo(startX + BALANCE_SECTION_WIDTH);
    }

    /** Absolute-position pen over the hud font's negative/positive space glyphs. */
    private static final class Cursor {
        private final TextComponent.Builder line;
        private int x;

        Cursor(TextComponent.Builder line) {
            this.line = line;
        }

        void moveTo(int target) {
            String moves = BreachHudFont.offset(target - x);
            if (!moves.isEmpty()) {
                line.append(Component.text(moves).font(BreachHudFont.FONT));
            }
            x = target;
        }

        void glyph(char glyphChar, TextColor color) {
            line.append(Component.text(String.valueOf(glyphChar)).font(BreachHudFont.FONT).color(color));
            x += BreachHudFont.ICON_ADVANCE;
        }

        void advance(int pixels) {
            x += pixels;
        }
    }
}
