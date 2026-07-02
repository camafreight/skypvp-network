package network.skypvp.proxy.command;

import network.skypvp.shared.ServerTextUtil;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.proxy.registry.NetworkStateRegistry;

import network.skypvp.proxy.repository.WorldPresetRegistryRepository;
import network.skypvp.proxy.state.WorldPresetStatus;

public final class ProxyPresetCommand {
   private final WorldPresetRegistryRepository presetRepository;
   private final NetworkStateRegistry stateRegistry;

   public ProxyPresetCommand(WorldPresetRegistryRepository presetRepository, NetworkStateRegistry stateRegistry) {
      this.presetRepository = presetRepository;
      this.stateRegistry = stateRegistry;
   }

   public BrigadierCommand build() {
      LiteralArgumentBuilder<CommandSource> root = LiteralArgumentBuilder.<CommandSource>literal("presetctl");
      root.then(LiteralArgumentBuilder.<CommandSource>literal("list").executes(ctx -> {
         this.executeList((CommandSource)ctx.getSource());
         return 1;
      }));
      root.then(LiteralArgumentBuilder.<CommandSource>literal("show").then(RequiredArgumentBuilder.<CommandSource, String>argument("presetId", StringArgumentType.word()).executes(ctx -> {
         this.executeShow((CommandSource)ctx.getSource(), StringArgumentType.getString(ctx, "presetId"));
         return 1;
      })));
      root.then(((LiteralArgumentBuilder)LiteralArgumentBuilder.<CommandSource>literal("history").executes(ctx -> {
         this.executeHistory((CommandSource)ctx.getSource(), 15);
         return 1;
      })).then(RequiredArgumentBuilder.<CommandSource, Integer>argument("limit", IntegerArgumentType.integer(1, 200)).executes(ctx -> {
         this.executeHistory((CommandSource)ctx.getSource(), IntegerArgumentType.getInteger(ctx, "limit"));
         return 1;
      })));
      root.then(
         LiteralArgumentBuilder.<CommandSource>literal("register")
            .then(
               RequiredArgumentBuilder.<CommandSource, String>argument("presetId", StringArgumentType.word())
                  .then(
                     RequiredArgumentBuilder.<CommandSource, Integer>argument("version", IntegerArgumentType.integer(1, 9999))
                        .executes(
                           ctx -> {
                              this.executeRegister(
                                 (CommandSource)ctx.getSource(), StringArgumentType.getString(ctx, "presetId"), IntegerArgumentType.getInteger(ctx, "version")
                              );
                              return 1;
                           }
                        )
                  )
            )
      );
      root.then(
         LiteralArgumentBuilder.<CommandSource>literal("status")
            .then(
               RequiredArgumentBuilder.<CommandSource, String>argument("presetId", StringArgumentType.word())
                  .then(
                     ((RequiredArgumentBuilder)RequiredArgumentBuilder.<CommandSource, String>argument("status", StringArgumentType.word())
                           .suggests((ctx, builder) -> {
                              Arrays.stream(WorldPresetStatus.values()).forEach(s -> builder.suggest(s.name()));
                              return builder.buildFuture();
                           })
                           .executes(
                              ctx -> {
                                 this.executeSetStatus(
                                    (CommandSource)ctx.getSource(),
                                    StringArgumentType.getString(ctx, "presetId"),
                                    StringArgumentType.getString(ctx, "status"),
                                    "manual status change"
                                 );
                                 return 1;
                              }
                           ))
                        .then(
                           RequiredArgumentBuilder.<CommandSource, String>argument("reason", StringArgumentType.greedyString())
                              .executes(
                                 ctx -> {
                                    this.executeSetStatus(
                                       (CommandSource)ctx.getSource(),
                                       StringArgumentType.getString(ctx, "presetId"),
                                       StringArgumentType.getString(ctx, "status"),
                                       StringArgumentType.getString(ctx, "reason")
                                    );
                                    return 1;
                                 }
                              )
                        )
                  )
            )
      );
      root.then(
         LiteralArgumentBuilder.<CommandSource>literal("activate")
            .then(((RequiredArgumentBuilder)RequiredArgumentBuilder.<CommandSource, String>argument("presetId", StringArgumentType.word()).executes(ctx -> {
               this.executeActivate((CommandSource)ctx.getSource(), StringArgumentType.getString(ctx, "presetId"), "activate preset");
               return 1;
            })).then(RequiredArgumentBuilder.<CommandSource, String>argument("reason", StringArgumentType.greedyString()).executes(ctx -> {
               this.executeActivate((CommandSource)ctx.getSource(), StringArgumentType.getString(ctx, "presetId"), StringArgumentType.getString(ctx, "reason"));
               return 1;
            })))
      );
      root.then(
         LiteralArgumentBuilder.<CommandSource>literal("rollback")
            .then(((RequiredArgumentBuilder)RequiredArgumentBuilder.<CommandSource, String>argument("presetId", StringArgumentType.word()).executes(ctx -> {
               this.executeRollback((CommandSource)ctx.getSource(), StringArgumentType.getString(ctx, "presetId"), "rollback preset");
               return 1;
            })).then(RequiredArgumentBuilder.<CommandSource, String>argument("reason", StringArgumentType.greedyString()).executes(ctx -> {
               this.executeRollback((CommandSource)ctx.getSource(), StringArgumentType.getString(ctx, "presetId"), StringArgumentType.getString(ctx, "reason"));
               return 1;
            })))
      );
      root.executes(
         ctx -> {
            ((CommandSource)ctx.getSource())
               .sendMessage(ServerTextUtil.component("&eUsage: /presetctl <list|show|history|register|status|activate|rollback>"));
            return 1;
         }
      );
      LiteralCommandNode<CommandSource> node = root.build();
      return new BrigadierCommand(node);
   }

