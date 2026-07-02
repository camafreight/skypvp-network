package network.skypvp.lobby.listener;

import network.skypvp.shared.ServerTextUtil;

import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.lobby.game.LobbyGameManager;
import network.skypvp.lobby.game.LobbyGameType;
import network.skypvp.paper.service.BuildProtectionSupport;

import network.skypvp.lobby.state.LobbyRuntimeStateRegistry;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.weather.WeatherChangeEvent;

public final class LobbyWorldGuardListener implements Listener {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   private final LobbyRuntimeStateRegistry lobbyStates;
   private final Location safeSpawn;
   private final int voidY;
   private final boolean hungerEnabled;
   private final boolean damageEnabled;
   private final boolean inventoryLockEnabled;
   private final LobbyGameManager gameManager;

   public LobbyWorldGuardListener(
      LobbyRuntimeStateRegistry lobbyStates,
      Location safeSpawn,
      int voidY,
      boolean hungerEnabled,
      boolean damageEnabled,
      boolean inventoryLockEnabled,
      LobbyGameManager gameManager
   ) {
      this.lobbyStates = lobbyStates;
      this.safeSpawn = safeSpawn;
      this.voidY = voidY;
      this.hungerEnabled = hungerEnabled;
      this.damageEnabled = damageEnabled;
      this.inventoryLockEnabled = inventoryLockEnabled;
      this.gameManager = gameManager;
   }

   private boolean isBypass(Player p) {
      return p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR;
   }

   @EventHandler(
      priority = EventPriority.HIGHEST,
      ignoreCancelled = true
   )
   public void onBreak(BlockBreakEvent event) {
      if (!this.isBypass(event.getPlayer())) {
         event.setCancelled(true);
      }
   }

   @EventHandler(
      priority = EventPriority.HIGHEST,
      ignoreCancelled = true
   )
   public void onPlace(BlockPlaceEvent event) {
      if (!this.isBypass(event.getPlayer())) {
         event.setCancelled(true);
      }
   }

   @EventHandler(
      priority = EventPriority.HIGHEST,
      ignoreCancelled = true
   )
   public void onEntityChangeBlock(EntityChangeBlockEvent event) {
      if (event.getEntity() instanceof Player player) {
         if (this.isBypass(player)) {
            return;
         }
      }
      event.setCancelled(true);
   }

   @EventHandler(
      priority = EventPriority.HIGHEST,
      ignoreCancelled = true
   )
   public void onBlockPhysics(BlockPhysicsEvent event) {
      if (BuildProtectionSupport.isProtectedLandscapeBlock(event.getBlock())) {
         event.setCancelled(true);
      }
   }

   @EventHandler(
      priority = EventPriority.HIGHEST,
      ignoreCancelled = true
   )
   public void onBlockFade(BlockFadeEvent event) {
      event.setCancelled(true);
   }

   @EventHandler(
      priority = EventPriority.HIGHEST,
      ignoreCancelled = true
   )
   public void onBlockForm(BlockFormEvent event) {
      event.setCancelled(true);
   }

   @EventHandler(
      priority = EventPriority.HIGHEST,
      ignoreCancelled = true
   )
   public void onLeavesDecay(LeavesDecayEvent event) {
      event.setCancelled(true);
   }

   @EventHandler(
      priority = EventPriority.HIGHEST,
      ignoreCancelled = true
   )
   public void onDrop(PlayerDropItemEvent event) {
      if (!this.isBypass(event.getPlayer())) {
         event.setCancelled(true);
      }
   }

   @EventHandler(
      priority = EventPriority.HIGHEST,
      ignoreCancelled = true
   )
   public void onPickup(EntityPickupItemEvent event) {
      if (event.getEntity() instanceof Player p && !this.isBypass(p)) {
         event.setCancelled(true);
      }
   }

   @EventHandler(
      priority = EventPriority.HIGHEST,
      ignoreCancelled = true
   )
   public void onFood(FoodLevelChangeEvent event) {
      if (event.getEntity() instanceof Player p) {
         if (!this.hungerEnabled) {
            if (!this.isBypass(p)) {
               event.setCancelled(true);
               p.setFoodLevel(20);
               p.setSaturation(20.0F);
            }
         }
      }
   }

   @EventHandler(
      priority = EventPriority.HIGHEST,
      ignoreCancelled = true
   )
   public void onDamage(EntityDamageEvent event) {
      if (event.getEntity() instanceof Player p) {
         LobbyGameType activeGame = this.gameManager.getActiveGame(p.getUniqueId());
         if (activeGame != LobbyGameType.DUELS && activeGame != LobbyGameType.TAG && activeGame != LobbyGameType.HIDE_AND_SEEK) {
            if (!this.damageEnabled) {
               if (!this.isBypass(p)) {
                  event.setCancelled(true);
                  AttributeInstance maxHealthAttr = p.getAttribute(Attribute.MAX_HEALTH);
                  double maxHealth = maxHealthAttr != null ? maxHealthAttr.getValue() : 20.0;
                  if (p.getHealth() < maxHealth) {
                     p.setHealth(maxHealth);
                  }
               }
            }
         }
      }
   }

   @EventHandler(
      priority = EventPriority.NORMAL,
      ignoreCancelled = true
   )
   public void onMove(PlayerMoveEvent event) {
      Player player = event.getPlayer();
      this.lobbyStates.refreshPlayerState(player);
      if (!this.gameManager.isInGame(player.getUniqueId())) {
         if (player.getLocation().getY() <= (double)this.voidY && !this.isBypass(player)) {
            player.teleportAsync(this.safeSpawn);
            player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>You were returned to spawn.<reset>"));
         }
      }
   }

   @EventHandler(
      priority = EventPriority.HIGHEST,
      ignoreCancelled = true
   )
   public void onWeather(WeatherChangeEvent event) {
      if (event.toWeatherState()) {
         event.setCancelled(true);
      }
   }

   @EventHandler(
      priority = EventPriority.HIGHEST,
      ignoreCancelled = true
   )
   public void onInventoryClick(InventoryClickEvent event) {
      if (!(event.getWhoClicked() instanceof Player p) || this.isBypass(p) || !this.inventoryLockEnabled) {
         return;
      }

      if (event.getClick() == ClickType.NUMBER_KEY || event.isShiftClick()) {
         event.setCancelled(true);
      } else if (event.getClickedInventory() != null) {
         if (!event.getClickedInventory().equals(event.getView().getTopInventory())) {
            event.setCancelled(true);
         }
      }
   }

   @EventHandler(
      priority = EventPriority.HIGHEST,
      ignoreCancelled = true
   )
   public void onInventoryDrag(InventoryDragEvent event) {
      if (event.getWhoClicked() instanceof Player p && !this.isBypass(p) && this.inventoryLockEnabled) {
         event.setCancelled(true);
      }
   }

   @EventHandler(
      priority = EventPriority.HIGHEST,
      ignoreCancelled = true
   )
   public void onSwapHands(PlayerSwapHandItemsEvent event) {
      if (!this.isBypass(event.getPlayer())) {
         event.setCancelled(true);
      }
   }

   @EventHandler(
      priority = EventPriority.HIGHEST,
      ignoreCancelled = true
   )
   public void onMobSpawn(CreatureSpawnEvent event) {
      if (event.getSpawnReason() != SpawnReason.CUSTOM) {
         event.setCancelled(true);
      }
   }
}
