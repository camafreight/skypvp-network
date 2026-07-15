package network.skypvp.paper.library;

import java.util.List;
import java.util.Locale;

/** Supported {@link InteractionActionExecutor} action type names for NPCs and holograms. */
public final class InteractionActionTypes {

    public static final String NONE = "NONE";
    public static final String CONNECT = "CONNECT";
    public static final String CONNECT_SERVER = "CONNECT_SERVER";
    public static final String COMMAND = "COMMAND";
    public static final String PLAYER_COMMAND = "PLAYER_COMMAND";
    public static final String CONSOLE_COMMAND = "CONSOLE_COMMAND";
    public static final String PROXY_COMMAND = "PROXY_COMMAND";
    public static final String PROXY_CONSOLE_COMMAND = "PROXY_CONSOLE_COMMAND";
    public static final String MESSAGE = "MESSAGE";
    public static final String OPEN_NETWORK_MENU = "OPEN_NETWORK_MENU";
    public static final String OPEN_MENU = "OPEN_MENU";
    public static final String NETWORK_MENU = "NETWORK_MENU";
    public static final String MENU = "MENU";
    public static final String OPEN_MENU_KEY = "OPEN_MENU_KEY";
    public static final String QUEST_DIALOGUE = "QUEST_DIALOGUE";

    public static final List<String> NPC_TAB_TYPES = List.of(
            NONE,
            CONNECT,
            COMMAND,
            PLAYER_COMMAND,
            CONSOLE_COMMAND,
            PROXY_COMMAND,
            PROXY_CONSOLE_COMMAND,
            MESSAGE,
            OPEN_NETWORK_MENU,
            MENU,
            QUEST_DIALOGUE
    );

    public static final List<String> HOLOGRAM_TAB_TYPES = List.of(
            NONE,
            CONNECT,
            COMMAND,
            PLAYER_COMMAND,
            CONSOLE_COMMAND,
            PROXY_COMMAND,
            PROXY_CONSOLE_COMMAND,
            MESSAGE,
            "NEXT_PAGE",
            "PREV_PAGE",
            "GOTO_PAGE",
            "TOGGLE_PAGE"
    );

    private InteractionActionTypes() {
    }

    public static String normalize(String actionType) {
        return actionType == null || actionType.isBlank() ? NONE : actionType.trim().toUpperCase(Locale.ROOT);
    }
}
