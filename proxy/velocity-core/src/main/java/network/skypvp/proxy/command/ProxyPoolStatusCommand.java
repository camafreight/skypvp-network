package network.skypvp.proxy.command;

import network.skypvp.shared.ServerTextUtil;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent.Builder;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import network.skypvp.proxy.registry.NetworkStateRegistry;

import network.skypvp.proxy.service.QueueService;
import network.skypvp.proxy.service.ServerRoutingService;

public final class ProxyPoolStatusCommand {
   private final ServerRoutingService routingService;
   private final QueueService queueService;
   private final NetworkStateRegistry stateRegistry;

   public ProxyPoolStatusCommand(
      ServerRoutingService routingService, QueueService queueService, NetworkStateRegistry stateRegistry
   ) {
      this.routingService = routingService;
      this.queueService = queueService;
      this.stateRegistry = stateRegistry;
   }

   public BrigadierCommand build() {
      LiteralArgumentBuilder<CommandSource> root = (LiteralArgumentBuilder<CommandSource>)LiteralArgumentBuilder.<CommandSource>literal("scpools").executes(ctx -> {
         this.executeList((CommandSource)ctx.getSource(), null);
         return 1;
      });
      root.then(RequiredArgumentBuilder.<CommandSource, String>argument("pool", StringArgumentType.word()).executes(ctx -> {
         this.executeList((CommandSource)ctx.getSource(), StringArgumentType.getString(ctx, "pool"));
         return 1;
      }));
      return new BrigadierCommand(root);
   }

   private void executeList(CommandSource source, String filterPool) {
      if (!this.isAuthorized(source)) {
         source.sendMessage(ServerTextUtil.component("&cYou do not have permission to use this command."));
      } else {
         List<ServerRoutingService.ServerRouteStatus> all = this.routingService.snapshotStatuses();
         Map<String, List<ServerRoutingService.ServerRouteStatus>> byPool = all.stream()
            .filter(s -> s.cluster() != null && !s.cluster().isBlank())
            .collect(Collectors.groupingBy(s -> s.cluster().toLowerCase(), LinkedHashMap::new, Collectors.toList()));
         all.stream()
            .filter(s -> s.cluster() == null || s.cluster().isBlank())
            .collect(Collectors.groupingBy(s -> "~" + s.role().toLowerCase(), Collectors.toList()))
            .forEach(byPool::put);
         Map<String, Integer> queueSizes = this.queueService.queueSizes();
         if (byPool.isEmpty()) {
            source.sendMessage(ServerTextUtil.component("&eNo server pools currently visible to the routing layer."));
         } else {
            source.sendMessage(
               ((Builder)Component.text()
                     .append(Component.text("=== SkyPvP Pool Status ===", NamedTextColor.GOLD, new TextDecoration[]{TextDecoration.BOLD})))
                  .build()
            );
            byPool.entrySet()
               .stream()
               .filter(e -> filterPool == null || filterPool.equalsIgnoreCase(e.getKey()))
               .sorted(Comparator.comparing(Entry::getKey))
               .forEach(entry -> this.renderPool(source, entry.getKey(), entry.getValue(), queueSizes));
            if (filterPool != null && !byPool.containsKey(filterPool.toLowerCase())) {
               source.sendMessage(ServerTextUtil.component("&c" + "Pool '" + filterPool + "' not found."));
            }

            source.sendMessage(
               ServerTextUtil.component("&7" + "Total pools: " + (filterPool == null ? byPool.size() : "1 (filtered)") + " | Total servers: " + all.size())
            );
         }
      }
   }

   private void renderPool(CommandSource source, String poolKey, List<ServerRoutingService.ServerRouteStatus> shards, Map<String, Integer> queueSizes) {
      int totalOnline = shards.stream().mapToInt(ServerRoutingService.ServerRouteStatus::onlinePlayers).sum();
      int totalCapacity = shards.stream().mapToInt(ServerRoutingService.ServerRouteStatus::maxPlayers).sum();
      long joinableCount = shards.stream().filter(s -> s.joinable() && !s.stale()).count();
      long staleCount = shards.stream().filter(ServerRoutingService.ServerRouteStatus::stale).count();
      int queueDepth = queueSizes.getOrDefault(poolKey.toUpperCase(), 0) + queueSizes.getOrDefault(poolKey, 0);
      String roles = shards.stream().map(ServerRoutingService.ServerRouteStatus::role).distinct().sorted().collect(Collectors.joining("/"));
      source.sendMessage(
         ServerTextUtil.component("&7&m                    ")
            .append(Component.newline())
            .append(ServerTextUtil.component(" Pool: "))
            .append(Component.text(poolKey, NamedTextColor.AQUA, new TextDecoration[]{TextDecoration.BOLD}))
            .append(ServerTextUtil.component("&3" + " [" + roles + "]"))
      );
      source.sendMessage(
         ServerTextUtil.component("   Shards: "
               + shards.size()
               + " | Joinable: "
               + joinableCount
               + (staleCount > 0L ? " | Stale: " + staleCount : "")
               + " | Players: "
               + totalOnline
               + "/"
               + totalCapacity
               + " | Queued: "
               + queueDepth,
            NamedTextColor.WHITE
         )
      );
      shards.stream()
         .sorted(Comparator.comparing(ServerRoutingService.ServerRouteStatus::serverId))
         .forEach(
            s -> {
               NamedTextColor statusColor = s.stale() ? NamedTextColor.DARK_GRAY : (s.joinable() ? NamedTextColor.GREEN : NamedTextColor.YELLOW);
               String lifecycle = s.lifecycleState() != null ? s.lifecycleState().name() : "?";
               source.sendMessage(
                  ServerTextUtil.component("     "
                        + s.serverId()
                        + " ["
                        + lifecycle
                        + "] "
                        + s.onlinePlayers()
                        + "/"
                        + s.maxPlayers()
                        + (s.stale() ? " STALE" : "")
                        + (s.overSoftCapacity() ? " FULL" : ""),
                     statusColor
                  )
               );
            }
         );
   }

   private boolean isAuthorized(CommandSource source) {
      if (source instanceof Player player) {
         return player.hasPermission("skypvp.admin.rank") || player.hasPermission("skypvp.admin");
      } else {
         return true;
      }
   }
}
