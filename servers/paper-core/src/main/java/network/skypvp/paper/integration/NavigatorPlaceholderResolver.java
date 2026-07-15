package network.skypvp.paper.integration;

import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gamemode.api.LobbyPlaceholderBridge;
import network.skypvp.paper.repository.NetworkServerDirectoryRepository;
import network.skypvp.shared.NetworkAnimationEngine;
import network.skypvp.shared.NetworkServerRole;
import network.skypvp.shared.ServerHeartbeatEvent;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

public final class NavigatorPlaceholderResolver {
   private NavigatorPlaceholderResolver() {
   }

   public static String resolve(PaperCorePlugin plugin, Player player, String key) {
      if (key == null || key.isBlank()) {
         return "";
      }

      String normalized = key.toLowerCase(Locale.ROOT);
      if (normalized.startsWith("lobby.") || normalized.startsWith("lobby_")) {
         return resolveLobby(plugin, player, normalized);
      }
      if (normalized.startsWith("navigator.")) {
         return resolveNavigator(plugin, player, normalized.substring("navigator.".length()));
      }
      if (normalized.startsWith("navigator_")) {
         return resolveNavigator(plugin, player, normalized.substring("navigator_".length()));
      }
      return "";
   }

   private static String resolveLobby(PaperCorePlugin plugin, Player player, String key) {
      String normalized = key.startsWith("lobby_")
         ? "lobby." + key.substring("lobby_".length())
         : key;
      LobbyPlaceholderBridge bridge = lobbyBridge(plugin);
      if (bridge != null && player != null) {
         String resolved = bridge.resolve(player, normalized);
         if (resolved != null && !resolved.isBlank()) {
            return resolved;
         }
      }
      return switch (normalized) {
         case "lobby.game_state" -> "unknown";
         case "lobby.player_state" -> "normal";
         case "lobby.queue_target" -> "-";
         case "lobby.player_line" -> "Ready to route";
         default -> "";
      };
   }

   private static String resolveNavigator(PaperCorePlugin plugin, Player player, String key) {
      NetworkHeartbeatCache cache = plugin.networkHeartbeatCache();
      NetworkServerDirectoryRepository directory = plugin.networkServerDirectoryRepository();

      String indexedBreach = parseIndexedKey(key, "breach_instance_");
      if (indexedBreach != null) {
         return resolveIndexedBreachInstance(indexedBreach, cache);
      }
      String indexedExtractionPod = parseIndexedKey(key, "extraction_pod_");
      if (indexedExtractionPod != null) {
         return resolveIndexedExtractionPod(indexedExtractionPod, cache, directory);
      }
      String indexedLobby = parseIndexedKey(key, "lobby_server_");
      if (indexedLobby != null) {
         return resolveIndexedLobbyServer(indexedLobby, cache, directory);
      }

      if (key.startsWith("lobby.server.")) {
         return resolveIndexedLobbyServer(key.substring("lobby.server.".length()), cache, directory);
      }
      if (key.startsWith("breach.instance.")) {
         return resolveIndexedBreachInstance(key.substring("breach.instance.".length()), cache);
      }
      if (key.startsWith("extraction.pod.")) {
         return resolveIndexedExtractionPod(key.substring("extraction.pod.".length()), cache, directory);
      }

      return switch (key) {
         case "lobby.servers" -> Integer.toString(Math.max(liveLobbyServers(cache, directory), 0));
         case "lobby.players" -> Integer.toString(Math.max(liveLobbyPlayers(cache, directory), 0));
         case "lobby.capacity" -> Integer.toString(Math.max(liveLobbyCapacity(cache, directory), 0));
         case "lobby.capacity_label" -> NetworkAnimationEngine.capacityLabel(
            Math.max(liveLobbyPlayers(cache, directory), 0),
            Math.max(liveLobbyCapacity(cache, directory), 1)
         );
         case "lobby.load_percent" -> Integer.toString(percent(
            liveLobbyPlayers(cache, directory),
            Math.max(liveLobbyCapacity(cache, directory), 1)
         ));
         case "extraction.servers" -> Integer.toString(Math.max(liveExtractionServers(cache, directory), 0));
         case "extraction.players" -> Integer.toString(Math.max(liveExtractionPlayers(cache, directory), 0));
         case "breach.active_instances" -> cache == null ? "0" : Integer.toString(cache.aggregatedBreachInstances().size());
         case "breach.open_slots" -> cache == null ? "0" : Integer.toString(cache.totalOpenBreachSlots());
         case "breach.queued_players" -> cache == null ? "0" : Integer.toString(cache.totalQueuedPlayers());
         case "breach.raid_players", "breach.active_players" -> cache == null ? "0" : Integer.toString(cache.totalRaidParticipants());
         case "breach.active_maps", "breach.map_list" -> cache == null ? "None" : cache.activeMapList();
         case "breach.instance_count" -> cache == null ? "0" : Integer.toString(cache.aggregatedBreachInstances().size());
         case "breach.status_line" -> buildBreachStatusLine(cache);
         case "extraction.status_line" -> buildExtractionStatusLine(cache, directory);
         case "lobby.status_line" -> buildLobbyStatusLine(cache, directory);
         default -> "";
      };
   }

