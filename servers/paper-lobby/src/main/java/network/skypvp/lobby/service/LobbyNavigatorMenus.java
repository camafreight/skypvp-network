package network.skypvp.lobby.service;

import java.util.ArrayList;
import java.util.List;
import network.skypvp.lobby.service.NavigatorRouteHandler;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gui.GuiButtonLibrary;
import network.skypvp.paper.gui.GuiClickContext;
import network.skypvp.paper.gui.GuiLayoutLibrary.Browser54Spacious;
import network.skypvp.paper.gui.GuiLayoutLibrary.NetworkNavigator54;
import network.skypvp.paper.gui.GuiManager;
import network.skypvp.paper.gui.GuiMenu;
import network.skypvp.paper.gui.GuiMenuBuilder;
import network.skypvp.paper.gui.GuiPlaceholderTexts;
import network.skypvp.paper.integration.NetworkHeartbeatCache;
import network.skypvp.paper.integration.SkyPvPPlaceholderSupport;
import network.skypvp.paper.repository.NetworkServerDirectoryRepository;
import network.skypvp.shared.NetworkServerRole;
import network.skypvp.shared.ServerHeartbeatEvent;
import network.skypvp.shared.ServerTextUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public final class LobbyNavigatorMenus {
   private static final String ROOT_TITLE = "<#ff00ff><bold>Network Navigator</bold></#ff00ff>";
   private static final String LOBBY_BROWSER_TITLE = "<#ffd700><bold>Lobby Selector</bold></#ffd700>";
   private static final String BREACH_BROWSER_TITLE = "<#c4b5fd><bold>Live Extraction Browser</bold></#c4b5fd>";
   private static final int[] BROWSER_SLOTS = Browser54Spacious.PAGE_SLOTS.stream().mapToInt(Integer::intValue).toArray();

   private final PaperCorePlugin plugin;
   private final GuiManager guiManager;
   private final NavigatorRouteHandler routes;

   public LobbyNavigatorMenus(PaperCorePlugin plugin, GuiManager guiManager, NavigatorRouteHandler routes) {
      this.plugin = plugin;
      this.guiManager = guiManager;
      this.routes = routes;
   }

   public void openRoot(Player player) {
      this.guiManager.open(player, this.buildRootMenu());
   }

   public void openRootChild(Player player) {
      this.guiManager.openChild(player, this.buildRootMenu());
   }

   private GuiMenu buildRootMenu() {
      GuiMenuBuilder menu = GuiMenuBuilder.create(ServerTextUtil.miniMessageComponent(ROOT_TITLE), 54)
         .button(NetworkNavigator54.CLOSE_SLOT, GuiButtonLibrary.close("Close the network navigator"), GuiClickContext::close)
         .button(NetworkNavigator54.BACK_SLOT, GuiButtonLibrary.back("Return to the previous menu"), GuiClickContext::back)
         .animatedButton(
            NetworkNavigator54.LOBBY_SLOT,
            (viewer, tick) -> GuiPlaceholderTexts.item(
               Material.NETHER_STAR,
               "<#ffd700><bold>Lobby Selector</bold>",
               List.of(
                  "<!italic><gray>• %skypvp_navigator_lobby_status_line%",
                  "<!italic><gray>• Hubs: <#60a5fa>%skypvp_navigator_lobby_servers%<gray> • Players: <#60a5fa>%skypvp_navigator_lobby_players%",
                  "<!italic><gray>• Load: <white>%skypvp_navigator_lobby_capacity_label%",
                  "<!italic><dark_gray> </dark_gray>",
                  "<!italic><yellow>Click to browse hubs"
               ),
               this.plugin,
               viewer,
               tick,
               false
            ),
            context -> context.open(this.buildLobbyBrowserMenu())
         )
         .animatedButton(
            NetworkNavigator54.EXTRACTION_SLOT,
            (viewer, tick) -> GuiPlaceholderTexts.item(
               Material.AMETHYST_SHARD,
               "<anim:glow><gradient:#ddd6fe:#c4b5fd:#a78bfa:#8b5cf6><bold>Aether Breach</bold></gradient></anim:glow>",
               List.of(
                  "<!italic><#d8b4fe>%skypvp_anim_brand_shimmer% <#c4b5fd><bold>Extraction</bold>",
                  "<!italic><gray>• %skypvp_navigator_extraction_status_line%",
                  "<!italic><gray>• Pods: <#c4b5fd>%skypvp_navigator_extraction_servers%<gray> • Raiders: <#c4b5fd>%skypvp_navigator_extraction_players%",
                  "<!italic><gray>• Open slots: <#a78bfa>%skypvp_navigator_breach_open_slots%<gray> • Queue: <#a78bfa>%skypvp_navigator_breach_queued_players%",
                  "<!italic><gray>• Live raids: <#ddd6fe>%skypvp_navigator_breach_active_instances%",
                  "<!italic><dark_gray> </dark_gray>",
                  "<!italic><#e9d5ff>Click to browse extraction pods"
               ),
               this.plugin,
               viewer,
               tick,
               true
            ),
            context -> context.open(this.buildExtractionBrowserMenu())
         );
      return menu.build();
   }

   private GuiMenu buildLobbyBrowserMenu() {
      List<LobbyServerEntry> servers = this.listLobbyServers();
      GuiMenuBuilder menu = GuiMenuBuilder.create(ServerTextUtil.miniMessageComponent(LOBBY_BROWSER_TITLE), 54)
         .button(NetworkNavigator54.CLOSE_SLOT, GuiButtonLibrary.close("Close lobby browser"), GuiClickContext::close)
         .button(NetworkNavigator54.BACK_SLOT, GuiButtonLibrary.back("Return to the network navigator"), GuiClickContext::back);

      int shown = Math.min(servers.size(), BROWSER_SLOTS.length);
      for (int index = 0; index < shown; index++) {
         int slot = BROWSER_SLOTS[index];
         int placeholderIndex = index + 1;
         LobbyServerEntry server = servers.get(index);
         menu.animatedButton(
            slot,
            (viewer, tick) -> GuiPlaceholderTexts.item(
               Material.EMERALD,
               "<#ffd700><bold>%skypvp_navigator_lobby_server_" + placeholderIndex + "_display%</bold>",
               List.of(
                  "<!italic><gray>• Server: <white>%skypvp_navigator_lobby_server_" + placeholderIndex + "_id%",
                  "<!italic><gray>• Players: <#60a5fa>%skypvp_navigator_lobby_server_" + placeholderIndex + "_players%<gray>/<white>%skypvp_navigator_lobby_server_" + placeholderIndex + "_capacity%",
                  "<!italic><gray>• Load: <white>%skypvp_navigator_lobby_server_" + placeholderIndex + "_load_percent%%",
                  "<!italic><gray>• Status: <#55ff55>%skypvp_navigator_lobby_server_" + placeholderIndex + "_joinable%",
                  "<!italic><dark_gray> </dark_gray>",
                  "<!italic><yellow>Click to connect"
               ),
               this.plugin,
               viewer,
               tick,
               false
            ),
            context -> this.routes.connectExact(context.viewer(), server.serverId(), server.displayLabel())
         );
      }

      if (shown == 0) {
         menu.button(
            Browser54Spacious.HEADER_SLOT,
            GuiButtonLibrary.infoExclamation("No Live Hubs", lore -> lore.plain("No lobby hubs are reporting right now.")),
            context -> {
            }
         );
      }

      return menu.build();
   }

   private GuiMenu buildExtractionBrowserMenu() {
      List<ExtractionPodEntry> pods = this.listExtractionPods();
      GuiMenuBuilder menu = GuiMenuBuilder.create(ServerTextUtil.miniMessageComponent(BREACH_BROWSER_TITLE), 54)
         .button(NetworkNavigator54.CLOSE_SLOT, GuiButtonLibrary.close("Close extraction browser"), GuiClickContext::close)
         .button(NetworkNavigator54.BACK_SLOT, GuiButtonLibrary.back("Return to the network navigator"), GuiClickContext::back);

      int shown = Math.min(pods.size(), BROWSER_SLOTS.length);
      for (int index = 0; index < shown; index++) {
         int slot = BROWSER_SLOTS[index];
         int placeholderIndex = index + 1;
         ExtractionPodEntry pod = pods.get(index);
         menu.animatedButton(
            slot,
            (viewer, tick) -> GuiPlaceholderTexts.item(
               Material.AMETHYST_CLUSTER,
               "<anim:glow><gradient:#ede9fe:#c4b5fd:#a78bfa><bold>%skypvp_navigator_extraction_pod_" + placeholderIndex + "_display%</bold></gradient></anim:glow>",
               List.of(
                  "<!italic><#ddd6fe>Extraction Pod <#c4b5fd>%skypvp_navigator_extraction_pod_" + placeholderIndex + "_id%",
                  "<!italic><gray>• Raiders: <#a78bfa>%skypvp_navigator_extraction_pod_" + placeholderIndex + "_players%<gray>/<white>%skypvp_navigator_extraction_pod_" + placeholderIndex + "_capacity%",
                  "<!italic><gray>• Load: <white>%skypvp_navigator_extraction_pod_" + placeholderIndex + "_load_percent%%",
                  "<!italic><gray>• Live raids: <#c4b5fd>%skypvp_navigator_extraction_pod_" + placeholderIndex + "_active_raids%",
                  "<!italic><gray>• Open breach slots: <#ddd6fe>%skypvp_navigator_extraction_pod_" + placeholderIndex + "_open_breach_slots%",
                  "<!italic><gray>• In-pod queue: <#a78bfa>%skypvp_navigator_extraction_pod_" + placeholderIndex + "_queued_players%",
                  "<!italic><gray>• Maps: <#888888>%skypvp_navigator_extraction_pod_" + placeholderIndex + "_active_maps%",
                  "<!italic><gray>• Status: <#55ff55>%skypvp_navigator_extraction_pod_" + placeholderIndex + "_joinable_label%",
                  "<!italic><dark_gray> </dark_gray>",
                  "<!italic><#e9d5ff>Click to queue for Aether Breach"
               ),
               this.plugin,
               viewer,
               tick,
               true
            ),
            context -> this.routes.routeExtraction(context.viewer(), pod.serverId(), pod.displayLabel())
         );
      }

      if (shown == 0) {
         menu.button(
            Browser54Spacious.HEADER_SLOT,
            GuiButtonLibrary.infoExclamation(
               "No Live Extraction Pods",
               lore -> lore.plain("No extraction pods are reporting right now.")
                     .plain("Try again shortly or use /play extraction.")
            ),
            context -> {
            }
         );
      }

      return menu.build();
   }

   private List<ExtractionPodEntry> listExtractionPods() {
      List<ExtractionPodEntry> entries = new ArrayList<>();
      NetworkHeartbeatCache cache = this.plugin.networkHeartbeatCache();
      if (cache != null) {
         for (ServerHeartbeatEvent heartbeat : cache.liveHeartbeatsForRole(NetworkServerRole.EXTRACTION)) {
            if (!heartbeat.joinable()) {
               continue;
            }
            entries.add(new ExtractionPodEntry(
               heartbeat.serverId(),
               SkyPvPPlaceholderSupport.compactServerNameForNavigator(heartbeat.serverId())
            ));
         }
      }
      if (!entries.isEmpty()) {
         return entries;
      }
      NetworkServerDirectoryRepository directory = this.plugin.networkServerDirectoryRepository();
      if (directory == null) {
         return List.of();
      }
      for (NetworkServerDirectoryRepository.NetworkServerSnapshot snapshot : directory.listJoinableByRole(NetworkServerRole.EXTRACTION)) {
         entries.add(new ExtractionPodEntry(snapshot.serverId(), SkyPvPPlaceholderSupport.compactServerNameForNavigator(snapshot.serverId())));
      }
      return entries;
   }

   private List<LobbyServerEntry> listLobbyServers() {
      List<LobbyServerEntry> entries = new ArrayList<>();
      NetworkHeartbeatCache cache = this.plugin.networkHeartbeatCache();
      if (cache != null) {
         for (ServerHeartbeatEvent heartbeat : cache.liveHeartbeatsForRole(NetworkServerRole.LOBBY)) {
            if (!heartbeat.joinable()) {
               continue;
            }
            entries.add(new LobbyServerEntry(
               heartbeat.serverId(),
               SkyPvPPlaceholderSupport.compactServerNameForNavigator(heartbeat.serverId())
            ));
         }
      }
      if (!entries.isEmpty()) {
         return entries;
      }
      NetworkServerDirectoryRepository directory = this.plugin.networkServerDirectoryRepository();
      if (directory == null) {
         return List.of();
      }
      for (NetworkServerDirectoryRepository.NetworkServerSnapshot snapshot : directory.listJoinableByRole(NetworkServerRole.LOBBY)) {
         entries.add(new LobbyServerEntry(snapshot.serverId(), SkyPvPPlaceholderSupport.compactServerNameForNavigator(snapshot.serverId())));
      }
      return entries;
   }

   private record LobbyServerEntry(String serverId, String displayLabel) {
   }

   private record ExtractionPodEntry(String serverId, String displayLabel) {
   }
}
