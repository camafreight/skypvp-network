package network.skypvp.paper.quest;

import java.util.LinkedHashMap;
import java.util.Map;
import network.skypvp.paper.model.WorldPoint;

/**
 * A named point of interest in the shared quest location pool.
 *
 * <p>A POI has one {@code anchor} spot plus optional named {@code aliases} — sub-locations a few
 * blocks apart (market stall left/right, forge bench vs. anvil). NPCs that share the same POI are
 * assigned different slots by {@link QuestLocationRegistry#reserveSlot}, so two vendors never
 * stand inside each other (the "smart object with N slots" pattern from modern crowd AI).
 */
public final class QuestPoi {

    public String name = "poi";
    public WorldPoint anchor = new WorldPoint();
    /** Alias name → sub-location. Iteration order is creation order (LinkedHashMap via Gson). */
    public Map<String, WorldPoint> aliases = new LinkedHashMap<>();
    /** Gamemode / decoration bucket ({@code lobby}, {@code extraction}). Blank = legacy lobby. */
    public String scope = "";

    public QuestPoi() {
    }

    public QuestPoi(String name, WorldPoint anchor) {
        this.name = name;
        this.anchor = anchor;
    }

    public String normalizedScope() {
        return scope == null ? "" : scope.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public boolean matchesScope(String decorationScope) {
        String wanted = decorationScope == null ? "" : decorationScope.trim().toLowerCase(java.util.Locale.ROOT);
        String own = normalizedScope();
        if (own.isEmpty()) {
            return "lobby".equals(wanted);
        }
        return own.equals(wanted);
    }

    /** Spot for {@code alias}, or the anchor when {@code alias} is null/blank. Null when unknown. */
    public WorldPoint spot(String alias) {
        if (alias == null || alias.isBlank()) {
            return anchor;
        }
        return aliases.get(alias);
    }
}
