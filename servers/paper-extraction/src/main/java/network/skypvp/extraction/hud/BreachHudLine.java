package network.skypvp.extraction.hud;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;

/**
 * Composes the graphical action-bar HUD with hotbar-anchored absolute positioning.
 *
 * <p>The line's total advance is normalized to ZERO, so the client's "centered" render
 * origin is exactly the crosshair column; every element is then placed at an absolute
 * pixel X relative to screen center (hotbar spans -91..+91):
 * <pre>
 *   LEFT  cluster (right edge past off-hand): shield bar stacked over health bar + HP digits,
 *                                      LOW tier (docked left of the off-hand slot)
 *   CENTER (straddling 0):             stamina bar directly above the hotbar
 *   RIGHT cluster (left edge at +95):  ammo icon + clip digits / reload spinner, LOW tier
 * </pre>
 * Everything is resource-pack glyphs (no default-font text), so all widths are exact.
 */
final class BreachHudLine {

    /** Hotbar half-width (182/2) plus a small gap to the right-side cluster. */
    private static final int HOTBAR_EDGE = 95;
    /** Off-hand slot (20px) + gap sits immediately left of the hotbar; vitals must clear it. */
    private static final int OFFHAND_SLOT_RESERVE = 24;
    private static final int LEFT_CLUSTER_EDGE = HOTBAR_EDGE + OFFHAND_SLOT_RESERVE;
    private static final int ICON_GAP = 2;
    /** Bar row footprint: icon(11) + gap(2) + frame(107). */
    private static final int BAR_ROW_WIDTH =
            BreachHudFont.ICON_ADVANCE + ICON_GAP + BreachHudFont.FRAME_ADVANCE;

    private static final TextColor FRAME_COLOR = TextColor.color(0x6B7A8F);
    private static final TextColor HEALTH_HIGH = TextColor.color(0x57F2A9);
    private static final TextColor HEALTH_MID = TextColor.color(0xFFB84D);
    private static final TextColor HEALTH_LOW = TextColor.color(0xFF4655);
    private static final TextColor SHIELD_COLOR = TextColor.color(0x40F0FF);
    private static final TextColor SHIELD_BROKEN = TextColor.color(0x8B1E2D);
    private static final TextColor STAMINA_COLOR = TextColor.color(0xFFD75A);
    private static final TextColor AMMO_COLOR = TextColor.color(0xE8F4FF);
    private static final TextColor AMMO_LOW = TextColor.color(0xFF4655);
    private static final TextColor RELOAD_COLOR = TextColor.color(0xFFB84D);

    /** Vitals snapshot; staminaRatio &lt; 0 hides the stamina bar. */
    record Vitals(
            double healthRatio,
            boolean hasShield,
            double shieldRatio,
            boolean destroyed,
            boolean depleted,
            boolean flashOn,
            double staminaRatio
    ) {
    }

    /** Held-weapon snapshot; null = unarmed (right cluster hidden). */
    record WeaponHud(String name, String clip, boolean reloading, int reloadFrame) {
    }

    /** Tracks absolute cursor X (relative to screen center) while appending glyphs. */
    private static final class Pen {
        private final TextComponent.Builder line = Component.text();
        private int cursor;

        void moveTo(int x) {
            if (x != cursor) {
                line.append(spaces(x - cursor));
                cursor = x;
            }
        }

        void glyph(char glyphChar, TextColor color, int advance) {
            line.append(Component.text(String.valueOf(glyphChar)).font(BreachHudFont.FONT).color(color));
            cursor += advance;
        }

        void raw(String glyphs, TextColor color, int advance) {
            if (!glyphs.isEmpty()) {
                line.append(Component.text(glyphs).font(BreachHudFont.FONT).color(color));
            }
            cursor += advance;
        }

        Component finish() {
            moveTo(0);
            return line.build();
        }
    }

    private BreachHudLine() {
    }

