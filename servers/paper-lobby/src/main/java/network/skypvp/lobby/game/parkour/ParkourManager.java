package network.skypvp.lobby.game.parkour;

import network.skypvp.shared.ServerTextUtil;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.lobby.game.LobbyGameManager;
import network.skypvp.lobby.game.LobbyGameType;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class ParkourManager implements Listener {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   private final JavaPlugin plugin;
   private final LobbyGameManager gameManager;
   private ParkourRedisSync redisSync;
   private final Map<String, ParkourTrack> tracks = new ConcurrentHashMap<>();
   private final Map<UUID, ParkourManager.ParkourSession> activeSessions = new ConcurrentHashMap<>();

   public ParkourManager(JavaPlugin plugin, LobbyGameManager gameManager) {
      this.plugin = plugin;
      this.gameManager = gameManager;
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
   }

   public void setRedisSync(ParkourRedisSync redisSync) {
      this.redisSync = redisSync;
   }

   public void updateTrackLocal(ParkourTrack track) {
      this.tracks.put(track.getName().toLowerCase(), track);
   }

   public void saveTrackRemote(ParkourTrack track) {
      this.updateTrackLocal(track);
      if (this.redisSync != null) {
         this.redisSync.publishTrackUpdate(track);
      }
   }

   public ParkourTrack getTrack(String name) {
      return this.tracks.get(name.toLowerCase());
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onMove(PlayerMoveEvent event) {
      Location from = event.getFrom();
      Location to = event.getTo();
      if (to != null) {
         if (from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY() || from.getBlockZ() != to.getBlockZ()) {
            Player p = event.getPlayer();
            UUID id = p.getUniqueId();
            Location under = to.clone().subtract(0.0, 1.0, 0.0);
            ParkourManager.ParkourSession session = this.activeSessions.get(id);
            if (session == null) {
               for (ParkourTrack track : this.tracks.values()) {
                  if (track.getStart() != null && track.getStart().matches(under)) {
                     this.startSession(p, track, under);
                     return;
                  }
               }
            } else {
               ParkourTrack currentTrack = this.tracks.get(session.trackName);
               if (currentTrack == null) {
                  this.cancelSession(p);
               } else if (to.getY() <= -20.0) {
                  p.teleportAsync(session.lastValidLocation);
                  p.sendMessage(ServerTextUtil.component("&7You fell! Recovering..."));
               } else {
                  int nextId = session.checkpointIndex + 1;
                  if (nextId < currentTrack.getCheckpoints().size() && currentTrack.getCheckpoints().get(nextId).matches(under)) {
                     session.checkpointIndex = nextId;
                     session.lastValidLocation = under.clone().add(0.0, 1.0, 0.0);
                     p.sendMessage(ServerTextUtil.component("&a" + "Checkpoint " + (nextId + 1) + " reached!"));
                  } else if (currentTrack.getFinish() != null && currentTrack.getFinish().matches(under)) {
                     if (session.checkpointIndex == currentTrack.getCheckpoints().size() - 1) {
                        this.completeSession(p, session);
                     } else {
                        p.sendMessage(ServerTextUtil.component("&cYou missed some checkpoints!"));
                        p.teleportAsync(session.lastValidLocation);
                     }
                  }
               }
            }
         }
      }
   }

   private void startSession(Player p, ParkourTrack track, Location startLoc) {
      if (this.gameManager.joinGame(p, LobbyGameType.PARKOUR)) {
         ParkourManager.ParkourSession session = new ParkourManager.ParkourSession();
         session.trackName = track.getName().toLowerCase();
         session.checkpointIndex = -1;
         session.startTimeMs = System.currentTimeMillis();
         session.lastValidLocation = startLoc.clone().add(0.0, 1.0, 0.0);
         this.activeSessions.put(p.getUniqueId(), session);
         p.sendMessage(ServerTextUtil.miniMessageComponent("<#FFD700>Parkour run started on " + track.getName() + "!<reset>"));
      }
   }

   private void cancelSession(Player p) {
      if (this.activeSessions.remove(p.getUniqueId()) != null) {
         this.gameManager.quitGame(p);
         p.sendMessage(ServerTextUtil.component("&cParkour session cancelled."));
      }
   }

   private void completeSession(Player p, ParkourManager.ParkourSession session) {
      long elapsed = System.currentTimeMillis() - session.startTimeMs;
      this.activeSessions.remove(p.getUniqueId());
      this.gameManager.quitGame(p);
      p.sendMessage(ServerTextUtil.miniMessageComponent("<#FFB300><bold>Parkour Completed!</bold><reset> <#FFD700>" + this.format(elapsed) + "<reset>"));
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      this.activeSessions.remove(event.getPlayer().getUniqueId());
      if (this.gameManager.getActiveGame(event.getPlayer().getUniqueId()) == LobbyGameType.PARKOUR) {
         this.gameManager.quitGame(event.getPlayer());
      }
   }

   private String format(long ms) {
      long seconds = ms / 1000L;
      long mins = seconds / 60L;
      long rem = seconds % 60L;
      long centis = ms % 1000L / 10L;
      return mins + "m " + rem + "." + (centis < 10L ? "0" : "") + centis + "s";
   }

   private static class ParkourSession {
      String trackName;
      int checkpointIndex;
      long startTimeMs;
      Location lastValidLocation;

      private ParkourSession() {
      }
   }
}
