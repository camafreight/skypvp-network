package network.skypvp.paper.ai.statetree;

/** MiniMessage labels for combat-agent debug nametags. */
public final class CombatAgentStateLabels {

    private CombatAgentStateLabels() {
    }

    public static String label(CombatAgentStateId state) {
        if (state == null) {
            return "<dark_gray>Unknown";
        }
        return switch (state) {
            case IDLE -> "<gray>Idle";
            case ENGAGE -> "<red>Engage";
            case MELEE -> "<dark_red>Melee";
            case TAKE_COVER -> "<blue>Cover";
            case PEEK -> "<aqua>Peek";
            case RELOAD -> "<yellow>Reload";
            case HEAL -> "<green>Heal";
            case SECONDARY_WEAPON -> "<gold>Secondary";
            case PURSUE -> "<light_purple>Pursue";
            case SQUAD_TACTIC -> "<dark_aqua>Squad";
            case SQUAD_LEAVE -> "<dark_gray>Leave";
            case RETREAT -> "<dark_blue>Retreat";
            case INSPECT -> "<dark_gray>Inspect";
        };
    }
}