    static Component compose(Vitals vitals, WeaponHud weapon) {
        Pen pen = new Pen();

        if (vitals != null) {
            int hpDigitsWidth = BreachHudFont.ammoWidth(healthPercentText(vitals));
            int clusterWidth = BAR_ROW_WIDTH + ICON_GAP + hpDigitsWidth;
            int clusterStart = -LEFT_CLUSTER_EDGE - clusterWidth;

            // Shield row docked just above the hotbar top edge, stacked over the health row.
            if (vitals.hasShield()) {
                appendBarRow(pen, clusterStart, BreachHudFont.RowTier.LOW_TOP,
                        BreachHudFont.ICON_SHIELD_LOW, BreachHudFont.FRAME_LOW_TOP,
                        shieldFillPixels(vitals), shieldColor(vitals));
            }
            // Health row inside the hotbar band + HP digits.
            appendBarRow(pen, clusterStart, BreachHudFont.RowTier.LOW_BOTTOM,
                    BreachHudFont.ICON_HEALTH_LOW, BreachHudFont.FRAME_LOW,
                    fillPixels(vitals.healthRatio()), healthColor(vitals.healthRatio()));
            String hpText = healthPercentText(vitals);
            pen.moveTo(clusterStart + BAR_ROW_WIDTH + ICON_GAP);
            pen.raw(BreachHudFont.ammoGlyphsLow(hpText), healthColor(vitals.healthRatio()), hpDigitsWidth);

            // Stamina bar centered above the hotbar (kept at the classic action-bar height).
            if (vitals.staminaRatio() >= 0.0D) {
                int staminaStart = -(BAR_ROW_WIDTH / 2);
                TextColor color = vitals.staminaRatio() <= 0.2D ? HEALTH_LOW : STAMINA_COLOR;
                appendBarRow(pen, staminaStart, BreachHudFont.RowTier.BOTTOM,
                        BreachHudFont.ICON_STAMINA, BreachHudFont.FRAME_BOTTOM,
                        fillPixels(vitals.staminaRatio()), color);
            }
        }

        if (weapon != null) {
            int x = HOTBAR_EDGE;
            pen.moveTo(x);
            TextColor iconColor = weapon.reloading() ? RELOAD_COLOR : AMMO_COLOR;
            pen.glyph(BreachHudFont.ICON_AMMO_LOW, iconColor, BreachHudFont.ICON_ADVANCE);
            pen.moveTo(x + BreachHudFont.ICON_ADVANCE + ICON_GAP);
            if (weapon.reloading()) {
                pen.glyph(BreachHudFont.reloadFrameLow(weapon.reloadFrame()), RELOAD_COLOR,
                        BreachHudFont.RELOAD_ADVANCE);
            } else {
                pen.raw(BreachHudFont.ammoGlyphsLow(weapon.clip()),
                        lowClip(weapon.clip()) ? AMMO_LOW : AMMO_COLOR,
                        BreachHudFont.ammoWidth(weapon.clip()));
            }
        }

        return pen.finish();
    }

    /** One bar row at absolute {@code startX}: icon + frame + continuous fill inside. */
    private static void appendBarRow(
            Pen pen,
            int startX,
            BreachHudFont.RowTier tier,
            char icon,
            char frame,
            int fillPixels,
            TextColor fillColor
    ) {
        pen.moveTo(startX);
        pen.glyph(icon, fillColor, BreachHudFont.ICON_ADVANCE);
        pen.moveTo(startX + BreachHudFont.ICON_ADVANCE + ICON_GAP);
        pen.glyph(frame, FRAME_COLOR, BreachHudFont.FRAME_ADVANCE);
        // Fill starts 2px inside the frame border; solidFill nets exactly fillPixels.
        pen.moveTo(startX + BreachHudFont.ICON_ADVANCE + ICON_GAP + 2);
        pen.raw(BreachHudFont.solidFill(fillPixels, tier), fillColor, fillPixels);
        // Land after the frame regardless of fill width.
        pen.moveTo(startX + BAR_ROW_WIDTH);
    }

    private static String healthPercentText(Vitals vitals) {
        return String.valueOf((int) Math.round(clamp(vitals.healthRatio()) * 100.0D));
    }

    private static int fillPixels(double ratio) {
        double clamped = clamp(ratio);
        int pixels = (int) Math.round(BreachHudFont.FRAME_INTERIOR * clamped);
        if (pixels == 0 && clamped > 0.0D) {
            pixels = 1;
        }
        return Math.min(pixels, BreachHudFont.FRAME_INTERIOR);
    }

    private static int shieldFillPixels(Vitals vitals) {
        if (vitals.destroyed() || vitals.depleted()) {
            return vitals.flashOn() ? BreachHudFont.FRAME_INTERIOR : 0;
        }
        return fillPixels(vitals.shieldRatio());
    }

    private static TextColor shieldColor(Vitals vitals) {
        if (vitals.destroyed()) {
            return SHIELD_BROKEN;
        }
        if (vitals.depleted()) {
            return HEALTH_LOW;
        }
        return SHIELD_COLOR;
    }

    private static TextColor healthColor(double ratio) {
        double clamped = clamp(ratio);
        if (clamped > 0.5D) {
            return HEALTH_HIGH;
        }
        if (clamped > 0.25D) {
            return HEALTH_MID;
        }
        return HEALTH_LOW;
    }

    private static boolean lowClip(String clip) {
        if (clip == null) {
            return false;
        }
        int slash = clip.indexOf('/');
        String loaded = slash >= 0 ? clip.substring(0, slash) : clip;
        try {
            return Integer.parseInt(loaded.trim()) <= 5;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static double clamp(double ratio) {
        return Math.max(0.0D, Math.min(1.0D, ratio));
    }

    private static Component spaces(int pixels) {
        String moves = BreachHudFont.offset(pixels);
        if (moves.isEmpty()) {
            return Component.empty();
        }
        return Component.text(moves).font(BreachHudFont.FONT);
    }
}
