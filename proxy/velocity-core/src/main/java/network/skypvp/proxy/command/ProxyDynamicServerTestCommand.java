package network.skypvp.proxy.command;

import network.skypvp.shared.ServerTextUtil;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import network.skypvp.proxy.registry.NetworkStateRegistry;
import network.skypvp.proxy.service.KubernetesApiService;

public final class ProxyDynamicServerTestCommand {
   private static final String CONFIG_MAP = "skypvp-game-server-config";
   private static final String GENERATION_KEY = "ORCHESTRATION_GENERATION";
   private final ProxyServer proxyServer;
   private final NetworkStateRegistry stateRegistry;
   private final KubernetesApiService kubeClient;

   public ProxyDynamicServerTestCommand(ProxyServer proxyServer, NetworkStateRegistry stateRegistry, KubernetesApiService kubeClient) {
      this.proxyServer = proxyServer;
      this.stateRegistry = stateRegistry;
      this.kubeClient = kubeClient;
   }

   public BrigadierCommand build() {
      SuggestionProvider<CommandSource> targetSuggestions = (ctx, builder) -> {
         ProxyDynamicServerTestCommand.KubeTarget.BY_ALIAS.keySet().forEach(builder::suggest);
         return builder.buildFuture();
      };
      LiteralArgumentBuilder<CommandSource> root = (LiteralArgumentBuilder<CommandSource>)LiteralArgumentBuilder.<CommandSource>literal("dynserver").executes(ctx -> {
         this.sendUsage((CommandSource)ctx.getSource());
         return 1;
      });
      root.then(LiteralArgumentBuilder.<CommandSource>literal("list").executes(ctx -> {
         this.executeList((CommandSource)ctx.getSource());
         return 1;
      }));
      RequiredArgumentBuilder<CommandSource, String> targetArg = (RequiredArgumentBuilder<CommandSource, String>)RequiredArgumentBuilder.<CommandSource, String>argument(
            "target", StringArgumentType.word()
         )
         .suggests(targetSuggestions)
         .executes(ctx -> {
            this.sendUsage((CommandSource)ctx.getSource());
            return 1;
         });
      targetArg.then(LiteralArgumentBuilder.<CommandSource>literal("status").executes(ctx -> {
         this.executeStatus((CommandSource)ctx.getSource(), StringArgumentType.getString(ctx, "target"));
         return 1;
      }));
      targetArg.then(LiteralArgumentBuilder.<CommandSource>literal("restart").executes(ctx -> {
         this.executeRestart((CommandSource)ctx.getSource(), StringArgumentType.getString(ctx, "target"));
         return 1;
      }));
      targetArg.then(
         LiteralArgumentBuilder.<CommandSource>literal("scale").then(RequiredArgumentBuilder.<CommandSource, Integer>argument("replicas", IntegerArgumentType.integer(0, 20)).executes(ctx -> {
            this.executeScale((CommandSource)ctx.getSource(), StringArgumentType.getString(ctx, "target"), IntegerArgumentType.getInteger(ctx, "replicas"));
            return 1;
         }))
      );
      targetArg.then(
         LiteralArgumentBuilder.<CommandSource>literal("preset").then(RequiredArgumentBuilder.<CommandSource, String>argument("presetId", StringArgumentType.word()).executes(ctx -> {
            this.executeSetPreset((CommandSource)ctx.getSource(), StringArgumentType.getString(ctx, "target"), StringArgumentType.getString(ctx, "presetId"));
            return 1;
         }))
      );
      targetArg.then(((LiteralArgumentBuilder)LiteralArgumentBuilder.<CommandSource>literal("bump").executes(ctx -> {
         this.executeBump((CommandSource)ctx.getSource(), StringArgumentType.getString(ctx, "target"), 1);
         return 1;
      })).then(RequiredArgumentBuilder.<CommandSource, Integer>argument("delta", IntegerArgumentType.integer(1, 1000)).executes(ctx -> {
         this.executeBump((CommandSource)ctx.getSource(), StringArgumentType.getString(ctx, "target"), IntegerArgumentType.getInteger(ctx, "delta"));
         return 1;
      })));
      root.then(targetArg);
      LiteralCommandNode<CommandSource> node = root.build();
      return new BrigadierCommand(node);
   }

