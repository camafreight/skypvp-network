package network.skypvp.paper.quest;

import org.bukkit.World;

/**
 * Day-time source for NPC schedules.
 *
 * <p>Hub worlds pin their world time to a fixed value (see {@code LobbyWorldMaintenanceTask}),
 * which would freeze every schedule forever. {@code VIRTUAL} mode therefore runs its own
 * wall-clock-driven day at a configurable speed, independent of {@code World#getTime()}.
 * {@code WORLD} mode mirrors the world's real day cycle for servers that keep one.
 */
public final class QuestClock {

    public enum Mode {
        WORLD,
        VIRTUAL
    }

    /** Serialized form (quest/clock.json). */
    public static final class Settings {
        public String mode = "VIRTUAL";
        /** Virtual game-ticks advanced per real second. 20 = vanilla pace (20-minute day). */
        public double ticksPerSecond = 20.0D;
        /** Virtual tick-of-day at {@code epochMillis}. */
        public long anchorTickOfDay = 0L;
        public long epochMillis = System.currentTimeMillis();
    }

    private volatile Settings settings = new Settings();

    public Settings settings() {
        return settings;
    }

    public void apply(Settings loaded) {
        if (loaded != null) {
            this.settings = loaded;
        }
    }

    public Mode mode() {
        return "WORLD".equalsIgnoreCase(settings.mode) ? Mode.WORLD : Mode.VIRTUAL;
    }

    public void setMode(Mode mode) {
        Settings next = copy();
        next.mode = mode.name();
        this.settings = next;
    }

    /** Re-anchors the virtual day so "now" equals {@code tickOfDay}. */
    public void setTimeOfDay(long tickOfDay) {
        Settings next = copy();
        next.anchorTickOfDay = Math.floorMod(tickOfDay, 24000L);
        next.epochMillis = System.currentTimeMillis();
        this.settings = next;
    }

    public void setTicksPerSecond(double ticksPerSecond) {
        // Re-anchor first so the elapsed portion keeps the old speed instead of being rescaled.
        Settings next = copy();
        next.anchorTickOfDay = virtualTickOfDay();
        next.epochMillis = System.currentTimeMillis();
        next.ticksPerSecond = Math.max(0.0D, ticksPerSecond);
        this.settings = next;
    }

    /** Current tick-of-day (0..23999) for schedule checks in {@code world}. */
    public long tickOfDay(World world) {
        if (mode() == Mode.WORLD && world != null) {
            return Math.floorMod(world.getTime(), 24000L);
        }
        return virtualTickOfDay();
    }

    private long virtualTickOfDay() {
        Settings snapshot = settings;
        double elapsedTicks = (System.currentTimeMillis() - snapshot.epochMillis) / 1000.0D * snapshot.ticksPerSecond;
        return Math.floorMod(snapshot.anchorTickOfDay + (long) elapsedTicks, 24000L);
    }

    private Settings copy() {
        Settings snapshot = settings;
        Settings next = new Settings();
        next.mode = snapshot.mode;
        next.ticksPerSecond = snapshot.ticksPerSecond;
        next.anchorTickOfDay = snapshot.anchorTickOfDay;
        next.epochMillis = snapshot.epochMillis;
        return next;
    }

    /** Formats a tick-of-day as {@code HH:MM} (Minecraft convention: tick 0 = 06:00). */
    public static String formatTick(long tickOfDay) {
        long t = Math.floorMod(tickOfDay, 24000L);
        long totalMinutes = t * 60L / 1000L + 6L * 60L;
        long hours = totalMinutes / 60L % 24L;
        long minutes = totalMinutes % 60L;
        return String.format("%02d:%02d", hours, minutes);
    }

    /**
     * Parses {@code HH:MM} (wall-clock, tick 0 = 06:00) or a raw tick count.
     *
     * @return tick-of-day 0..23999, or {@code -1} when unparseable
     */
    public static long parseTimeOfDay(String raw) {
        if (raw == null || raw.isBlank()) {
            return -1L;
        }
        String value = raw.trim();
        int colon = value.indexOf(':');
        if (colon > 0) {
            try {
                int hours = Integer.parseInt(value.substring(0, colon));
                int minutes = Integer.parseInt(value.substring(colon + 1));
                if (hours < 0 || hours > 23 || minutes < 0 || minutes > 59) {
                    return -1L;
                }
                long tick = (hours * 60L + minutes - 6L * 60L) * 1000L / 60L;
                return Math.floorMod(tick, 24000L);
            } catch (NumberFormatException ignored) {
                return -1L;
            }
        }
        try {
            return Math.floorMod(Long.parseLong(value), 24000L);
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }
}
