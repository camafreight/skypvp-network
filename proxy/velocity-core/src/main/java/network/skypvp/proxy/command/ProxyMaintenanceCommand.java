package network.skypvp.proxy.command;

import network.skypvp.shared.ServerTextUtil;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.proxy.registry.MaintenanceRegistry;
import network.skypvp.proxy.registry.NetworkStateRegistry;

public final class ProxyMaintenanceCommand {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   private final ProxyServer proxyServer;
   private final MaintenanceRegistry maintenanceRegistry;
   private final NetworkStateRegistry stateRegistry;

   public ProxyMaintenanceCommand(ProxyServer proxyServer, MaintenanceRegistry maintenanceRegistry, NetworkStateRegistry stateRegistry) {
      this.proxyServer = proxyServer;
      this.maintenanceRegistry = maintenanceRegistry;
      this.stateRegistry = stateRegistry;
   }

   public BrigadierCommand build() {
      LiteralCommandNode<CommandSource> node = ((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)LiteralArgumentBuilder.literal(
                     "maintenance"
                  )
                  .then(LiteralArgumentBuilder.literal("on").executes(ctx -> {
                     this.execute((CommandSource)ctx.getSource(), true);
                     return 1;
                  })))
               .then(LiteralArgumentBuilder.literal("off").executes(ctx -> {
                  this.execute((CommandSource)ctx.getSource(), false);
                  return 1;
               })))
            .executes(
               ctx -> {
                  boolean current = this.maintenanceRegistry.isEnabled();
                  String stateColor = current ? "#FF5555" : "#55FF55";
                  String stateLabel = current ? "ON" : "OFF";
                  ((CommandSource)ctx.getSource())
                     .sendMessage(
                        ServerTextUtil.miniMessageComponent(
                           "<#888888>Maintenance mode is currently <reset><"
                              + stateColor
                              + "><bold>"
                              + stateLabel
                              + "</bold><reset><#888888>. Usage: /maintenance <on|off><reset>"
                        )
                     );
                  return 1;
               }
            ))
         .build();
      return new BrigadierCommand(node);
   }

   private void execute(CommandSource source, boolean enable) {
      if (source instanceof Player) {
         source.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>[!] <reset><#888888>This command is console-only.<reset>"));
      } else {
         this.maintenanceRegistry.setEnabled(enable);
         String state = enable ? "ENABLED" : "DISABLED";
         String stateColor = enable ? "#FF5555" : "#55FF55";
         Component announcement = ServerTextUtil.miniMessageComponent(
            "<#FF5555>[STAFF] <reset><gradient:#FFB300:#FF6F00>Maintenance mode<reset> <" + stateColor + "><bold>" + state + "</bold><reset>"
         );
         this.proxyServer.getAllPlayers().forEach(player -> {
            String rankKey = this.stateRegistry.getPlayerRankKey(player.getUniqueId());
            if ("staff".equals(rankKey) || "admin".equals(rankKey) || "owner".equals(rankKey) || player.hasPermission("skypvp.admin.rank")) {
               player.sendMessage(announcement);
            }
         });
         source.sendMessage(announcement);
      }
   }
}
