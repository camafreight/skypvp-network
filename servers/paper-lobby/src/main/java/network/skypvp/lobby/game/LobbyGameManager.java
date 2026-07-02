package network.skypvp.lobby.game;

import network.skypvp.shared.ServerTextUtil;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.lobby.library.HotbarItemsLibrary;
import network.skypvp.paper.platform.ServerPlatform;
import network.skypvp.shared.NetworkServerRole;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

public class LobbyGameManager implements Listener {
   private static final ItemStack LEAVE_ITEM = new ItemStack(Material.RED_BED);
   private final JavaPlugin plugin;
   private final Map<UUID, LobbyGameType> activeGames = new ConcurrentHashMap<>();
   private final HotbarItemsLibrary hotbarLibrary;
   private final ServerPlatform scheduler;

   public LobbyGameManager(JavaPlugin plugin, HotbarItemsLibrary hotbarLibrary, ServerPlatform scheduler) {
      this.plugin = plugin;
      this.hotbarLibrary = hotbarLibrary;
      this.scheduler = scheduler;
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
   }

   public JavaPlugin getPlugin() {
      return this.plugin;
   }

   public LobbyGameType getActiveGame(UUID playerId) {
      return this.activeGames.getOrDefault(playerId, LobbyGameType.NONE);
   }

   public boolean isInGame(UUID playerId) {
      return this.getActiveGame(playerId) != LobbyGameType.NONE;
   }

   public boolean joinGame(Player player, LobbyGameType gameType) {
      if (this.isInGame(player.getUniqueId())) {
         return false;
      } else {
         this.activeGames.put(player.getUniqueId(), gameType);
         player.getInventory().clear();
         player.getActivePotionEffects().forEach(e -> player.removePotionEffect(e.getType()));
         player.getInventory().setItem(8, LEAVE_ITEM);
         return true;
      }
   }

   public void quitGame(Player player) {
      this.activeGames.remove(player.getUniqueId());
      player.getInventory().clear();
      player.getActivePotionEffects().forEach(e -> player.removePotionEffect(e.getType()));
      if (this.hotbarLibrary != null) {
         this.hotbarLibrary.apply(player, true);
      }
   }

   public void unregister(UUID playerId) {
      this.activeGames.remove(playerId);
   }

   @EventHandler
   public void onInteract(PlayerInteractEvent event) {
      if (event.getItem() != null && event.getItem().isSimilar(LEAVE_ITEM)) {
         event.setCancelled(true);
         event.getPlayer().performCommand("lobbygame leave");
      }
   }

   @EventHandler
   public void onInventoryClick(InventoryClickEvent event) {
      if (event.getCurrentItem() != null && event.getCurrentItem().isSimilar(LEAVE_ITEM)) {
         event.setCancelled(true);
      }
   }

   @EventHandler
   public void onDrop(PlayerDropItemEvent event) {
      if (event.getItemDrop().getItemStack().isSimilar(LEAVE_ITEM)) {
         event.setCancelled(true);
      }
   }

   @EventHandler
   public void onEntityDamage(EntityDamageEvent event) {
      if (event.getEntity() instanceof Player player && this.isInGame(player.getUniqueId())) {
         if (event.getCause() == DamageCause.FALL) {
            event.setCancelled(true);
         } else if (event.getCause() == DamageCause.VOID) {
            event.setCancelled(true);
            player.teleport(player.getWorld().getSpawnLocation());
         }
      }
   }

   @EventHandler
   public void onPlayerDeath(PlayerDeathEvent event) {
      Player player = event.getEntity();
      if (this.isInGame(player.getUniqueId())) {
         this.scheduler.runOnPlayer(player, () -> {
            if (player.isOnline()) {
               player.performCommand("lobbygame leave");
            }
         });
      }
   }

   static {
      ItemMeta meta = LEAVE_ITEM.getItemMeta();
      meta.displayName(ServerTextUtil.component("&cLeave Game"));
      LEAVE_ITEM.setItemMeta(meta);
   }
}
