package network.skypvp.paper.library;

import java.util.Locale;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.integration.InteractionPlaceholderResolver;
import network.skypvp.paper.integration.ProxyRouteMessenger;
import network.skypvp.shared.ServerTextUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

final class InteractionActionExecutor {

    private InteractionActionExecutor() {
    }

    static void execute(PaperCorePlugin plugin, Player player, String actionType, String actionData) {
        execute(plugin, player, actionType, actionData, null, null);
    }

    static void execute(PaperCorePlugin plugin, Player player, String actionType, String actionData, Location anchor) {
        execute(plugin, player, actionType, actionData, anchor, null);
    }

    static void execute(
            PaperCorePlugin plugin,
            Player player,
            String actionType,
            String actionData,
            Location anchor,
            Entity speaker
    ) {
        if (plugin == null || player == null) {
            return;
        }
        String normalizedAction = InteractionActionTypes.normalize(actionType);
        String normalizedData = actionData == null ? "" : actionData.trim();
        switch (normalizedAction) {
            case InteractionActionTypes.CONNECT:
            case InteractionActionTypes.CONNECT_SERVER:
                if (!normalizedData.isBlank()) {
                    String destination = InteractionPlaceholderResolver.resolve(plugin, player, normalizedData)
                            .trim()
                            .toLowerCase(Locale.ROOT);
                    if (!destination.isBlank()) {
                        ProxyRouteMessenger.routePlayer(plugin, player, destination);
                    }
                }
                break;
            case InteractionActionTypes.COMMAND:
            case InteractionActionTypes.PLAYER_COMMAND:
                dispatchPlayerCommand(plugin, player, normalizedData);
                break;
            case InteractionActionTypes.CONSOLE_COMMAND:
                dispatchConsoleCommand(plugin, player, normalizedData);
                break;
            case InteractionActionTypes.PROXY_COMMAND:
                dispatchProxyCommand(plugin, player, normalizedData);
                break;
            case InteractionActionTypes.PROXY_CONSOLE_COMMAND:
                dispatchProxyConsoleCommand(plugin, player, normalizedData);
                break;
            case InteractionActionTypes.OPEN_NETWORK_MENU:
            case InteractionActionTypes.OPEN_MENU:
            case InteractionActionTypes.NETWORK_MENU:
                if (plugin.networkMenuService() != null) {
                    plugin.networkMenuService().openRootMenu(player);
                }
                break;
            case InteractionActionTypes.MENU:
            case InteractionActionTypes.OPEN_MENU_KEY:
                if (plugin.networkMenuService() != null) {
                    String menuKey = InteractionPlaceholderResolver.resolve(plugin, player, normalizedData).trim();
                    plugin.networkMenuService().openMenuByKey(player, menuKey);
                }
                break;
            case InteractionActionTypes.MESSAGE:
                if (!normalizedData.isBlank()) {
                    String message = InteractionPlaceholderResolver.resolve(plugin, player, normalizedData);
                    player.sendMessage(ServerTextUtil.miniMessageComponent(message));
                }
                break;
            case InteractionActionTypes.QUEST_DIALOGUE:
                if (normalizedData.isBlank()) {
                    break;
                }
                if (plugin.questDialogueBridge() == null) {
                    player.sendMessage(ServerTextUtil.miniMessageComponent(
                            "<red>Dialogues are unavailable on this server.</red>"
                    ));
                    break;
                }
                plugin.questDialogueBridge().open(player, normalizedData.trim(), anchor, speaker);
                break;
            default:
                break;
        }
    }

    private static void dispatchPlayerCommand(PaperCorePlugin plugin, Player player, String rawCommand) {
        String command = InteractionPlaceholderResolver.normalizeCommand(
                InteractionPlaceholderResolver.resolve(plugin, player, rawCommand)
        );
        if (!command.isBlank()) {
            player.performCommand(command);
        }
    }

    private static void dispatchConsoleCommand(PaperCorePlugin plugin, Player player, String rawCommand) {
        String command = InteractionPlaceholderResolver.normalizeCommand(
                InteractionPlaceholderResolver.resolve(plugin, player, rawCommand)
        );
        if (!command.isBlank()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        }
    }

    private static void dispatchProxyCommand(PaperCorePlugin plugin, Player player, String rawCommand) {
        String command = InteractionPlaceholderResolver.normalizeCommand(
                InteractionPlaceholderResolver.resolve(plugin, player, rawCommand)
        );
        if (!command.isBlank()) {
            ProxyRouteMessenger.dispatchProxyCommand(plugin, player, command);
        }
    }

    private static void dispatchProxyConsoleCommand(PaperCorePlugin plugin, Player player, String rawCommand) {
        String command = InteractionPlaceholderResolver.normalizeCommand(
                InteractionPlaceholderResolver.resolve(plugin, player, rawCommand)
        );
        if (!command.isBlank()) {
            ProxyRouteMessenger.dispatchProxyConsoleCommand(plugin, player, command);
        }
    }
}
