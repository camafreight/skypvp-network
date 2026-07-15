package network.skypvp.paper.hud;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import network.skypvp.paper.gamemode.api.HudProvider;

/**
 * Optional multi-page sidebar rotation with configurable transition styles.
 * Call {@link Builder#transition(long, ScoreboardTransitionType)} to enable; omit for instant swaps.
 */
public final class ScoreboardRotation {

    public static final long DEFAULT_TRANSITION_MILLIS = 900L;
    private static final double TITLE_VISIBILITY_THRESHOLD = 0.35D;

    private ScoreboardRotation() {
    }

    public static Builder builder(long tickMillis) {
        return new Builder(tickMillis);
    }

    public static final class Builder {

        private final long tickMillis;
        private long viewDurationMillis = 6000L;
        private long transitionMillis = 0L;
        private ScoreboardTransitionType transitionType = ScoreboardTransitionType.SCROLL;
        private final List<Supplier<HudProvider.ScoreboardFrame>> pages = new ArrayList<>();

        private Builder(long tickMillis) {
            this.tickMillis = tickMillis;
        }

        /** How long each page stays fully visible before transitioning away. */
        public Builder viewDuration(long millis) {
            this.viewDurationMillis = Math.max(1L, millis);
            return this;
        }

        /** Enables transitions with the default {@link ScoreboardTransitionType#SCROLL} style. */
        public Builder transition(long millis) {
            return transition(millis, this.transitionType);
        }

        /** Enables transitions using the given visual style. {@code 0} or {@link ScoreboardTransitionType#NONE} disables animation. */
        public Builder transition(long millis, ScoreboardTransitionType type) {
            this.transitionType = type == null ? ScoreboardTransitionType.SCROLL : type;
            this.transitionMillis = this.transitionType == ScoreboardTransitionType.NONE ? 0L : Math.max(0L, millis);
            return this;
        }

        /** Enables/disables transitions using {@link #DEFAULT_TRANSITION_MILLIS}. */
        public Builder transition(boolean enabled) {
            return transition(enabled ? DEFAULT_TRANSITION_MILLIS : 0L);
        }

        /** Enables/disables transitions with an explicit visual style. */
        public Builder transition(boolean enabled, ScoreboardTransitionType type) {
            return transition(enabled ? DEFAULT_TRANSITION_MILLIS : 0L, type);
        }

        /** Sets the transition style used by {@link #transition(long)} and {@link #transition(boolean)}. */
        public Builder transitionStyle(ScoreboardTransitionType type) {
            this.transitionType = type == null ? ScoreboardTransitionType.SCROLL : type;
            return this;
        }

        public Builder page(Supplier<HudProvider.ScoreboardFrame> supplier) {
            if (supplier != null) {
                this.pages.add(supplier);
            }
            return this;
        }

        public HudProvider.ScoreboardFrame build() {
            if (this.pages.isEmpty()) {
                return emptyFrame();
            }
            if (this.pages.size() == 1
                    || this.transitionMillis <= 0L
                    || this.transitionType == ScoreboardTransitionType.NONE
                    || this.transitionMillis >= this.viewDurationMillis) {
                int index = resolveHoldPageIndex(this.tickMillis, this.viewDurationMillis, this.pages.size());
                return safeFrame(this.pages.get(index));
            }

            RotationWindow window = resolveWindow(
                    this.tickMillis,
                    this.viewDurationMillis,
                    this.transitionMillis,
                    this.pages.size()
            );
            HudProvider.ScoreboardFrame frame = safeFrame(this.pages.get(window.pageIndex()));
            if (window.phase() == RotationPhase.HOLD) {
                return frame;
            }
            return applyTransition(frame, window, this.transitionType);
        }
    }

    private static HudProvider.ScoreboardFrame applyTransition(
            HudProvider.ScoreboardFrame frame,
            RotationWindow window,
            ScoreboardTransitionType transitionType
    ) {
        double visibility = window.lineVisibility();
        if (visibility >= 0.999D) {
            return frame;
        }

        List<Component> source = padLines(frame.lines(), ScoreboardLayout.SIDEBAR_LINE_COUNT);
        int[] populated = populatedLineIndices(source);
        List<Component> lines = switch (transitionType) {
            case REVEAL -> applyReveal(source, populated, visibility);
            case SCROLL -> applyScroll(source, populated, visibility, window.phase());
            case NONE -> source;
        };
        Component title = visibility >= TITLE_VISIBILITY_THRESHOLD ? frame.title() : Component.empty();
        return new HudProvider.ScoreboardFrame(title, lines);
    }

    private static List<Component> applyReveal(List<Component> source, int[] populated, double visibility) {
        if (populated.length == 0) {
            return emptySidebar();
        }
        int visibleCount = (int) Math.ceil(visibility * populated.length);
        if (visibleCount >= populated.length) {
            return source;
        }
        if (visibleCount <= 0) {
            return emptySidebar();
        }
        return partialSidebar(source, populated, visibleCount);
    }

