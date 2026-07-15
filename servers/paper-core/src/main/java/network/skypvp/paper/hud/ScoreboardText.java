package network.skypvp.paper.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import network.skypvp.shared.ServerTextUtil;
import network.skypvp.shared.chat.ClientLocaleUtil;

/**
 * Core scoreboard template renderer with optional {@code <anim:glow>} gradient animation.
 * Modes supply catalog/localization via {@link Builder#localizer(ScoreboardTemplateLocalizer)}.
 */
public final class ScoreboardText {

    private static final Pattern GRADIENT = Pattern.compile("<gradient:([^>]+)>");
    private static final String GLOW_TAG = "<anim:glow>";
    private static final ConcurrentHashMap<String, Component> STATIC_RAW_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Component> DYNAMIC_RAW_CACHE = new ConcurrentHashMap<>();
    private static final int DYNAMIC_CACHE_MAX = 4096;

    private ScoreboardText() {
    }

    public static Builder builder(long tickMillis) {
        return new Builder(tickMillis);
    }

    public static Component render(String template, long tickMillis) {
        return render(template, tickMillis, ClientLocaleUtil.defaultMinecraftLocale());
    }

    public static Component render(String template, long tickMillis, String locale) {
        return render(template, tickMillis, locale, ScoreboardTemplateLocalizer.IDENTITY);
    }

    public static Component render(
            String template,
            long tickMillis,
            String locale,
            ScoreboardTemplateLocalizer localizer
    ) {
        return parseTemplate(prepareTemplate(template, tickMillis), locale, localizer);
    }

    public static Component renderStatic(String template) {
        return renderStatic(template, ClientLocaleUtil.defaultMinecraftLocale());
    }

    public static Component renderStatic(String template, String locale) {
        return renderStatic(template, locale, ScoreboardTemplateLocalizer.IDENTITY);
    }

    public static Component renderStatic(String template, String locale, ScoreboardTemplateLocalizer localizer) {
        String prepared = prepareTemplate(template, 0L);
        if (prepared.isEmpty()) {
            return Component.empty();
        }
        String normalizedLocale = normalizeLocale(locale);
        String localized = localizer.localize(prepared, normalizedLocale);
        String cacheKey = normalizedLocale + "\u0000" + localized;
        return STATIC_RAW_CACHE.computeIfAbsent(
                cacheKey,
                ignored -> ServerTextUtil.miniMessageComponent(localized)
        );
    }

    public static Component renderCentered(String template, long tickMillis) {
        return renderCentered(template, tickMillis, ServerTextUtil.SCOREBOARD_LINE_WIDTH_PIXELS);
    }

    public static Component renderCentered(String template, long tickMillis, int lineWidthPixels) {
        Component rendered = render(template, tickMillis);
        return ServerTextUtil.centerComponent(rendered, lineWidthPixels);
    }

    static String prepareTemplate(String template, long tickMillis) {
        String text = template == null ? "" : template;
        boolean glow = text.contains(GLOW_TAG) || text.contains("</anim:glow>");
        text = ServerTextUtil.stripAnimationMarkup(text);
        if (glow) {
            text = injectGradientPhase(text, tickMillis);
        }
        return text;
    }

    private static Component parseTemplate(String prepared, String locale, ScoreboardTemplateLocalizer localizer) {
        if (prepared.isEmpty()) {
            return Component.empty();
        }
        String normalizedLocale = normalizeLocale(locale);
        String localized = localizer.localize(prepared, normalizedLocale);
        return ServerTextUtil.miniMessageComponent(localized);
    }

    private static String normalizeLocale(String locale) {
        return ClientLocaleUtil.normalizeMinecraftLocale(
                locale == null || locale.isBlank() ? ClientLocaleUtil.defaultMinecraftLocale() : locale
        );
    }

