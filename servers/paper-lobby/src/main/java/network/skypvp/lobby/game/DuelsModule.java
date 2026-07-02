package network.skypvp.lobby.game;

import network.skypvp.shared.ServerTextUtil;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.paper.platform.ServerPlatform;
import network.skypvp.paper.repository.PlayerStatsRepository;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public class DuelsModule implements Listener {
   private final JavaPlugin plugin;
   private final LobbyGameManager gameManager;
   private final PlayerStatsRepository stats;
   private final ServerPlatform scheduler;
   private final Map<UUID, Map<UUID, Long>> pendingChallenges = new ConcurrentHashMap<>();
   private final Map<UUID, UUID> activeDuels = new ConcurrentHashMap<>();
   private static final long CHALLENGE_EXPIRY_MS = 20000L;

   public DuelsModule(JavaPlugin plugin, LobbyGameManager gameManager, PlayerStatsRepository stats, ServerPlatform scheduler) {
      this.plugin = plugin;
      this.gameManager = gameManager;
      this.stats = stats;
      this.scheduler = scheduler;
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
      scheduler.runSyncTimer(this::cleanupChallenges, 20L, 100L);
   }

   private void cleanupChallenges() {
      long now = System.currentTimeMillis();
      this.pendingChallenges.values().removeIf(targetMap -> {
         targetMap.values().removeIf(time -> now - time > 20000L);
         return targetMap.isEmpty();
      });
   }

   @EventHandler(
      priority = EventPriority.HIGH,
      ignoreCancelled = true
   )
   public void onInteract(PlayerInteractEntityEvent event) {
      if (event.getRightClicked() instanceof Player target) {
         this.handleChallengeAttempt(event.getPlayer(), target);
      }
   }

   @EventHandler(
      priority = EventPriority.HIGH
   )
   public void onDamageAttempt(EntityDamageByEntityEvent event) {
      if (!(event.getEntity() instanceof Player victim) || !(event.getDamager() instanceof Player attacker)) {
         return;
      }

      if (this.gameManager.getActiveGame(attacker.getUniqueId()) == LobbyGameType.DUELS
         && this.gameManager.getActiveGame(victim.getUniqueId()) == LobbyGameType.DUELS
         && this.activeDuels.getOrDefault(attacker.getUniqueId(), UUID.randomUUID()).equals(victim.getUniqueId())) {
         event.setCancelled(false);
         if (victim.getHealth() - event.getFinalDamage() <= 0.0) {
            event.setCancelled(true);
            attacker.sendMessage(ServerTextUtil.component("&a" + "You won the duel against " + victim.getName() + "!"));
            victim.sendMessage(ServerTextUtil.component("&c" + "You lost the duel against " + attacker.getName() + "."));
            this.scheduler.runAsync(() -> this.stats.incrementDuelWins(attacker.getUniqueId()));
            this.scheduler.runAsync(() -> this.stats.incrementDuelLosses(victim.getUniqueId()));
            AttributeInstance healthAttr = victim.getAttribute(Attribute.MAX_HEALTH);
            victim.setHealth(healthAttr != null ? healthAttr.getValue() : 20.0);
            this.activeDuels.remove(attacker.getUniqueId());
            this.activeDuels.remove(victim.getUniqueId());
            this.gameManager.quitGame(attacker);
            this.gameManager.quitGame(victim);
         }
      } else {
         this.handleChallengeAttempt(attacker, victim);
      }
   }

   private void handleChallengeAttempt(Player attacker, Player victim) {
      if (!this.gameManager.isInGame(attacker.getUniqueId()) && !this.gameManager.isInGame(victim.getUniqueId())) {
         UUID attackerId = attacker.getUniqueId();
         UUID victimId = victim.getUniqueId();
         Map<UUID, Long> victimChallenges = this.pendingChallenges.get(victimId);
         if (victimChallenges != null && victimChallenges.containsKey(attackerId)) {
            victimChallenges.remove(attackerId);
            this.pendingChallenges.remove(attackerId);
            this.pendingChallenges.remove(victimId);
            this.startDuel(attacker, victim);
         } else {
            Map<UUID, Long> attackerMap = this.pendingChallenges.computeIfAbsent(attackerId, k -> new ConcurrentHashMap<>());
            if (attackerMap.containsKey(victimId)) {
               long timeSince = System.currentTimeMillis() - attackerMap.get(victimId);
               if (timeSince < 5000L) {
                  return;
               }
            }

            attackerMap.put(victimId, System.currentTimeMillis());
            attacker.sendMessage(ServerTextUtil.component("&e" + "You challenged " + victim.getName() + " to a duel!"));
            victim.sendMessage(ServerTextUtil.component("&6" + attacker.getName() + " has challenged you to a duel! Click them back to accept."));
         }
      }
   }

   private void startDuel(Player p1, Player p2) {
      if (this.gameManager.joinGame(p1, LobbyGameType.DUELS) && this.gameManager.joinGame(p2, LobbyGameType.DUELS)) {
         this.activeDuels.put(p1.getUniqueId(), p2.getUniqueId());
         this.activeDuels.put(p2.getUniqueId(), p1.getUniqueId());
         Component startMsg = ServerTextUtil.component("&aDuel started! PvP is now enabled between you two.");
         p1.sendMessage(startMsg);
         p2.sendMessage(startMsg);
         ItemStack sword = new ItemStack(Material.IRON_SWORD);
         p1.getInventory().setItem(0, sword);
         p2.getInventory().setItem(0, sword);
         p1.setHealth(20.0);
         p2.setHealth(20.0);
         p1.setFoodLevel(20);
         p2.setFoodLevel(20);
      }
   }

   public void endDuel(Player player) {
      this.pendingChallenges.remove(player.getUniqueId());

      for (Map<UUID, Long> targets : this.pendingChallenges.values()) {
         targets.remove(player.getUniqueId());
      }

      UUID opponentId = this.activeDuels.remove(player.getUniqueId());
      if (opponentId != null) {
         this.activeDuels.remove(opponentId);
         Player opponent = this.plugin.getServer().getPlayer(opponentId);
         if (opponent != null && opponent.isOnline()) {
            this.gameManager.quitGame(opponent);
            opponent.sendMessage(ServerTextUtil.component("&cYour opponent left the game. Duel ended."));
         }
      }

      if (this.gameManager.getActiveGame(player.getUniqueId()) == LobbyGameType.DUELS) {
         this.gameManager.quitGame(player);
         player.sendMessage(ServerTextUtil.component("&eYou left the duel."));
      }
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      this.endDuel(event.getPlayer());
   }

   @EventHandler
   public void onRegainHealth(EntityRegainHealthEvent event) {
      if (event.getEntity() instanceof Player player && this.activeDuels.containsKey(player.getUniqueId())) {
         event.setCancelled(true);
      }
   }
}
