package network.skypvp.lobby.listener;

import network.skypvp.shared.ServerTextUtil;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gui.GuiButtonLibrary;
import network.skypvp.paper.gui.GuiManager;
import network.skypvp.paper.gui.GuiMenuBuilder;
import network.skypvp.paper.gui.GuiLayoutLibrary.QuickSelect9;
import network.skypvp.paper.integration.ProxyRouteMessenger;
import network.skypvp.lobby.state.LobbyFlowState;
import network.skypvp.lobby.state.LobbyAudienceState;
import network.skypvp.lobby.state.LobbyRuntimeStateRegistry;
import network.skypvp.shared.NetworkServerRole;
import network.skypvp.shared.ServerTextUtil.ThemeTone;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

public final class LobbySelectorListener implements Listener {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   private static final String MENU_TITLE = "<#ff00ff><bold>Server Navigator</bold></#ff00ff>";
   private static final String LOBBY_DESTINATION = "lobby";
   private static final String EXTRACTION_DESTINATION = "extraction";
   private static final String BODY_HEX = ThemeTone.BRAND_100.hex();
   private static final String HIGHLIGHT_HEX = ThemeTone.BRAND_400.hex();
   private static final String STRUCTURE_HEX = ThemeTone.BRAND_600.hex();
   private final PaperCorePlugin plugin;
   private final LobbyRuntimeStateRegistry states;
   private final GuiManager guiManager;
   private final Map<UUID, Long> clickCooldowns = new ConcurrentHashMap<>();

   public LobbySelectorListener(PaperCorePlugin plugin, LobbyRuntimeStateRegistry states, GuiManager guiManager) {
      this.plugin = plugin;
      this.states = states;
      this.guiManager = guiManager;
   }

   @EventHandler(
      priority = EventPriority.HIGHEST
   )
   public void onInteract(PlayerInteractEvent event) {
      Player player = event.getPlayer();
      boolean defaultLobbySystems = this.plugin.serverRole() == NetworkServerRole.LOBBY;
      if (this.plugin.gameModeBehaviorService().booleanValue("core.lobby.systems.enabled", defaultLobbySystems)) {
         if (event.getHand() == EquipmentSlot.HAND) {
            if (event.getItem() != null && event.getItem().getType() == Material.COMPASS) {
               if (event.getAction().isRightClick() || event.getAction().isLeftClick()) {
                  event.setCancelled(true);
                  this.openMenu(player);
               }
            }
         }
      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR
   )
   public void onQuit(PlayerQuitEvent event) {
      this.clickCooldowns.remove(event.getPlayer().getUniqueId());
   }

   private void openMenu(Player player) {
      GuiMenuBuilder builder = GuiMenuBuilder.create(ServerTextUtil.miniMessageComponent(MENU_TITLE), 9)
         .button(
            (Integer)QuickSelect9.ACTION_SLOTS.get(0),
            GuiButtonLibrary.primaryAction(
               Material.NETHER_STAR,
               "Lobby Hubs",
               lore -> lore.bullet("Switch to another active lobby.")
                     .bullet("Uses the healthiest lobby pool.")
                     .footerStrong("<yellow>", "Click to route")
            ),
            context -> this.routeSelection(context.viewer(), LOBBY_DESTINATION, "Lobby Hubs")
         )
         .button(
            (Integer)QuickSelect9.ACTION_SLOTS.get(1),
            GuiButtonLibrary.primaryAction(
               Material.IRON_SWORD,
               "Extraction",
               lore -> lore.bullet("Match-based extraction runs.").bullet("Routes through the extraction pool.").footerStrong("<yellow>", "Click to route")
            ),
            context -> this.routeSelection(context.viewer(), EXTRACTION_DESTINATION, "Extraction")
         );
      this.guiManager.open(player, builder.build());
   }

   private void routeSelection(Player player, String target, String label) {
      if (this.states.gameState() == LobbyFlowState.RESTARTING) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<" + BODY_HEX + ">Lobby is restarting. Please wait...<reset>"));
         player.closeInventory();
      } else {
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
         } else if (target != null && !target.isBlank()) {
            boolean alreadyHere = target.equalsIgnoreCase(this.plugin.serverRole().name())
                || (target.equalsIgnoreCase("extraction") && this.plugin.serverRole() == NetworkServerRole.EXTRACTION);

            if (alreadyHere) {
               this.clickCooldowns.put(player.getUniqueId(), now + cooldownMs);
               player.sendMessage(ServerTextUtil.miniMessageComponent("<#FFD700>➤ <reset><#888888>You are already connected to that destination.<reset>"));
               player.closeInventory();
            } else {
               this.clickCooldowns.put(player.getUniqueId(), now + cooldownMs);
               long queueTtlMs = Math.max(4000L, this.plugin.getConfig().getLong("lobby.selector.transfer-timeout-ms", 12000L));
               this.states.setQueued(player.getUniqueId(), label, queueTtlMs);
               this.connect(player, target);
               player.closeInventory();
            }
         } else {
            player.sendMessage(ServerTextUtil.miniMessageComponent("<" + BODY_HEX + ">That destination is currently unavailable.<reset>"));
         }
      }
   }

   private void connect(Player player, String targetServer) {
      ProxyRouteMessenger.routePlayer(this.plugin, player, targetServer);
      player.sendMessage(
         ServerTextUtil.miniMessageComponent("<" + STRUCTURE_HEX + ">><reset> <" + BODY_HEX + ">Routing to <reset><" + HIGHLIGHT_HEX + ">" + targetServer + "<reset>")
      );
   }
}
