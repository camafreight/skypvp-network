package network.skypvp.shared;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared text and UI formatting helpers for Paper and Velocity.
 *
 * <p>
 * The class is intentionally stateless. All builders are immutable so the
 * shared API remains safe to use from scheduler threads, proxy tasks, and
 * plugin startup code without hidden mutable state.
 * </p>
 */
public final class ServerTextUtil {

    public static final String TEXTURE_PACK_LOGO_CHAR = "\uE001";
    public static final int GUI_LORE_WIDTH = 30;
    /** Vanilla action bar line width at GUI scale 1 with the default font. */
    public static final int ACTION_BAR_LINE_WIDTH_PIXELS = 320;
    /** Vanilla sidebar line width at GUI scale 1 with the default font. */
    public static final int TAB_LINE_WIDTH_PIXELS = 360;
    public static final int SCOREBOARD_LINE_WIDTH_PIXELS = 142;
    /**
     * Vanilla multiplayer server-list MOTD drawable width at GUI scale 1 (pixels).
     * <p>
     * Empirically ~265px: Spigot developers measured 53 hyphen ({@code -}) characters fitting one MOTD line
     * ({@linkplain <a href="https://www.spigotmc.org/threads/max-characters-in-motd-and-chat-line.317332/">Spigot thread</a>},
     * each hyphen is 5px in the default font → 265px). This is <em>not</em> the 320px chat line width.
     * <p>
     * For plain ASCII, ~45 visible characters per line is the safe community limit (formatting codes excluded).
     * The wiki notes MOTD strings over 59 characters may cause ping communication errors — that is a protocol
     * limit, separate from this drawable pixel slot.
     *
     * @see #MOTD_LINE_WIDTH_CHARS
     */
    public static final int MOTD_LINE_WIDTH_PIXELS = 265;
    /** Safe visible-character budget per MOTD line for plain-text helpers (formatting codes excluded). */
    public static final int MOTD_LINE_WIDTH_CHARS = 45;
    public static final int MIN_FRAME_WIDTH = 45;
    public static final int MAX_FRAME_WIDTH = 64;
    public static final int DEFAULT_FRAME_WIDTH = 48;
    public static final int SPACE_PIXEL_WIDTH = 4;

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** Applies {@code {0}}-style placeholders to a catalog or template string. */
    public static String applyArgs(String template, Object... args) {
        if (template == null || args == null || args.length == 0) {
            return template == null ? "" : template;
        }
        String resolved = template;
        for (int index = 0; index < args.length; index++) {
            String replacement = args[index] == null ? "" : String.valueOf(args[index]);
            resolved = resolved.replace("{" + index + "}", replacement);
        }
        return resolved;
    }

    /** English catalog text for a key (locale is ignored; UI copy is English-only). */
    public static String localized(String key, String locale) {
        return ServerTextCatalog.source(key);
    }

    /** English catalog text with {@code {0}}-style placeholders applied after lookup. */
    public static String localized(String key, String locale, Object... args) {
        return applyArgs(ServerTextCatalog.source(key), args);
    }

    /** Returns the English template or catalog source (locale is ignored). */
    public static String localizeTemplate(String englishTemplate, String locale) {
        if (englishTemplate == null) {
            return "";
        }
        if (ServerTextCatalog.contains(englishTemplate)) {
            return ServerTextCatalog.source(englishTemplate);
        }
        return englishTemplate;
    }

    public static Component miniMessageComponent(String miniMessageText, String locale) {
        return miniMessageComponent(localizeTemplate(miniMessageText, locale), ThemeTone.BRAND_50);
    }

    public static Component miniMessageKey(String catalogKey, String locale) {
        return miniMessageComponent(localized(catalogKey, locale), ThemeTone.BRAND_50);
    }

    public static Component miniMessageKey(String catalogKey, String locale, Object... args) {
        return miniMessageComponent(localized(catalogKey, locale, args), ThemeTone.BRAND_50);
    }

    public static Component component(String legacyText, String locale) {
        return component(localizeTemplate(legacyText, locale), ThemeTone.BRAND_50);
    }

    public static Component componentKey(String catalogKey, String locale) {
        return component(localized(catalogKey, locale), ThemeTone.BRAND_50);
    }

    public static Component componentKey(String catalogKey, String locale, Object... args) {
        return component(localized(catalogKey, locale, args), ThemeTone.BRAND_50);
    }

    private static final LegacyComponentSerializer AMPERSAND_SERIALIZER = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();
    private static final LegacyComponentSerializer SECTION_SERIALIZER = LegacyComponentSerializer.builder()
            .character('§')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private static final Pattern LEGACY_CODE = Pattern.compile("(?i)&[0-9A-FK-ORX]");
    private static final Pattern MINI_TAG = Pattern.compile("^\\s*<[^>]+>");
    private static final Pattern STRIP_LEGACY = Pattern.compile("(?i)[&§][0-9A-FK-ORX]");
    private static final Pattern STRIP_MINI = Pattern.compile("<[^>]+>");

    private static final String RESET = "&r";
    private static final String STRUCTURE_STRIKE = "&b&m";
    private static final String STRUCTURE_BOLD = "&3&l";
    private static final String TITLE_BOLD = "&6&l";
    private static final String SUCCESS = "&a";
    private static final TextColor FIELD_VALUE_COLOR = TextColor.fromHexString(ThemeTone.BRAND_400.hex());
    private static final Map<Character, String> SMALL_CAPS_MAP = new HashMap<>();
    private static final Pattern SMALL_CAPS_TAG = Pattern.compile("(?is)(?<!\\\\)<smallcaps>(.*?)</smallcaps>");

    static {
        String normal = "abcdefghijklmnopqrstuvwxyz";
        // Matching small caps Unicode characters
        String[] smallCaps = {
                "ᴀ", "ʙ", "ᴄ", "ᴅ", "ᴇ", "ꜰ", "ɢ", "ʜ", "ɪ", "ᴊ", "ᴋ", "ʟ", "ᴍ", "ɴ", "ᴏ", "ᴘ", "ǫ", "ʀ",
                "ꜱ", "ᴛ", "ᴜ", "ᴠ", "ᴡ", "x", "ʏ", "ᴢ"
        };
        for (int i = 0; i < normal.length(); i++) {
            SMALL_CAPS_MAP.put(normal.charAt(i), smallCaps[i]);
        }
    }

    private ServerTextUtil() {
    }

