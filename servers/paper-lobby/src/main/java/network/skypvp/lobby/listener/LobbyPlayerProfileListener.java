package network.skypvp.lobby.listener;

import network.skypvp.paper.PaperCorePlugin;

import network.skypvp.lobby.library.HotbarItemsLibrary;
import network.skypvp.lobby.state.LobbyRuntimeStateRegistry;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;

public final class LobbyPlayerProfileListener implements Listener {
   private final PaperCorePlugin plugin;
   private final LobbyRuntimeStateRegistry states;
   private final Location spawn;
   private final HotbarItemsLibrary hotbarLibrary;

   public LobbyPlayerProfileListener(PaperCorePlugin plugin, LobbyRuntimeStateRegistry states, Location spawn, HotbarItemsLibrary hotbarLibrary) {
      this.plugin = plugin;
      this.states = states;
      this.spawn = spawn;
      this.hotbarLibrary = hotbarLibrary;
   }

   @EventHandler(
      priority = EventPriority.HIGHEST
   )
   public void onJoin(PlayerJoinEvent event) {
      Player p = event.getPlayer();
      if (!this.isBypass(p)) {
         p.setGameMode(GameMode.ADVENTURE);
         p.getInventory().clear();
         p.getInventory().setArmorContents(null);
         p.setExp(0.0F);
         p.setLevel(0);
         p.setFireTicks(0);
         p.setFallDistance(0.0F);
         AttributeInstance maxHealthAttr = p.getAttribute(Attribute.MAX_HEALTH);
         p.setHealth(maxHealthAttr != null ? maxHealthAttr.getValue() : 20.0);
         p.setFoodLevel(20);
         p.setSaturation(20.0F);

         for (PotionEffect effect : p.getActivePotionEffects()) {
            p.removePotionEffect(effect.getType());
         }

         p.teleportAsync(this.spawn);
         this.hotbarLibrary.apply(p, false);
      }

      this.states.refreshPlayerState(p);
   }

   private boolean isBypass(Player player) {
      return player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR;
   }

   @EventHandler(
      priority = EventPriority.MONITOR
   )
   public void onQuit(PlayerQuitEvent event) {
      this.states.removePlayer(event.getPlayer().getUniqueId());
   }
}
