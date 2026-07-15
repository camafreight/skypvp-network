package network.skypvp.paper.quest;

/** Daily-routine states for a {@link QuestNpcAgent} state tree. */
public enum QuestNpcState {
    /** At (or heading nowhere but) home, outside the schedule window. */
    OFF_DUTY,
    /** Walking from wherever it is to its reserved POI slot. */
    COMMUTE,
    /** Standing at the reserved slot, greeting/facing players. */
    AT_POST,
    /** Short stroll around the post before returning to it. */
    STROLL,
    /** Schedule closed — walking back home. */
    RETURN_HOME
}
