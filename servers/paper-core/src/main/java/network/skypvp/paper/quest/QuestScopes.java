package network.skypvp.paper.quest;

import java.util.List;
import java.util.Locale;
import network.skypvp.paper.util.DecorationScopes;

/** Gamemode / decoration bucket helpers for quest NPCs and POIs. */
public final class QuestScopes {

    /** Quest content is gamemode-local — not {@code global}. */
    public static final List<String> KNOWN = List.of("lobby", "extraction");

    private QuestScopes() {
    }

    public static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isQuestScope(String scope) {
        String normalized = normalize(scope);
        return KNOWN.contains(normalized);
    }

    /** @deprecated prefer {@link #isQuestScope(String)} */
    @Deprecated
    public static boolean isKnown(String scope) {
        return isQuestScope(scope) || DecorationScopes.KNOWN.contains(normalize(scope));
    }
}
