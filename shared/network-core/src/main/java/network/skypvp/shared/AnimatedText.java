package network.skypvp.shared;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Immutable animated text builder for reusable HUD effects.
 */
public final class AnimatedText {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final String DEFAULT_PRIMARY = ServerTextUtil.ThemeTone.BRAND_700.hex();
    private static final String DEFAULT_SECONDARY = ServerTextUtil.ThemeTone.BRAND_400.hex();
    private static final String DEFAULT_HIGHLIGHT = ServerTextUtil.ThemeTone.BRAND_50.hex();
    private static final String[] SPARKLES = { "✦", "✧", "★", "✧" };

    private AnimatedText() {
    }

    public enum Effect {
        GLOW,
        GLARE,
        PULSE,
        WAVE,
        SHIMMER,
        BREATHE,
        SPARKLE
    }

    public static Builder builder(String text) {
        return new Builder(
                text == null ? "" : text,
                DEFAULT_PRIMARY,
                DEFAULT_SECONDARY,
                DEFAULT_HIGHLIGHT,
                false,
                260,
                List.of()
        );
    }

    public static final class Builder {
        private final String text;
        private final String primaryHex;
        private final String secondaryHex;
        private final String highlightHex;
        private final boolean bold;
        private final int intervalMillis;
        private final List<Effect> effects;

        private Builder(
                String text,
                String primaryHex,
                String secondaryHex,
                String highlightHex,
                boolean bold,
                int intervalMillis,
                List<Effect> effects
        ) {
            this.text = text;
            this.primaryHex = sanitizeHex(primaryHex, DEFAULT_PRIMARY);
            this.secondaryHex = sanitizeHex(secondaryHex, DEFAULT_SECONDARY);
            this.highlightHex = sanitizeHex(highlightHex, DEFAULT_HIGHLIGHT);
            this.bold = bold;
            this.intervalMillis = Math.max(120, intervalMillis);
            this.effects = List.copyOf(effects);
        }

        public Builder bold() {
            return new Builder(text, primaryHex, secondaryHex, highlightHex, true, intervalMillis, effects);
        }

        public Builder intervalMillis(int intervalMillis) {
            return new Builder(text, primaryHex, secondaryHex, highlightHex, bold, intervalMillis, effects);
        }

        public Builder primary(String primaryHex) {
            return new Builder(text, primaryHex, secondaryHex, highlightHex, bold, intervalMillis, effects);
        }

        public Builder secondary(String secondaryHex) {
            return new Builder(text, primaryHex, secondaryHex, highlightHex, bold, intervalMillis, effects);
        }

        public Builder highlight(String highlightHex) {
            return new Builder(text, primaryHex, secondaryHex, highlightHex, bold, intervalMillis, effects);
        }

        public Builder palette(String primaryHex, String secondaryHex, String highlightHex) {
            return new Builder(text, primaryHex, secondaryHex, highlightHex, bold, intervalMillis, effects);
        }

        public Builder addEffect(Effect effect) {
            if (effect == null || effects.contains(effect)) {
                return this;
            }
            List<Effect> updated = new ArrayList<>(effects);
            updated.add(effect);
            return new Builder(text, primaryHex, secondaryHex, highlightHex, bold, intervalMillis, updated);
        }

        public String buildMiniMessage() {
            return buildMiniMessage(System.currentTimeMillis());
        }

        public String buildMiniMessage(long tickMillis) {
            if (text.isBlank()) {
                return "";
            }

            Palette palette = palette(tickMillis);
            String[] colors = gradientBase(text, palette);

            if (effects.contains(Effect.WAVE)) {
                applyWave(colors, palette, tickMillis);
            }
            if (effects.contains(Effect.GLOW)) {
                applySweep(colors, palette, tickMillis, 2, Math.max(180, intervalMillis));
            }
            if (effects.contains(Effect.GLARE)) {
                applySweep(colors, palette, tickMillis, 3, Math.max(220, intervalMillis + 40));
            }
            if (effects.contains(Effect.SHIMMER)) {
                applyShimmer(colors, palette, tickMillis);
            }

            String content = renderCharacters(text, colors, bold);
            if (!effects.contains(Effect.SPARKLE)) {
                return content;
            }

            String sparkleColor = palette.secondary();
            String leftSparkle = sparkle(sparkleColor, tickMillis, 0L);
            String rightSparkle = sparkle(sparkleColor, tickMillis, 140L);
            return leftSparkle + " " + content + " " + rightSparkle;
        }

        public Component buildComponent() {
            return buildComponent(System.currentTimeMillis());
        }

        public Component buildComponent(long tickMillis) {
            return MINI.deserialize(buildMiniMessage(tickMillis));
        }

        private Palette palette(long tickMillis) {
            Palette current = new Palette(primaryHex, secondaryHex, highlightHex);
            if (effects.contains(Effect.BREATHE)) {
                double ratio = cycleRatio(tickMillis, intervalMillis * 5L);
                current = new Palette(
                        blend(current.primary(), current.secondary(), ratio * 0.45D),
                        blend(current.secondary(), current.highlight(), ratio * 0.35D),
                        blend(current.highlight(), "#ffffff", ratio * 0.15D)
                );
            }
            if (effects.contains(Effect.PULSE)) {
                double ratio = cycleRatio(tickMillis, intervalMillis * 4L);
                current = new Palette(
                        blend(current.primary(), current.secondary(), ratio * 0.70D),
                        blend(current.secondary(), current.highlight(), ratio * 0.55D),
                        blend(current.highlight(), "#ffffff", ratio * 0.30D)
                );
            }
            return current;
        }