    private static String injectGradientPhase(String input, long tickMillis) {
        double phase = Math.sin(tickMillis / 500.0);
        String phaseStr = String.format(Locale.ROOT, "%.3f", phase);

        Matcher matcher = GRADIENT.matcher(input);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String inner = matcher.group(1);
            String[] parts = inner.split(":");
            String replacement;
            if (parts.length >= 2 && isNumeric(parts[parts.length - 1])) {
                StringBuilder rebuilt = new StringBuilder("<gradient:");
                for (int i = 0; i < parts.length - 1; i++) {
                    if (i > 0) {
                        rebuilt.append(':');
                    }
                    rebuilt.append(parts[i]);
                }
                rebuilt.append(':').append(phaseStr).append('>');
                replacement = rebuilt.toString();
            } else {
                replacement = "<gradient:" + inner + ":" + phaseStr + ">";
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static boolean isNumeric(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            Double.parseDouble(value.trim());
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    public record BuiltScoreboard(Component title, List<Component> lines) {
    }

    public static final class Builder {

        private final long tickMillis;
        private String clientLocale = ClientLocaleUtil.defaultMinecraftLocale();
        private ScoreboardTemplateLocalizer localizer = ScoreboardTemplateLocalizer.IDENTITY;
        private TitleKind titleKind = TitleKind.STATIC;
        private String titleTemplate = "";
        private final List<Entry> entries = new ArrayList<>();

        private Builder(long tickMillis) {
            this.tickMillis = tickMillis;
        }

        public Builder locale(String locale) {
            this.clientLocale = normalizeLocale(locale);
            return this;
        }

        public Builder localizer(ScoreboardTemplateLocalizer localizer) {
            this.localizer = localizer == null ? ScoreboardTemplateLocalizer.IDENTITY : localizer;
            return this;
        }

        public Builder staticTitle(String template) {
            this.titleKind = TitleKind.STATIC;
            this.titleTemplate = template == null ? "" : template;
            return this;
        }

        public Builder animatedTitle(String template) {
            this.titleKind = TitleKind.ANIMATED;
            this.titleTemplate = template == null ? "" : template;
            return this;
        }

        public Builder staticLine(String template) {
            entries.add(Entry.of(template, false, LineKind.STATIC));
            return this;
        }

        public Builder staticCentered(String template) {
            entries.add(Entry.of(template, true, LineKind.STATIC));
            return this;
        }

        public Builder animatedLine(String template) {
            entries.add(Entry.of(template, false, LineKind.ANIMATED));
            return this;
        }

        public Builder animatedCentered(String template) {
            entries.add(Entry.of(template, true, LineKind.ANIMATED));
            return this;
        }

        public Builder dynamicLine(String template) {
            entries.add(Entry.of(template, false, LineKind.DYNAMIC));
            return this;
        }

        public Builder dynamicCentered(String template) {
            entries.add(Entry.of(template, true, LineKind.DYNAMIC));
            return this;
        }

        public Builder blank() {
            entries.add(Entry.blank());
            return this;
        }

        public BuiltScoreboard build() {
            Component title = renderTitle();
            int maxWidth = ServerTextUtil.componentVisibleWidth(title);

            List<Component> rendered = new ArrayList<>(entries.size());
            for (Entry entry : entries) {
                if (entry.blank) {
                    rendered.add(Component.empty());
                    continue;
                }
                Component line = renderEntryRaw(entry);
                rendered.add(line);
                maxWidth = Math.max(maxWidth, ServerTextUtil.componentVisibleWidth(line));
            }

            int referenceWidth = Math.max(1, maxWidth);
            List<Component> lines = new ArrayList<>(entries.size());
            for (int index = 0; index < entries.size(); index++) {
                Entry entry = entries.get(index);
                Component line = rendered.get(index);
                if (entry.blank) {
                    lines.add(Component.empty());
                } else if (entry.centered) {
                    lines.add(ServerTextUtil.centerComponent(line, referenceWidth));
                } else {
                    lines.add(line);
                }
            }
            return new BuiltScoreboard(title, lines);
        }

        private Component renderTitle() {
            return switch (titleKind) {
                case STATIC -> renderStatic(titleTemplate, clientLocale, localizer);
                case ANIMATED -> render(titleTemplate, tickMillis, clientLocale, localizer);
            };
        }

        private Component renderEntryRaw(Entry entry) {
            return switch (entry.kind) {
                case STATIC -> renderStatic(entry.template, clientLocale, localizer);
                case ANIMATED -> render(entry.template, tickMillis, clientLocale, localizer);
                case DYNAMIC -> renderDynamicCached(entry.template, clientLocale);
            };
        }

        private Component renderDynamicCached(String template, String locale) {
            String prepared = prepareTemplate(template, 0L);
            if (prepared.isEmpty()) {
                return Component.empty();
            }
            if (DYNAMIC_RAW_CACHE.size() > DYNAMIC_CACHE_MAX) {
                DYNAMIC_RAW_CACHE.clear();
            }
            String normalizedLocale = normalizeLocale(locale);
            String localized = localizer.localize(prepared, normalizedLocale);
            String cacheKey = normalizedLocale + "\u0000" + localized;
            return DYNAMIC_RAW_CACHE.computeIfAbsent(
                    cacheKey,
                    ignored -> ServerTextUtil.miniMessageComponent(localized)
            );
        }

        private enum TitleKind {
            STATIC,
            ANIMATED
        }

        private static final class Entry {
            private final String template;
            private final boolean centered;
            private final boolean blank;
            private final LineKind kind;

            private Entry(String template, boolean centered, boolean blank, LineKind kind) {
                this.template = template;
                this.centered = centered;
                this.blank = blank;
                this.kind = kind;
            }

            private static Entry of(String template, boolean centered, LineKind kind) {
                return new Entry(template, centered, false, kind);
            }

            private static Entry blank() {
                return new Entry("", false, true, LineKind.STATIC);
            }
        }

        private enum LineKind {
            STATIC,
            ANIMATED,
            DYNAMIC
        }
    }
}
