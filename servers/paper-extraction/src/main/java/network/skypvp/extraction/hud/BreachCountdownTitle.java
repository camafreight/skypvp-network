package network.skypvp.extraction.hud;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

/**
 * Builds the animated breach countdown title: a BIG resource-pack digit that slides in
 * toward screen center, one frame per tick.
 *
 * <p>Each frame is a single hud-font line whose total advance is normalized to zero, so the
 * client's title centering pins the line origin to the screen center; the digit is then
 * absolutely placed at the eased slide offset. Titles render text at 4x scale, so the 20px
 * digit glyphs show ~80px tall and every 1px offset moves 4 screen pixels.
 */
public final class BreachCountdownTitle {

    /** Eased slide-in from the left of center (join countdown). */
    private static final int[] SLIDE_FROM_LEFT = {-64, -34, -16, -7, -2, 0};

    /** Eased slide-in from the right of center (extract dwell countdown). */
    private static final int[] SLIDE_FROM_RIGHT = {64, 34, 16, 7, 2, 0};

    private static final TextColor CALM = TextColor.color(0xE8F4FF);
    private static final TextColor WARN = TextColor.color(0xFFD75A);
    private static final TextColor HOT = TextColor.color(0xFF9A3D);
    private static final TextColor CRITICAL = TextColor.color(0xFF4655);

    public enum Slide {
        FROM_LEFT,
        FROM_RIGHT
    }

    private BreachCountdownTitle() {
    }

    /** Number of slide frames; schedule one per tick starting at the second boundary. */
    public static int frameCount() {
        return SLIDE_FROM_LEFT.length;
    }

    /** Title line for slide frame {@code frameIndex} of the given remaining-seconds value (from left). */
    public static Component frame(int seconds, int frameIndex) {
        return frame(seconds, frameIndex, Slide.FROM_LEFT);
    }

    /** Extract dwell digit that slides in from the right. */
    public static Component frameFromRight(int seconds, int frameIndex) {
        return frame(seconds, frameIndex, Slide.FROM_RIGHT);
    }

    public static Component frame(int seconds, int frameIndex, Slide slide) {
        String text = Integer.toString(Math.max(0, seconds));
        String glyphs = BreachHudFont.bigDigits(text);
        if (glyphs.isEmpty()) {
            return Component.empty();
        }
        int[] offsets = slide == Slide.FROM_RIGHT ? SLIDE_FROM_RIGHT : SLIDE_FROM_LEFT;
        int width = BreachHudFont.bigDigitsWidth(text);
        int slidePx = offsets[Math.max(0, Math.min(offsets.length - 1, frameIndex))];
        int startX = slidePx - width / 2;

        return Component.text()
                .append(fontText(BreachHudFont.offset(startX), null))
                .append(fontText(glyphs, colorFor(seconds)))
                .append(fontText(BreachHudFont.offset(-(startX + width)), null))
                .build();
    }

    private static Component fontText(String content, TextColor color) {
        if (content.isEmpty()) {
            return Component.empty();
        }
        Component text = Component.text(content).font(BreachHudFont.FONT);
        return color != null ? text.color(color) : text;
    }

    private static TextColor colorFor(int seconds) {
        if (seconds <= 1) {
            return CRITICAL;
        }
        if (seconds == 2) {
            return HOT;
        }
        if (seconds == 3) {
            return WARN;
        }
        return CALM;
    }
}
