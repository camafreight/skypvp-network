package network.skypvp.lobby.game;

import network.skypvp.shared.ServerTextUtil;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public class TagModule implements Listener {
   private final JavaPlugin plugin;
   private final LobbyGameManager gameManager;
   private final Set<UUID> tagPlayers = ConcurrentHashMap.newKeySet();
   private UUID currentIt = null;
   private final Map<UUID, Long> doubleJumpCooldowns = new ConcurrentHashMap<>();
   private final Map<UUID, Long> sprintBoostCooldowns = new ConcurrentHashMap<>();

   public TagModule(JavaPlugin plugin, LobbyGameManager gameManager) {
      this.plugin = plugin;
      this.gameManager = gameManager;
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
   }

   public void toggleTag(Player player) {
      UUID id = player.getUniqueId();
      if (this.tagPlayers.contains(id)) {
         this.leaveTag(player);
      } else {
         this.joinTag(player);
      }
   }

   private void joinTag(Player player) {
      if (!this.gameManager.joinGame(player, LobbyGameType.TAG)) {
         player.sendMessage(ServerTextUtil.component("&cYou are already in another game!"));
      } else {
         this.tagPlayers.add(player.getUniqueId());
         player.setHealth(20.0);
         player.setFoodLevel(20);
         player.sendMessage(ServerTextUtil.component("&aYou joined Knockback Tag!"));
         if (this.currentIt == null || this.plugin.getServer().getPlayer(this.currentIt) == null) {
            this.makeIt(player);
         }
      }
   }

   public void leaveTag(Player player) {
      if (this.tagPlayers.contains(player.getUniqueId())) {
         this.tagPlayers.remove(player.getUniqueId());
         this.gameManager.quitGame(player);
         player.sendMessage(ServerTextUtil.component("&eYou left Knockback Tag."));
         if (player.getUniqueId().equals(this.currentIt)) {
            this.currentIt = null;
            this.assignRandomIt();
         }

         this.doubleJumpCooldowns.remove(player.getUniqueId());
         this.sprintBoostCooldowns.remove(player.getUniqueId());
         player.setAllowFlight(false);
      }
   }

   private void assignRandomIt() {
      if (!this.tagPlayers.isEmpty()) {
         for (UUID id : this.tagPlayers) {
            Player p = this.plugin.getServer().getPlayer(id);
            if (p != null && p.isOnline()) {
               this.makeIt(p);
               return;
            }
         }
      }
   }

   private void makeIt(Player player) {
      if (this.currentIt != null) {
         Player oldIt = this.plugin.getServer().getPlayer(this.currentIt);
         if (oldIt != null && oldIt.isOnline()) {
            oldIt.getInventory().remove(Material.STICK);
            oldIt.sendMessage(ServerTextUtil.component("&aYou are no longer IT!"));
         }
      }

      this.currentIt = player.getUniqueId();
      ItemStack stick = new ItemStack(Material.STICK);
      ItemMeta meta = stick.getItemMeta();
      meta.displayName(ServerTextUtil.component("&cThe IT Stick"));
      meta.addEnchant(Enchantment.KNOCKBACK, 30, true);
      meta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ENCHANTS});
      stick.setItemMeta(meta);
      player.getInventory().setItem(0, stick);
      player.getInventory().setHeldItemSlot(0);
      player.sendMessage(ServerTextUtil.component("&cYou are now IT! Hit someone else!"));

      for (UUID id : this.tagPlayers) {
         Player p = this.plugin.getServer().getPlayer(id);
         if (p != null && p.isOnline() && !p.equals(player)) {
            p.sendMessage(ServerTextUtil.component("&6" + player.getName() + " is now IT!"));
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5F, 1.5F);
         }
      }
   }

   @EventHandler(
      priority = EventPriority.HIGH
   )
   public void onDamage(EntityDamageByEntityEvent event) {
      if (event.getEntity() instanceof Player victim && event.getDamager() instanceof Player attacker) {
         if (this.gameManager.getActiveGame(attacker.getUniqueId()) == LobbyGameType.TAG
            && this.gameManager.getActiveGame(victim.getUniqueId()) == LobbyGameType.TAG) {
            event.setCancelled(false);
            event.setDamage(0.0);
            if (attacker.getUniqueId().equals(this.currentIt)) {
               this.makeIt(victim);
            }
         } else if(this.gameManager.getActiveGame(attacker.getUniqueId()) == LobbyGameType.TAG && this.gameManager.getActiveGame(victim.getUniqueId()) != LobbyGameType.TAG) {
            event.setCancelled(true);
         } else if(this.gameManager.getActiveGame(attacker.getUniqueId()) != LobbyGameType.TAG && this.gameManager.getActiveGame(victim.getUniqueId()) == LobbyGameType.TAG) {
            event.setCancelled(true);
         }
      }
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      if (this.tagPlayers.contains(event.getPlayer().getUniqueId())) {
         this.leaveTag(event.getPlayer());
      }
   }

   @EventHandler
   public void onPlayerToggleFlight(PlayerToggleFlightEvent event) {
      Player player = event.getPlayer();
      if (this.tagPlayers.contains(player.getUniqueId())) {
         if (player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR) {
            event.setCancelled(true);
            player.setAllowFlight(false);
            player.setFlying(false);
            long now = System.currentTimeMillis();
            long lastUse = this.doubleJumpCooldowns.getOrDefault(player.getUniqueId(), 0L);
            if (now - lastUse >= 3000L) {
               this.doubleJumpCooldowns.put(player.getUniqueId(), now);
               player.setVelocity(player.getLocation().getDirection().multiply(1.5).setY(1.0));
               player.playSound(player.getLocation(), Sound.ENTITY_BAT_TAKEOFF, 1.0F, 1.0F);
               player.spawnParticle(Particle.CLOUD, player.getLocation(), 20, 0.5, 0.1, 0.5, 0.1);
            }
         }
      }
   }

   @EventHandler
   public void onPlayerMove(PlayerMoveEvent event) {
      Player player = event.getPlayer();
      if (this.tagPlayers.contains(player.getUniqueId())) {
         if (player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR) {
            if (player.isOnGround()) {
               player.setAllowFlight(true);
            }
         }
      }
   }

   @EventHandler
   public void onPlayerToggleSprint(PlayerToggleSprintEvent event) {
      Player player = event.getPlayer();
      if (event.isSprinting() && this.tagPlayers.contains(player.getUniqueId())) {
         long now = System.currentTimeMillis();
         long lastUse = this.sprintBoostCooldowns.getOrDefault(player.getUniqueId(), 0L);
         if (now - lastUse >= 5000L) {
            this.sprintBoostCooldowns.put(player.getUniqueId(), now);
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20, 2));
            player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 1.0F, 1.5F);
            player.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, player.getLocation(), 20, 0.5, 0.2, 0.5, 0.05);
         }
      }
   }
}