    private static List<Component> applyScroll(
            List<Component> source,
            int[] populated,
            double visibility,
            RotationPhase phase
    ) {
        if (populated.length == 0) {
            return emptySidebar();
        }
        int scrollDistance = ScoreboardLayout.SIDEBAR_LINE_COUNT;
        int offset = (int) Math.floor((1.0D - visibility) * scrollDistance);
        if (offset <= 0) {
            return source;
        }

        Component[] slots = new Component[ScoreboardLayout.SIDEBAR_LINE_COUNT];
        Arrays.fill(slots, Component.empty());
        boolean fadeIn = phase == RotationPhase.FADE_IN;
        for (int index = 0; index < populated.length; index++) {
            int originalSlot = populated[index];
            int displaySlot = fadeIn ? originalSlot + offset : originalSlot - offset;
            if (displaySlot >= 0 && displaySlot < ScoreboardLayout.SIDEBAR_LINE_COUNT) {
                slots[displaySlot] = source.get(originalSlot);
            }
        }
        return java.util.Collections.unmodifiableList(Arrays.asList(slots));
    }

    /**
     * Builds a sidebar showing only the first {@code visibleCount} populated lines.
     * Hidden slots reuse {@link Component#empty()} so {@link network.skypvp.paper.service.ScoreboardService}
     * skips unchanged visible line packets.
     */
    private static List<Component> partialSidebar(List<Component> source, int[] populated, int visibleCount) {
        Component[] slots = new Component[ScoreboardLayout.SIDEBAR_LINE_COUNT];
        Arrays.fill(slots, Component.empty());
        for (int index = 0; index < visibleCount; index++) {
            int lineIndex = populated[index];
            slots[lineIndex] = source.get(lineIndex);
        }
        return java.util.Collections.unmodifiableList(Arrays.asList(slots));
    }

    private static List<Component> emptySidebar() {
        Component[] slots = new Component[ScoreboardLayout.SIDEBAR_LINE_COUNT];
        Arrays.fill(slots, Component.empty());
        return java.util.Collections.unmodifiableList(Arrays.asList(slots));
    }

    private static List<Component> padLines(List<Component> lines, int count) {
        if (lines.size() >= count) {
            if (lines.size() == count) {
                return lines;
            }
            return List.copyOf(lines.subList(0, count));
        }
        List<Component> padded = new ArrayList<>(count);
        padded.addAll(lines);
        while (padded.size() < count) {
            padded.add(Component.empty());
        }
        return padded;
    }

    private static int[] populatedLineIndices(List<Component> lines) {
        int limit = Math.min(lines.size(), ScoreboardLayout.SIDEBAR_LINE_COUNT);
        int[] buffer = new int[limit];
        int count = 0;
        for (int index = 0; index < limit; index++) {
            if (!Component.empty().equals(lines.get(index))) {
                buffer[count++] = index;
            }
        }
        return count == buffer.length ? buffer : Arrays.copyOf(buffer, count);
    }

    private static int resolveHoldPageIndex(long tickMillis, long viewDurationMillis, int pageCount) {
        if (pageCount <= 1) {
            return 0;
        }
        long cycle = viewDurationMillis * pageCount;
        long phase = Math.floorMod(tickMillis, cycle);
        return (int) (phase / viewDurationMillis);
    }

    static RotationWindow resolveWindow(long tickMillis, long viewDurationMillis, long transitionMillis, int pageCount) {
        if (pageCount <= 1) {
            return new RotationWindow(0, RotationPhase.HOLD, 1.0D);
        }
        long cycle = viewDurationMillis * pageCount;
        long phase = Math.floorMod(tickMillis, cycle);
        int pageIndex = (int) (phase / viewDurationMillis);
        long pagePhase = phase % viewDurationMillis;

        if (pagePhase < transitionMillis) {
            return new RotationWindow(pageIndex, RotationPhase.FADE_IN, pagePhase / (double) transitionMillis);
        }
        if (pagePhase > viewDurationMillis - transitionMillis) {
            return new RotationWindow(
                    pageIndex,
                    RotationPhase.FADE_OUT,
                    (pagePhase - (viewDurationMillis - transitionMillis)) / (double) transitionMillis
            );
        }
        return new RotationWindow(pageIndex, RotationPhase.HOLD, 1.0D);
    }

    private static HudProvider.ScoreboardFrame safeFrame(Supplier<HudProvider.ScoreboardFrame> supplier) {
        HudProvider.ScoreboardFrame frame = supplier.get();
        return frame == null ? emptyFrame() : frame;
    }

    private static HudProvider.ScoreboardFrame emptyFrame() {
        return new HudProvider.ScoreboardFrame(Component.empty(), List.of());
    }

    enum RotationPhase {
        HOLD,
        FADE_IN,
        FADE_OUT
    }

    record RotationWindow(int pageIndex, RotationPhase phase, double progress) {
        double lineVisibility() {
            return switch (this.phase) {
                case HOLD -> 1.0D;
                case FADE_IN -> this.progress;
                case FADE_OUT -> 1.0D - this.progress;
            };
        }
    }
}