   private static String parseIndexedKey(String key, String prefix) {
      if (!key.startsWith(prefix)) {
         return null;
      }
      String rest = key.substring(prefix.length());
      int indexEnd = 0;
      while (indexEnd < rest.length() && Character.isDigit(rest.charAt(indexEnd))) {
         indexEnd++;
      }
      if (indexEnd == 0 || indexEnd >= rest.length() || rest.charAt(indexEnd) != '_') {
         return null;
      }
      return rest.substring(0, indexEnd) + "." + rest.substring(indexEnd + 1);
   }

   private static String resolveIndexedExtractionPod(String remainder, NetworkHeartbeatCache cache, NetworkServerDirectoryRepository directory) {
      int dot = remainder.indexOf('.');
      if (dot <= 0) {
         return "";
      }
      int index;
      try {
         index = Integer.parseInt(remainder.substring(0, dot));
      } catch (NumberFormatException ex) {
         return "";
      }
      String field = remainder.substring(dot + 1);
      Optional<ServerHeartbeatEvent> heartbeat = cache == null ? Optional.empty() : cache.extractionServerAt(index);
      if (heartbeat.isPresent()) {
         ServerHeartbeatEvent event = heartbeat.get();
         return switch (field) {
            case "id", "server_id", "pod" -> event.serverId();
            case "players", "online" -> Integer.toString(event.onlinePlayers());
            case "capacity", "max_players" -> Integer.toString(event.maxPlayers());
            case "display" -> SkyPvPPlaceholderSupport.compactServerNameForNavigator(event.serverId());
            case "load_percent" -> Integer.toString(percent(event.onlinePlayers(), Math.max(event.maxPlayers(), 1)));
            case "load_bar" -> NetworkAnimationEngine.capacityLabel(event.onlinePlayers(), Math.max(event.maxPlayers(), 1));
            case "joinable" -> event.joinable() ? "Open" : "Closed";
            case "joinable_label" -> event.joinable() ? "Joinable" : "Closed";
            case "active_raids", "active_breaches", "raids" -> Integer.toString(event.activeBreaches());
            case "open_breach_slots", "open_slots", "breach_slots" -> Integer.toString(event.openBreachSlots());
            case "queued_players", "queue" -> Integer.toString(event.queuedPlayers());
            case "active_maps", "maps" -> formatPodMapList(event);
            default -> "";
         };
      }

      if (directory == null) {
         return "";
      }
      var snapshots = directory.listJoinableByRole(NetworkServerRole.EXTRACTION);
      if (index < 1 || index > snapshots.size()) {
         return defaultExtractionPod(field);
      }
      var snapshot = snapshots.get(index - 1);
      return switch (field) {
         case "id", "server_id", "pod" -> snapshot.serverId();
         case "players", "online" -> Integer.toString(snapshot.onlinePlayers());
         case "capacity", "max_players" -> Integer.toString(snapshot.maxPlayers());
         case "display" -> SkyPvPPlaceholderSupport.compactServerNameForNavigator(snapshot.serverId());
         case "load_percent" -> Integer.toString(percent(snapshot.onlinePlayers(), Math.max(snapshot.maxPlayers(), 1)));
         case "load_bar" -> NetworkAnimationEngine.capacityLabel(snapshot.onlinePlayers(), Math.max(snapshot.maxPlayers(), 1));
         case "joinable" -> snapshot.isRoutable() ? "Open" : "Closed";
         case "joinable_label" -> snapshot.isRoutable() ? "Joinable" : "Closed";
         case "active_raids", "active_breaches", "raids", "open_breach_slots", "open_slots", "breach_slots", "queued_players", "queue", "active_maps", "maps" -> defaultExtractionPod(field);
         default -> "";
      };
   }

   private static String defaultExtractionPod(String field) {
      return switch (field) {
         case "joinable" -> "no";
         case "joinable_label" -> "Unavailable";
         case "display" -> "None";
         case "active_raids", "active_breaches", "raids", "open_breach_slots", "open_slots", "breach_slots", "queued_players", "queue" -> "0";
         case "active_maps", "maps" -> "None";
         default -> "";
      };
   }

