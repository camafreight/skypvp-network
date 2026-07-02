package network.skypvp.shared;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Stateless animation utility used by both the Velocity proxy and Paper backends.
 * <p>
 * All methods are pure functions: given a {@code frameTick} (derived from
 * {@code System.currentTimeMillis() / 50L}), they return the string for that frame.
 * No Bukkit or Adventure imports — stays plain Java to remain usable in the shared module.
 */
public final class NetworkAnimationEngine {

    // ── Sparkle symbols cycling ─────────────────────────────────────────────
    private static final String[] SPARKLES = { "✦", "✧", "✦", "★", "✦", "✧", "✦", "⭐" };
    private static final String[] SPARKLES_SIMPLE = { "✦", "✧", "★", "✧" };
    private static final String[] DOTS = { "⬤", "◉", "◎", "○", "◎", "◉" };
    private static final String[] ARROWS = { "➤", "▶", "►", "▷", "►", "▶" };
    private static final String[] WAVES = { "〜", "～", "〜", "～" };

    // ── Brand gradient palette frames ───────────────────────────────────────
    // Each frame is [fromHex, toHex] used to construct MiniMessage gradient tags.
    private static final String[][] BRAND_GRADIENT_FRAMES = {
        { "#dbeafe", "#2563eb" },
        { "#bfdbfe", "#1d4ed8" },
        { "#93c5fd", "#3b82f6" },
        { "#eff6ff", "#2563eb" },
        { "#60a5fa", "#1e40af" },
        { "#93c5fd", "#2563eb" },
    };

    // ── Rank accent palette for cycling footer callouts ─────────────────────
    private static final String[] RANK_CYCLE_COLORS = {
        BrandStyle.RANK_VIP,
        BrandStyle.RANK_VIP_PLUS,
        BrandStyle.RANK_MVP,
        BrandStyle.RANK_MVP_PLUS_FROM,
        BrandStyle.RANK_LEGEND_FROM,
    };

    // ── Divider styles ──────────────────────────────────────────────────────
    private static final String[] DIVIDERS = {
        "━━━━━━━━━━━━━━━━━━━",
        "─────────────────────",
        "▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬",
        "═══════════════════",
    };

    // ── Server-list MOTD rotating taglines (≤28 visible chars each) ──────────
    public static final String[] MOTD_TAGLINES = {
        "Compete · Survive · Explore",
        "SkyWars · Survival · Events",
        "Your adventure starts here",
        "Bedrock + Java welcome!",
        "New Season — join now!",
        "store.skypvp.net",
        "discord.gg/SkyPvP",
        "Ranks · Coins · Rewards",
        "Fresh servers · Low ping",
        "Join 2,000+ players daily",
    };

    // ── Maintenance MOTD short suffix (≤22 visible chars) ────────────────────
    public static final String[] MOTD_MAINTENANCE_SHORT = {
        "Back soon!",
        "Please wait...",
        "Updates in progress",
        "Thank you for waiting!",
    };

    // ── TAB footer rotating callouts ─────────────────────────────────────────
    public static final String[] FOOTER_CALLOUTS = {
        "Campfires cook food for free",
        "Copper darkens as it ages",
        "Shields stop most arrows",
        "Rain boosts fishing odds",
        "Sneak to hold the edge",
        "Soul Speed loves soul soil",
        "Wolves hunt skeletons",
        "Nether travel runs 1:8",
    };

    private NetworkAnimationEngine() {}

    // ────────────────────────────────────────────────────────────────────────
    //  Core frame indexing
    // ────────────────────────────────────────────────────────────────────────