        private void applyWave(String[] colors, Palette palette, long tickMillis) {
            int phase = frameIndex(tickMillis, Math.max(3, colors.length), Math.max(120, intervalMillis / 2));
            for (int index = 0; index < colors.length; index++) {
                int lane = Math.floorMod(index + phase, 4);
                if (lane == 1) {
                    colors[index] = blend(colors[index], palette.secondary(), 0.45D);
                } else if (lane == 2) {
                    colors[index] = blend(colors[index], palette.highlight(), 0.30D);
                } else if (lane == 3) {
                    colors[index] = blend(colors[index], palette.secondary(), 0.20D);
                }
            }
        }

        private void applySweep(String[] colors, Palette palette, long tickMillis, int radius, int intervalMillis) {
            int center = frameIndex(tickMillis, colors.length + (radius * 2) + 1, intervalMillis) - radius;
            for (int index = 0; index < colors.length; index++) {
                int distance = Math.abs(index - center);
                if (distance == 0) {
                    colors[index] = palette.highlight();
                } else if (distance == 1) {
                    colors[index] = blend(colors[index], palette.highlight(), 0.70D);
                } else if (distance <= radius) {
                    double ratio = radius == 0 ? 0D : (double) (radius - distance + 1) / (double) (radius + 1);
                    colors[index] = blend(colors[index], palette.secondary(), 0.25D + (ratio * 0.35D));
                }
            }
        }

        private void applyShimmer(String[] colors, Palette palette, long tickMillis) {
            int position = frameIndex(tickMillis, colors.length, Math.max(120, intervalMillis / 2));
            colors[position] = palette.highlight();
            if (position > 0) {
                colors[position - 1] = blend(colors[position - 1], palette.secondary(), 0.60D);
            }
            if (position + 1 < colors.length) {
                colors[position + 1] = blend(colors[position + 1], palette.secondary(), 0.60D);
            }
        }

        private String renderCharacters(String text, String[] colors, boolean bold) {
            StringBuilder out = new StringBuilder();
            for (int index = 0; index < text.length(); index++) {
                char character = text.charAt(index);
                if (character == ' ') {
                    out.append(' ');
                    continue;
                }
                out.append('<').append(colors[index]).append('>');
                if (bold) {
                    out.append("<bold>");
                }
                out.append(escape(character));
                if (bold) {
                    out.append("</bold>");
                }
                out.append("<reset>");
            }
            return out.toString();
        }

        private String sparkle(String colorHex, long tickMillis, long offsetMillis) {
            String sparkle = SPARKLES[frameIndex(tickMillis + offsetMillis, SPARKLES.length, Math.max(150, intervalMillis))];
            return "<" + colorHex + ">" + sparkle + "<reset>";
        }

        private String[] gradientBase(String text, Palette palette) {
            String[] colors = new String[text.length()];
            int visibleCharacters = 0;
            for (int index = 0; index < text.length(); index++) {
                if (text.charAt(index) != ' ') {
                    visibleCharacters++;
                }
            }

            if (visibleCharacters <= 1) {
                for (int index = 0; index < colors.length; index++) {
                    colors[index] = palette.primary();
                }
                return colors;
            }

            int visibleIndex = 0;
            for (int index = 0; index < text.length(); index++) {
                if (text.charAt(index) == ' ') {
                    colors[index] = palette.primary();
                    continue;
                }

                double ratio = visibleIndex / (double) (visibleCharacters - 1);
                double smoothedRatio = smoothStep(ratio);
                colors[index] = blend(palette.primary(), palette.secondary(), smoothedRatio);
                visibleIndex++;
            }
            return colors;
        }
    }

    private record Palette(String primary, String secondary, String highlight) {
    }

    private static double cycleRatio(long tickMillis, long periodMillis) {
        long safePeriod = Math.max(1L, periodMillis);
        double angle = ((tickMillis % safePeriod) / (double) safePeriod) * (Math.PI * 2D);
        return (Math.sin(angle) + 1D) / 2D;
    }

    private static double smoothStep(double ratio) {
        double safeRatio = Math.max(0D, Math.min(1D, ratio));
        return safeRatio * safeRatio * (3D - (2D * safeRatio));
    }

    private static int frameIndex(long tickMillis, int frameCount, int intervalMillis) {
        if (frameCount <= 0) {
            return 0;
        }
        int safeInterval = Math.max(50, intervalMillis);
        return (int) Math.floorMod(tickMillis / safeInterval, frameCount);
    }

    private static String blend(String fromHex, String toHex, double ratio) {
        int[] from = parseHex(fromHex);
        int[] to = parseHex(toHex);
        double safeRatio = Math.max(0D, Math.min(1D, ratio));

        int red = (int) Math.round(from[0] + ((to[0] - from[0]) * safeRatio));
        int green = (int) Math.round(from[1] + ((to[1] - from[1]) * safeRatio));
        int blue = (int) Math.round(from[2] + ((to[2] - from[2]) * safeRatio));
        return String.format("#%02x%02x%02x", red, green, blue);
    }

    private static int[] parseHex(String hex) {
        String normalized = sanitizeHex(hex, DEFAULT_PRIMARY).substring(1);
        return new int[] {
                Integer.parseInt(normalized.substring(0, 2), 16),
                Integer.parseInt(normalized.substring(2, 4), 16),
                Integer.parseInt(normalized.substring(4, 6), 16)
        };
    }

    private static String sanitizeHex(String hex, String fallback) {
        if (hex == null || !hex.matches("#?[0-9a-fA-F]{6}")) {
            return fallback;
        }
        return hex.startsWith("#") ? hex.toLowerCase() : ("#" + hex.toLowerCase());
    }

    private static String escape(char character) {
        return switch (character) {
            case '<', '>', '\\' -> "\\" + character;
            default -> Character.toString(character);
        };
    }
}