   private static String formatPodMapList(ServerHeartbeatEvent event) {
      if (event.breachInstances() == null || event.breachInstances().isEmpty()) {
         return "None";
      }
      return event.breachInstances().stream()
         .filter(snapshot -> snapshot != null && snapshot.mapId() != null && !snapshot.mapId().isBlank())
         .map(snapshot -> NetworkHeartbeatCache.formatMapDisplay(snapshot.mapId()))
         .distinct()
         .reduce((left, right) -> left + ", " + right)
         .orElse("None");
   }

   private static String resolveIndexedLobbyServer(String remainder, NetworkHeartbeatCache cache, NetworkServerDirectoryRepository directory) {
      int dot = remainder.indexOf('.');
      if (dot <= 0) {
         return "";
      }
      int index;
      try {
         index = Integer.parseInt(remainder.substring(0, dot));
      } catch (NumberFormatException ex) {
         return "";
      }
      String field = remainder.substring(dot + 1);
      Optional<ServerHeartbeatEvent> heartbeat = cache == null ? Optional.empty() : cache.lobbyServerAt(index);
      if (heartbeat.isPresent()) {
         ServerHeartbeatEvent event = heartbeat.get();
         return switch (field) {
            case "id", "server_id" -> event.serverId();
            case "players", "online" -> Integer.toString(event.onlinePlayers());
            case "capacity", "max_players" -> Integer.toString(event.maxPlayers());
            case "display" -> SkyPvPPlaceholderSupport.compactServerNameForNavigator(event.serverId());
            case "load_percent" -> Integer.toString(percent(event.onlinePlayers(), Math.max(event.maxPlayers(), 1)));
            case "load_bar" -> NetworkAnimationEngine.capacityLabel(event.onlinePlayers(), Math.max(event.maxPlayers(), 1));
            case "joinable" -> event.joinable() ? "Open" : "Closed";
            default -> "";
         };
      }

      if (directory == null) {
         return "";
      }
      var snapshots = directory.listJoinableByRole(NetworkServerRole.LOBBY);
      if (index < 1 || index > snapshots.size()) {
         return "";
      }
      var snapshot = snapshots.get(index - 1);
      return switch (field) {
         case "id", "server_id" -> snapshot.serverId();
         case "players", "online" -> Integer.toString(snapshot.onlinePlayers());
         case "capacity", "max_players" -> Integer.toString(snapshot.maxPlayers());
         case "display" -> SkyPvPPlaceholderSupport.compactServerNameForNavigator(snapshot.serverId());
         case "load_percent" -> Integer.toString(percent(snapshot.onlinePlayers(), Math.max(snapshot.maxPlayers(), 1)));
         case "load_bar" -> NetworkAnimationEngine.capacityLabel(snapshot.onlinePlayers(), Math.max(snapshot.maxPlayers(), 1));
         case "joinable" -> snapshot.isRoutable() ? "Open" : "Closed";
         default -> "";
      };
   }

   private static String resolveIndexedBreachInstance(String remainder, NetworkHeartbeatCache cache) {
      if (cache == null) {
         return "";
      }
      int dot = remainder.indexOf('.');
      if (dot <= 0) {
         return "";
      }
      int index;
      try {
         index = Integer.parseInt(remainder.substring(0, dot));
      } catch (NumberFormatException ex) {
         return "";
      }
      String field = remainder.substring(dot + 1);
      Optional<NetworkHeartbeatCache.AggregatedBreachInstance> instanceOpt = cache.breachInstanceAt(index);
      if (instanceOpt.isEmpty()) {
         return defaultBreachInstance(field);
      }
      NetworkHeartbeatCache.AggregatedBreachInstance instance = instanceOpt.get();
      return switch (field) {
         case "server", "server_id", "pod" -> instance.serverId();
         case "instance_id", "id" -> instance.snapshot().instanceId();
         case "map", "map_id" -> instance.snapshot().mapId();
         case "map_display", "display" -> NetworkHeartbeatCache.formatMapDisplay(instance.snapshot().mapId());
         case "open_slots", "slots_open" -> Integer.toString(instance.snapshot().openSlots());
         case "max_players", "capacity" -> Integer.toString(instance.snapshot().maxPlayers());
         case "players", "occupied", "active_players" -> Integer.toString(instance.occupiedPlayers());
         case "joinable" -> instance.snapshot().joinable() ? "yes" : "no";
         case "joinable_label" -> instance.snapshot().joinable() ? "Joinable" : "Full";
         case "load_percent" -> Integer.toString(percent(instance.occupiedPlayers(), Math.max(instance.snapshot().maxPlayers(), 1)));
         case "version", "instance_version" -> shortInstanceVersion(instance.snapshot().instanceId());
         default -> "";
      };
   }