    /** Convert millis tick to a frame index over an array of the given length. */
    public static int frameIndex(long tickMillis, int frameCount, int intervalMillis) {
        if (frameCount <= 0) return 0;
        int safe = Math.max(50, intervalMillis);
        return (int) Math.floorMod(tickMillis / safe, frameCount);
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Sparkle / symbol animations
    // ────────────────────────────────────────────────────────────────────────

    /** Returns a cycling sparkle glyph (full set — 8 frames). */
    public static String sparkle(long tick) {
        return SPARKLES[frameIndex(tick, SPARKLES.length, 400)];
    }

    /** Returns a simple 4-frame sparkle glyph (faster). */
    public static String sparkleSimple(long tick) {
        return SPARKLES_SIMPLE[frameIndex(tick, SPARKLES_SIMPLE.length, 500)];
    }

    /** Returns a cycling dot glyph. */
    public static String dot(long tick) {
        return DOTS[frameIndex(tick, DOTS.length, 300)];
    }

    /** Returns a cycling arrow glyph. */
    public static String arrow(long tick) {
        return ARROWS[frameIndex(tick, ARROWS.length, 350)];
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Gradient helpers (return MiniMessage-compatible strings)
    // ────────────────────────────────────────────────────────────────────────

    /** Returns a cycling brand gradient MiniMessage tag opening, e.g. {@code <gradient:#FFB300:#FF6F00>}. */
    public static String brandGradientOpen(long tick) {
        String[] frame = BRAND_GRADIENT_FRAMES[frameIndex(tick, BRAND_GRADIENT_FRAMES.length, 600)];
        return "<gradient:" + frame[0] + ":" + frame[1] + ">";
    }

    /** Wraps {@code text} in an animated brand gradient with bold. */
    public static String brandGradientBold(String text, long tick) {
        return brandGradientOpen(tick) + "<bold>" + text + "</bold></gradient>";
    }

    /**
     * Returns a MiniMessage-formatted gradient-wrapped text that smoothly
     * scrolls through the brand palette — good for scoreboard title.
     */
    public static String scrollingTitle(String text, long tick) {
        String[] frame = BRAND_GRADIENT_FRAMES[frameIndex(tick, BRAND_GRADIENT_FRAMES.length, 500)];
        return "<gradient:" + frame[0] + ":" + frame[1] + "><bold>" + text + "</bold></gradient>";
    }

    public static String brandGlare(long tick) {
        return animatedBrand("SkyPvP")
                .addEffect(AnimatedText.Effect.GLARE)
                .addEffect(AnimatedText.Effect.SHIMMER)
                .buildMiniMessage(tick);
    }

    public static String networkGlare(long tick) {
        return animatedBrand("SkyPvP Network")
                .addEffect(AnimatedText.Effect.GLARE)
                .addEffect(AnimatedText.Effect.SHIMMER)
                .buildMiniMessage(tick);
    }

    public static String brandGlow(long tick) {
        return animatedBrand("SkyPvP")
                .addEffect(AnimatedText.Effect.GLOW)
                .buildMiniMessage(tick);
    }

    public static String brandWave(long tick) {
        return animatedBrand("SkyPvP")
                .addEffect(AnimatedText.Effect.WAVE)
                .addEffect(AnimatedText.Effect.BREATHE)
                .buildMiniMessage(tick);
    }

    public static String brandShimmer(long tick) {
        return animatedBrand("SkyPvP")
                .addEffect(AnimatedText.Effect.SHIMMER)
                .addEffect(AnimatedText.Effect.SPARKLE)
                .buildMiniMessage(tick);
    }

    public static String brandPulse(long tick) {
        return animatedBrand("SkyPvP")
                .addEffect(AnimatedText.Effect.PULSE)
                .buildMiniMessage(tick);
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Pulse colour (for accents that glow)
    // ────────────────────────────────────────────────────────────────────────

    /** Cycles through 3 brightness levels of the brand accent. */
    public static String pulseAccentHex(long tick) {
        String[] pulses = { "#60a5fa", "#93c5fd", "#dbeafe" };
        return pulses[frameIndex(tick, pulses.length, 400)];
    }

    /** Returns the current rank-highlight color cycling through paid ranks. */
    public static String rankCycleHex(long tick) {
        return RANK_CYCLE_COLORS[frameIndex(tick, RANK_CYCLE_COLORS.length, 2000)];
    }

    // ────────────────────────────────────────────────────────────────────────
    //  MOTD tagline
    // ────────────────────────────────────────────────────────────────────────

    /** Returns the current rotating MOTD tagline (changes every ~8 s). */
    public static String motdTagline(long tick) {
        return MOTD_TAGLINES[frameIndex(tick, MOTD_TAGLINES.length, 8000)];
    }

    /** Returns a short rotating maintenance suffix for the MOTD (changes every ~4 s). */
    public static String motdMaintenanceShort(long tick) {
        return MOTD_MAINTENANCE_SHORT[frameIndex(tick, MOTD_MAINTENANCE_SHORT.length, 4000)];
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Footer callout
    // ────────────────────────────────────────────────────────────────────────

    /** Returns the current rotating footer callout (changes every ~6 s). */
    public static String footerCallout(long tick) {
        return FOOTER_CALLOUTS[frameIndex(tick, FOOTER_CALLOUTS.length, 6000)];
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Divider
    // ────────────────────────────────────────────────────────────────────────

    /** Returns the same divider (non-animated variant, index 0). */
    public static String divider() {
        return DIVIDERS[0];
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Time
    // ────────────────────────────────────────────────────────────────────────

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    /** Returns the current wall-clock time as {@code HH:mm}. */
    public static String wallClock() {
        return LocalTime.now().format(HH_MM);
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Capacity indicator (for MOTD line 2)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Returns a short capacity tag based on player fill ratio.
     * E.g. {@code "◆ Nearly Full"} at 80%+ or {@code "◆ Join Now"} when empty.
     */
    public static String capacityLabel(int online, int max) {
        if (max <= 0) return "";
        double ratio = (double) online / max;
        if (ratio >= 0.9) return "⬛ Crowded";
        if (ratio >= 0.7) return "◈ Thriving";
        if (ratio >= 0.4) return "◎ Adventuring";
        if (ratio >= 0.1) return "◌ Settled";
        return "✦ First Footsteps";
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Custom frame list animator (replaces config-only approach for built-ins)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Pick a frame from a list using the standard clock. Good for custom
     * animation sequences not hardcoded in this class.
     */
    public static String fromFrames(List<String> frames, long tick, int intervalMillis) {
        if (frames == null || frames.isEmpty()) return "";
        return frames.get(frameIndex(tick, frames.size(), intervalMillis));
    }

    private static AnimatedText.Builder animatedBrand(String text) {
        return AnimatedText.builder(text)
                .bold()
                .palette("#1d4ed8", "#60a5fa", "#eff6ff")
                .intervalMillis(220);
    }
}
