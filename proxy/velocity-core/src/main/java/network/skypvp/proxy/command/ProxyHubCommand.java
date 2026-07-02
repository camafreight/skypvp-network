package network.skypvp.proxy.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import network.skypvp.proxy.service.ProxyDestinationRouter;
import network.skypvp.shared.ServerTextUtil;

public final class ProxyHubCommand {
    private final ProxyServer proxyServer;
    private final ProxyDestinationRouter destinationRouter;

    public ProxyHubCommand(ProxyServer proxyServer, ProxyDestinationRouter destinationRouter) {
        this.proxyServer = proxyServer;
        this.destinationRouter = destinationRouter;
    }

    public BrigadierCommand build() {
        LiteralCommandNode<CommandSource> node = ((LiteralArgumentBuilder) LiteralArgumentBuilder.literal("hub")
                .executes(context -> {
                    this.execute((CommandSource) context.getSource());
                    return 1;
                }))
                .build();
        return new BrigadierCommand(node);
    }

    private void execute(CommandSource source) {
        if (!(source instanceof Player player)) {
            source.sendMessage(ServerTextUtil.component("&cOnly players can use /hub."));
            return;
        }
        this.destinationRouter.routeToLobby(player);
    }
}
