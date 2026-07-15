package network.skypvp.extraction.ai.raider;

import java.util.UUID;
import network.skypvp.paper.ai.statetree.CombatAgentStateId;
import network.skypvp.paper.ai.statetree.CombatAgentStateLabels;

/** Extraction-specific nametag labels (squad role suffix on generic combat states). */
public final class RaiderStateLabels {

    private static final String[] SQUAD_CALLSIGNS = {
            "Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Ghost", "Havoc"
    };

    private RaiderStateLabels() {
    }

    public static String label(CombatAgentStateId state) {
        return display(state, RaiderGroupRole.SOLO, 1);
    }

    /** Team callsign shown to the right of the mob name when in a squad. */
    public static String teamTag(UUID groupId, int groupSize) {
        if (groupId == null || groupSize < 2) {
            return "";
        }
        int index = Math.floorMod(groupId.hashCode(), SQUAD_CALLSIGNS.length);
        String callsign = SQUAD_CALLSIGNS[index] + "-" + groupSize;
        return " <dark_aqua>'[" + callsign + "]'";
    }

    public static String display(CombatAgentStateId state, RaiderGroupRole role, int groupSize) {
        String base = CombatAgentStateLabels.label(state);
        if (groupSize < 2 || role == null || role == RaiderGroupRole.SOLO) {
            return base;
        }
        return base + " <dark_gray>| " + roleLabel(role);
    }

    private static String roleLabel(RaiderGroupRole role) {
        return switch (role) {
            case SUPPRESS -> "<white>Suppress";
            case FLANK_LEFT -> "<aqua>Left";
            case FLANK_RIGHT -> "<aqua>Right";
            case BREACH -> "<gold>Breach";
            default -> "<gray>Solo";
        };
    }
}
