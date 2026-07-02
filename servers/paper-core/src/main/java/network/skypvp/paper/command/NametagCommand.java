package network.skypvp.paper.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.library.NametagLibrary;
import network.skypvp.paper.model.NametagDefinition;
import network.skypvp.paper.repository.NametagRepository;
import network.skypvp.paper.util.DecorationScopes;
import network.skypvp.shared.ServerTextUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * In-game editor for the server-wide player nametag layout ({@link NametagLibrary}).
 *
 * <p>Edits are stored in PostgreSQL under this server's decoration scope, cached locally, and
 * replicated to matching backends through the decoration refresh channel.
 */
public final class NametagCommand implements CommandExecutor, TabCompleter {

   private final PaperCorePlugin plugin;
   private final NametagRepository repository;
   private final NametagLibrary library;
   private final String storageScope;

   public NametagCommand(PaperCorePlugin plugin, NametagRepository repository, NametagLibrary library, String storageScope) {
      this.plugin = plugin;
      this.repository = repository;
      this.library = library;
      this.storageScope = storageScope;
   }

   @Override
   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!(sender instanceof Player player)) {
         sender.sendMessage(ServerTextUtil.component("Only players can use /nametag."));
         return true;
      }
      if (!player.hasPermission("skypvp.staff")) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>[!]<reset> <#888888>No permission.<reset>"));
         return true;
      }
      if (args.length == 0) {
         this.sendHelp(player);
         return true;
      }
      switch (args[0].toLowerCase(Locale.ROOT)) {
         case "line" -> this.handleLine(player, args);
         case "scope" -> this.handleScope(player, args);
         case "list", "lines", "info" -> this.handleList(player);
         case "offset" -> this.handleDoubleSetting(player, args, "offset", 0.0, 4.0, (def, value) -> def.baseHeight = value);
         case "spacing" -> this.handleDoubleSetting(player, args, "spacing", 0.05, 2.0, (def, value) -> def.lineSpacing = value);
         case "scale" -> this.handleDoubleSetting(player, args, "scale", 0.1, 5.0, (def, value) -> def.scale = (float) value);
         case "refresh" -> this.handleRefresh(player, args);
         case "background" -> this.handleBooleanSetting(player, args, "background", (def, value) -> def.background = value);
         case "hidevanilla" -> this.handleBooleanSetting(player, args, "hidevanilla", (def, value) -> def.hideVanillaName = value);
         case "self" -> this.handleBooleanSetting(player, args, "self", (def, value) -> def.visibleToSelf = value);
         case "toggle" -> this.handleToggle(player);
         case "sample" -> this.handleSample(player);
         case "reload" -> this.handleReload(player);
         default -> this.sendHelp(player);
      }
      return true;
   }

   private void handleLine(Player player, String[] args) {
      if (args.length < 2) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /nametag line <add|set|insert|remove> ...<reset>"));
         return;
      }
      switch (args[1].toLowerCase(Locale.ROOT)) {
         case "add" -> {
            if (args.length < 3) {
               player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /nametag line add <text...><reset>"));
               return;
            }
            String text = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
            this.edit(player, def -> def.lines.add(text), def -> player.sendMessage(ServerTextUtil.miniMessageComponent(
               "<#FFD700>Added nametag line <white>" + (def.lines.size() - 1) + "<reset><#FFD700>: <reset>" + text)));
         }
         case "set" -> {
            this.withEditingDefinition(player, def -> {
               Integer index = this.parseIndex(player, args, def.lines.size() - 1);
               if (index == null || args.length < 4) {
                  if (index != null) {
                     player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /nametag line set <index> <text...><reset>"));
                  }
                  return;
               }
               String text = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
               def.lines.set(index, text);
               this.save(player, def, saved -> player.sendMessage(ServerTextUtil.miniMessageComponent(
                  "<#FFD700>Updated nametag line <white>" + index + "<reset><#FFD700>: <reset>" + text)));
            });
         }
         case "insert" -> {
            this.withEditingDefinition(player, def -> {
               Integer index = this.parseIndex(player, args, def.lines.size());
               if (index == null || args.length < 4) {
                  if (index != null) {
                     player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /nametag line insert <index> <text...><reset>"));
                  }
                  return;
               }
               String text = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
               def.lines.add(index, text);
               this.save(player, def, saved -> player.sendMessage(ServerTextUtil.miniMessageComponent(
                  "<#FFD700>Inserted nametag line at <white>" + index + "<reset><#FFD700>: <reset>" + text)));
            });
         }
         case "remove" -> {
            this.withEditingDefinition(player, def -> {
               Integer index = this.parseIndex(player, args, def.lines.size() - 1);
               if (index == null) {
                  return;
               }
               String removed = def.lines.remove((int) index);
               this.save(player, def, saved -> player.sendMessage(ServerTextUtil.miniMessageComponent(
                  "<#FFD700>Removed nametag line <white>" + index + "<reset><#FFD700>: <reset>" + removed)));
            });
         }
         default -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /nametag line <add|set|insert|remove> ...<reset>"));
      }
   }

   private void handleScope(Player player, String[] args) {
      if (args.length < 2) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /nametag scope <add|remove|list> [scope]<reset>"));
         return;
      }
      switch (args[1].toLowerCase(Locale.ROOT)) {
         case "add" -> {
            if (args.length < 3) {
               player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /nametag scope add <scope><reset>"));
               return;
            }
            String scope = DecorationScopes.normalize(args[2]);
            if (scope.isEmpty()) {
               player.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>Invalid scope.<reset>"));
               return;
            }
            this.withEditingDefinition(player, def -> {
               if (def.scopes.contains(scope)) {
                  player.sendMessage(ServerTextUtil.miniMessageComponent(
                     "<#888888>Scope '<white>" + scope + "<reset><#888888>' is already set.<reset>"));
                  return;
               }
               def.scopes.add(scope);
               this.save(player, def, saved -> player.sendMessage(ServerTextUtil.miniMessageComponent(
                  "<#FFD700>Added nametag scope <white>" + scope + "<reset><#FFD700>. Apply scopes: <white>"
                     + String.join(", ", saved.scopes) + "<reset>")));
            });
         }
         case "remove" -> {
            if (args.length < 3) {
               player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /nametag scope remove <scope><reset>"));
               return;
            }
            String scope = DecorationScopes.normalize(args[2]);
            this.withEditingDefinition(player, def -> {
               if (!def.scopes.remove(scope)) {
                  player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Scope '<white>" + scope + "<reset><#888888>' is not set.<reset>"));
                  return;
               }
               this.save(player, def, saved -> player.sendMessage(ServerTextUtil.miniMessageComponent(
                  "<#FFD700>Removed nametag scope <white>" + scope + "<reset><#FFD700>. Apply scopes: <white>"
                     + (saved.scopes.isEmpty() ? "(none — treated as global)" : String.join(", ", saved.scopes)) + "<reset>")));
            });
         }
         case "list" -> this.withEditingDefinition(player, def -> player.sendMessage(ServerTextUtil.miniMessageComponent(
            "<#FFD700>Nametag apply scopes for <white>" + this.storageScope + "<reset><#FFD700>: <white>"
               + (def.scopes.isEmpty() ? "(none)" : String.join(", ", def.scopes)) + "<reset>")));
         default -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /nametag scope <add|remove|list> [scope]<reset>"));
      }
   }

   private void edit(Player player, Consumer<NametagDefinition> mutator, Consumer<NametagDefinition> onSuccess) {
      this.withEditingDefinition(player, def -> {
         mutator.accept(def);
         this.save(player, def, onSuccess);
      });
   }

   private void withEditingDefinition(Player player, Consumer<NametagDefinition> action) {
      if (this.repository != null) {
         this.plugin.platformScheduler().runAsync(() -> {
            NametagDefinition def = this.repository.load(this.storageScope).orElseGet(this::newEditingDefinition);
            this.plugin.platformScheduler().runGlobal(() -> action.accept(def));
         });
      } else {
         action.accept(this.library.definitionSnapshot());
      }
   }

   private void save(Player player, NametagDefinition def, Consumer<NametagDefinition> onSuccess) {
      if (this.repository != null) {
         this.plugin.platformScheduler().runAsync(() -> {
            this.repository.upsert(this.storageScope, def, player.getName());
            this.plugin.platformScheduler().runGlobal(() -> {
               this.plugin.reloadNametagDecorations();
               onSuccess.accept(def);
            });
         });
      } else {
         this.library.applyDefinition(def);
         onSuccess.accept(def);
      }
   }

   private NametagDefinition newEditingDefinition() {
      NametagDefinition def = new NametagDefinition();
      def.scope = this.storageScope;
      def.scopes = new ArrayList<>(List.of(this.storageScope));
      return def;
   }

   private Integer parseIndex(Player player, String[] args, int maxIndex) {
      if (args.length < 3) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Missing line index.<reset>"));
         return null;
      }
      int index;
      try {
         index = Integer.parseInt(args[2]);
      } catch (NumberFormatException exception) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Invalid index.<reset>"));
         return null;
      }
      if (index < 0 || index > maxIndex) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Index out of bounds (0-" + Math.max(0, maxIndex) + ").<reset>"));
         return null;
      }
      return index;
   }

   private void handleList(Player player) {
      this.withEditingDefinition(player, stored -> {
         NametagDefinition applied = this.library.definitionSnapshot();
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#555555>────────────────────────────<reset>"));
         player.sendMessage(ServerTextUtil.miniMessageComponent("<gradient:#FFB300:#FF6F00><bold>  Player Nametag</bold><reset>"));
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#555555>────────────────────────────<reset>"));
         player.sendMessage(ServerTextUtil.miniMessageComponent(
            "  <#888888>storage=<white>" + this.storageScope + "<reset> <#888888>applied-from=<white>"
               + (applied.scope == null ? "?" : applied.scope) + "<reset>"));
         if (stored.lines.isEmpty()) {
            player.sendMessage(ServerTextUtil.miniMessageComponent("  <#888888>No lines stored for this scope. Use /nametag line add or /nametag sample.<reset>"));
         } else {
            for (int i = 0; i < stored.lines.size(); i++) {
               player.sendMessage(ServerTextUtil.miniMessageComponent("  <#888888>" + i + ": <reset>" + stored.lines.get(i)));
            }
         }
         player.sendMessage(ServerTextUtil.miniMessageComponent(
            "  <#888888>apply-scopes=<white>" + (stored.scopes.isEmpty() ? "global" : String.join(",", stored.scopes))
               + "<reset> <#888888>enabled=<white>" + stored.enabled
               + "<reset> <#888888>offset=<white>" + stored.baseHeight
               + "<reset> <#888888>spacing=<white>" + stored.lineSpacing
               + "<reset> <#888888>scale=<white>" + stored.scale + "<reset>"));
         player.sendMessage(ServerTextUtil.miniMessageComponent(
            "  <#888888>refresh=<white>" + stored.refreshTicks
               + "<reset> <#888888>background=<white>" + stored.background
               + "<reset> <#888888>hidevanilla=<white>" + stored.hideVanillaName
               + "<reset> <#888888>self=<white>" + stored.visibleToSelf + "<reset>"));
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#555555>────────────────────────────<reset>"));
      });
   }

   private void handleDoubleSetting(Player player, String[] args, String name, double min, double max, DoubleSetter setter) {
      if (args.length < 2) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /nametag " + name + " <value><reset>"));
         return;
      }
      double value;
      try {
         value = Double.parseDouble(args[1]);
      } catch (NumberFormatException exception) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Invalid value. Use a number.<reset>"));
         return;
      }
      if (value < min || value > max) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Value must be between " + min + " and " + max + ".<reset>"));
         return;
      }
      this.edit(player, def -> setter.set(def, value), def -> player.sendMessage(
         ServerTextUtil.miniMessageComponent("<#FFD700>Nametag " + name + " set to <white>" + value + "<reset>")));
   }

   private void handleRefresh(Player player, String[] args) {
      if (args.length < 2) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /nametag refresh <ticks><reset>"));
         return;
      }
      int ticks;
      try {
         ticks = Integer.parseInt(args[1]);
      } catch (NumberFormatException exception) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Invalid tick count.<reset>"));
         return;
      }
      if (ticks < 1 || ticks > 200) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Refresh must be between 1 and 200 ticks.<reset>"));
         return;
      }
      this.edit(player, def -> def.refreshTicks = ticks, def -> player.sendMessage(
         ServerTextUtil.miniMessageComponent("<#FFD700>Nametag refresh set to <white>" + ticks + "<reset><#FFD700> ticks.<reset>")));
   }

   private void handleBooleanSetting(Player player, String[] args, String name, BooleanSetter setter) {
      if (args.length < 2) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /nametag " + name + " <true|false><reset>"));
         return;
      }
      boolean value = Boolean.parseBoolean(args[1]);
      this.edit(player, def -> setter.set(def, value), def -> player.sendMessage(
         ServerTextUtil.miniMessageComponent("<#FFD700>Nametag " + name + " set to <white>" + value + "<reset>")));
   }

   private void handleToggle(Player player) {
      this.edit(player, def -> def.enabled = !def.enabled, def -> player.sendMessage(ServerTextUtil.miniMessageComponent(
         def.enabled ? "<#FFD700>Nametags <green>enabled<reset><#FFD700>.<reset>" : "<#FFD700>Nametags <red>disabled<reset><#FFD700>.<reset>")));
   }

   private void handleSample(Player player) {
      this.edit(player, def -> {
         def.enabled = true;
         def.lines = new ArrayList<>(List.of(
            "<anim:glow><gradient:gold:yellow><bold>%player_name%</bold></gradient>",
            "<red>❤ %health%<gray>/<red>%max_health% <dark_gray>| <aqua>✦ Lv.%level%",
            "<gray>%world% <dark_gray>• <green>%ping%ms"
         ));
      }, def -> player.sendMessage(ServerTextUtil.miniMessageComponent(
         "<green>Applied the sample nametag layout for scope <white>" + this.storageScope + "<reset><green>. Edit with /nametag line set <index> <text>.<reset>")));
   }

   private void handleReload(Player player) {
      this.plugin.reloadNametagDecorations();
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#FFD700>Reloaded nametags from database/cache.<reset>"));
   }

   private void sendHelp(Player player) {
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#555555>────────────────────────────<reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<gradient:#FFB300:#FF6F00><bold>  Nametag Commands</bold><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#555555>────────────────────────────<reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/nametag line add <text><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/nametag line set <index> <text><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/nametag line insert <index> <text><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/nametag line remove <index><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/nametag scope <add|remove|list> [scope] <dark_gray>— where this layout applies<reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/nametag list<reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/nametag offset <y> <dark_gray>— height above head<reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/nametag spacing <value><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/nametag scale <value><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/nametag refresh <ticks><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/nametag background <true|false><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/nametag hidevanilla <true|false><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/nametag self <true|false> <dark_gray>— see your own tag<reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/nametag toggle<reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/nametag sample<reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/nametag reload<reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent(
         "<#888888>Tags: <white><anim:glow|rainbow|blink|scroll|typewriter><reset><#888888>, <white><glow:color><reset><#888888>, <white><item:MATERIAL[:scale]><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent(
         "<#888888>Placeholders: <white>%player_name% %health% %max_health% %food% %level% %world% %ping%<reset><#888888> + PlaceholderAPI<reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#555555>────────────────────────────<reset>"));
   }

   @Override
   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (!(sender instanceof Player player) || !player.hasPermission("skypvp.staff")) {
         return List.of();
      }
      if (args.length == 1) {
         return filterPrefix(args[0], List.of(
            "line", "scope", "list", "offset", "spacing", "scale", "refresh",
            "background", "hidevanilla", "self", "toggle", "sample", "reload"
         ));
      }
      if (args.length == 2 && "line".equalsIgnoreCase(args[0])) {
         return filterPrefix(args[1], List.of("add", "set", "insert", "remove"));
      }
      if (args.length == 2 && "scope".equalsIgnoreCase(args[0])) {
         return filterPrefix(args[1], List.of("add", "remove", "list"));
      }
      if (args.length == 3 && "scope".equalsIgnoreCase(args[0])) {
         if ("add".equalsIgnoreCase(args[1])) {
            return filterPrefix(args[2], DecorationScopes.KNOWN);
         }
         if ("remove".equalsIgnoreCase(args[1])) {
            return filterPrefix(args[2], this.library.definitionSnapshot().scopes);
         }
      }
      if (args.length == 3 && "line".equalsIgnoreCase(args[0])
         && ("set".equalsIgnoreCase(args[1]) || "insert".equalsIgnoreCase(args[1]) || "remove".equalsIgnoreCase(args[1]))) {
         int lineCount = this.library.definitionSnapshot().lines.size();
         List<String> indices = new ArrayList<>();
         for (int i = 0; i < lineCount; i++) {
            indices.add(String.valueOf(i));
         }
         return filterPrefix(args[2], indices);
      }
      if (args.length == 2
         && ("background".equalsIgnoreCase(args[0]) || "hidevanilla".equalsIgnoreCase(args[0]) || "self".equalsIgnoreCase(args[0]))) {
         return filterPrefix(args[1], List.of("true", "false"));
      }
      return List.of();
   }

   private static List<String> filterPrefix(String prefix, List<String> options) {
      String lower = prefix.toLowerCase(Locale.ROOT);
      List<String> matches = new ArrayList<>();
      for (String option : options) {
         if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
            matches.add(option);
         }
      }
      return matches;
   }

   @FunctionalInterface
   private interface DoubleSetter {
      void set(NametagDefinition definition, double value);
   }

   @FunctionalInterface
   private interface BooleanSetter {
      void set(NametagDefinition definition, boolean value);
   }
}