   private void executeList(CommandSource source) {
      if (!this.hasAdminPermission(source)) {
         source.sendMessage(ServerTextUtil.component("&cNo permission."));
      } else {
         List<WorldPresetRegistryRepository.WorldPresetSnapshot> presets = this.presetRepository.listPresets();
         if (presets.isEmpty()) {
            source.sendMessage(ServerTextUtil.component("&eNo presets registered in SQL."));
         } else {
            source.sendMessage(ServerTextUtil.component("&bWorld presets:"));

            for (WorldPresetRegistryRepository.WorldPresetSnapshot p : presets) {
               source.sendMessage(
                  ServerTextUtil.component("&7" + p.presetId() + " | v" + p.version() + " | status=" + p.status() + " | worlds=" + p.worldCount())
               );
            }
         }
      }
   }

   private void executeShow(CommandSource source, String presetId) {
      if (!this.hasAdminPermission(source)) {
         source.sendMessage(ServerTextUtil.component("&cNo permission."));
      } else {
         Optional<WorldPresetRegistryRepository.WorldPresetSnapshot> snapshot = this.presetRepository.findPreset(presetId);
         if (snapshot.isEmpty()) {
            source.sendMessage(ServerTextUtil.component("&c" + "Preset not found: " + presetId));
         } else {
            WorldPresetRegistryRepository.WorldPresetSnapshot p = snapshot.get();
            source.sendMessage(ServerTextUtil.component("&b" + "Preset " + p.presetId()));
            source.sendMessage(ServerTextUtil.component("&7" + "  version=" + p.version() + " | status=" + p.status()));
            source.sendMessage(
               ServerTextUtil.component("&7" + "  worlds=" + p.worldCount() + " | checksum=" + (p.checksumSha256() == null ? "-" : p.checksumSha256()))
            );
            source.sendMessage(ServerTextUtil.component("&7" + "  createdBy=" + p.createdBy() + " | createdAt=" + p.createdAt()));
            source.sendMessage(
               ServerTextUtil.component("&7" + "  updatedAt=" + p.updatedAt() + " | description=" + (p.description() == null ? "" : p.description()))
            );
         }
      }
   }

