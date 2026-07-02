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
import com.velocitypowered.api.proxy.ProxyServer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import network.skypvp.proxy.repository.PunishmentRepository;
import network.skypvp.shared.PunishmentRecord;
import org.slf4j.Logger;

public final class ProxyPunishCommand {
   private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMM d yyyy HH:mm z").withZone(ZoneId.systemDefault());
   private final ProxyServer proxyServer;
   private final PunishmentRepository punishments;
   private final Logger logger;

   public ProxyPunishCommand(ProxyServer proxyServer, PunishmentRepository punishments, Logger logger) {
      this.proxyServer = proxyServer;
      this.punishments = punishments;
      this.logger = logger;
   }

   public BrigadierCommand build() {
      LiteralArgumentBuilder<CommandSource> root = LiteralArgumentBuilder.<CommandSource>literal("punish")
         .requires(src -> src.hasPermission("skypvp.staff"));

      // /punish <type> <player> <durationSeconds> <reason>
      root.then(RequiredArgumentBuilder.<CommandSource, String>argument("type", StringArgumentType.word())
         .suggests((ctx, builder) -> {
            builder.suggest("ban");
            builder.suggest("mute");
            builder.suggest("kick");
            builder.suggest("warn");
            return builder.buildFuture();
         })
         .then(RequiredArgumentBuilder.<CommandSource, String>argument("player", StringArgumentType.word())
         .then(RequiredArgumentBuilder.<CommandSource, Integer>argument("durationSeconds", IntegerArgumentType.integer(-1))
         .then(RequiredArgumentBuilder.<CommandSource, String>argument("reason", StringArgumentType.greedyString())
         .executes(ctx -> {
            String typeStr = StringArgumentType.getString(ctx, "type").toLowerCase();
            String player = StringArgumentType.getString(ctx, "player");
            int duration = IntegerArgumentType.getInteger(ctx, "durationSeconds");
            String reason = StringArgumentType.getString(ctx, "reason");

            if (typeStr.equals("ban")) {
               this.executePunishment((CommandSource)ctx.getSource(), player, duration, reason, PunishmentRecord.PunishmentType.BAN);
            } else if (typeStr.equals("mute")) {
               this.executePunishment((CommandSource)ctx.getSource(), player, duration, reason, PunishmentRecord.PunishmentType.MUTE);
            } else if (typeStr.equals("kick")) {
               this.executeKick((CommandSource)ctx.getSource(), player, reason);
            } else if (typeStr.equals("warn")) {
               this.executeWarn((CommandSource)ctx.getSource(), player, reason);
            } else {
               reply((CommandSource)ctx.getSource(), error("Unknown punishment type: " + typeStr + ". Valid types: ban, mute, kick, warn."));
            }
            return 1;
         })))));

      // /punish unban <player>
      root.then(LiteralArgumentBuilder.<CommandSource>literal("unban")
         .then(RequiredArgumentBuilder.<CommandSource, String>argument("player", StringArgumentType.word())
         .executes(ctx -> {
            String player = StringArgumentType.getString(ctx, "player");
            this.executePardon((CommandSource)ctx.getSource(), player, PunishmentRecord.PunishmentType.BAN, "ban");
            return 1;
         })));

      // /punish unmute <player>
      root.then(LiteralArgumentBuilder.<CommandSource>literal("unmute")
         .then(RequiredArgumentBuilder.<CommandSource, String>argument("player", StringArgumentType.word())
         .executes(ctx -> {
            String player = StringArgumentType.getString(ctx, "player");
            this.executePardon((CommandSource)ctx.getSource(), player, PunishmentRecord.PunishmentType.MUTE, "mute");
            return 1;
         })));

      // /punish history <player>
      root.then(LiteralArgumentBuilder.<CommandSource>literal("history")
         .then(RequiredArgumentBuilder.<CommandSource, String>argument("player", StringArgumentType.word())
         .executes(ctx -> {
            String player = StringArgumentType.getString(ctx, "player");
            this.executeHistory((CommandSource)ctx.getSource(), player);
            return 1;
         })));

      LiteralCommandNode<CommandSource> node = root.build();
      return new BrigadierCommand(node);
   }

