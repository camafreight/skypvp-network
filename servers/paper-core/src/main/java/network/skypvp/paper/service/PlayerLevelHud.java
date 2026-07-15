package network.skypvp.paper.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/**
 * Composes the level badge HUD that replaces the vanilla experience-level number, plus the
 * meteor-shower XP-gain animation, as an advance-neutral {@code skypvp:hud} glyph cluster.
 *
 * <p><b>Vertical layout</b> — for a glyph on the action-bar line, the top pixel row lands at
 * {@code screenHeight - 61 - ascent} (same mapping the extraction HUD tiers are built on).
 * The vanilla XP bar occupies rows {@code h-29..h-25} and the vanilla level number sat at
 * {@code h-35..h-28}, so the bar-tier providers in {@code hud.json} place:
 * <ul>
 *   <li>badge (height 12, ascent -21): rows {@code h-40..h-29} — bottom edge kissing the bar
 *       top exactly like the vanilla number's outline did;</li>
 *   <li>level digits (height 10, ascent -22): rows {@code h-39..h-30}, centered in the badge band;</li>
 *   <li>meteor frames (40x32, ascent 11): rows {@code h-72..h-41}, so the falling streaks end
 *       one row above the badge and the impact flash reads as hitting it.</li>
 * </ul>
 *
 * <p><b>Horizontal layout</b> — the cluster's total advance is normalized to zero, so the
 * client's centered render origin stays at the crosshair column no matter what it is appended
 * to, and appending it to another advance-neutral line (the extraction HUD) or to an empty bar
 * never shifts either party. Badge + level digits are centered as a group on x=0, mirroring the
 * centered vanilla number; meteors are centered on the badge.
 */
public final class PlayerLevelHud {

    // --- glyph tables (mirrors hud.json; PUA chars kept as ints to survive tooling) ---------
    private static final char BADGE_FIRST = (char) 0xE910;
    private static final char BADGE_GLOW_FIRST = (char) 0xE918;
    private static final char DIGIT_FIRST = (char) 0xE920;
    private static final char DIGIT_PLUS = (char) 0xE92A;
    private static final char METEOR_FIRST = (char) 0xE930;
    public static final int METEOR_FRAMES = 6;

    // --- advances in GUI pixels (bitmap advance = rendered width + 1) -----------------------
    /** 16px art rendered at height 12 -> 12px wide. */
    private static final int BADGE_WIDTH = 12;
    private static final int BADGE_ADVANCE = BADGE_WIDTH + 1;
    /** 6x10 digit art at native height. */
    private static final int DIGIT_WIDTH = 6;
    private static final int DIGIT_ADVANCE = DIGIT_WIDTH + 1;
    /** 40x32 meteor canvas at native height. */
    private static final int METEOR_WIDTH = 40;
    private static final int METEOR_ADVANCE = METEOR_WIDTH + 1;
    private static final int BADGE_DIGIT_GAP = 2;
    private static final int GAIN_GAP = 5;

    // --- space provider (hud.json): exact negative/positive pixel advances ------------------
    private static final int[] NEGATIVE_STEPS = {128, 64, 32, 16, 8, 4, 3, 2, 1};
    private static final char[] NEGATIVE_CHARS = {
            (char) 0xE858, (char) 0xE857, (char) 0xE856, (char) 0xE855, (char) 0xE854,
            (char) 0xE853, (char) 0xE852, (char) 0xE851, (char) 0xE850
    };
    private static final int[] POSITIVE_STEPS = {64, 32, 16, 8, 4, 2, 1};
    private static final char[] POSITIVE_CHARS = {
            (char) 0xE85F, (char) 0xE85E, (char) 0xE85D, (char) 0xE85C,
            (char) 0xE85B, (char) 0xE85A, (char) 0xE859
    };

    /** Static (no animation) cluster per level; levels are immutable so benign races are fine. */
    private static final Component[] STATIC_CACHE = new Component[PlayerLevelService.MAX_LEVEL + 1];

    private PlayerLevelHud() {
    }

    /** Persistent badge + level readout, centered where the vanilla level number was. */
    public static Component overlay(int level) {
        int clamped = Math.max(1, Math.min(level, PlayerLevelService.MAX_LEVEL));
        Component cached = STATIC_CACHE[clamped];
        if (cached == null) {
            cached = compose(clamped, false, -1, 0L);
            STATIC_CACHE[clamped] = cached;
        }
        return cached;
    }