   private static String defaultBreachInstance(String field) {
      return switch (field) {
         case "joinable" -> "no";
         case "joinable_label" -> "Unavailable";
         case "map_display", "display" -> "None";
         default -> "";
      };
   }

   private static String buildLobbyStatusLine(NetworkHeartbeatCache cache, NetworkServerDirectoryRepository directory) {
      int players = liveLobbyPlayers(cache, directory);
      int servers = liveLobbyServers(cache, directory);
      return players + " online across " + servers + " hub" + (servers == 1 ? "" : "s");
   }

   private static String buildExtractionStatusLine(NetworkHeartbeatCache cache, NetworkServerDirectoryRepository directory) {
      int pods = liveExtractionServers(cache, directory);
      int players = liveExtractionPlayers(cache, directory);
      if (pods <= 0) {
         return "No live extraction pods";
      }
      int openSlots = cache == null ? 0 : cache.totalOpenBreachSlots();
      int queued = cache == null ? 0 : cache.totalQueuedPlayers();
      return players + " in pods • " + pods + " live • " + openSlots + " breach slots • " + queued + " queued";
   }

   private static String buildBreachStatusLine(NetworkHeartbeatCache cache) {
      if (cache == null) {
         return "No live breach data";
      }
      int raids = cache.aggregatedBreachInstances().size();
      int raiders = cache.totalRaidParticipants();
      int queued = cache.totalQueuedPlayers();
      if (raids <= 0) {
         return queued > 0 ? queued + " queued for breach" : "No active breaches";
      }
      return raiders + " raiding • " + raids + " live • " + queued + " queued";
   }

   private static int liveLobbyServers(NetworkHeartbeatCache cache, NetworkServerDirectoryRepository directory) {
      if (cache != null) {
         int count = cache.liveServerCountForRole(NetworkServerRole.LOBBY);
         if (count > 0) {
            return count;
         }
      }
      return directory == null ? 0 : directory.summarizeRole(NetworkServerRole.LOBBY).liveServers();
   }

   private static int liveLobbyPlayers(NetworkHeartbeatCache cache, NetworkServerDirectoryRepository directory) {
      if (cache != null) {
         int count = cache.totalPlayersForRole(NetworkServerRole.LOBBY);
         if (count > 0) {
            return count;
         }
      }
      return directory == null ? 0 : directory.summarizeRole(NetworkServerRole.LOBBY).totalPlayers();
   }

   private static int liveLobbyCapacity(NetworkHeartbeatCache cache, NetworkServerDirectoryRepository directory) {
      if (cache != null) {
         int count = cache.totalCapacityForRole(NetworkServerRole.LOBBY);
         if (count > 0) {
            return count;
         }
      }
      return directory == null ? 0 : directory.summarizeRole(NetworkServerRole.LOBBY).totalCapacity();
   }

   private static int liveExtractionServers(NetworkHeartbeatCache cache, NetworkServerDirectoryRepository directory) {
      if (cache != null) {
         int count = cache.liveServerCountForRole(NetworkServerRole.EXTRACTION);
         if (count > 0) {
            return count;
         }
      }
      return directory == null ? 0 : directory.summarizeRole(NetworkServerRole.EXTRACTION).liveServers();
   }

   private static int liveExtractionPlayers(NetworkHeartbeatCache cache, NetworkServerDirectoryRepository directory) {
      if (cache != null) {
         int count = cache.totalPlayersForRole(NetworkServerRole.EXTRACTION);
         if (count > 0) {
            return count;
         }
      }
      return directory == null ? 0 : directory.summarizeRole(NetworkServerRole.EXTRACTION).totalPlayers();
   }

   private static int percent(int value, int total) {
      if (total <= 0) {
         return 0;
      }
      return Math.min(100, (int)Math.round((double)value * 100.0 / (double)total));
   }

   private static String shortInstanceVersion(String instanceId) {
      if (instanceId == null || instanceId.isBlank()) {
         return "v?";
      }
      if (instanceId.length() <= 8) {
         return instanceId;
      }
      return instanceId.substring(0, 8);
   }

   private static LobbyPlaceholderBridge lobbyBridge(PaperCorePlugin plugin) {
      Collection<RegisteredServiceProvider<LobbyPlaceholderBridge>> registrations = plugin.getServer()
         .getServicesManager()
         .getRegistrations(LobbyPlaceholderBridge.class);
      for (RegisteredServiceProvider<LobbyPlaceholderBridge> registration : registrations) {
         LobbyPlaceholderBridge provider = registration.getProvider();
         if (provider != null) {
            return provider;
         }
      }
      return null;
   }
}
