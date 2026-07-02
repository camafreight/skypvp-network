package network.skypvp.lobby.service;

import network.skypvp.lobby.game.LobbyGameManager;
import network.skypvp.shared.ServerTextUtil;

import java.util.UUID;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.paper.PaperCorePlugin;

import network.skypvp.lobby.library.HotbarItemsLibrary;
import network.skypvp.lobby.state.LobbyRuntimeStateRegistry;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;

public final class LobbyRuntimeMonitor {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   private final PaperCorePlugin plugin;
   private final LobbyRuntimeStateRegistry states;
   private final Location spawn;
   private final HotbarItemsLibrary hotbarLibrary;
   private final LobbyGameManager gameManager;
   private long lastSpawnWarningMs;

   public LobbyRuntimeMonitor(PaperCorePlugin plugin, LobbyRuntimeStateRegistry states, Location spawn, HotbarItemsLibrary hotbarLibrary, LobbyGameManager gameManager) {
      this.plugin = plugin;
      this.states = states;
      this.spawn = spawn;
      this.hotbarLibrary = hotbarLibrary;
      this.gameManager = gameManager;
   }

   public void tick() {
      if (this.spawn.getWorld() == null) {
         long now = System.currentTimeMillis();
         if (now - this.lastSpawnWarningMs >= 30000L) {
            this.plugin.getLogger().warning("[LobbyMonitor] Spawn world is unavailable; recovery teleports may fail.");
            this.lastSpawnWarningMs = now;
         }
      }

      int corrected = 0;

      for (Player p : this.plugin.getServer().getOnlinePlayers()) {
         this.states.refreshPlayerState(p);
         if (!this.isBypass(p) && !this.gameManager.isInGame(p.getUniqueId())) {
            if (p.getGameMode() != GameMode.ADVENTURE) {
               p.setGameMode(GameMode.ADVENTURE);
               corrected++;
            }

            AttributeInstance maxHealthAttr = p.getAttribute(Attribute.MAX_HEALTH);
            double maxHealth = maxHealthAttr != null ? maxHealthAttr.getValue() : 20.0;
            if (p.getHealth() < maxHealth) {
               p.setHealth(maxHealth);
               corrected++;
            }

            if (p.getFoodLevel() < 20) {
               p.setFoodLevel(20);
               p.setSaturation(20.0F);
               corrected++;
            }

            corrected += this.hotbarLibrary.ensure(p);
         }
      }

      if (corrected > 0) {
         this.plugin.getLogger().fine("[LobbyMonitor] Applied " + corrected + " lobby profile corrections.");
      }

      for (UUID id : this.states.clearExpiredQueued()) {
         Player player = this.plugin.getServer().getPlayer(id);
         if (player != null && player.isOnline()) {
            player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Queue handoff timed out. State reset to normal. Select again if needed.<reset>"));
         }
      }
   }

   private boolean isBypass(Player player) {
      return player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR;
   }
}