   private void executePunishment(CommandSource source, String targetName, int durationSeconds, String reason, PunishmentRecord.PunishmentType type) {
      String issuedBy = sourceIdentifier(source);
      Instant expiresAt = durationSeconds <= 0 ? null : Instant.now().plusSeconds(durationSeconds);
      String reasonFinal = reason.isBlank() ? "No reason provided." : reason;

      CompletableFuture.runAsync(
         () -> {
            UUID uuid = this.resolveUuid(targetName);
            if (uuid == null) {
               reply(source, error("Player '" + targetName + "' not found."));
            } else {
               this.punishments.issue(uuid, targetName, type, reasonFinal, issuedBy, expiresAt);
               this.logger.info("[PUNISH] {} issued {} to {} ({}): {}", issuedBy, type, targetName, uuid, reasonFinal);
               String expiry = expiresAt == null ? "Permanent" : DATE_FMT.format(expiresAt);
               Component staffMsg = ServerTextUtil.miniMessageComponent(
                  "<#FF5555>[STAFF] <reset><#FFFFFF>"
                     + targetName
                     + "<reset><#888888> has been "
                     + type.name().toLowerCase()
                     + "d by <reset><#FFD700>"
                     + issuedBy
                     + "<reset><#888888> · Reason: <reset><#FFFFFF>"
                     + sanitize(reasonFinal)
                     + "<reset><#888888> · Duration: <reset><#FFD700>"
                     + expiry
                     + "<reset>"
               );
               this.broadcastToStaff(staffMsg);
               if (type == PunishmentRecord.PunishmentType.BAN) {
                  this.proxyServer
                     .getPlayer(targetName)
                     .ifPresent(
                        p -> {
                           Component dc = ServerTextUtil.miniMessageComponent(
                              "\n<#CC0000><bold>⛔ You have been banned</bold><reset>\n\n<#888888>Reason: <reset><#FFFFFF>"
                                 + sanitize(reasonFinal)
                                 + "<reset>\n<#888888>Duration: <reset><#FFD700>"
                                 + expiry
                                 + "<reset>\n\n<#555555>───────────────────<reset>\n<#888888>Appeal: <reset><#55FF55>discord.gg/SkyPvP<reset>\n"
                           );
                           p.disconnect(dc);
                        }
                     );
               } else if (type == PunishmentRecord.PunishmentType.MUTE) {
                  this.proxyServer
                     .getPlayer(targetName)
                     .ifPresent(
                        p -> p.sendMessage(
                              ServerTextUtil.miniMessageComponent(
                                 "<#FF5555>[MUTED] <reset><#888888>You have been muted. Reason: <reset><#FFFFFF>" + sanitize(reasonFinal) + "<reset>"
                              )
                           )
                     );
               }

               reply(source, success(type.name() + " issued for " + targetName + "."));
            }
         }
      );
   }

   private void executeKick(CommandSource source, String targetName, String reason) {
      Player target = (Player)this.proxyServer.getPlayer(targetName).orElse(null);
      if (target == null) {
         reply(source, error("Player '" + targetName + "' is not online."));
      } else {
         String issuedBy = sourceIdentifier(source);
         Component dc = ServerTextUtil.miniMessageComponent(
            "\n<#FF9900><bold>⛔ You have been kicked</bold><reset>\n\n<#888888>Reason: <reset><#FFFFFF>"
               + sanitize(reason)
               + "<reset>\n\n<#888888>You may reconnect at any time.<reset>\n"
         );
         target.disconnect(dc);
         Component staffMsg = ServerTextUtil.miniMessageComponent(
            "<#FF9900>[STAFF] <reset><#FFFFFF>"
               + targetName
               + "<reset><#888888> was kicked by <reset><#FFD700>"
               + issuedBy
               + "<reset><#888888> · Reason: <reset><#FFFFFF>"
               + sanitize(reason)
               + "<reset>"
         );
         this.broadcastToStaff(staffMsg);
         this.logger.info("[KICK] {} kicked {} — {}", issuedBy, targetName, reason);
         reply(source, success("Kicked " + targetName + "."));
      }
   }

   private void executeWarn(CommandSource source, String targetName, String reason) {
      String issuedBy = sourceIdentifier(source);
      CompletableFuture.runAsync(
         () -> {
            UUID uuid = this.resolveUuid(targetName);
            if (uuid == null) {
               reply(source, error("Player '" + targetName + "' not found."));
            } else {
               this.punishments.issue(uuid, targetName, PunishmentRecord.PunishmentType.WARN, reason, issuedBy, null);
               this.proxyServer
                  .getPlayer(targetName)
                  .ifPresent(
                     p -> p.sendMessage(
                           ServerTextUtil.miniMessageComponent(
                              "<#FFD700>[WARNING] <reset><#888888>You have received a warning: <reset><#FFFFFF>" + sanitize(reason) + "<reset>"
                           )
                        )
                  );
               this.logger.info("[WARN] {} warned {} — {}", issuedBy, targetName, reason);
               reply(source, success("Warning issued to " + targetName + "."));
            }
         }
      );
   }

