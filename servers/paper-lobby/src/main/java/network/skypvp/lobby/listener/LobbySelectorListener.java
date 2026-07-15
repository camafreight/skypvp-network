package network.skypvp.lobby.listener;

import network.skypvp.shared.ServerTextUtil;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.lobby.service.LobbyNavigatorMenus;
import network.skypvp.lobby.service.NavigatorRouteHandler;
import network.skypvp.lobby.state.LobbyFlowState;
import network.skypvp.lobby.state.LobbyRuntimeStateRegistry;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.integration.ProxyRouteMessenger;
import network.skypvp.shared.NetworkServerRole;
import network.skypvp.shared.ServerTextUtil.ThemeTone;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class LobbySelectorListener implements Listener, NavigatorRouteHandler {
   private static final String LOBBY_DESTINATION = "lobby";
   private static final String EXTRACTION_DESTINATION = "extraction";
   private static final String BODY_HEX = ThemeTone.BRAND_100.hex();
   private static final String HIGHLIGHT_HEX = ThemeTone.BRAND_400.hex();
   private static final String STRUCTURE_HEX = ThemeTone.BRAND_600.hex();
   private final PaperCorePlugin plugin;
   private final LobbyRuntimeStateRegistry states;
   private LobbyNavigatorMenus navigatorMenus;
   private final Map<UUID, Long> clickCooldowns = new ConcurrentHashMap<>();

   public LobbySelectorListener(PaperCorePlugin plugin, LobbyRuntimeStateRegistry states) {
      this.plugin = plugin;
      this.states = states;
   }

   public void bindNavigatorMenus(LobbyNavigatorMenus navigatorMenus) {
      this.navigatorMenus = navigatorMenus;
   }

   @EventHandler(
      priority = EventPriority.MONITOR
   )
   public void onQuit(PlayerQuitEvent event) {
      this.clickCooldowns.remove(event.getPlayer().getUniqueId());
   }

   public void openServerNavigator(Player player) {
      if (this.navigatorMenus == null) {
         return;
      }
      this.navigatorMenus.openRoot(player);
   }

   public void openServerNavigatorChild(Player player) {
      if (this.navigatorMenus == null) {
         return;
      }
      this.navigatorMenus.openRootChild(player);
   }

   @Override
   public void connectExact(Player player, String serverId, String label) {
      if (serverId == null || serverId.isBlank()) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<" + BODY_HEX + ">That destination is currently unavailable.<reset>"));
         return;
      }
      if (this.states.gameState() == LobbyFlowState.RESTARTING) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<" + BODY_HEX + ">Lobby is restarting. Please wait...<reset>"));
         player.closeInventory();
         return;
      }
      long now = System.currentTimeMillis();
      long cooldownMs = Math.max(750L, this.plugin.getConfig().getLong("lobby.selector.cooldown-ms", 2500L));
      long allowedAt = this.clickCooldowns.getOrDefault(player.getUniqueId(), 0L);
      if (now < allowedAt) {
         long left = Math.max(1L, (allowedAt - now) / 1000L);
         player.sendMessage(
            ServerTextUtil.miniMessageComponent(
               "<" + BODY_HEX + ">Please wait <reset><" + HIGHLIGHT_HEX + ">" + left + "s<reset><" + BODY_HEX + "> before selecting again.<reset>"
            )
         );
         return;
      }
      this.clickCooldowns.put(player.getUniqueId(), now + cooldownMs);
      long queueTtlMs = Math.max(4000L, this.plugin.getConfig().getLong("lobby.selector.transfer-timeout-ms", 12000L));
      this.states.setQueued(player.getUniqueId(), label == null || label.isBlank() ? serverId : label, queueTtlMs);
      ProxyRouteMessenger.connectExact(this.plugin, player, serverId);
      player.sendMessage(
         ServerTextUtil.miniMessageComponent("<" + STRUCTURE_HEX + ">><reset> <" + BODY_HEX + ">Routing to <reset><" + HIGHLIGHT_HEX + ">" + serverId + "<reset>")
      );
      player.closeInventory();
   }

   @Override
   public void routeExtraction(Player player, String serverId, String label) {
      String destination = serverId == null || serverId.isBlank() ? EXTRACTION_DESTINATION : serverId;
      String display = label == null || label.isBlank() ? destination : label;
      this.routeSelection(player, destination, display);
   }

   public void routeSelection(Player player, String target, String label) {
      if (target == null || target.isBlank()) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<" + BODY_HEX + ">That destination is currently unavailable.<reset>"));
         return;
      }
      if (this.states.gameState() == LobbyFlowState.RESTARTING) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<" + BODY_HEX + ">Lobby is restarting. Please wait...<reset>"));
         player.closeInventory();
         return;
      }
      long now = System.currentTimeMillis();
      long cooldownMs = Math.max(750L, this.plugin.getConfig().getLong("lobby.selector.cooldown-ms", 2500L));
      long allowedAt = this.clickCooldowns.getOrDefault(player.getUniqueId(), 0L);
      if (now < allowedAt) {
         long left = Math.max(1L, (allowedAt - now) / 1000L);
         player.sendMessage(
            ServerTextUtil.miniMessageComponent(
               "<" + BODY_HEX + ">Please wait <reset><" + HIGHLIGHT_HEX + ">" + left + "s<reset><" + BODY_HEX + "> before selecting again.<reset>"
            )
         );
         return;
      }
      boolean alreadyHere = target.equalsIgnoreCase(this.plugin.serverRole().name())
         || (target.equalsIgnoreCase(EXTRACTION_DESTINATION) && this.plugin.serverRole() == NetworkServerRole.EXTRACTION)
         || (target.equalsIgnoreCase(LOBBY_DESTINATION) && this.plugin.serverRole() == NetworkServerRole.LOBBY);
      if (alreadyHere) {
         this.clickCooldowns.put(player.getUniqueId(), now + cooldownMs);
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#FFD700>➤ <reset><#888888>You are already connected to that destination.<reset>"));
         player.closeInventory();
         return;
      }
      this.clickCooldowns.put(player.getUniqueId(), now + cooldownMs);
      long queueTtlMs = Math.max(4000L, this.plugin.getConfig().getLong("lobby.selector.transfer-timeout-ms", 12000L));
      this.states.setQueued(player.getUniqueId(), label, queueTtlMs);
      ProxyRouteMessenger.routePlayer(this.plugin, player, target);
      player.sendMessage(
         ServerTextUtil.miniMessageComponent("<" + STRUCTURE_HEX + ">><reset> <" + BODY_HEX + ">Routing to <reset><" + HIGHLIGHT_HEX + ">" + target + "<reset>")
      );
      player.closeInventory();
   }
}
