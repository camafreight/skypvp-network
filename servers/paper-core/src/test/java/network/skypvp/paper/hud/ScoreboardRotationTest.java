package network.skypvp.paper.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.kyori.adventure.text.Component;
import network.skypvp.paper.gamemode.api.HudProvider;
import org.junit.jupiter.api.Test;

class ScoreboardRotationTest {

    @Test
    void resolveWindowAlternatesPagesAcrossCycle() {
        long view = 6000L;
        long transition = 900L;

        ScoreboardRotation.RotationWindow start = ScoreboardRotation.resolveWindow(0L, view, transition, 2);
        assertEquals(0, start.pageIndex());
        assertEquals(ScoreboardRotation.RotationPhase.FADE_IN, start.phase());

        ScoreboardRotation.RotationWindow hubHold = ScoreboardRotation.resolveWindow(3000L, view, transition, 2);
        assertEquals(0, hubHold.pageIndex());
        assertEquals(ScoreboardRotation.RotationPhase.HOLD, hubHold.phase());

        ScoreboardRotation.RotationWindow partyHold = ScoreboardRotation.resolveWindow(9000L, view, transition, 2);
        assertEquals(1, partyHold.pageIndex());
        assertEquals(ScoreboardRotation.RotationPhase.HOLD, partyHold.phase());
    }

    @Test
    void transitionDisabledWhenZeroMillis() {
        var builder = ScoreboardRotation.builder(0L)
                .viewDuration(6000L)
                .transition(0L)
                .page(() -> frame("A", line("top")))
                .page(() -> frame("B", line("other")));
        assertTrue(builder.build().title().toString().contains("A"));
    }

    @Test
    void revealOnlyShowsLeadingPopulatedLines() {
        Component top = line("top");
        Component middle = line("middle");
        List<Component> lines = linesAtSlots(0, top, 1, middle);

        HudProvider.ScoreboardFrame built = ScoreboardRotation.builder(450L)
                .viewDuration(6000L)
                .transition(900L, ScoreboardTransitionType.REVEAL)
                .page(() -> frame("hub", lines))
                .page(() -> frame("party", lines))
                .build();

        assertSame(top, built.lines().get(0));
        assertTrue(Component.empty().equals(built.lines().get(1)));
    }

    @Test
    void scrollShiftsLinesVerticallyDuringFadeIn() {
        Component top = line("top");
        Component middle = line("middle");
        List<Component> lines = linesAtSlots(5, top, 6, middle);

        HudProvider.ScoreboardFrame scrolled = ScoreboardRotation.builder(675L)
                .viewDuration(6000L)
                .transition(900L, ScoreboardTransitionType.SCROLL)
                .page(() -> frame("hub", lines))
                .page(() -> frame("party", lines))
                .build();

        assertSame(top, scrolled.lines().get(8));
        assertSame(middle, scrolled.lines().get(9));
    }

    @Test
    void scrollReturnsOriginalLayoutWhenFullyVisible() {
        Component top = line("top");
        List<Component> lines = paddedSidebar(top, Component.empty(), Component.empty());

        HudProvider.ScoreboardFrame built = ScoreboardRotation.builder(3000L)
                .viewDuration(6000L)
                .transition(900L, ScoreboardTransitionType.SCROLL)
                .page(() -> frame("hub", lines))
                .page(() -> frame("party", lines))
                .build();

        assertSame(top, built.lines().get(0));
    }

    private static HudProvider.ScoreboardFrame frame(String title, List<Component> lines) {
        return new HudProvider.ScoreboardFrame(Component.text(title), lines);
    }

    private static HudProvider.ScoreboardFrame frame(String title, Component line) {
        return frame(title, List.of(line));
    }

    private static Component line(String value) {
        return Component.text(value);
    }

    private static List<Component> linesAtSlots(int firstSlot, Component first, int secondSlot, Component second) {
        Component[] slots = new Component[ScoreboardLayout.SIDEBAR_LINE_COUNT];
        java.util.Arrays.fill(slots, Component.empty());
        slots[firstSlot] = first;
        slots[secondSlot] = second;
        return java.util.Collections.unmodifiableList(java.util.Arrays.asList(slots));
    }

    private static List<Component> paddedSidebar(Component... lines) {
        Component[] slots = new Component[ScoreboardLayout.SIDEBAR_LINE_COUNT];
        java.util.Arrays.fill(slots, Component.empty());
        for (int index = 0; index < lines.length && index < slots.length; index++) {
            slots[index] = lines[index];
        }
        return java.util.Collections.unmodifiableList(java.util.Arrays.asList(slots));
    }
}