   private void executeList(CommandSource source) {
      if (!this.hasAdminPermission(source)) {
         source.sendMessage(ServerTextUtil.component("&cNo permission."));
      } else if (!this.kubeClient.available()) {
         source.sendMessage(this.unavailableMsg());
      } else {
         source.sendMessage(
            ServerTextUtil.component("&b&l-- SkyPvP Dynamic Servers -- ns=" + this.kubeClient.namespace() + " --")
         );

         for (ProxyDynamicServerTestCommand.KubeTarget target : ProxyDynamicServerTestCommand.KubeTarget.values()) {
            try {
               KubernetesApiService.KubernetesResourceStatus s = this.kubeClient.getResourceStatus(target.k8sName, target.kind);
               String flags = target.scalable ? "" : " &8[fixed]";
               String kindLabel = target.kind == KubernetesApiService.ResourceKind.STATEFULSET ? "STS" : "DEP";
               String color = s.readyReplicas() == s.specReplicas() ? "&a" : (s.readyReplicas() > 0 ? "&e" : "&c");
               
               source.sendMessage(
                  ServerTextUtil.component(
                     String.format(
                        "  &f%-10s &7%s  %s%d/%d ready &7(%d avail)%s", 
                        target.alias, kindLabel, color, s.readyReplicas(), s.specReplicas(), s.availableReplicas(), flags
                     )
                  )
               );
            } catch (IllegalArgumentException e) {
               String flags = target.scalable ? "" : " &8[fixed]";
               String kindLabel = target.kind == KubernetesApiService.ResourceKind.STATEFULSET ? "STS" : "DEP";
               source.sendMessage(
                  ServerTextUtil.component(
                     String.format(
                        "  &f%-10s &7%s  &c0/0 ready &7(Offline)%s", 
                        target.alias, kindLabel, flags
                     )
                  )
               );
            } catch (Exception var9) {
               source.sendMessage(ServerTextUtil.component("&c  " + target.alias + ": ERROR - " + var9.getMessage()));
            }
         }
      }
   }

   private void executeStatus(CommandSource source, String targetAlias) {
      if (!this.hasAdminPermission(source)) {
         source.sendMessage(ServerTextUtil.component("&cNo permission."));
      } else {
         ProxyDynamicServerTestCommand.KubeTarget target = this.resolveOrError(source, targetAlias);
         if (target != null) {
            if (!this.kubeClient.available()) {
               source.sendMessage(this.unavailableMsg());
            } else {
               try {
                  KubernetesApiService.KubernetesResourceStatus s = this.kubeClient.getResourceStatus(target.k8sName, target.kind);
                  OptionalInt gen = target.isGameServer
                     ? this.kubeClient.getConfigMapInt(CONFIG_MAP, GENERATION_KEY)
                     : OptionalInt.empty();
                  StringBuilder sb = new StringBuilder()
                     .append("[dynserver] ")
                     .append(target.alias)
                     .append(" (")
                     .append(target.k8sName)
                     .append(")")
                     .append("  spec=")
                     .append(s.specReplicas())
                     .append(" ready=")
                     .append(s.readyReplicas())
                     .append(" avail=")
                     .append(s.availableReplicas());
                  if (gen.isPresent()) {
                     sb.append("  gen=").append(gen.getAsInt());
                  }

                  source.sendMessage(ServerTextUtil.component("&b" + sb.toString()));
               } catch (Exception var7) {
                  source.sendMessage(ServerTextUtil.component("&c" + "[dynserver] status failed: " + var7.getMessage()));
               }
            }
         }
      }
   }

