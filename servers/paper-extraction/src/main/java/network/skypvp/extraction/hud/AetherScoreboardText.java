package network.skypvp.extraction.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import network.skypvp.extraction.text.ExtractionTexts;

/**
 * Renders scoreboard line templates, adding support for a custom {@code <anim:glow>} prefix tag.
 */
public final class AetherScoreboardText {

    private static final Pattern GRADIENT = Pattern.compile("<gradient:([^>]+)>");
    private static final String GLOW_TAG = "<anim:glow>";
    private static final ConcurrentHashMap<String, Component> STATIC_RAW_CACHE = new ConcurrentHashMap<>();

    private AetherScoreboardText() {
    }

    public static Builder builder(long tickMillis) {
        return new Builder(tickMillis);
    }

    public static Component render(String template, long tickMillis) {
        return render(template, tickMillis, ExtractionTexts.defaultLocale());
    }

    public static Component render(String template, long tickMillis, String locale) {
        return ExtractionTexts.miniMessageTemplate(prepareTemplate(template, tickMillis), locale);
    }

    public static Component renderStatic(String template) {
        return renderStatic(template, ExtractionTexts.defaultLocale());
    }

    public static Component renderStatic(String template, String locale) {
        String prepared = prepareTemplate(template == null ? "" : template, 0L);
        if (prepared.isEmpty()) {
            return Component.empty();
        }
        String normalizedLocale = ExtractionTexts.normalizeLocale(locale);
        String localized = ExtractionTexts.localizeTemplate(prepared, normalizedLocale);
        String cacheKey = normalizedLocale + "\u0000" + localized;
        return STATIC_RAW_CACHE.computeIfAbsent(cacheKey, ignored -> ExtractionTexts.miniMessageTemplate(localized, normalizedLocale));
    }

    public static Component renderCentered(String template, long tickMillis) {
        return renderCentered(template, tickMillis, ExtractionTexts.scoreboardLineWidthPixels());
    }

    public static Component renderCentered(String template, long tickMillis, int lineWidthPixels) {
        Component rendered = render(template, tickMillis);
        return ExtractionTexts.centerComponent(rendered, lineWidthPixels);
    }

    static String prepareTemplate(String template, long tickMillis) {
        String text = template == null ? "" : template;
        if (text.contains(GLOW_TAG)) {
            text = text.replace(GLOW_TAG, "");
            text = injectGradientPhase(text, tickMillis);
        }
        return text;
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
        private String clientLocale = ExtractionTexts.defaultLocale();
        private TitleKind titleKind = TitleKind.STATIC;
        private String titleTemplate = "";
        private final List<Entry> entries = new ArrayList<>();

        private Builder(long tickMillis) {
            this.tickMillis = tickMillis;
        }

        public Builder locale(String locale) {
            this.clientLocale = ExtractionTexts.normalizeLocale(locale);
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

        @Deprecated
        public Builder title(String template) {
            return staticTitle(template);
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

        @Deprecated
        public Builder line(String template) {
            return dynamicLine(template);
        }

        @Deprecated
        public Builder centered(String template) {
            return dynamicCentered(template);
        }

        public Builder blank() {
            entries.add(Entry.blank());
            return this;
        }

        public BuiltScoreboard build() {
            Component title = renderTitle();
            int maxWidth = ExtractionTexts.componentVisibleWidth(title);

            List<Component> rendered = new ArrayList<>(entries.size());
            for (Entry entry : entries) {
                if (entry.blank) {
                    rendered.add(Component.empty());
                    continue;
                }
                Component line = renderEntryRaw(entry);
                rendered.add(line);
                maxWidth = Math.max(maxWidth, ExtractionTexts.componentVisibleWidth(line));
            }

            int referenceWidth = Math.max(1, maxWidth);
            List<Component> lines = new ArrayList<>(entries.size());
            for (int index = 0; index < entries.size(); index++) {
                Entry entry = entries.get(index);
                Component line = rendered.get(index);
                if (entry.blank) {
                    lines.add(Component.empty());
                } else if (entry.centered) {
                    lines.add(ExtractionTexts.centerComponent(line, referenceWidth));
                } else {
                    lines.add(line);
                }
            }
            return new BuiltScoreboard(title, lines);
        }

        private Component renderTitle() {
            return switch (titleKind) {
                case STATIC -> renderStatic(titleTemplate, clientLocale);
                case ANIMATED -> render(titleTemplate, tickMillis, clientLocale);
            };
        }

        private Component renderEntryRaw(Entry entry) {
            return switch (entry.kind) {
                case STATIC -> renderStatic(entry.template, clientLocale);
                case ANIMATED -> render(entry.template, tickMillis, clientLocale);
                case DYNAMIC -> ExtractionTexts.miniMessageTemplate(prepareTemplate(entry.template, 0L), clientLocale);
            };
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
