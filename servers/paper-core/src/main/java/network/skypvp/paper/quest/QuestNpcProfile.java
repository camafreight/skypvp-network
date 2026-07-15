package network.skypvp.paper.quest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import network.skypvp.paper.model.WorldPoint;

/**
 * Persisted definition of one dynamic quest NPC managed by {@link QuestNpcService}.
 *
 * <p>Unlike the static packet NPCs in {@code NpcLibrary}, these are real pathfinding
 * {@link org.bukkit.entity.Mob}s driven by a state tree: they commute to their assigned
 * POIs when their daily schedule opens, stroll around their post, and walk back to
 * {@code home} when the schedule closes — like a villager with a day job.
 */
public final class QuestNpcProfile {

    public String id = "npc";
    public String displayName = "<gold><bold>Quest NPC</bold></gold>";
    /** Extra MiniMessage lines rendered under {@link #displayName} on the floating nametag. */
    public List<String> extraNameLines = new ArrayList<>();
    /** Bukkit {@link org.bukkit.entity.EntityType} name; must resolve to a {@link org.bukkit.entity.Mob}. */
    public String entityType = "VILLAGER";
    /** Off-duty anchor: where the NPC lives and returns to outside its schedule. */
    public WorldPoint home = new WorldPoint();
    /** Schedule window in ticks-of-day (0..23999, tick 0 = 06:00). May wrap past midnight. */
    public int scheduleStartTick = 0;
    public int scheduleEndTick = 12000;
    /** Stroll radius around the working POI; {@code <= 0} disables strolling. */
    public double wanderRadius = 6.0D;
    /** Pathfinder speed multiplier while commuting/strolling. */
    public double walkSpeed = 1.0D;
    /** POI refs from the shared pool: {@code "market"} (any free slot) or {@code "market:stall2"} (pinned). */
    public List<String> pois = new ArrayList<>();
    /** Interact action passthrough — same vocabulary as {@code NpcDefinition} (DIALOGUE, COMMAND, SERVER…). */
    public String actionType = "NONE";
    public String actionData = "";
    /** MiniMessage line whispered to players who come close; null/blank = silent. */
    public String greeting = null;
    /** When true, on-duty NPCs advertise a navigator beacon to players entering the world (from spawn). */
    public boolean beacon = false;
    /** Staff toggle: paused NPCs freeze in place and skip scheduling. */
    public boolean paused = false;
    /**
     * Decoration / gamemode bucket this NPC belongs to ({@code lobby}, {@code extraction}, …).
     * Blank or null is treated as legacy and only loads on the creating scope after backfill.
     */
    public String scope = "";

    public QuestNpcProfile() {
    }

    public String key() {
        return id == null ? "" : id.toLowerCase(Locale.ROOT);
    }

    /** All nametag lines top-to-bottom: {@link #displayName} first, then the extras (blanks skipped). */
    public List<String> nameLines() {
        List<String> lines = new ArrayList<>();
        if (displayName != null && !displayName.isBlank()) {
            lines.add(displayName);
        }
        if (extraNameLines != null) {
            for (String line : extraNameLines) {
                if (line != null && !line.isBlank()) {
                    lines.add(line);
                }
            }
        }
        return lines;
    }

    public String normalizedScope() {
        return scope == null ? "" : scope.trim().toLowerCase(Locale.ROOT);
    }

    public boolean matchesScope(String decorationScope) {
        String wanted = decorationScope == null ? "" : decorationScope.trim().toLowerCase(Locale.ROOT);
        String own = normalizedScope();
        if (own.isEmpty()) {
            // Legacy rows (pre-scope): treat as lobby so extraction does not inherit them by accident.
            return "lobby".equals(wanted);
        }
        return own.equals(wanted);
    }

    /** True when {@code tickOfDay} falls inside the schedule window (handles midnight wrap). */
    public boolean isOnDuty(long tickOfDay) {
        int start = Math.floorMod(scheduleStartTick, 24000);
        int end = Math.floorMod(scheduleEndTick, 24000);
        long t = Math.floorMod(tickOfDay, 24000L);
        if (start == end) {
            return true;
        }
        if (start < end) {
            return t >= start && t < end;
        }
        return t >= start || t < end;
    }
}
