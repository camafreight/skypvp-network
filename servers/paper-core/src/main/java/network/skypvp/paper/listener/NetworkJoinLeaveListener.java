package network.skypvp.paper.listener;

import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.repository.FriendGraphRepository;
import network.skypvp.paper.service.RankService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class NetworkJoinLeaveListener implements Listener {
   private final PaperCorePlugin corePlugin;
   private final FriendGraphRepository friendGraphRepository;
   private final RankService rankService;

   public NetworkJoinLeaveListener(PaperCorePlugin corePlugin) {
      this.corePlugin = corePlugin;
      this.friendGraphRepository = corePlugin.friendGraphRepository();
      this.rankService = corePlugin.rankService();
   }

   @EventHandler(
      priority = EventPriority.NORMAL
   )
   public void onJoin(PlayerJoinEvent event) {
      event.joinMessage(null);
   }

   @EventHandler(
      priority = EventPriority.NORMAL
   )
   public void onQuit(PlayerQuitEvent event) {
      event.quitMessage(null);
   }
}
