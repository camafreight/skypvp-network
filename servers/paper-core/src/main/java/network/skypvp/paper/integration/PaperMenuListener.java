package network.skypvp.paper.integration;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.service.NetworkMenuService;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

public final class PaperMenuListener implements PluginMessageListener {
   private final NetworkMenuService networkMenuService;

   public PaperMenuListener(NetworkMenuService networkMenuService) {
      this.networkMenuService = networkMenuService;
   }

   @Override
   public void onPluginMessageReceived(String channel, Player player, byte[] message) {
      if (!"skypvp:menu".equalsIgnoreCase(channel) || message == null || message.length == 0) {
         return;
      }
      ByteArrayDataInput input = ByteStreams.newDataInput(message);
      String menuKey = input.readUTF();
      this.networkMenuService.openMenuByKey(player, menuKey);
   }

   public static void register(PaperCorePlugin plugin, PaperMenuListener listener) {
      plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, "skypvp:menu", listener);
   }
}