    public static String toSmallCaps(String input) {
        if (input == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder(input.length());
        // Convert to lowercase first so all targeted letters get mapped
        for (char c : input.toLowerCase(Locale.ROOT).toCharArray()) {
            builder.append(SMALL_CAPS_MAP.getOrDefault(c, String.valueOf(c)));
        }
        return builder.toString();
    }

    /**
     * Strips custom {@code <anim:...>} / {@code </anim:...>} markup that is not real MiniMessage.
     * Closing tags used to leak into GUI lore as literal {@code </anim:glow>} text.
     */
    public static String stripAnimationMarkup(String input) {
        if (input == null || input.isEmpty()) {
            return input == null ? "" : input;
        }
        return input
                .replaceAll("(?i)</?anim(?::[a-z0-9_-]+)?>", "")
                .replaceAll("(?i)</anim>", "");
    }

    /**
     * Expands {@code <smallcaps>...</smallcaps>} regions to Unicode small-caps letters.
     * Nested MiniMessage tags inside the region are preserved; only plain text is converted.
     * Close with {@code </smallcaps>}. Escape a literal open tag with {@code \<smallcaps>}.
     */
    public static String applySmallCapsTags(String input) {
        if (input == null || input.isEmpty()) {
            return input == null ? "" : input;
        }
        String lower = input.toLowerCase(Locale.ROOT);
        if (!lower.contains("<smallcaps>") && !lower.contains("\\<smallcaps>")) {
            return input;
        }
        Matcher matcher = SMALL_CAPS_TAG.matcher(input);
        StringBuilder out = new StringBuilder(input.length());
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(toSmallCapsPreservingTags(matcher.group(1))));
        }
        matcher.appendTail(out);
        // Keep \<smallcaps> escapes so MiniMessage can render them as literal tags.
        return out.toString();
    }

    /** Converts letters to small-caps while leaving {@code <...>} tag bodies untouched. */
    public static String toSmallCapsPreservingTags(String input) {
        if (input == null || input.isEmpty()) {
            return input == null ? "" : input;
        }
        StringBuilder out = new StringBuilder(input.length());
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);
            if (c == '<') {
                int end = input.indexOf('>', i);
                if (end < 0) {
                    out.append(toSmallCaps(input.substring(i)));
                    break;
                }
                out.append(input, i, end + 1);
                i = end + 1;
            } else {
                int next = input.indexOf('<', i);
                if (next < 0) {
                    next = input.length();
                }
                out.append(toSmallCaps(input.substring(i, next)));
                i = next;
            }
        }
        return out.toString();
    }

    /**
     * Theme tokens that bridge the web palette into the closest Minecraft-safe
     * legacy colour for chat, lore, and command output.
     */
    public enum ThemeTone {
        BRAND_50("brand-50", "#eff6ff", "&f"),
        BRAND_100("brand-100", "#dbeafe", "&7"),
        BRAND_200("brand-200", "#bfdbfe", "&7"),
        BRAND_300("brand-300", "#93c5fd", "&b"),
        BRAND_400("brand-400", "#60a5fa", "&b"),
        BRAND_500("brand-500", "#3b82f6", "&3"),
        BRAND_600("brand-600", "#2563eb", "&3"),
        BRAND_700("brand-700", "#1d4ed8", "&9"),
        BRAND_800("brand-800", "#1e40af", "&9"),
        BRAND_900("brand-900", "#1e3a8a", "&1"),
        BRAND_950("brand-950", "#172554", "&1"),
        ALERT_GOLD("alert-gold", "#ffaa00", "&6"),
        ALERT_YELLOW("alert-yellow", "#ffff55", "&e"),
        SUCCESS_GREEN("success-green", "#55ff55", "&a");

        private final String token;
        private final String hex;
        private final String legacy;

        ThemeTone(String token, String hex, String legacy) {
            this.token = token;
            this.hex = hex;
            this.legacy = legacy;
        }

        public String token() {
            return token;
        }

        public String hex() {
            return hex;
        }

        public String legacy() {
            return legacy;
        }

        public String section() {
            return legacy.replace('&', '§');
        }

        public String miniTag() {
            return "<" + hex + ">";
        }
    }

    private enum FramePosition {
        HEADER,
        FOOTER
    }

    private enum LineFormat {
        PLAIN,
        LEGACY,
        MINIMESSAGE
    }

    private record NoticeLine(LineFormat format, String value) {
    }

    public record NoticeMetrics(int resolvedWidth, int longestVisibleSegment, int titleLength, int lineCount) {
    }

    public static ThemeTone tone(String token) {
        if (token == null || token.isBlank()) {
            return ThemeTone.BRAND_50;
        }
        String normalized = token.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (ThemeTone tone : ThemeTone.values()) {
            if (tone.token.equals(normalized)
                    || tone.name().toLowerCase(Locale.ROOT).replace('_', '-').equals(normalized)) {
                return tone;
            }
        }
        return ThemeTone.BRAND_50;
    }

    public static ThemeTone bodyTone() {
        return ThemeTone.BRAND_50;
    }

    public static ThemeTone mutedTone() {
        return ThemeTone.BRAND_100;
    }

    public static ThemeTone highlightTone() {
        return ThemeTone.BRAND_300;
    }

    public static ThemeTone structureTone() {
        return ThemeTone.BRAND_500;
    }

    public static ThemeTone headerTone() {
        return ThemeTone.BRAND_700;
    }

    public static ThemeTone deepHeaderTone() {
        return ThemeTone.BRAND_900;
    }

    public static FrameBuilder createHeader() {
        return new FrameBuilder(FramePosition.HEADER, DEFAULT_FRAME_WIDTH, "");
    }

    public static FrameBuilder createFooter() {
        return new FrameBuilder(FramePosition.FOOTER, DEFAULT_FRAME_WIDTH, "");
    }

    public static NoticeBuilder createNotice() {
        return new NoticeBuilder(DEFAULT_FRAME_WIDTH, "", ThemeTone.BRAND_50, List.of());
    }

    public static AnimatedText.Builder createAnimatedText(String text) {
        return AnimatedText.builder(text);
    }

    /**
     * Builds standardized lore lines using the strict shared theme palette.
     */
    public static List<String> createGuiLore(String featureName, String description, Map<String, String> keyValues) {
        List<String> lore = new ArrayList<>();

        if (featureName != null && !featureName.isBlank()) {
            lore.add(bulletLine("Feature", featureName, ThemeTone.ALERT_YELLOW));
        }

        for (String wrapped : wrapPlainText(description, GUI_LORE_WIDTH)) {
            lore.add(ThemeTone.BRAND_100.legacy() + wrapped + RESET);
        }

        if (keyValues != null && !keyValues.isEmpty()) {
            Map<String, String> ordered = new LinkedHashMap<>(keyValues);
            ordered.forEach((key, value) -> lore.add(bulletLine(key, value, valueTone(value))));
        }

        return List.copyOf(lore);
    }

    public static List<String> createGuiLoreMiniMessage(String featureName, String description,
            Map<String, String> keyValues) {
        return createGuiLore(featureName, description, keyValues).stream()
                .map(ServerTextUtil::legacyToMiniMessage)
                .toList();
    }

    public static List<Component> createGuiLoreComponents(String featureName, String description,
            Map<String, String> keyValues) {
        return createGuiLore(featureName, description, keyValues).stream()
                .map(ServerTextUtil::component)
                .toList();
    }

    /**
     * Converts ampersand-formatted text into section sign formatted text and
     * appends a reset to every line to prevent formatting bleed.
     */
    public static String colorize(String text) {
        return translateAlternateColorCodes(normalizeLegacyMultiline(text, ThemeTone.BRAND_50));
    }

    public static Component component(String legacyText) {
        return component(legacyText, ThemeTone.BRAND_50);
    }

    public static Component component(String legacyText, ThemeTone defaultTone) {
        String normalized = normalizeLegacyMultiline(legacyText, defaultTone);
        return AMPERSAND_SERIALIZER.deserialize(normalized);
    }

    public static Component component(String legacyText, TextColor color) {
        Component base = component(legacyText);
        return color == null ? base : base.colorIfAbsent(color);
    }

    public static Component component(String legacyText, TextColor color, TextDecoration[] decorations) {
        Component base = component(legacyText, color);
        if (decorations == null) {
            return base;
        }
        for (TextDecoration decoration : decorations) {
            if (decoration != null) {
                base = base.decorate(decoration);
            }
        }
        return base;
    }

    public static Component miniMessageComponent(String miniMessageText) {
        return miniMessageComponent(miniMessageText, ThemeTone.BRAND_50);
    }

    public static Component miniMessageComponent(String miniMessageText, ThemeTone defaultTone) {
        String withSmallCaps = applySmallCapsTags(safeString(miniMessageText));
        String normalized = normalizeMiniMessageMultiline(withSmallCaps, defaultTone);
        return MINI.deserialize(normalized);
    }

    /**
     * Measures the pixel width of a rendered {@link Component} using Minecraft's default font metrics.
     * Honors bold decoration on each text segment (including MiniMessage {@code <b>} output).
     */
    public static int componentVisibleWidth(Component component) {
        if (component == null) {
            return 0;
        }
        int[] width = new int[1];
        measureComponentWidth(component, Style.empty(), width);
        return width[0];
    }

    /**
     * Prepends neutral spaces so {@code component} is horizontally centered on a scoreboard line.
     */
    public static Component centerComponent(Component component, int lineWidthPixels) {
        Objects.requireNonNull(component, "component");
        int safeWidth = Math.max(SPACE_PIXEL_WIDTH, lineWidthPixels);
        int textWidth = componentVisibleWidth(component);
        int paddingPixels = Math.max(0, (safeWidth - textWidth) / 2);
        int spaceCount = paddingPixels / SPACE_PIXEL_WIDTH;
        if (spaceCount <= 0) {
            return component;
        }
        return Component.text(" ".repeat(spaceCount)).append(component);
    }

    /**
     * Renders a MiniMessage template and centers it on a scoreboard line.
     */
    public static Component centerMiniMessageLine(String miniMessageText, int lineWidthPixels) {
        return centerComponent(miniMessageComponent(miniMessageText), lineWidthPixels);
    }

    public static Component centerMiniMessageLine(String miniMessageText) {
        return centerMiniMessageLine(miniMessageText, SCOREBOARD_LINE_WIDTH_PIXELS);
    }

    /**
     * Centers a MiniMessage line for the multiplayer server-list MOTD using pixel-aware
     * measurement (bold, unicode, and gradients included). The client left-aligns MOTD rows,
     * so only <em>leading</em> space padding is applied — trailing spaces would not shift text.
     */
    public static Component centerMotdMiniMessageLine(String miniMessageText) {
        return centerMotdMiniMessageLine(miniMessageText, MOTD_LINE_WIDTH_PIXELS);
    }

    public static Component centerMotdMiniMessageLine(String miniMessageText, int lineWidthPixels) {
        return centerMotdLine(miniMessageComponent(miniMessageText), lineWidthPixels);
    }

    /**
     * Centers a rendered component for the server-list MOTD. When the content is wider
     * than the drawable slot it is returned unchanged — callers should compact copy first.
     */
    public static Component centerMotdLine(Component component) {
        return centerMotdLine(component, MOTD_LINE_WIDTH_PIXELS);
    }

    public static Component centerMotdLine(Component component, int lineWidthPixels) {
        Objects.requireNonNull(component, "component");
        int safeWidth = Math.max(SPACE_PIXEL_WIDTH, lineWidthPixels);
        int textWidth = componentVisibleWidth(component);
        if (textWidth <= 0) {
            return component;
        }
        if (textWidth > safeWidth) {
            return component;
        }
        return centerComponent(component, safeWidth);
    }

    /** Returns {@code true} when a MiniMessage MOTD fragment fits the drawable server-list slot. */
    public static boolean fitsMotdLine(String miniMessageText) {
        return motdMiniMessageWidth(miniMessageText) <= MOTD_LINE_WIDTH_PIXELS;
    }

    /** Returns the measured pixel width of a MiniMessage MOTD fragment. */
    public static int motdMiniMessageWidth(String miniMessageText) {
        if (miniMessageText == null || miniMessageText.isBlank()) {
            return 0;
        }
        return componentVisibleWidth(miniMessageComponent(miniMessageText));
    }

    /**
     * Centers plain MOTD text with ASCII space padding for legacy / config snippets.
     * For formatted MOTD output prefer {@link #centerMotdMiniMessageLine(String)}.
     */
    public static String centerPlainMotdText(String plainText) {
        return centerPlainMotdText(plainText, MOTD_LINE_WIDTH_CHARS);
    }

    public static String centerPlainMotdText(String plainText, int lineWidthChars) {
        String safe = plainText == null ? "" : plainText;
        int width = Math.max(1, lineWidthChars);
        int visible = safe.length();
        if (visible >= width) {
            return safe;
        }
        int pad = (width - visible) / 2;
        return " ".repeat(Math.max(0, pad)) + safe;
    }

    /**
     * Lays out left, center, and right segments across an action bar line using space padding.
     * Empty segments are skipped.
     */
    public static Component layoutThreeZone(Component left, Component center, Component right, int lineWidthPixels) {
        Component safeLeft = left == null ? Component.empty() : left;
        Component safeCenter = center == null ? Component.empty() : center;
        Component safeRight = right == null ? Component.empty() : right;

        int leftWidth = componentVisibleWidth(safeLeft);
        int centerWidth = componentVisibleWidth(safeCenter);
        int rightWidth = componentVisibleWidth(safeRight);
        if (leftWidth + centerWidth + rightWidth == 0) {
            return Component.empty();
        }

        int safeLineWidth = Math.max(SPACE_PIXEL_WIDTH, lineWidthPixels);
        if (leftWidth == 0 && rightWidth == 0) {
            return centerComponent(safeCenter, safeLineWidth);
        }
        if (leftWidth == 0 && centerWidth == 0) {
            return padLeadingSpaces(safeRight, Math.max(0, safeLineWidth - rightWidth));
        }
        if (centerWidth == 0 && rightWidth == 0) {
            return safeLeft;
        }

        int centerStart = Math.max(0, (safeLineWidth - centerWidth) / 2);
        int rightStart = Math.max(0, safeLineWidth - rightWidth);

        Component line = Component.empty();
        int cursor = 0;
        if (leftWidth > 0) {
            line = line.append(safeLeft);
            cursor += leftWidth;
        }
        if (centerWidth > 0) {
            line = line.append(spacesForPixels(Math.max(0, centerStart - cursor))).append(safeCenter);
            cursor = centerStart + centerWidth;
        }
        if (rightWidth > 0) {
            line = line.append(spacesForPixels(Math.max(0, rightStart - cursor))).append(safeRight);
        }
        return line;
    }

    /** Horizontal alignment of content inside a fixed-width column. */
    public enum HAlign {
        LEFT,
        CENTER,
        RIGHT
    }

    /**
     * Lays out three sections into fixed-width columns so that a section changing width — or emptying entirely, e.g.
     * the weapon/ammo readout when the player holsters — never shifts the other sections. The {@code left} column is
     * flush-left, the {@code center} column is centered inside its own fixed {@code centerZoneWidthPixels} region, and
     * the {@code right} column is right-aligned to the end of the line. The whole line is always padded to
     * {@code totalWidthPixels}, so the client (which horizontally centers the action bar) positions it identically
     * every frame regardless of which sections currently have content.
     *
     * <p>The right column occupies the remainder ({@code total - left - center}) and is allowed to grow leftward if
     * its content is wider than that remainder; only the left and center columns are hard-pinned.</p>
     */
    public static Component layoutFixedZones(
            Component left,
            Component center,
            Component right,
            int totalWidthPixels,
            int leftZoneWidthPixels,
            int centerZoneWidthPixels
    ) {
        Component safeLeft = left == null ? Component.empty() : left;
        Component safeCenter = center == null ? Component.empty() : center;
        Component safeRight = right == null ? Component.empty() : right;

        int leftWidth = componentVisibleWidth(safeLeft);
        int centerWidth = componentVisibleWidth(safeCenter);
        int rightWidth = componentVisibleWidth(safeRight);
        if (leftWidth + centerWidth + rightWidth == 0) {
            return Component.empty();
        }

        int total = Math.max(SPACE_PIXEL_WIDTH, totalWidthPixels);
        int leftZone = Math.max(0, leftZoneWidthPixels);
        int centerZone = Math.max(0, centerZoneWidthPixels);

        int[] cursor = {0};
        Component line = Component.empty();

        if (leftWidth > 0) {
            line = line.append(safeLeft);
            cursor[0] += leftWidth;
        }
        if (centerWidth > 0) {
            int centerStart = leftZone + Math.max(0, (centerZone - centerWidth) / 2);
            line = appendGap(line, cursor, centerStart);
            line = line.append(safeCenter);
            cursor[0] += centerWidth;
        }
        if (rightWidth > 0) {
            int rightStart = Math.max(cursor[0], total - rightWidth);
            line = appendGap(line, cursor, rightStart);
            line = line.append(safeRight);
            cursor[0] += rightWidth;
        }
        // Trailing pad keeps the rendered line a constant width so nothing shifts when a column is empty.
        line = appendGap(line, cursor, total);
        return line;
    }

    /** Appends spaces to advance {@code cursor} toward {@code targetPixels}; updates {@code cursor} by what was added. */
    private static Component appendGap(Component line, int[] cursor, int targetPixels) {
        int gap = targetPixels - cursor[0];
        int spaces = Math.max(0, gap / SPACE_PIXEL_WIDTH);
        if (spaces <= 0) {
            return line;
        }
        cursor[0] += spaces * SPACE_PIXEL_WIDTH;
        return line.append(Component.text(" ".repeat(spaces)));
    }

    /**
     * Pads {@code content} with spaces so it occupies a fixed pixel width, aligned {@code left}, {@code center}, or
     * {@code right}. Used to give HUD sub-fields (progress bars, percentages, flashing "SHIELD DOWN") a constant
     * footprint so neighbouring text never jitters as their values change.
     */
    public static Component padToWidth(Component content, int targetWidthPixels, HAlign align) {
        Component safe = content == null ? Component.empty() : content;
        int contentWidth = componentVisibleWidth(safe);
        int spaces = Math.max(0, (targetWidthPixels - contentWidth) / SPACE_PIXEL_WIDTH);
        if (spaces <= 0) {
            return safe;
        }
        return switch (align == null ? HAlign.LEFT : align) {
            case LEFT -> safe.append(Component.text(" ".repeat(spaces)));
            case RIGHT -> Component.text(" ".repeat(spaces)).append(safe);
            case CENTER -> {
                int leftSpaces = spaces / 2;
                int rightSpaces = spaces - leftSpaces;
                Component padded = Component.text(" ".repeat(leftSpaces)).append(safe);
                yield rightSpaces > 0 ? padded.append(Component.text(" ".repeat(rightSpaces))) : padded;
            }
        };
    }

    /**
     * Cuts {@code component} so its rendered width never exceeds {@code maxWidthPixels}, preserving
     * per-segment styles. Content past the budget is dropped without an ellipsis — fixed-width HUD
     * columns (tab board) prefer a clean cut over a suffix that eats column space.
     */
    public static Component truncateComponentToWidth(Component component, int maxWidthPixels) {
        if (component == null || maxWidthPixels <= 0) {
            return Component.empty();
        }
        if (componentVisibleWidth(component) <= maxWidthPixels) {
            return component;
        }
        int[] budget = {maxWidthPixels};
        Component truncated = truncateNode(component, Style.empty(), budget);
        return truncated == null ? Component.empty() : truncated;
    }

    private static Component truncateNode(Component node, Style inheritedStyle, int[] budgetPixels) {
        if (budgetPixels[0] <= 0) {
            return null;
        }
        Style merged = inheritedStyle.merge(node.style(), Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        Component base;
        if (node instanceof TextComponent textComponent) {
            boolean bold = merged.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE;
            String content = textComponent.content();
            StringBuilder kept = new StringBuilder(content.length());
            for (int index = 0; index < content.length(); ) {
                int codePoint = content.codePointAt(index);
                int advance = minecraftCodePointWidth(codePoint, bold);
                if (advance > budgetPixels[0]) {
                    budgetPixels[0] = 0;
                    break;
                }
                budgetPixels[0] -= advance;
                kept.appendCodePoint(codePoint);
                index += Character.charCount(codePoint);
            }
            base = Component.text(kept.toString()).style(node.style());
        } else {
            // Non-text nodes (translatable, keybind, ...) are opaque: keep whole or drop.
            int[] selfWidth = new int[1];
            measureComponentWidth(node.children(List.of()), inheritedStyle, selfWidth);
            if (selfWidth[0] > budgetPixels[0]) {
                budgetPixels[0] = 0;
                return null;
            }
            budgetPixels[0] -= selfWidth[0];
            base = node.children(List.of());
        }
        List<Component> keptChildren = new ArrayList<>();
        for (Component child : node.children()) {
            if (budgetPixels[0] <= 0) {
                break;
            }
            Component truncatedChild = truncateNode(child, merged, budgetPixels);
            if (truncatedChild != null) {
                keptChildren.add(truncatedChild);
            }
        }
        return keptChildren.isEmpty() ? base : base.children(keptChildren);
    }

    private static Component padLeadingSpaces(Component component, int paddingPixels) {
        return spacesForPixels(paddingPixels).append(component);
    }

    private static Component spacesForPixels(int paddingPixels) {
        int spaceCount = Math.max(0, paddingPixels / SPACE_PIXEL_WIDTH);
        if (spaceCount <= 0) {
            return Component.empty();
        }
        return Component.text(" ".repeat(spaceCount));
    }

    /**
     * Builds a semantic field:value line in MiniMessage format.
     * Format: label(white) + separator(dark gray) + value(brand blue).
     */
    public static String fieldValueMiniMessage(String field, String value) {
        String safeField = safeString(field);
        String safeValue = safeString(value);
        return "<white>" + safeField + "</white><dark_gray>: </dark_gray><" + ThemeTone.BRAND_400.hex() + ">"
                + safeValue + "</" + ThemeTone.BRAND_400.hex() + ">";
    }

    /**
     * Builds a semantic field:value line in legacy ampersand format.
     * Format: label(&f) + separator(&8) + value(&b).
     */
    public static String fieldValueLegacy(String field, String value) {
        return "&f" + safeString(field) + "&8: &b" + safeString(value);
    }

    /**
     * Builds a semantic field:value Component line.
     */
    public static Component fieldValue(String field, String value) {
        return labelWithSeparator(field).append(dataValue(value));
    }

    /**
     * Builds a semantic field label and separator Component.
     */
    public static Component labelWithSeparator(String field) {
        return Component.text(safeString(field), NamedTextColor.WHITE)
                .append(Component.text(": ", NamedTextColor.DARK_GRAY));
    }

    /**
     * Builds a brand-colored data value Component.
     */
    public static Component dataValue(String value) {
        return Component.text(safeString(value),
                FIELD_VALUE_COLOR == null ? TextColor.color(96, 165, 250) : FIELD_VALUE_COLOR);
    }

    /**
     * Formats a coordinate pair with semantic world + coordinate styling.
     */
    public static Component coordinatePair(String world, int x, int z) {
        return dataValue(world).append(Component.text(" (" + x + ", " + z + ")", NamedTextColor.WHITE));
    }

    /**
     * Formats a coordinate triple with semantic world + coordinate styling.
     */
    public static Component coordinateTriple(String world, int x, int y, int z) {
        return dataValue(world).append(Component.text(" (" + x + ", " + y + ", " + z + ")", NamedTextColor.WHITE));
    }

    /**
     * Builds a semantic status field line with customizable status color.
     */
    public static Component statusField(String field, String status, NamedTextColor statusColor) {
        return labelWithSeparator(field).append(Component.text(safeString(status), statusColor));
    }

    public static Component commandChip(String label, String command, String hoverMiniMessage) {
        String safeLabel = safeString(label).trim();
        if (safeLabel.isBlank()) {
            safeLabel = "Action";
        }

        Component chip = Component.text("[", themeColor(ThemeTone.BRAND_600))
                .append(Component.text(safeLabel, themeColor(ThemeTone.BRAND_300))
                        .decorate(TextDecoration.BOLD))
                .append(Component.text("]", themeColor(ThemeTone.BRAND_600)));

        String safeCommand = safeString(command).trim();
        if (!safeCommand.isBlank()) {
            chip = chip.clickEvent(ClickEvent.runCommand(safeCommand));
        }

        String safeHover = safeString(hoverMiniMessage).trim();
        if (!safeHover.isBlank()) {
            chip = chip.hoverEvent(HoverEvent.showText(miniMessageComponent(safeHover, ThemeTone.BRAND_100)));
        }
        return chip;
    }

    /**
     * Clickable party invite prompt sent to the invite target.
     */
    public static Component partyInviteMessage(String inviterName) {
        String safeName = sanitizeCommandUsername(inviterName);
        String displayName = escapeMiniMessageText(safeName);
        String acceptCommand = "/party accept " + safeName;
        String denyCommand = "/party deny " + safeName;
        return miniMessageComponent(
                "<green><bold>" + displayName + "</bold> "
                        + "<gray>has sent you a party invite, "
                        + "<green><bold><click:run_command:'" + acceptCommand + "'>ᴀᴄᴄᴇᴘᴛ</click></bold> "
                        + "<gray>or "
                        + "<red><bold><click:run_command:'" + denyCommand + "'>ᴅᴇɴʏ</click></bold>."
        );
    }

    public static Component partyInviteSentMessage(String targetName) {
        String displayName = escapeMiniMessageText(sanitizeCommandUsername(targetName));
        return miniMessageComponent("<green>Party request sent to <white>" + displayName + "<green>.");
    }

    /** Network-wide friend presence: a friend joined the proxy. */
    public static Component friendNetworkJoinMessage(String username) {
        String displayName = escapeMiniMessageText(sanitizeCommandUsername(username));
        return miniMessageComponent(
                "<gray>[<aqua>Friends</aqua>]</gray> <green>" + displayName + "</green> <gray>joined the network.</gray>"
        );
    }

    /** Network-wide friend presence: a friend left the proxy. */
    public static Component friendNetworkLeaveMessage(String username) {
        String displayName = escapeMiniMessageText(sanitizeCommandUsername(username));
        return miniMessageComponent(
                "<gray>[<aqua>Friends</aqua>]</gray> <red>" + displayName + "</red> <gray>left the network.</gray>"
        );
    }

    public static String legacyToMiniMessage(String legacyText) {
        if (legacyText == null || legacyText.isBlank()) {
            return "";
        }
        return MINI.serialize(component(legacyText));
    }

    public static String miniMessageToLegacy(String miniMessageText) {
        if (miniMessageText == null || miniMessageText.isBlank()) {
            return "";
        }
        Component component = miniMessageComponent(miniMessageText);
        String legacy = SECTION_SERIALIZER.serialize(component);
        return ensureSectionReset(legacy);
    }

    private static TextColor themeColor(ThemeTone tone) {
        TextColor resolved = TextColor.fromHexString(Objects.requireNonNullElse(tone, ThemeTone.BRAND_50).hex());
        return resolved == null ? TextColor.color(255, 255, 255) : resolved;
    }

    public static final class FrameBuilder {
        private final FramePosition position;
        private final int width;
        private final String title;

        private FrameBuilder(FramePosition position, int width, String title) {
            this.position = position;
            this.width = clampWidth(width);
            this.title = sanitizeTitle(title);
        }

        public FrameBuilder includeTitle(String title) {
            return new FrameBuilder(position, width, title);
        }

        public FrameBuilder width(int width) {
            return new FrameBuilder(position, width, title);
        }

        public String build() {
            return buildFrameLine(position, width, title);
        }

        public String buildMiniMessage() {
            return legacyToMiniMessage(build());
        }

        public Component buildComponent() {
            return component(build(), ThemeTone.BRAND_50);
        }
    }

    public static final class NoticeBuilder {
        private final int width;
        private final String title;
        private final ThemeTone defaultBodyTone;
        private final List<NoticeLine> lines;

        private NoticeBuilder(int width, String title, ThemeTone defaultBodyTone, List<NoticeLine> lines) {
            this.width = clampWidth(width);
            this.title = sanitizeTitle(title);
            this.defaultBodyTone = Objects.requireNonNullElse(defaultBodyTone, ThemeTone.BRAND_50);
            this.lines = List.copyOf(lines);
        }

        public NoticeBuilder includeTitle(String title) {
            return new NoticeBuilder(width, title, defaultBodyTone, lines);
        }

        public NoticeBuilder width(int width) {
            return new NoticeBuilder(width, title, defaultBodyTone, lines);
        }

        public NoticeBuilder defaultBodyTone(ThemeTone tone) {
            return new NoticeBuilder(width, title, Objects.requireNonNullElse(tone, ThemeTone.BRAND_50), lines);
        }

        public NoticeBuilder addLine(String line) {
            return addPlainLine(line);
        }

        public NoticeBuilder addPlainLine(String line) {
            return append(new NoticeLine(LineFormat.PLAIN, safeString(line)));
        }

        public NoticeBuilder addLegacyLine(String line) {
            return append(new NoticeLine(LineFormat.LEGACY, safeString(line)));
        }

        public NoticeBuilder addMiniMessageLine(String line) {
            return append(new NoticeLine(LineFormat.MINIMESSAGE, safeString(line)));
        }

        public List<String> buildLegacyLines() {
            int resolvedWidth = resolvedWidth();
            List<String> output = new ArrayList<>();
            output.add(ServerTextUtil.createHeader().width(resolvedWidth).includeTitle(title).build());
            for (NoticeLine line : lines) {
                switch (line.format()) {
                    case PLAIN -> wrapPlainText(line.value(), resolvedWidth)
                            .forEach(segment -> output.add(defaultBodyTone.legacy() + segment + RESET));
                    case LEGACY ->
                        splitLines(normalizeLegacyMultiline(line.value(), defaultBodyTone)).forEach(output::add);
                    case MINIMESSAGE ->
                        splitLines(miniMessageToLegacy(normalizeMiniMessageMultiline(line.value(), defaultBodyTone)))
                                .forEach(lineValue -> output.add(normalizeLegacyLine(lineValue, defaultBodyTone)));
                }
            }
            output.add(ServerTextUtil.createFooter().width(resolvedWidth).build());
            return List.copyOf(output);
        }

        public List<String> buildMiniMessageLines() {
            return buildLegacyLines().stream()
                    .map(ServerTextUtil::legacyToMiniMessage)
                    .toList();
        }

        public Component buildComponent() {
            List<Component> components = buildLegacyLines().stream()
                    .map(ServerTextUtil::component)
                    .toList();

            Component result = Component.text("");
            for (int i = 0; i < components.size(); i++) {
                if (i > 0) {
                    result = result.append(Component.newline());
                }
                result = result.append(components.get(i));
            }
            return result;
        }

        public NoticeMetrics metrics() {
            int longestVisibleSegment = lines.stream()
                    .flatMap(line -> visibleSegments(line).stream())
                    .mapToInt(String::length)
                    .max()
                    .orElse(0);
            return new NoticeMetrics(
                    resolvedWidth(),
                    longestVisibleSegment,
                    sanitizeTitle(title).length(),
                    lines.size());
        }

        private NoticeBuilder append(NoticeLine line) {
            List<NoticeLine> updated = new ArrayList<>(lines);
            updated.add(line);
            return new NoticeBuilder(width, title, defaultBodyTone, updated);
        }

        private int resolvedWidth() {
            int resolved = width;
            if (!title.isBlank()) {
                resolved = Math.max(resolved, sanitizeTitle(title).length() + 16);
            }
            for (NoticeLine line : lines) {
                for (String visible : visibleSegments(line)) {
                    resolved = Math.max(resolved, visible.length() + 8);
                }
            }
            return clampWidth(resolved);
        }

        private List<String> visibleSegments(NoticeLine line) {
            List<String> segments = new ArrayList<>();
            for (String raw : splitLines(line.value())) {
                String visible = sanitizeInline(raw, "");
                if (!visible.isBlank()) {
                    segments.add(visible);
                }
            }
            return segments;
        }
    }

    private static String buildFrameLine(FramePosition position, int width, String title) {
        int safeWidth = clampWidth(width);
        String fill = " ";
        if (title.isBlank()) {
            return STRUCTURE_STRIKE + fill.repeat(2) + STRUCTURE_BOLD + "■" + STRUCTURE_STRIKE
                    + fill.repeat(safeWidth - 6)
                    + STRUCTURE_BOLD + "■" + STRUCTURE_STRIKE + fill.repeat(2) + RESET;
        }

        int innerSpaces = Math.max(0, safeWidth - 6);
        int innerPixels = innerSpaces * SPACE_PIXEL_WIDTH;
        int minSidePadding = Math.max(4, Math.min(7, Math.max(4, innerSpaces / 5)));
        int blockPaddingPixels = minecraftTextWidth("[  ]", true);
        String visibleTitle = truncateToPixelWidth(
                title,
                Math.max(SPACE_PIXEL_WIDTH,
                        innerPixels - (minSidePadding * 2 * SPACE_PIXEL_WIDTH) - blockPaddingPixels));
        String titleBlock = "[ " + visibleTitle + " ]";

        int titlePixels = minecraftTextWidth(titleBlock, true);
        int fillerSpaces = Math.max(0, (innerPixels - titlePixels) / SPACE_PIXEL_WIDTH);
        int left = Math.max(minSidePadding, fillerSpaces / 2);
        int right = Math.max(minSidePadding, fillerSpaces - left);

        while (((left + right) * SPACE_PIXEL_WIDTH) + titlePixels > innerPixels) {
            if (right > minSidePadding) {
                right--;
            } else if (left > minSidePadding) {
                left--;
            } else {
                break;
            }
        }

        while ((((left + right) * SPACE_PIXEL_WIDTH) + titlePixels + SPACE_PIXEL_WIDTH) <= innerPixels) {
            if (left <= right) {
                left++;
            } else {
                right++;
            }
        }

        String line = STRUCTURE_STRIKE + fill.repeat(2) + STRUCTURE_BOLD + "■" + STRUCTURE_STRIKE
                + fill.repeat(left)
                + STRUCTURE_BOLD + "[ " + TITLE_BOLD + visibleTitle + STRUCTURE_BOLD + " ]"
                + STRUCTURE_STRIKE + fill.repeat(right)
                + STRUCTURE_BOLD + "■" + STRUCTURE_STRIKE + fill.repeat(2) + RESET;

        return line;
    }

    private static String bulletLine(String label, String value, ThemeTone valueTone) {
        String safeLabel = sanitizeInline(label, "Feature");
        String safeValue = sanitizeInline(value, "N/A");
        return ThemeTone.BRAND_500.legacy() + "■ "
                + ThemeTone.BRAND_300.legacy() + safeLabel + ": "
                + valueTone.legacy() + safeValue + RESET;
    }

    private static ThemeTone valueTone(String value) {
        String normalized = sanitizeInline(value, "").toLowerCase(Locale.ROOT);
        if (normalized.equals("enabled")
                || normalized.equals("active")
                || normalized.equals("available")
                || normalized.equals("online")
                || normalized.equals("ready")
                || normalized.equals("live")
                || normalized.equals("open")
                || normalized.equals("yes")
                || normalized.equals("true")
                || normalized.equals("success")) {
            return ThemeTone.SUCCESS_GREEN;
        }
        return ThemeTone.ALERT_YELLOW;
    }

    private static String normalizeLegacyMultiline(String text, ThemeTone defaultTone) {
        StringJoiner joiner = new StringJoiner("\n");
        for (String line : splitLines(safeString(text))) {
            joiner.add(normalizeLegacyLine(line, defaultTone));
        }
        return joiner.toString();
    }

    private static String normalizeLegacyLine(String text, ThemeTone defaultTone) {
        String safe = safeString(text).replace('§', '&');
        if (safe.isBlank()) {
            return defaultTone.legacy() + RESET;
        }
        if (!startsWithLegacyCode(safe)) {
            safe = defaultTone.legacy() + safe;
        }
        if (!safe.endsWith(RESET)) {
            safe = safe + RESET;
        }
        return safe;
    }

    private static String normalizeMiniMessageMultiline(String text, ThemeTone defaultTone) {
        StringJoiner joiner = new StringJoiner("\n");
        for (String line : splitLines(safeString(text))) {
            joiner.add(normalizeMiniMessageLine(line, defaultTone));
        }
        return joiner.toString();
    }

    private static String normalizeMiniMessageLine(String text, ThemeTone defaultTone) {
        String safe = safeString(text);
        if (safe.isBlank()) {
            return defaultTone.miniTag() + "<reset>";
        }
        if (!startsWithMiniTag(safe)) {
            safe = defaultTone.miniTag() + safe;
        }
        if (!safe.endsWith("<reset>")) {
            safe = safe + "<reset>";
        }
        return safe;
    }

    private static boolean startsWithLegacyCode(String text) {
        return LEGACY_CODE.matcher(text).find() && text.indexOf('&') <= 1;
    }

    private static boolean startsWithMiniTag(String text) {
        return MINI_TAG.matcher(text).find();
    }

    private static String translateAlternateColorCodes(String text) {
        char[] chars = safeString(text).toCharArray();
        for (int i = 0; i < chars.length - 1; i++) {
            if (chars[i] == '&' && isLegacyChar(chars[i + 1])) {
                chars[i] = '§';
                chars[i + 1] = Character.toLowerCase(chars[i + 1]);
            }
        }
        return new String(chars);
    }

    private static boolean isLegacyChar(char candidate) {
        return (candidate >= '0' && candidate <= '9')
                || (candidate >= 'a' && candidate <= 'f')
                || (candidate >= 'A' && candidate <= 'F')
                || "KLMNORXklmnorx".indexOf(candidate) >= 0;
    }

    private static List<String> wrapPlainText(String text, int maxWidth) {
        int width = Math.max(1, maxWidth);
        String normalized = stripFormatting(safeString(text)).replace('\n', ' ').trim();
        if (normalized.isBlank()) {
            return List.of("");
        }

        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : normalized.split("\\s+")) {
            if (word.length() > width) {
                if (!current.isEmpty()) {
                    lines.add(current.toString());
                    current.setLength(0);
                }
                int start = 0;
                while (start < word.length()) {
                    int end = Math.min(start + width, word.length());
                    lines.add(word.substring(start, end));
                    start = end;
                }
                continue;
            }

            if (current.isEmpty()) {
                current.append(word);
                continue;
            }

            if (current.length() + 1 + word.length() <= width) {
                current.append(' ').append(word);
            } else {
                lines.add(current.toString());
                current.setLength(0);
                current.append(word);
            }
        }

        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return List.copyOf(lines);
    }

    private static String stripFormatting(String text) {
        String noLegacy = STRIP_LEGACY.matcher(safeString(text)).replaceAll("");
        return STRIP_MINI.matcher(noLegacy).replaceAll("");
    }

    private static String sanitizeTitle(String title) {
        return sanitizeInline(title, "");
    }

    private static String sanitizeInline(String text, String fallback) {
        String normalized = stripFormatting(safeString(text))
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim()
                .replaceAll("\\s+", " ");
        return normalized.isBlank() ? fallback : normalized;
    }

    private static String sanitizeCommandUsername(String username) {
        String safe = sanitizeInline(username, "player");
        if (!safe.matches("[a-zA-Z0-9_]{1,16}")) {
            return "player";
        }
        return safe;
    }

    private static String escapeMiniMessageText(String input) {
        return safeString(input).replace("<", "\\<");
    }

    private static String safeString(String text) {
        return text == null ? "" : text;
    }

    private static List<String> splitLines(String text) {
        return List.of(safeString(text).replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
    }

    private static String ensureSectionReset(String sectionText) {
        String safe = sectionText == null ? "" : sectionText;
        return safe.endsWith("§r") ? safe : safe + "§r";
    }

    private static int clampWidth(int width) {
        return Math.max(MIN_FRAME_WIDTH, Math.min(MAX_FRAME_WIDTH, width));
    }

    private static String truncateToPixelWidth(String text, int maxPixels) {
        String safe = sanitizeTitle(text);
        if (safe.isBlank() || minecraftTextWidth(safe, true) <= maxPixels) {
            return safe;
        }

        String suffix = "...";
        int suffixPixels = minecraftTextWidth(suffix, true);
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < safe.length(); index++) {
            char character = safe.charAt(index);
            int nextWidth = minecraftTextWidth(out.toString() + character, true);
            if (nextWidth + suffixPixels > maxPixels) {
                break;
            }
            out.append(character);
        }

        if (out.isEmpty()) {
            return suffix;
        }
        return out.toString().stripTrailing() + suffix;
    }

    private static int minecraftTextWidth(String text, boolean bold) {
        int width = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            width += minecraftCodePointWidth(codePoint, bold);
            index += Character.charCount(codePoint);
        }
        return width;
    }

    private static void measureComponentWidth(Component component, Style inheritedStyle, int[] widthOut) {
        Style merged = inheritedStyle.merge(component.style(), Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        if (component instanceof TextComponent textComponent) {
            boolean bold = merged.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE;
            String content = textComponent.content();
            for (int index = 0; index < content.length(); ) {
                int codePoint = content.codePointAt(index);
                widthOut[0] += minecraftCodePointWidth(codePoint, bold);
                index += Character.charCount(codePoint);
            }
        }
        for (Component child : component.children()) {
            measureComponentWidth(child, merged, widthOut);
        }
    }

    private static int minecraftCodePointWidth(int codePoint, boolean bold) {
        int width = motdSymbolWidth(codePoint);
        if (width < 0) {
            if (codePoint <= 127) {
                width = minecraftCharWidth((char) codePoint);
            } else if (codePoint >= 0x1D00 && codePoint <= 0x1D7F) {
                width = 5;
            } else if (codePoint >= 0x2580 && codePoint <= 0x259F) {
                width = blockElementWidth(codePoint);
            } else if (codePoint >= 0x1F300) {
                width = 10;
            } else {
                width = 6;
            }
        }
        if (bold && codePoint != ' ') {
            width++;
        }
        return width;
    }

    /** Fixed widths for symbols commonly used in server-list MOTDs (unifont / default font). */
    private static int motdSymbolWidth(int codePoint) {
        return switch (codePoint) {
            case 0x2726, 0x2727, 0x2605, 0x273F -> 6; // ✦ ✧ ★ ✿
            case 0x2022, 0x00B7 -> 2; // • ·
            case 0x2013, 0x2014 -> 6; // – —
            case 0x00BB, 0x00AB -> 5; // » «
            case 0x26A0 -> 6; // ⚠
            default -> -1;
        };
    }

    /**
     * Pixel advance for the Unicode Block Elements (U+2580–U+259F). The full/half/shade blocks ship in Minecraft's
     * default bitmap font (measured widths); the eighth blocks (▉▊▋▍▎▏) are not, so the client renders them from the
     * unifont fallback — their widths here are the proportional 8px-cell estimate, which is what the vitals bars use.
     */
    private static int blockElementWidth(int codePoint) {
        return switch (codePoint) {
            case 0x2588, 0x2580, 0x2584, 0x2592, 0x2593 -> 8; // █ ▀ ▄ ▒ ▓
            case 0x2591 -> 7;                                 // ░ light shade
            case 0x2589 -> 7;                                 // ▉ 7/8
            case 0x258A -> 6;                                 // ▊ 3/4
            case 0x258B -> 5;                                 // ▋ 5/8  (progress-bar glyph)
            case 0x258C, 0x2590 -> 4;                         // ▌ ▐ half block
            case 0x258D -> 4;                                 // ▍ 3/8
            case 0x258E -> 2;                                 // ▎ 1/4
            case 0x258F -> 1;                                 // ▏ 1/8
            default -> 6;
        };
    }

    private static int minecraftCharWidth(char character) {
        return switch (character) {
            case ' ' -> 4;
            case 'i', '!', '\'', '.', ',', ':', ';', '|', '`' -> 2;
            case 'l', 'I' -> 3;
            case '[', ']', '(', ')', '{', '}', 't', 'f', 'k', '<', '>', '/', '\\', '"' -> 4;
            case 'M', 'W', '@', '#', '%', '&' -> 6;
            default -> 5;
        };
    }
}