    /**
     * One animation frame of the XP-gain sequence: meteors raining into the (glowing) badge with
     * the gained amount beside it. {@code meteorFrame < 0} renders no meteors (resolve frame).
     */
    public static Component gainFrame(int level, boolean glow, int meteorFrame, long gain) {
        return compose(level, glow, meteorFrame, gain);
    }

    private static Component compose(int level, boolean glow, int meteorFrame, long gain) {
        TextColor tier = PlayerLevelBadges.tierColor(level);
        String levelDigits = String.valueOf(Math.max(0, level));
        Pen pen = new Pen();

        // Badge + level digits centered as a group on the crosshair column.
        int digitsVisual = levelDigits.length() * DIGIT_ADVANCE - 1;
        int groupWidth = BADGE_WIDTH + BADGE_DIGIT_GAP + digitsVisual;
        int start = -(groupWidth / 2);
        pen.moveTo(start);
        // Badge art carries its own palette: render untinted (white multiplier).
        pen.glyph((char) (badgeBase(glow) + PlayerLevelBadges.tierForLevel(level)),
                NamedTextColor.WHITE, BADGE_ADVANCE);
        pen.moveTo(start + BADGE_WIDTH + BADGE_DIGIT_GAP);
        pen.raw(digitGlyphs(levelDigits), tier, levelDigits.length() * DIGIT_ADVANCE);

        // Gained amount to the right of the group, in the tier color.
        if (gain > 0L) {
            String gainDigits = String.valueOf(gain);
            pen.moveTo(start + groupWidth + GAIN_GAP);
            pen.glyph(DIGIT_PLUS, tier, DIGIT_ADVANCE);
            pen.raw(digitGlyphs(gainDigits), tier, gainDigits.length() * DIGIT_ADVANCE);
        }

        // Meteor shower centered above the badge (grayscale art tinted with the tier color).
        if (meteorFrame >= 0) {
            pen.moveTo(-(METEOR_WIDTH / 2));
            pen.glyph((char) (METEOR_FIRST + (meteorFrame % METEOR_FRAMES)), tier, METEOR_ADVANCE);
        }

        return pen.finish();
    }

    private static char badgeBase(boolean glow) {
        return glow ? BADGE_GLOW_FIRST : BADGE_FIRST;
    }

    private static String digitGlyphs(String digits) {
        StringBuilder out = new StringBuilder(digits.length());
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c >= '0' && c <= '9') {
                out.append((char) (DIGIT_FIRST + (c - '0')));
            }
        }
        return out.toString();
    }

    /** Exact horizontal cursor move in pixels, composed greedily from the space glyphs. */
    private static String offset(int pixels) {
        if (pixels == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int remaining = Math.abs(pixels);
        int[] steps = pixels < 0 ? NEGATIVE_STEPS : POSITIVE_STEPS;
        char[] chars = pixels < 0 ? NEGATIVE_CHARS : POSITIVE_CHARS;
        for (int index = 0; index < steps.length && remaining > 0; index++) {
            while (remaining >= steps[index]) {
                out.append(chars[index]);
                remaining -= steps[index];
            }
        }
        return out.toString();
    }

    /** Tracks absolute cursor X (relative to screen center) while appending glyphs. */
    private static final class Pen {
        private final TextComponent.Builder line = Component.text();
        private int cursor;

        void moveTo(int x) {
            if (x != cursor) {
                line.append(Component.text(offset(x - cursor)).font(PlayerLevelBadges.FONT));
                cursor = x;
            }
        }

        void glyph(char glyphChar, TextColor color, int advance) {
            line.append(Component.text(String.valueOf(glyphChar)).font(PlayerLevelBadges.FONT).color(color));
            cursor += advance;
        }

        void raw(String glyphs, TextColor color, int advance) {
            if (!glyphs.isEmpty()) {
                line.append(Component.text(glyphs).font(PlayerLevelBadges.FONT).color(color));
                cursor += advance;
            }
        }

        /** Normalizes total advance to zero so the cluster never shifts what it is appended to. */
        Component finish() {
            moveTo(0);
            return line.build();
        }
    }
}
