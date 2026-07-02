package network.skypvp.proxy.listener;

import network.skypvp.shared.ServerTextUtil;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent.ForwardResult;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;

public final class ProxySocialRequestListener {
   private static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from("skypvp:social");
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   private final ProxyServer proxyServer;
   private final Logger logger;

   public ProxySocialRequestListener(ProxyServer proxyServer, Logger logger) {
      this.proxyServer = proxyServer;
      this.logger = logger;
   }

   @Subscribe
   public void onPluginMessage(PluginMessageEvent event) {
      if (CHANNEL.equals(event.getIdentifier())) {
         if (event.getTarget() instanceof Player player) {
            event.setResult(ForwardResult.handled());
            ProxySocialRequestListener.SocialRequest request = this.decode(event.getData()).orElse(null);
            if (request == null) {
               player.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>[!] <reset><#888888>Invalid social request.<reset>"));
            } else {
               String command = this.resolveCommand(request);
               if (!this.isAllowed(command)) {
                  player.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>[!] <reset><#888888>Blocked unsafe social request.<reset>"));
               } else {
                  this.proxyServer.getCommandManager().executeAsync(player, command).exceptionally(exception -> {
                     this.logger.warn("Failed to execute social request '{}' from {}: {}", command, player.getUsername(), exception.getMessage());
                     player.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>[!] <reset><#888888>Social action failed. Try again.<reset>"));
                     return null;
                  });
               }
            }
         }
      }
   }

   private String resolveCommand(ProxySocialRequestListener.SocialRequest request) {
      String action = request.action() == null ? "" : request.action().trim().toUpperCase(Locale.ROOT);
      String command = request.command() == null ? "" : request.command().trim();
      return switch (action) {
         case "FRIEND" -> command.toLowerCase(Locale.ROOT).startsWith("friend") ? command : "friend " + command;
         case "PARTY" -> command.toLowerCase(Locale.ROOT).startsWith("party") ? command : "party " + command;
         default -> command;
      };
   }

   private boolean isAllowed(String command) {
      if (command == null) {
         return false;
      }
      String normalizedCommand = command.trim().toLowerCase(Locale.ROOT);
      if (normalizedCommand.isBlank() || normalizedCommand.contains("\n") || normalizedCommand.contains("\r")) {
         return false;
      }
      return normalizedCommand.startsWith("friend ") || normalizedCommand.equals("friend")
         || normalizedCommand.startsWith("party ") || normalizedCommand.equals("party");
   }

   private Optional<ProxySocialRequestListener.SocialRequest> decode(byte[] data) {
      try {
         Optional var3;
         try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(data))) {
            var3 = Optional.of(new ProxySocialRequestListener.SocialRequest(input.readUTF(), input.readUTF()));
         }

         return var3;
      } catch (IOException var7) {
         this.logger.warn("Failed to decode proxy social request: {}", var7.getMessage());
         return Optional.empty();
      }
   }

   private static record SocialRequest(String action, String command) {
   }
}
