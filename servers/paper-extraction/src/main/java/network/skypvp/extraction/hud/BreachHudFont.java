package network.skypvp.extraction.hud;

/**
 * Glyph table and pixel-advance math for the {@code skypvp:hud} bitmap font.
 *
 * <p>Every constant here mirrors {@code resource-packs/skypvp-core/assets/skypvp/font/hud.json}
 * exactly. Bitmap glyph advance is {@code textureWidth + 1} at native height, so:
 * frame 106px → 107, fill 2px → 3, icons 10px → 11. The frame interior is 102px,
 * which is exactly {@link #BAR_SEGMENTS} fill segments at 3px each. "TOP" variants are
 * the same textures re-declared with ascents 11px higher, forming the second (shield)
 * row stacked above the health row inside a single action-bar line.
 */
final class BreachHudFont {

    static final net.kyori.adventure.key.Key FONT = net.kyori.adventure.key.Key.key("skypvp", "hud");

    // --- bitmap glyphs (codepoints match hud.json) -----------------------------------
    static final char FRAME_BOTTOM = (char) 0xE840;
    static final char FRAME_TOP = (char) 0xE841;
    static final char FILL_BOTTOM = (char) 0xE842;
    static final char FILL_TOP = (char) 0xE843;
    static final char ICON_HEALTH = (char) 0xE844;
    static final char ICON_SHIELD = (char) 0xE845;
    static final char ICON_AMMO = (char) 0xE846;
    static final char ICON_TIMER = (char) 0xE847;
    static final char ICON_EXTRACT = (char) 0xE848;
    static final char ICON_STAMINA = (char) 0xE849;
    static final char ICON_GOLD = (char) 0xE8BA;
    static final char ICON_COIN = (char) 0xE8BB;

    // --- LOW tier: same art re-declared 58px lower (negative ascents) so the vitals and
    // ammo clusters render BESIDE the hotbar instead of floating above it. -----------------
    static final char FRAME_LOW = (char) 0xE8C0;
    static final char FRAME_LOW_TOP = (char) 0xE8C1;
    static final char ICON_HEALTH_LOW = (char) 0xE8C2;
    static final char ICON_SHIELD_LOW = (char) 0xE8C3;
    static final char ICON_AMMO_LOW = (char) 0xE8C4;

    // --- advances in GUI pixels -------------------------------------------------------
    static final int FRAME_ADVANCE = 107;
    static final int FILL_ADVANCE = 3;
    static final int ICON_ADVANCE = 11;
    /** Fill area inside the frame: 2px border each side of the 106px texture. */
    static final int FRAME_INTERIOR = 102;
    static final int BAR_SEGMENTS = FRAME_INTERIOR / FILL_ADVANCE;

    // --- solid fill strips (continuous bars) ---------------------------------------------
    private static final int[] FILL_WIDTHS = {64, 32, 16, 8, 4, 2, 1};
    private static final char[] FILL_BOTTOM_STRIPS = {
            (char) 0xE876, (char) 0xE875, (char) 0xE874, (char) 0xE873,
            (char) 0xE872, (char) 0xE871, (char) 0xE870
    };
    private static final char[] FILL_TOP_STRIPS = {
            (char) 0xE87E, (char) 0xE87D, (char) 0xE87C, (char) 0xE87B,
            (char) 0xE87A, (char) 0xE879, (char) 0xE878
    };
    /** Low-tier strips: 0xE8C5.. is w1..w64 ascending, so index by reversed FILL_WIDTHS order. */
    private static final char[] FILL_LOW_BOTTOM_STRIPS = {
            (char) 0xE8CB, (char) 0xE8CA, (char) 0xE8C9, (char) 0xE8C8,
            (char) 0xE8C7, (char) 0xE8C6, (char) 0xE8C5
    };
    private static final char[] FILL_LOW_TOP_STRIPS = {
            (char) 0xE8D3, (char) 0xE8D2, (char) 0xE8D1, (char) 0xE8D0,
            (char) 0xE8CF, (char) 0xE8CE, (char) 0xE8CD
    };
    /** -1px space appended after each strip to cancel the +1 glyph advance (seamless join). */
    private static final char JOIN = (char) 0xE850;

