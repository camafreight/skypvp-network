package network.skypvp.paper.command;

import network.skypvp.shared.NetworkRoutes;
import network.skypvp.shared.ServerTextUtil;

import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.integration.ProxyRouteMessenger;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class HubCommand implements CommandExecutor {
    private final PaperCorePlugin plugin;

    public HubCommand(PaperCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ServerTextUtil.miniMessageComponent("<red>Only players can use /hub.</red>"));
            return true;
        }
        ProxyRouteMessenger.routePlayer(this.plugin, player, NetworkRoutes.LOBBY);
        player.sendMessage(
                ServerTextUtil.miniMessageComponent(
                        "<#FFD700>➤ <reset><#888888>Queueing you for the <reset><#FFB300>lobby<reset><#888888>...<reset>"
                )
        );
        return true;
    }
}
