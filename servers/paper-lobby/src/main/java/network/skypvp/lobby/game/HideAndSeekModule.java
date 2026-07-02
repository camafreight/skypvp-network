package network.skypvp.lobby.game;

import network.skypvp.shared.ServerTextUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.paper.platform.ServerPlatform;
import network.skypvp.paper.platform.PlatformTask;
import network.skypvp.paper.repository.PlayerStatsRepository;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.scoreboard.Team.Option;
import org.bukkit.scoreboard.Team.OptionStatus;

public class HideAndSeekModule implements Listener {
   private final JavaPlugin plugin;
   private final LobbyGameManager gameManager;
   private final PlayerStatsRepository stats;
   private final ServerPlatform scheduler;

   private final Map<UUID, HideAndSeekModule.Role> participants = new ConcurrentHashMap<>();
   private HideAndSeekModule.GameState state = HideAndSeekModule.GameState.WAITING;
   private PlatformTask countdownTask = null;
   private int countdownSeconds = 15;
   private PlatformTask endingTask = null;
   private UUID currentSeeker = null;
   private static final String TEAM_NAME = "hns_hidden";
   private Team hiddenTeam;

   public HideAndSeekModule(JavaPlugin plugin, LobbyGameManager gameManager, PlayerStatsRepository stats, ServerPlatform scheduler) {
      this.plugin = plugin;
      this.gameManager = gameManager;
      this.stats = stats;
      this.scheduler = scheduler;
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
      scheduler.runGlobalScoreboard(() -> {
         Scoreboard scoreboard = plugin.getServer().getScoreboardManager().getMainScoreboard();
         this.hiddenTeam = scoreboard.getTeam("hns_hidden");
         if (this.hiddenTeam == null) {
            this.hiddenTeam = scoreboard.registerNewTeam("hns_hidden");
         }

         this.hiddenTeam.setOption(Option.NAME_TAG_VISIBILITY, OptionStatus.NEVER);
      });
   }

   public void joinGame(Player player) {
      if (this.gameManager.isInGame(player.getUniqueId()) && this.participants.get(player.getUniqueId()) == null) {
         player.sendMessage(ServerTextUtil.component("&cYou are already inside another game!"));
      } else {
         if (this.state == HideAndSeekModule.GameState.WAITING) {
            this.participants.put(player.getUniqueId(), HideAndSeekModule.Role.HIDER);
            this.gameManager.joinGame(player, LobbyGameType.HIDE_AND_SEEK);
            this.broadcast(ServerTextUtil.component("&e" + player.getName() + " joined Hide & Seek waiting queue! (" + this.participants.size() + ")"));
            this.checkStart();
         } else {
            this.participants.put(player.getUniqueId(), HideAndSeekModule.Role.SPECTATOR);
            this.gameManager.joinGame(player, LobbyGameType.HIDE_AND_SEEK);
            player.sendMessage(ServerTextUtil.component("&eGame already in progress! You joined as a spectator."));
            player.setAllowFlight(true);
            player.setFlying(true);
         }

         this.updateVisibility();
      }
   }

   public void leaveGame(Player player) {
      if (this.participants.containsKey(player.getUniqueId())) {
         this.participants.remove(player.getUniqueId());
         this.gameManager.quitGame(player);
         if (this.hiddenTeam.hasEntry(player.getName())) {
            this.hiddenTeam.removeEntry(player.getName());
         }

         player.removePotionEffect(PotionEffectType.BLINDNESS);
         player.setAllowFlight(false);
         player.setFlying(false);

         for (Player online : this.plugin.getServer().getOnlinePlayers()) {
            player.showPlayer(this.plugin, online);
            online.showPlayer(this.plugin, player);
         }

         player.sendMessage(ServerTextUtil.component("&eYou left Hide & Seek."));
         this.updateVisibility();
         this.checkEndConditions();
      }
   }

   private void checkStart() {
      if (this.state == HideAndSeekModule.GameState.WAITING && this.participants.size() >= 2 && this.countdownTask == null) {
         this.countdownSeconds = 10;
         this.broadcast(ServerTextUtil.component("&a" + "Hide & Seek starting in " + this.countdownSeconds + " seconds!"));
         this.countdownTask = this.scheduler.runSyncTimer(() -> {
            this.countdownSeconds--;
            if (this.countdownSeconds <= 0) {
               this.countdownTask.cancel();
               this.countdownTask = null;
               this.startGame();
            } else if (this.countdownSeconds <= 5) {
               this.broadcast(ServerTextUtil.component("&e" + "Starting in " + this.countdownSeconds + "..."));
            }
         }, 20L, 20L);
      }
   }