    /** Vertical tier of a bar row: floating above the hotbar or docked beside it. */
    enum RowTier {
        TOP(FILL_TOP_STRIPS),
        BOTTOM(FILL_BOTTOM_STRIPS),
        LOW_TOP(FILL_LOW_TOP_STRIPS),
        LOW_BOTTOM(FILL_LOW_BOTTOM_STRIPS);

        private final char[] strips;

        RowTier(char[] strips) {
            this.strips = strips;
        }
    }

    /**
     * Composes a continuous fill exactly {@code pixels} wide from power-of-two strips.
     * Net advance equals {@code pixels}.
     */
    static String solidFill(int pixels, RowTier tier) {
        if (pixels <= 0) {
            return "";
        }
        int[] widths = FILL_WIDTHS;
        char[] strips = tier.strips;
        StringBuilder out = new StringBuilder();
        int remaining = pixels;
        for (int index = 0; index < widths.length && remaining > 0; index++) {
            while (remaining >= widths[index]) {
                out.append(strips[index]).append(JOIN);
                remaining -= widths[index];
            }
        }
        return out.toString();
    }

    // --- ammo digits + reload spinner ---------------------------------------------------
    static final char DIGIT_SLASH = (char) 0xE86A;
    static final int DIGIT_ADVANCE = 7;
    static final int SLASH_ADVANCE = 5;
    static final int RELOAD_ADVANCE = 11;

    /** Maps a clip string like {@code "24/90"} onto the HUD digit glyphs. */
    static String ammoGlyphs(String clip) {
        if (clip == null || clip.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(clip.length());
        for (int i = 0; i < clip.length(); i++) {
            char c = clip.charAt(i);
            if (c >= '0' && c <= '9') {
                out.append((char) (0xE860 + (c - '0')));
            } else if (c == '/') {
                out.append(DIGIT_SLASH);
            }
        }
        return out.toString();
    }

    /** Pixel width of {@link #ammoGlyphs(String)} output for the same clip string. */
    static int ammoWidth(String clip) {
        if (clip == null || clip.isEmpty()) {
            return 0;
        }
        int width = 0;
        for (int i = 0; i < clip.length(); i++) {
            char c = clip.charAt(i);
            if (c >= '0' && c <= '9') {
                width += DIGIT_ADVANCE;
            } else if (c == '/') {
                width += SLASH_ADVANCE;
            }
        }
        return width;
    }

    /** Rotating reload spinner frame (4 frames). */
    static char reloadFrame(int frameIndex) {
        return (char) (0xE86B + Math.floorMod(frameIndex, 4));
    }

    // --- LOW-tier digits/spinner (0xE8D8..0xE8E2, 0xE8E4..0xE8E7) -----------------------

    /** {@link #ammoGlyphs(String)} at the low tier (docked beside the hotbar). */
    static String ammoGlyphsLow(String clip) {
        if (clip == null || clip.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(clip.length());
        for (int i = 0; i < clip.length(); i++) {
            char c = clip.charAt(i);
            if (c >= '0' && c <= '9') {
                out.append((char) (0xE8D8 + (c - '0')));
            } else if (c == '/') {
                out.append((char) 0xE8E2);
            }
        }
        return out.toString();
    }

    /** Low-tier rotating reload spinner frame (4 frames). */
    static char reloadFrameLow(int frameIndex) {
        return (char) (0xE8E4 + Math.floorMod(frameIndex, 4));
    }

    // --- BIG countdown digits (0xE8F0..0xE8F9, 20px tall; titles scale 4x on top) --------
    /** 12px art + 1px advance at double height. */
    static final int BIG_DIGIT_ADVANCE = 13;

    /** Maps a number string onto the big countdown digit glyphs. */
    static String bigDigits(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                out.append((char) (0xE8F0 + (c - '0')));
            }
        }
        return out.toString();
    }

    /** Pixel width of {@link #bigDigits(String)} output. */
    static int bigDigitsWidth(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                width += BIG_DIGIT_ADVANCE;
            }
        }
        return width;
    }

    // --- space provider: negative and positive pixel advances --------------------------
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

    private BreachHudFont() {
    }

    /** Exact horizontal cursor move in pixels, composed greedily from the space glyphs. */
    static String offset(int pixels) {
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
}
