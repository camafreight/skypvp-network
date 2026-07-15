package network.skypvp.paper.questdialogue;

import net.kyori.adventure.key.Key;

/**
 * Glyph table for the RPG-style dialogue HUD ({@code skypvp:dialogue}).
 *
 * <p>Layout (screen center = 0), BetonQuest-style:
 * <pre>
 *   LEFT:  nameplate above speech panel + portrait in speech bottom-left
 *   RIGHT: choice panel (only while awaiting a choice)
 * </pre>
 * Constants mirror {@code resource-packs/skypvp-core/assets/skypvp/font/dialogue.json}.
 * Bitmap advance is {@code textureWidth + 1}.
 */
public final class QuestDialogueFont {

    public static final Key FONT = Key.key("skypvp", "dialogue");
    public static final Key TEXT_NAME = Key.key("skypvp", "dialogue_text_header");
    public static final Key TEXT_CONTROLS = Key.key("skypvp", "dialogue_text_controls");
    public static final Key[] TEXT_SPEECH = {
            Key.key("skypvp", "dialogue_text_0"),
            Key.key("skypvp", "dialogue_text_1"),
            Key.key("skypvp", "dialogue_text_2"),
            Key.key("skypvp", "dialogue_text_3")
    };
    public static final Key[] TEXT_CHOICE = TEXT_SPEECH;

    public static final char SPEECH_PANEL = (char) 0xE900;
    public static final char NAMEPLATE = (char) 0xE901;
    public static final char CHOICE_PANEL = (char) 0xE902;
    public static final char PORTRAIT_FRAME = (char) 0xE910;
    public static final char SILHOUETTE = (char) 0xE911;
    public static final char[] CHOICE_CURSORS = {
            (char) 0xE912, (char) 0xE913, (char) 0xE914, (char) 0xE915
    };
    public static final char ICON_CONTINUE = (char) 0xE920;
    public static final char ICON_CHOOSE = (char) 0xE921;
    public static final char ICON_LEAVE = (char) 0xE922;

    public static final int SPEECH_ADVANCE = 201;
    public static final int NAMEPLATE_ADVANCE = 97;
    public static final int CHOICE_ADVANCE = 141;
    public static final int PORTRAIT_ADVANCE = 41;
    public static final int SILHOUETTE_ADVANCE = 33;
    public static final int CURSOR_ADVANCE = 11;
    public static final int ICON_ADVANCE = 11;

    /** Left edge of the speech panel (negative = left of crosshair). */
    public static final int SPEECH_X = -170;
    /** Rendered width of speech_panel.png art. */
    public static final int SPEECH_WIDTH = 200;
    /** Portrait aligns with the well drawn at art x=6 inside speech_panel.png. */
    public static final int PORTRAIT_X = SPEECH_X + 6;
    public static final int SILHOUETTE_X = PORTRAIT_X + 4;
    /** Body text starts clear of the portrait well. */
    public static final int SPEECH_TEXT_X = SPEECH_X + 52;
    public static final int NAMEPLATE_X = SPEECH_X + 8;
    public static final int NAME_TEXT_X = NAMEPLATE_X + 6;
    /** Right-side choice panel. */
    public static final int CHOICE_X = 55;
    public static final int CHOICE_CURSOR_X = CHOICE_X + 5;
    public static final int CHOICE_TEXT_X = CHOICE_X + 17;

    /** Interior pixel budget for wrapped NPC speech. */
    public static final int SPEECH_TEXT_WIDTH = 138;
    public static final int CHOICE_TEXT_WIDTH = 116;
    /** Interior pixel budget for the nameplate label. */
    public static final int NAME_TEXT_WIDTH = 84;

    /**
     * Exact per-glyph advances for {@code dialogue_ascii.png} (codes 32..126), measured from
     * the atlas pixels (advance = glyph width + 1 at scale 1). Space is 4 via the font's
     * space provider. Any codepoint outside this range falls back to 6.
     */
    private static final int[] ASCII_ADVANCES = {
            4, 2, 4, 6, 6, 6, 6, 2, 4, 4, 4, 6, 2, 6, 2, 6,
            6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 2, 2, 5, 6, 5, 6,
            7, 6, 6, 6, 6, 6, 6, 6, 6, 4, 6, 6, 6, 6, 6, 6,
            6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 4, 6, 4, 6, 6,
            3, 6, 6, 6, 6, 6, 5, 6, 6, 2, 6, 5, 3, 6, 6, 6,
            6, 6, 6, 6, 4, 6, 6, 6, 6, 6, 6, 4, 2, 4, 7
    };

    /** Pixel advance of one character in the dialogue text fonts. */
    public static int advance(char c) {
        if (c >= 32 && c <= 126) {
            return ASCII_ADVANCES[c - 32];
        }
        return 6;
    }

    /** Exact rendered pixel width of a string in the dialogue text fonts. */
    public static int width(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < text.length(); i++) {
            total += advance(text.charAt(i));
        }
        return total;
    }

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

    private QuestDialogueFont() {
    }

    public static String offset(int pixels) {
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