   private void startGame() {
      if (this.participants.size() < 2) {
         this.broadcast(ServerTextUtil.component("&cNot enough players to start Hide & Seek."));
      } else {
         this.state = HideAndSeekModule.GameState.HIDING;
         List<UUID> players = new ArrayList<>(this.participants.keySet());
         this.currentSeeker = players.get((int)(Math.random() * (double)players.size()));

         for (UUID id : this.participants.keySet()) {
            Player p = this.plugin.getServer().getPlayer(id);
            if (p != null) {
               if (id.equals(this.currentSeeker)) {
                  this.participants.put(id, HideAndSeekModule.Role.SEEKER);
                  p.sendMessage(ServerTextUtil.component("&cYou are the Seeker! Wait 30 seconds while they hide..."));
                  p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 600, 255, false, false, false));
                  p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 600, 255, false, false, false));
                  p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 600, 250, false, false, false));
               } else {
                  this.participants.put(id, HideAndSeekModule.Role.HIDER);
                  p.sendMessage(ServerTextUtil.component("&aYou are a Hider! You have 30 seconds to hide!"));
                  if (!this.hiddenTeam.hasEntry(p.getName())) {
                     this.hiddenTeam.addEntry(p.getName());
                  }
               }
            }
         }

         this.updateVisibility();
         this.scheduler.runSyncLater(this::startSeeking, 600L);
      }
   }

   private void startSeeking() {
      if (this.state == HideAndSeekModule.GameState.HIDING) {
         this.state = HideAndSeekModule.GameState.SEEKING;
         this.broadcast(Component.text("Ready or not, here comes the seeker!", NamedTextColor.RED));
         Player seeker = this.getSeekerPlayer();
         if (seeker != null) {
            seeker.removePotionEffect(PotionEffectType.BLINDNESS);
            seeker.removePotionEffect(PotionEffectType.SLOWNESS);
            seeker.removePotionEffect(PotionEffectType.JUMP_BOOST);
            seeker.sendMessage(ServerTextUtil.component("&cGO SEEK!"));
         }

         this.updateVisibility();
         this.endingTask = this.scheduler.runSyncLater(() -> {
            if (this.state == HideAndSeekModule.GameState.SEEKING) {
               this.broadcast(ServerTextUtil.component("&aTime is up! The Hiders win!"));

               for (Entry<UUID, HideAndSeekModule.Role> entry : this.participants.entrySet()) {
                  if (entry.getValue() == HideAndSeekModule.Role.HIDER) {
                     this.scheduler.runAsync(() -> this.stats.incrementHnsHiderWins(entry.getKey()));
                  }
               }

               this.endGame();
            }
         }, 3600L);
      }
   }

   @EventHandler(
      priority = EventPriority.HIGH
   )
   public void onMove(PlayerMoveEvent event) {
      if (this.state == HideAndSeekModule.GameState.HIDING
         && this.participants.getOrDefault(event.getPlayer().getUniqueId(), null) == HideAndSeekModule.Role.SEEKER) {
         Location from = event.getFrom();
         Location to = event.getTo();
         if (to != null
            && (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ() || from.getYaw() != to.getYaw() || to.getPitch() != 90.0F)) {
            Location lock = from.clone();
            lock.setPitch(90.0F);
            event.setTo(lock);
         }
      }
   }

   private void checkEndConditions() {
      if (this.state == HideAndSeekModule.GameState.WAITING) {
         if (this.participants.size() < 2 && this.countdownTask != null) {
            this.countdownTask.cancel();
            this.countdownTask = null;
            this.broadcast(ServerTextUtil.component("&cCountdown cancelled. Need more players."));
         }
      } else {
         boolean hasSeeker = false;
         boolean hasHiders = false;

         for (Entry<UUID, HideAndSeekModule.Role> entry : this.participants.entrySet()) {
            if (entry.getValue() == HideAndSeekModule.Role.SEEKER) {
               hasSeeker = true;
            }

            if (entry.getValue() == HideAndSeekModule.Role.HIDER) {
               hasHiders = true;
            }
         }

         if (!hasSeeker) {
            this.broadcast(ServerTextUtil.component("&aThe Seeker left! The Hiders win!"));

            for (Entry<UUID, HideAndSeekModule.Role> entry : this.participants.entrySet()) {
               if (entry.getValue() == HideAndSeekModule.Role.HIDER) {
                     this.scheduler.runAsync(() -> this.stats.incrementHnsHiderWins(entry.getKey()));
               }
            }

            this.endGame();
         } else if (!hasHiders) {
            this.broadcast(ServerTextUtil.component("&aAll Hiders were found! The Seeker wins!"));
            if (this.currentSeeker != null) {
               this.scheduler.runAsync(() -> this.stats.incrementHnsSeekerWins(this.currentSeeker));
            }

            this.endGame();
         }
      }
   }

   private void endGame() {
      this.state = HideAndSeekModule.GameState.WAITING;
      if (this.endingTask != null) {
         this.endingTask.cancel();
         this.endingTask = null;
      }

      for (UUID id : new ArrayList<>(this.participants.keySet())) {
         Player p = this.plugin.getServer().getPlayer(id);
         if (p != null) {
            this.leaveGame(p);
         }
      }

      this.currentSeeker = null;
   }

   private void broadcast(Component msg) {
      for (UUID id : this.participants.keySet()) {
         Player p = this.plugin.getServer().getPlayer(id);
         if (p != null) {
            p.sendMessage(msg);
         }
      }
   }

   private Player getSeekerPlayer() {
      return this.currentSeeker == null ? null : this.plugin.getServer().getPlayer(this.currentSeeker);
   }

   private void updateVisibility() {
      for (Player online : this.plugin.getServer().getOnlinePlayers()) {
         HideAndSeekModule.Role onlineRole = this.participants.get(online.getUniqueId());

         for (Player other : this.plugin.getServer().getOnlinePlayers()) {
            if (!online.equals(other)) {
               HideAndSeekModule.Role otherRole = this.participants.get(other.getUniqueId());
               if (onlineRole == null && otherRole == null) {
                  online.showPlayer(this.plugin, other);
               } else if (onlineRole == null || otherRole == null) {
                  online.hidePlayer(this.plugin, other);
               } else if (otherRole == HideAndSeekModule.Role.SPECTATOR) {
                  online.hidePlayer(this.plugin, other);
               } else if (onlineRole == HideAndSeekModule.Role.SPECTATOR) {
                  online.showPlayer(this.plugin, other);
               } else if (this.state == HideAndSeekModule.GameState.WAITING) {
                  online.showPlayer(this.plugin, other);
               } else if (onlineRole == HideAndSeekModule.Role.SEEKER) {
                  if (this.state == HideAndSeekModule.GameState.SEEKING) {
                     online.showPlayer(this.plugin, other);
                  } else {
                     online.hidePlayer(this.plugin, other);
                  }
               } else {
                  online.showPlayer(this.plugin, other);
               }
            }
         }
      }


   }

   @EventHandler(
      priority = EventPriority.HIGH,
      ignoreCancelled = true
   )
   public void onInteract(PlayerInteractEntityEvent event) {
      if (event.getRightClicked() instanceof Player target) {
         Player var5 = event.getPlayer();
         if (this.state == HideAndSeekModule.GameState.SEEKING && var5.getUniqueId().equals(this.currentSeeker)) {
            HideAndSeekModule.Role targetRole = this.participants.get(target.getUniqueId());
            if (targetRole == HideAndSeekModule.Role.HIDER) {
               target.sendMessage(ServerTextUtil.component("&cYou were found by the Seeker!"));
               var5.sendMessage(ServerTextUtil.component("&a" + "You found " + target.getName() + "!"));
               this.scheduler.runAsync(() -> this.stats.incrementHnsPlayersFound(var5.getUniqueId()));
               this.broadcast(ServerTextUtil.component("&e" + target.getName() + " was found!"));
               this.participants.put(target.getUniqueId(), HideAndSeekModule.Role.SPECTATOR);
               target.setAllowFlight(true);
               target.setFlying(true);
               if (this.hiddenTeam.hasEntry(target.getName())) {
                  this.hiddenTeam.removeEntry(target.getName());
               }

               this.updateVisibility();
               this.checkEndConditions();
            }
         }
      }
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent event) {
      this.scheduler.runSyncLater(this::updateVisibility, 2L);
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      if (this.participants.containsKey(event.getPlayer().getUniqueId())) {
         this.leaveGame(event.getPlayer());
      }
   }

   private static enum GameState {
      WAITING,
      HIDING,
      SEEKING;

      private GameState() {
      }
   }

   private static enum Role {
      HIDER,
      SEEKER,
      SPECTATOR;

      private Role() {
      }
   }
}