   private void executeHistory(CommandSource source, String targetName) {
      CompletableFuture.runAsync(
         () -> {
            UUID uuid = this.resolveUuid(targetName);
            if (uuid == null) {
               reply(source, error("Player '" + targetName + "' not found in punishment records."));
            } else {
               List<PunishmentRecord> records = this.punishments.history(uuid);
               if (records.isEmpty()) {
                  reply(source, info("No punishment history for " + targetName + "."));
               } else {
                  reply(source, ServerTextUtil.miniMessageComponent("<#FFD700>── History for " + targetName + " ──<reset>"));

                  for (PunishmentRecord r : records) {
                     String status = r.isActive() ? "<#FF5555>ACTIVE<reset>" : "<#888888>expired<reset>";
                     String expiry = r.isPermanent() ? "perm" : DATE_FMT.format(r.expiresAt());
                     reply(
                        source,
                        ServerTextUtil.miniMessageComponent(
                           "<#888888>#"
                              + r.id()
                              + " <reset>"
                              + status
                              + "<#555555> | <reset><#FFD700>"
                              + r.type().name()
                              + "<reset><#555555> | <reset><#888888>by <reset><#FFFFFF>"
                              + r.issuedBy()
                              + "<reset><#555555> | <reset><#888888>"
                              + expiry
                              + "<reset>\n  <#888888>Reason: <reset><#FFFFFF>"
                              + sanitize(r.reason())
                              + "<reset>"
                        )
                     );
                  }
               }
            }
         }
      );
   }

   private void executePardon(CommandSource source, String targetName, PunishmentRecord.PunishmentType type, String typeName) {
      String issuedBy = sourceIdentifier(source);
      CompletableFuture.runAsync(
         () -> {
            UUID uuid = this.resolveUuid(targetName);
            if (uuid == null) {
               reply(source, error("Player '" + targetName + "' not found."));
            } else {
               boolean pardoned = this.punishments.pardon(uuid, type, issuedBy);
               if (pardoned) {
                  Component msg = ServerTextUtil.miniMessageComponent(
                     "<#FF5555>[STAFF] <reset><#FFFFFF>"
                        + targetName
                        + "<reset><#888888>'s "
                        + typeName
                        + " was lifted by <reset><#FFD700>"
                        + issuedBy
                        + "<reset>"
                  );
                  this.broadcastToStaff(msg);
                  reply(source, success(targetName + "'s " + typeName + " has been lifted."));
               } else {
                  reply(source, error(targetName + " has no active " + typeName + "."));
               }
            }
         }
      );
   }

   private UUID resolveUuid(String username) {
      return this.proxyServer.getPlayer(username).<UUID>map(Player::getUniqueId).or(() -> this.punishments.resolvePlayerUuid(username)).orElse(null);
   }

   private void broadcastToStaff(Component message) {
      this.proxyServer.getAllPlayers().stream().filter(p -> p.hasPermission("skypvp.staff")).forEach(p -> p.sendMessage(message));
      this.proxyServer.getConsoleCommandSource().sendMessage(message);
   }

   private static String sourceIdentifier(CommandSource source) {
      return source instanceof Player p ? p.getUsername() : "CONSOLE";
   }

   private static void reply(CommandSource source, Component msg) {
      source.sendMessage(msg);
   }

   private static Component error(String msg) {
      return ServerTextUtil.miniMessageComponent("<#FF5555>[!] <reset><#888888>" + msg + "<reset>");
   }

   private static Component success(String msg) {
      return ServerTextUtil.miniMessageComponent("<#55FF55>[✓] <reset><#FFFFFF>" + msg + "<reset>");
   }

   private static Component info(String msg) {
      return ServerTextUtil.miniMessageComponent("<#FFD700>[i] <reset><#888888>" + msg + "<reset>");
   }

   private static String sanitize(String input) {
      return input == null ? "" : input.replace("<", "\\<").replace(">", "\\>");
   }
}
