package network.skypvp.paper.listener;

import network.skypvp.shared.ServerTextUtil;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.service.PlayerLocaleService;
import network.skypvp.paper.service.PlayerSessionService;
import network.skypvp.paper.service.PlayerSocialSettingsService;
import network.skypvp.paper.service.RankService;
import network.skypvp.paper.service.ScoreboardService;
import network.skypvp.paper.service.VanishService;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result;

public final class NetworkJoinListener implements Listener {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   private final PaperCorePlugin plugin;
   private final PlayerSessionService sessionService;
   private final RankService rankService;
   private final ScoreboardService scoreboardService;
   private final PlayerSocialSettingsService socialSettingsService;
   private final PlayerLocaleService playerLocaleService;

   public NetworkJoinListener(
      PaperCorePlugin plugin,
      PlayerSessionService sessionService,
      RankService rankService,
      ScoreboardService scoreboardService,
      PlayerSocialSettingsService socialSettingsService,
      PlayerLocaleService playerLocaleService
   ) {
      this.plugin = plugin;
      this.sessionService = sessionService;
      this.rankService = rankService;
      this.scoreboardService = scoreboardService;
      this.socialSettingsService = socialSettingsService;
      this.playerLocaleService = playerLocaleService;
   }

   @EventHandler
   public void onPreLogin(AsyncPlayerPreLoginEvent event) {
      if (this.plugin.gracefulDrainService() != null && this.plugin.gracefulDrainService().isDraining()) {
         event.disallow(Result.KICK_OTHER, ServerTextUtil.component("&cServer is restarting/draining. Please join another server."));
      } else {
         PlayerProfile profile = event.getPlayerProfile();
         boolean hasSkin = false;

         for (ProfileProperty prop : profile.getProperties()) {
            if ("textures".equals(prop.getName())) {
               hasSkin = true;
               break;
            }
         }

         if (!hasSkin) {
            try {
               PlayerProfile skinLookup = Bukkit.createProfile(event.getName());
               skinLookup.complete(true);

               for (ProfileProperty propx : skinLookup.getProperties()) {
                  if ("textures".equals(propx.getName())) {
                     profile.setProperty(propx);
                     break;
                  }
               }
            } catch (Exception var7) {
            }
         }
      }
   }

   @EventHandler
   public void onPlayerJoin(PlayerJoinEvent event) {
      String serverId = this.plugin.serverId();
      String username = event.getPlayer().getName();
      UUID playerId = event.getPlayer().getUniqueId();
      event.joinMessage(null);
      if (this.playerLocaleService != null) {
         this.playerLocaleService.capture(event.getPlayer());
      }
      boolean isVanished = VanishService.VANISHED.contains(playerId);
      if (isVanished) {
         Component staffAlert = ServerTextUtil.miniMessageComponent("<#FF5555>[VANISH] <reset><#FFFFFF>" + username + "<reset><#888888> connected (vanished).<reset>");
         this.plugin.getServer().getOnlinePlayers().stream().filter(p -> p.hasPermission("skypvp.staff")).forEach(p -> p.sendMessage(staffAlert));
      }

      if (this.scoreboardService != null) {
         this.scoreboardService.queueSetup(event.getPlayer());
      }

      if (this.plugin.bossBarService() != null) {
         this.plugin.bossBarService().showForPlayer(event.getPlayer());
      }

      if (this.plugin.gameModeBehaviorService().booleanValue("core.hotbar.enabled", false) && this.plugin.coreHotbarService() != null) {
         this.plugin.coreHotbarService().ensureNetworkItems(event.getPlayer());
      }

      if (this.plugin.playerInventoryManager() != null) {
         this.plugin.playerInventoryManager().prepareJoinedPlayerInventory(event.getPlayer());
      }

      this.plugin.platformScheduler().runAsync( () -> {
         if (this.sessionService != null) {
            this.sessionService.onPlayerJoin(playerId, username, serverId);
         }

         if (this.rankService != null) {
            this.rankService.loadAndCache(playerId, username);
         }

         if (this.socialSettingsService != null) {
            this.socialSettingsService.preload(playerId);
         }

      });
   }
}