   private void executeRestart(CommandSource source, String targetAlias) {
      if (!this.hasAdminPermission(source)) {
         source.sendMessage(ServerTextUtil.component("&cNo permission."));
      } else {
         ProxyDynamicServerTestCommand.KubeTarget target = this.resolveOrError(source, targetAlias);
         if (target != null) {
            if (!this.kubeClient.available()) {
               source.sendMessage(this.unavailableMsg());
            } else {
               try {
                  this.kubeClient.restartResource(target.k8sName, target.kind);
                  source.sendMessage(
                     ServerTextUtil.component("&a" + "[dynserver] Rolling restart triggered for " + target.alias + " (" + target.k8sName + ").")
                  );
               } catch (Exception var5) {
                  source.sendMessage(ServerTextUtil.component("&c" + "[dynserver] restart failed: " + var5.getMessage()));
               }
            }
         }
      }
   }

   private void executeScale(CommandSource source, String targetAlias, int replicas) {
      if (!this.hasAdminPermission(source)) {
         source.sendMessage(ServerTextUtil.component("&cNo permission."));
      } else {
         ProxyDynamicServerTestCommand.KubeTarget target = this.resolveOrError(source, targetAlias);
         if (target != null) {
            if (!target.scalable) {
               source.sendMessage(
                  ServerTextUtil.component("&c" + "[dynserver] " + target.alias + " is fixed in this topology and cannot be scaled horizontally from here.")
               );
            } else if (!this.kubeClient.available()) {
               source.sendMessage(this.unavailableMsg());
            } else {
               try {
                  this.kubeClient.scaleResource(target.k8sName, target.kind, replicas);
                  source.sendMessage(ServerTextUtil.component("&a" + "[dynserver] Scaled " + target.alias + " to " + replicas + " replica(s)."));
               } catch (Exception var6) {
                  source.sendMessage(ServerTextUtil.component("&c" + "[dynserver] scale failed: " + var6.getMessage()));
               }
            }
         }
      }
   }

   private void executeBump(CommandSource source, String targetAlias, int delta) {
      if (!this.hasAdminPermission(source)) {
         source.sendMessage(ServerTextUtil.component("&cNo permission."));
      } else {
         ProxyDynamicServerTestCommand.KubeTarget target = this.resolveOrError(source, targetAlias);
         if (target != null) {
            if (!target.isGameServer) {
               source.sendMessage(ServerTextUtil.component("&c[dynserver] bump is only valid for game-server targets."));
            } else if (!this.kubeClient.available()) {
               source.sendMessage(this.unavailableMsg());
            } else {
               try {
                  int current = this.kubeClient.getConfigMapInt(CONFIG_MAP, GENERATION_KEY).orElse(0);
                  int next = Math.max(0, current + delta);
                  this.kubeClient.patchConfigMapValue(CONFIG_MAP, GENERATION_KEY, Integer.toString(next));
                  this.kubeClient.restartResource(target.k8sName, target.kind);
                  source.sendMessage(
                     ServerTextUtil.component("&a[dynserver] Generation " + current + " -> " + next + " committed; rolling restart triggered for " + target.alias + ".")
                  );
               } catch (Exception var7) {
                  source.sendMessage(ServerTextUtil.component("&c" + "[dynserver] bump failed: " + var7.getMessage()));
               }
            }
         }
      }
   }

