package network.skypvp.proxy.listener;

import network.skypvp.shared.ServerTextUtil;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent.ForwardResult;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Optional;
import network.skypvp.proxy.service.ProxyDestinationRouter;
import org.slf4j.Logger;

public final class ProxyRouteRequestListener {
   private static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from("skypvp:route");
   private final ProxyDestinationRouter destinationRouter;
   private final Logger logger;

   public ProxyRouteRequestListener(ProxyDestinationRouter destinationRouter, Logger logger) {
      this.destinationRouter = destinationRouter;
      this.logger = logger;
   }

   @Subscribe
   public void onPluginMessage(PluginMessageEvent event) {
      if (CHANNEL.equals(event.getIdentifier())) {
         if (event.getTarget() instanceof Player player) {
            event.setResult(ForwardResult.handled());
            ProxyRouteRequestListener.RouteRequest request = this.decode(event.getData()).orElse(null);
            if (request == null) {
               player.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>[!] <reset><#888888>Invalid route request.<reset>"));
            } else if ("CONNECT".equalsIgnoreCase(request.action())) {
               this.destinationRouter.connectExact(player, request.destination());
            } else {
               this.destinationRouter.route(player, request.destination());
            }
         }
      }
   }

   private Optional<ProxyRouteRequestListener.RouteRequest> decode(byte[] data) {
      try {
         Optional var3;
         try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(data))) {
            var3 = Optional.of(new ProxyRouteRequestListener.RouteRequest(input.readUTF(), input.readUTF()));
         }

         return var3;
      } catch (IOException exception) {
         this.logger.warn("Failed to decode proxy route request: {}", exception.getMessage());
         return Optional.empty();
      }
   }

   private static record RouteRequest(String action, String destination) {
   }
}