   private void executeRegister(CommandSource source, String presetId, int version) {
      if (!this.hasAdminPermission(source)) {
         source.sendMessage(ServerTextUtil.component("&cNo permission."));
      } else if (!this.isValidPresetId(presetId)) {
         source.sendMessage(ServerTextUtil.component("&cInvalid preset ID."));
      } else {
         String actor = this.actorName(source);
         boolean ok = this.presetRepository.upsertPreset(presetId, version, WorldPresetStatus.DRAFT, "", null, 1, actor);
         if (ok) {
            source.sendMessage(ServerTextUtil.component("&a" + "Registered preset " + presetId + " v" + version + " as DRAFT."));
         } else {
            source.sendMessage(ServerTextUtil.component("&cFailed to register preset."));
         }
      }
   }

   private void executeHistory(CommandSource source, int limit) {
      if (!this.hasAdminPermission(source)) {
         source.sendMessage(ServerTextUtil.component("&cNo permission."));
      } else {
         List<WorldPresetRegistryRepository.WorldPresetPromotionAudit> rows = this.presetRepository.listRecentPromotions(limit);
         if (rows.isEmpty()) {
            source.sendMessage(ServerTextUtil.component("&eNo preset promotion history found."));
         } else {
            source.sendMessage(ServerTextUtil.component("&bRecent preset promotions:"));

            for (WorldPresetRegistryRepository.WorldPresetPromotionAudit row : rows) {
               source.sendMessage(
                  ServerTextUtil.component("#"
                        + row.promotionId()
                        + " | "
                        + row.presetId()
                        + " | "
                        + (row.fromStatus() == null ? "-" : row.fromStatus())
                        + " -> "
                        + row.toStatus()
                        + " | by="
                        + (row.promotedBy() == null ? "system" : row.promotedBy())
                        + " | at="
                        + row.promotedAt(),
                     NamedTextColor.GRAY
                  )
               );
            }
         }
      }
   }

   private void executeSetStatus(CommandSource source, String presetId, String statusRaw, String reason) {
      if (!this.hasAdminPermission(source)) {
         source.sendMessage(ServerTextUtil.component("&cNo permission."));
      } else if (!this.isValidPresetId(presetId)) {
         source.sendMessage(ServerTextUtil.component("&cInvalid preset ID."));
      } else {
         WorldPresetStatus status;
         try {
            status = WorldPresetStatus.valueOf(statusRaw.trim().toUpperCase(Locale.ROOT));
         } catch (IllegalArgumentException var7) {
            source.sendMessage(ServerTextUtil.component("&cInvalid status. Use DRAFT|VALIDATED|ACTIVE|DEPRECATED."));
            return;
         }

         boolean ok = this.presetRepository.setStatus(presetId, status, reason, this.actorName(source));
         if (ok) {
            source.sendMessage(ServerTextUtil.component("&a" + "Preset " + presetId + " -> " + status + "."));
         } else {
            source.sendMessage(ServerTextUtil.component("&cFailed to change preset status."));
         }
      }
   }

   private void executeActivate(CommandSource source, String presetId, String reason) {
      this.executeSetStatus(source, presetId, WorldPresetStatus.ACTIVE.name(), reason);
   }

   private void executeRollback(CommandSource source, String presetId, String reason) {
      this.executeSetStatus(source, presetId, WorldPresetStatus.DEPRECATED.name(), reason);
   }

   private String actorName(CommandSource source) {
      return source instanceof Player player ? player.getUsername() : "console";
   }

   private boolean isValidPresetId(String presetId) {
      return presetId != null && !presetId.isBlank() && presetId.matches("[a-zA-Z0-9_\\-]+$");
   }

   private boolean hasAdminPermission(CommandSource source) {
      if (source instanceof Player player) {
         return player.hasPermission("skypvp.admin.rank") || player.hasPermission("skypvp.admin");
      } else {
         return true;
      }
   }
}