   private void executeSetPreset(CommandSource source, String targetAlias, String presetId) {
      if (!this.hasAdminPermission(source)) {
         source.sendMessage(ServerTextUtil.component("&cNo permission."));
      } else {
         ProxyDynamicServerTestCommand.KubeTarget target = this.resolveOrError(source, targetAlias);
         if (target != null) {
            if (!target.isGameServer) {
               source.sendMessage(ServerTextUtil.component("&c[dynserver] preset is only valid for game-server targets."));
            } else if (!this.kubeClient.available()) {
               source.sendMessage(this.unavailableMsg());
            } else {
               try {
                  this.kubeClient.patchConfigMapValue(CONFIG_MAP, "PRESET_ID", presetId);
                  this.kubeClient.restartResource(target.k8sName, target.kind);
                  source.sendMessage(
                     ServerTextUtil.component("&a[dynserver] Global preset set to '" + presetId + "' and rolling restart triggered for " + target.alias + ".")
                  );
               } catch (Exception var6) {
                  source.sendMessage(ServerTextUtil.component("&c[dynserver] preset failed: " + var6.getMessage()));
               }
            }
         }
      }
   }

   private ProxyDynamicServerTestCommand.KubeTarget resolveOrError(CommandSource source, String alias) {
      ProxyDynamicServerTestCommand.KubeTarget t = ProxyDynamicServerTestCommand.KubeTarget.resolve(alias);
      if (t == null) {
         String known = ProxyDynamicServerTestCommand.KubeTarget.BY_ALIAS.keySet().stream().collect(Collectors.joining(", "));
         source.sendMessage(ServerTextUtil.component("&c" + "[dynserver] Unknown target '" + alias + "'. Known: " + known));
      }

      return t;
   }

   private Component unavailableMsg() {
      return ServerTextUtil.component("&c[dynserver] Kubernetes in-cluster credentials not available. Is the proxy running inside the cluster?");
   }

   private void sendUsage(CommandSource source) {
      String targets = Arrays.stream(ProxyDynamicServerTestCommand.KubeTarget.values()).map(t -> t.alias).collect(Collectors.joining("|"));
      source.sendMessage(ServerTextUtil.component("&eUsage:"));
      source.sendMessage(ServerTextUtil.component("&7  /dynserver list"));
      source.sendMessage(ServerTextUtil.component("&7" + "  /dynserver <" + targets + "> status"));
      source.sendMessage(ServerTextUtil.component("&7  /dynserver <lobby|extraction> scale <replicas>"));
      source.sendMessage(ServerTextUtil.component("&7  /dynserver <staging> preset <presetId>"));
      source.sendMessage(ServerTextUtil.component("&7" + "  /dynserver <" + targets + "> restart"));
      source.sendMessage(ServerTextUtil.component("&7  /dynserver <lobby|extraction> bump [delta]"));
   }

   private boolean hasAdminPermission(CommandSource source) {
      if (source instanceof Player player) {
         return player.hasPermission("skypvp.admin.rank") || player.hasPermission("skypvp.admin");
      } else {
         return true;
      }
   }

   static enum KubeTarget {
      LOBBY("lobby", "skypvp-lobby", KubernetesApiService.ResourceKind.STATEFULSET, true, true),
      EXTRACTION("extraction", "skypvp-extraction", KubernetesApiService.ResourceKind.STATEFULSET, true, true),
      PROXY("proxy", "skypvp-proxy", KubernetesApiService.ResourceKind.DEPLOYMENT, false, false);

      final String alias;
      final String k8sName;
      final KubernetesApiService.ResourceKind kind;
      final boolean isGameServer;
      final boolean scalable;
      static final Map<String, ProxyDynamicServerTestCommand.KubeTarget> BY_ALIAS = new LinkedHashMap<>();

      private KubeTarget(String alias, String k8sName, KubernetesApiService.ResourceKind kind, boolean isGameServer, boolean scalable) {
         this.alias = alias;
         this.k8sName = k8sName;
         this.kind = kind;
         this.isGameServer = isGameServer;
         this.scalable = scalable;
      }

      static ProxyDynamicServerTestCommand.KubeTarget resolve(String input) {
         return BY_ALIAS.get(input == null ? "" : input.toLowerCase());
      }

      static {
         for (ProxyDynamicServerTestCommand.KubeTarget t : values()) {
            BY_ALIAS.put(t.alias, t);
         }
      }
   }
}
