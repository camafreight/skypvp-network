package network.skypvp.paper.service;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

/**
 * Glyph table for the level badge sprites in the {@code skypvp:hud} bitmap font.
 *
 * <p>Mirrors {@code resource-packs/skypvp-core/assets/skypvp/font/hud.json}: badge tiers occupy
 * {@code U+E900..U+E905}, their glow (XP-gain pulse) variants {@code U+E908..U+E90D}. The badge gets
 * more epic at level milestones; the milestone table lives here so gating, HUD, and celebrations
 * all agree on when the badge evolves.
 */
public final class PlayerLevelBadges {

    public static final Key FONT = Key.key("skypvp", "hud");

    /** Inclusive level floor of each badge tier (index = tier). */
    private static final int[] TIER_FLOORS = {1, 10, 25, 50, 75, 100};
    private static final char FIRST_BADGE = '\uE900';
    private static final char FIRST_GLOW = '\uE908';
    private static final TextColor[] TIER_COLORS = {
            TextColor.color(0x9AA7B8), // 1  — steel
            TextColor.color(0x4ADE80), // 10 — emerald
            TextColor.color(0x38BDF8), // 25 — sky
            TextColor.color(0xA78BFA), // 50 — amethyst
            TextColor.color(0xFBBF24), // 75 — gold
            TextColor.color(0xFB7185)  // 100 — mythic
    };

    private PlayerLevelBadges() {
    }

    /** Badge tier for a level, 0-based (0..5). */
    public static int tierForLevel(int level) {
        int tier = 0;
        for (int index = 0; index < TIER_FLOORS.length; index++) {
            if (level >= TIER_FLOORS[index]) {
                tier = index;
            }
        }
        return tier;
    }

    /** True when reaching {@code level} crosses into a new badge tier ("badge evolved"). */
    public static boolean isTierMilestone(int level) {
        for (int floor : TIER_FLOORS) {
            if (level == floor && floor > 1) {
                return true;
            }
        }
        return false;
    }

    public static Component badge(int level) {
        return glyph((char) (FIRST_BADGE + tierForLevel(level)), level);
    }

    /** Brightened badge frame used by the XP-gain pulse animation. */
    public static Component badgeGlow(int level) {
        return glyph((char) (FIRST_GLOW + tierForLevel(level)), level);
    }

    public static TextColor tierColor(int level) {
        return TIER_COLORS[tierForLevel(level)];
    }

    private static Component glyph(char glyph, int level) {
        // Glyph pixels carry their own colors; WHITE avoids the default font tint multiplying them.
        return Component.text(String.valueOf(glyph))
                .font(FONT)
                .color(net.kyori.adventure.text.format.NamedTextColor.WHITE);
    }
}
