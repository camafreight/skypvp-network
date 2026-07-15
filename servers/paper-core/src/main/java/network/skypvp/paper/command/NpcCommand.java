package network.skypvp.paper.command;

import network.skypvp.shared.ServerTextUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.library.InteractionActionTypes;
import network.skypvp.paper.library.NpcLibrary;
import network.skypvp.paper.model.HologramDefinition;
import network.skypvp.paper.model.NpcDefinition;
import network.skypvp.paper.model.WorldPoint;
import network.skypvp.paper.repository.HologramRepository;
import network.skypvp.paper.repository.NpcRepository;
import java.util.stream.Collectors;
import network.skypvp.paper.util.DecorationScopes;
import network.skypvp.paper.util.SkinFetcher;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

public final class NpcCommand implements CommandExecutor, TabCompleter {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   private final NpcRepository repository;
   private final HologramRepository hologramRepository;
   private final NpcLibrary npcLibrary;
   private final String serverId;
   private final PaperCorePlugin plugin;

   public NpcCommand(NpcRepository repository, HologramRepository hologramRepository, NpcLibrary npcLibrary, String serverId, PaperCorePlugin plugin) {
      this.repository = repository;
      this.hologramRepository = hologramRepository;
      this.npcLibrary = npcLibrary;
      this.serverId = serverId;
      this.plugin = plugin;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (sender instanceof Player player) {
         if (!player.hasPermission("skypvp.staff")) {
            player.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>[!]<reset> <#888888>No permission.<reset>"));
            return true;
         } else if (args.length == 0) {
            this.sendHelp(player);
            return true;
         } else {
            String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
               case "create":
                  this.handleCreate(player, args);
                  break;
               case "delete":
                  this.handleDelete(player, args);
                  break;
               case "list":
                  this.handleList(player, args);
                  break;
               case "action":
                  this.handleAction(player, args);
                  break;
               case "type":
                  this.handleType(player, args);
                  break;
               case "skin":
                  this.handleSkin(player, args);
                  break;
               case "glow":
                  this.handleGlow(player, args);
                  break;
               case "faceplayer":
                  this.handleFacePlayer(player, args);
                  break;
               case "navigator":
                  this.handleNavigator(player, args);
                  break;
               case "scale":
                  this.handleScale(player, args);
                  break;
               case "background":
                  this.handleNpcDisplayBoolean(player, args, "background", (def, v) -> def.hologramBackground = v);
                  break;
               case "seethrough":
                  this.handleNpcDisplayBoolean(player, args, "seethrough", (def, v) -> def.hologramSeeThrough = v);
                  break;
               case "shadowed":
                  this.handleNpcDisplayBoolean(player, args, "shadowed", (def, v) -> def.hologramShadowed = v);
                  break;
               case "freeze":
                  this.handleNpcDisplayBoolean(player, args, "freeze", (def, v) -> def.hologramFreeze = v);
                  break;
               case "alignment":
                  this.handleNpcAlignment(player, args);
                  break;
               case "viewrange":
                  this.handleNpcViewRange(player, args);
                  break;
               case "holobillboard":
                  this.handleNpcHoloBillboard(player, args);
                  break;
               case "holoscale":
                  this.handleNpcHoloScale(player, args);
                  break;
               case "line":
                  this.handleLine(player, args);
                  break;
               case "name":
                  this.handleName(player, args);
                  break;
               case "movehere":
                  this.handleMoveHere(player, args);
                  break;
               case "confirmdelete":
                  this.handleConfirmDelete(player, args);
                  break;
               case "reload":
                  this.handleReload(player);
                  break;
               case "scope":
                  this.handleScope(player, args);
                  break;
               default:
                  this.sendHelp(player);
            }

            return true;
         }
      } else {
         sender.sendMessage(ServerTextUtil.component("Only players can use /npc."));
         return true;
      }
   }

   private void handleCreate(Player player, String[] args) {
      if (args.length < 2) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /npc create <id> [display name...]<reset>"));
      } else {
         String id = args[1].toLowerCase(Locale.ROOT);
         String displayName = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "<gold><bold>" + args[1] + "</bold></gold>";
         NpcDefinition def = new NpcDefinition();
         def.id = id;
         def.displayName = displayName;
         def.entityType = "PLAYER";
         def.location = WorldPoint.fromLocation(player.getLocation());
         def.actionType = "NONE";
         def.actionData = "";
         def.hologramLines.add(displayName);
         this.plugin.platformScheduler().runAsync( () -> {
            this.repository.upsert(this.serverId, def, player.getName());
            List<NpcDefinition> allDefs = this.repository.loadAll(this.serverId);
            this.npcLibrary.applyAndNotify(player.getWorld(), player.getLocation(), allDefs, () ->
               player.sendMessage(ServerTextUtil.miniMessageComponent("<#FFD700>NPC <reset><#FFFFFF>" + id + "<reset><#FFD700> created at your location.<reset>"))
            );
         });
      }
   }

   private void handleDelete(Player player, String[] args) {
      if (args.length < 2) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /npc delete <id><reset>"));
      } else {
         String id = args[1].toLowerCase(Locale.ROOT);
         this.plugin.platformScheduler().runAsync( () -> {
            boolean removed = this.repository.delete(this.serverId, id);
            List<NpcDefinition> allDefs = this.repository.loadAll(this.serverId);
            this.npcLibrary.applyAndNotify(player.getWorld(), player.getLocation(), allDefs, () -> {
               HologramDefinition emptyHolo = new HologramDefinition();
               emptyHolo.id = id + "_holo";
               emptyHolo.lines = new ArrayList<>();
               ((PaperCorePlugin)this.plugin).holographicLibrary().apply(player.getWorld(), List.of(emptyHolo));
               if (removed) {
                  player.sendMessage(ServerTextUtil.miniMessageComponent("<#FFD700>NPC <reset><#FFFFFF>" + id + "<reset><#FFD700> deleted.<reset>"));
               } else {
                  player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>No NPC with id '<white>" + id + "<reset><#888888>' found.<reset>"));
               }
            });
         });
      }
   }

   private void handleConfirmDelete(Player player, String[] args) {
      this.handleDelete(player, args);
   }

   private void handleLine(Player player, String[] args) {
      if (args.length < 3) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /npc line <add|set|remove> <id> ...<reset>"));
      } else {
         String action = args[1].toLowerCase(Locale.ROOT);
         String id = args[2].toLowerCase(Locale.ROOT);
         this.plugin.platformScheduler().runAsync(
               () -> {
                  List<NpcDefinition> npcs = this.repository.loadAll(this.serverId);
                  NpcDefinition npc = npcs.stream().filter(n -> id.equals(n.id)).findFirst().orElse(null);
                  if (npc == null) {
this.plugin.platformScheduler().runGlobal(
                           () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>No NPC found with id '<white>" + id + "<reset><#888888>'.<reset>"))
                        );
                  } else {
                     if (npc.hologramLines == null) {
                        npc.hologramLines = new ArrayList<>();
                     }

                     boolean updated = false;
                     switch (action) {
                        case "add":
                           if (args.length < 4) {
                              this.plugin.platformScheduler().runGlobal( () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /npc line add <id> <text><reset>")));
                              return;
                           }

                           String text = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                           npc.hologramLines.add(text);
                           updated = true;
                           break;
                        case "remove":
                           if (args.length < 4) {
                              this.plugin.platformScheduler().runGlobal( () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /npc line remove <id> <index><reset>")));
                              return;
                           }

                           try {
                              int index = Integer.parseInt(args[3]);
                              if (index >= 0 && index < npc.hologramLines.size()) {
                                 npc.hologramLines.remove(index);
                                 updated = true;
                                 break;
                              }

                              this.plugin.platformScheduler().runGlobal( () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Invalid line index.<reset>")));
                              return;
                           } catch (NumberFormatException var13) {
                              this.plugin.platformScheduler().runGlobal( () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Index must be a number.<reset>")));
                              return;
                           }
                        case "set":
                           if (args.length < 5) {
this.plugin.platformScheduler().runGlobal( () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /npc line set <id> <index> <text><reset>"))
                                 );
                              return;
                           }

                           try {
                              int index = Integer.parseInt(args[3]);
                              if (index < 0 || index >= npc.hologramLines.size()) {
                                 this.plugin.platformScheduler().runGlobal( () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Invalid line index.<reset>")));
                                 return;
                              }

                              String textSet = String.join(" ", Arrays.copyOfRange(args, 4, args.length));
                              npc.hologramLines.set(index, textSet);
                              updated = true;
                           } catch (NumberFormatException var12) {
                              this.plugin.platformScheduler().runGlobal( () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Index must be a number.<reset>")));
                              return;
                           }
                     }

                     if (updated) {
                        this.repository.upsert(this.serverId, npc, player.getName());
                        List<NpcDefinition> allDefs = this.repository.loadAll(this.serverId);
                        this.npcLibrary.applyAndNotify(player.getWorld(), player.getLocation(), allDefs, () ->
                           player.sendMessage(ServerTextUtil.miniMessageComponent("<#FFD700>NPC hologram updated.<reset>"))
                        );
                     }
                  }
               }
            );
      }
   }

   private void handleName(Player player, String[] args) {
      if (args.length < 3) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /npc name <id> <display name...><reset>"));
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Supports MiniMessage: <gold><bold>Shop</bold></gold><reset>"));
      } else {
         String id = args[1].toLowerCase(Locale.ROOT);
         String newName = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
         this.plugin.platformScheduler().runAsync(
               () -> {
                  List<NpcDefinition> npcs = this.repository.loadAll(this.serverId);
                  NpcDefinition npc = npcs.stream().filter(n -> id.equals(n.id)).findFirst().orElse(null);
                  if (npc == null) {
this.plugin.platformScheduler().runGlobal(
                           () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>No NPC found with id '<white>" + id + "<reset><#888888>'.<reset>"))
                        );
                  } else {
                     npc.displayName = newName;
                     if (npc.hologramLines == null) {
                        npc.hologramLines = new ArrayList<>();
                     }

                     if (npc.hologramLines.isEmpty()) {
                        npc.hologramLines.add(newName);
                     } else {
                        npc.hologramLines.set(0, newName);
                     }

                     this.repository.upsert(this.serverId, npc, player.getName());
                     List<NpcDefinition> allDefs = this.repository.loadAll(this.serverId);
                     World w = npc.location != null && npc.location.world != null ? this.plugin.getServer().getWorld(npc.location.world) : player.getWorld();
                     this.npcLibrary.applyAndNotify(w, w == null ? null : this.npcLibrary.resolveLocation(w, npc), allDefs, () ->
                        player.sendMessage(ServerTextUtil.miniMessageComponent("<#FFD700>NPC <#FFFFFF>" + id + "<reset><#FFD700> renamed to: <reset>" + newName))
                     );
                  }
               }
            );
      }
   }

   private void handleList(Player player, String[] args) {
      String filterScope = args.length >= 2 ? DecorationScopes.normalize(args[1]) : null;
      this.plugin.platformScheduler().runAsync(
            () -> {
               List<NpcDefinition> list;
               try {
                  list = filterScope == null
                     ? this.repository.loadAllScopedAsync().get()
                     : this.repository.loadAllAsync(filterScope).get();
               } catch (Exception exception) {
                  this.plugin.platformScheduler().runGlobal(
                     () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>Failed to load NPC list.<reset>"))
                  );
                  return;
               }

               this.plugin.platformScheduler().runGlobal(
                     () -> {
                        player.sendMessage(
                           ServerTextUtil.miniMessageComponent(
                              "<#555555>-<reset>"
                           )
                        );
                        String title = filterScope == null
                           ? "All NPCs (" + list.size() + ")"
                           : "NPCs in " + filterScope + " (" + list.size() + ")";
                        player.sendMessage(
                           ServerTextUtil.miniMessageComponent("<gradient:#FFB300:#FF6F00><bold>  " + title + "</bold><reset>")
                        );
                        player.sendMessage(
                           ServerTextUtil.miniMessageComponent(
                              "<#555555>-<reset>"
                           )
                        );
                        if (list.isEmpty()) {
                           player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>None registered.<reset>"));
                        } else if (filterScope == null) {
                           Map<String, List<NpcDefinition>> byScope = list.stream()
                              .collect(Collectors.groupingBy(d -> d.scope != null ? d.scope : "?"));

                           for (Entry<String, List<NpcDefinition>> scopeEntry : byScope.entrySet()) {
                              player.sendMessage(
                                 ServerTextUtil.miniMessageComponent("<#FFD700>[" + scopeEntry.getKey() + "]<reset>")
                              );
                              for (NpcDefinition d : scopeEntry.getValue()) {
                                 this.sendNpcListLine(player, d);
                              }
                           }
                        } else {
                           for (NpcDefinition d : list) {
                              this.sendNpcListLine(player, d);
                           }
                        }

                        player.sendMessage(
                           ServerTextUtil.miniMessageComponent(
                              "<#555555>-<reset>"
                           )
                        );
                     }
                  );
            }
         );
   }

   private void sendNpcListLine(Player player, NpcDefinition d) {
      WorldPoint pt = d.location != null ? d.location : new WorldPoint();
      player.sendMessage(
         ServerTextUtil.miniMessageComponent(
            "<#FFFFFF>"
               + d.id
               + "<reset> <#888888>type=<reset><white>"
               + d.entityType
               + "<reset> <#888888>action=<reset><white>"
               + d.actionType
               + "<reset> <#888888>at <reset><white>"
               + String.format(Locale.ROOT, "%.1f %.1f %.1f", pt.x, pt.y, pt.z)
               + "<reset>"
         )
      );
   }

   private void handleScope(Player player, String[] args) {
      if (args.length < 3) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /npc scope <id> <scope><reset>"));
         return;
      }

      String id = args[1].toLowerCase(Locale.ROOT);
      String newScope = DecorationScopes.normalize(args[2]);
      if (newScope.isEmpty()) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>Invalid scope.<reset>"));
         return;
      }

      this.plugin.platformScheduler().runAsync(
         () -> {
            List<NpcDefinition> matches;
            try {
               matches = this.repository.findAllByIdAsync(id).get();
            } catch (Exception exception) {
               this.plugin.platformScheduler().runGlobal(
                  () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>Failed to look up NPC.<reset>"))
               );
               return;
            }

            NpcDefinition source = matches.stream().filter(d -> this.serverId.equals(d.scope)).findFirst().orElse(null);
            if (source == null && matches.size() == 1) {
               source = matches.get(0);
            }
            if (source == null) {
               this.plugin.platformScheduler().runGlobal(
                  () -> {
                     if (matches.isEmpty()) {
                        player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>NPC '<white>" + id + "<reset><#888888>' not found.<reset>"));
                     } else {
                        player.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>NPC '<white>" + id + "<reset><#FF5555>' exists in multiple scopes. Use /npc list to see them.<reset>"));
                     }
                  }
               );
               return;
            }

            if (newScope.equals(source.scope)) {
               this.plugin.platformScheduler().runGlobal(
                  () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>NPC '<white>" + id + "<reset><#888888>' is already in scope '<white>" + newScope + "<reset><#888888>'.<reset>"))
               );
               return;
            }

            String fromScope = source.scope;
            boolean changed = this.repository.changeScope(id, fromScope, newScope);
            this.plugin.platformScheduler().runGlobal(
               () -> {
                  if (!changed) {
                     player.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>Could not move NPC '<white>" + id + "<reset><#FF5555>' to scope '<white>" + newScope + "<reset><#FF5555>' (target scope may already have that id).<reset>"));
                     return;
                  }

                  player.sendMessage(
                     ServerTextUtil.miniMessageComponent(
                        "<#FFD700>Moved NPC <#FFFFFF>" + id + "<reset><#FFD700> from <white>" + fromScope + "<reset><#FFD700> to <white>" + newScope + "<reset><#FFD700>.<reset>"
                     )
                  );
                  if (this.serverId.equals(fromScope) || this.serverId.equals(newScope)) {
                     this.plugin.reloadNpcDecorations();
                  }
               }
            );
         }
      );
   }

   private void handleAction(Player player, String[] args) {
      if (args.length < 3) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /npc action <id> <type> [data...]<reset>"));
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Types: NONE, CONNECT, COMMAND, CONSOLE_COMMAND, PROXY_COMMAND, PROXY_CONSOLE_COMMAND, MESSAGE, OPEN_NETWORK_MENU, MENU<reset>"));
      } else {
         String id = args[1].toLowerCase(Locale.ROOT);
         String actionType = args[2].toUpperCase(Locale.ROOT);
         String actionData = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : "";
         this.plugin.platformScheduler().runAsync(
               () -> {
                  List<NpcDefinition> existing = this.repository.loadAll(this.serverId);
                  NpcDefinition def = existing.stream().filter(d -> id.equals(d.id)).findFirst().orElse(null);
                  if (def == null) {
this.plugin.platformScheduler().runGlobal( () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>NPC '<white>" + id + "<reset><#888888>' not found.<reset>"))
                        );
                  } else {
                     def.actionType = actionType;
                     def.actionData = actionData;
                     this.repository.upsert(this.serverId, def, player.getName());
                     World w = def.location != null && def.location.world != null
                        ? this.plugin.getServer().getWorld(def.location.world)
                        : player.getWorld();
                     this.npcLibrary.applyAndNotify(w, w == null ? null : this.npcLibrary.resolveLocation(w, def), existing, () ->
                        player.sendMessage(
                           ServerTextUtil.miniMessageComponent(
                              "<#FFD700>NPC <reset><#FFFFFF>"
                                 + id
                                 + "<reset><#FFD700> action set to <reset><white>"
                                 + actionType
                                 + (actionData.isBlank() ? "" : " " + actionData)
                                 + "<reset>"
                           )
                        )
                     );
                  }
               }
            );
      }
   }

   private void handleType(Player player, String[] args) {
      if (args.length < 3) {
         player.sendMessage(ServerTextUtil.miniMessageComponent(
               "<#888888>Usage: /npc type <id> <entity_type|PLAYER|BLOCK:MATERIAL|CHEST...><reset>"));
      } else {
         String id = args[1].toLowerCase(Locale.ROOT);
         String rawType = args[2].trim();
         org.bukkit.Material blockMaterial = NpcLibrary.resolveBlockMaterial(rawType);
         String entityType;
         if (blockMaterial != null) {
            entityType = "BLOCK:" + blockMaterial.name();
         } else {
            try {
               entityType = org.bukkit.entity.EntityType.valueOf(rawType.toUpperCase(Locale.ROOT)).name();
            } catch (IllegalArgumentException ex) {
               player.sendMessage(ServerTextUtil.miniMessageComponent(
                     "<#FF5555>Unknown type '<white>" + rawType
                           + "<reset><#FF5555>'. Use an EntityType (VILLAGER, PLAYER) or a block material (CHEST, BLOCK:CHEST).<reset>"));
               return;
            }
         }
         final String resolvedType = entityType;
         this.plugin.platformScheduler().runAsync(
               () -> {
                  List<NpcDefinition> existing = this.repository.loadAll(this.serverId);
                  NpcDefinition def = existing.stream().filter(d -> id.equals(d.id)).findFirst().orElse(null);
                  if (def == null) {
this.plugin.platformScheduler().runGlobal( () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>NPC '<white>" + id + "<reset><#888888>' not found.<reset>"))
                        );
                  } else {
                     def.entityType = resolvedType;
                     this.repository.upsert(this.serverId, def, player.getName());
                     World w = def.location != null && def.location.world != null ? this.plugin.getServer().getWorld(def.location.world) : player.getWorld();
                     this.npcLibrary.applyAndNotify(w, w == null ? null : this.npcLibrary.resolveLocation(w, def), existing, () ->
                        player.sendMessage(
                           ServerTextUtil.miniMessageComponent("<#FFD700>NPC <#FFFFFF>" + id + "<reset><#FFD700> type set to <white>" + resolvedType + "<reset>")
                        )
                     );
                  }
               }
            );
      }
   }

   private void handleSkin(Player player, String[] args) {
      if (args.length < 3) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /npc skin <id> <clear|url_or_playername><reset>"));
      } else {
         String id = args[1].toLowerCase(Locale.ROOT);
         String input = args[2];
         if (input.equalsIgnoreCase("clear")) {
            this.updateSkin(player, id, null, null);
         } else {
            player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Fetching skin data... please wait.<reset>"));
            SkinFetcher.fetchSkinAsync(input)
               .thenAccept(
                  result -> {
                     if (result == null) {
this.plugin.platformScheduler().runGlobal(
                              () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Failed to fetch skin data for input: " + input + "<reset>"))
                           );
                     } else {
                        this.updateSkin(player, id, result[0], result[1]);
                     }
                  }
               );
         }
      }
   }

   private void updateSkin(Player player, String id, String skinValue, String skinSignature) {
      this.plugin.platformScheduler().runAsync(
            () -> {
               List<NpcDefinition> existing = this.repository.loadAll(this.serverId);
               NpcDefinition def = existing.stream().filter(d -> id.equals(d.id)).findFirst().orElse(null);
               if (def == null) {
this.plugin.platformScheduler().runGlobal( () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>NPC '<white>" + id + "<reset><#888888>' not found.<reset>"))
                     );
               } else {
                  def.skinUrl = skinValue;
                  def.skinSignature = skinSignature;
                  this.repository.upsert(this.serverId, def, player.getName());
                  World w = def.location != null && def.location.world != null ? this.plugin.getServer().getWorld(def.location.world) : player.getWorld();
                  this.npcLibrary.applyAndNotify(w, w == null ? null : this.npcLibrary.resolveLocation(w, def), existing, () ->
                     player.sendMessage(ServerTextUtil.miniMessageComponent("<#FFD700>NPC <#FFFFFF>" + id + "<reset><#FFD700> skin updated.<reset>"))
                  );
               }
            }
         );
   }

   private void handleMoveHere(Player player, String[] args) {
      if (args.length < 2) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /npc movehere <id><reset>"));
      } else {
         String id = args[1].toLowerCase(Locale.ROOT);
         this.plugin.platformScheduler().runAsync(
               () -> {
                  List<NpcDefinition> existing = this.repository.loadAll(this.serverId);
                  NpcDefinition def = existing.stream().filter(d -> id.equals(d.id)).findFirst().orElse(null);
                  if (def == null) {
this.plugin.platformScheduler().runGlobal( () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>NPC '<white>" + id + "<reset><#888888>' not found.<reset>"))
                        );
                  } else {
                     def.location = WorldPoint.fromLocation(player.getLocation());
                     this.repository.upsert(this.serverId, def, player.getName());
                     World w = def.location.world != null ? this.plugin.getServer().getWorld(def.location.world) : player.getWorld();
                     this.npcLibrary.applyAndNotify(w, player.getLocation(), existing, () ->
                        player.sendMessage(ServerTextUtil.miniMessageComponent("<#FFD700>NPC <#FFFFFF>" + id + "<reset><#FFD700> moved to your location.<reset>"))
                     );
                  }
               }
            );
      }
   }

   private void handleGlow(Player player, String[] args) {
      if (args.length < 3) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /npc glow <id> <true|false> [color|rainbow]<reset>"));
      } else {
         String id = args[1].toLowerCase(Locale.ROOT);
         boolean glow = Boolean.parseBoolean(args[2]);
         String color = args.length > 3 ? args[3].toLowerCase(Locale.ROOT).replaceAll("[<>]", "") : null;
         this.plugin.platformScheduler().runAsync(
               () -> {
                  List<NpcDefinition> existing = this.repository.loadAll(this.serverId);
                  NpcDefinition def = existing.stream().filter(d -> id.equals(d.id)).findFirst().orElse(null);
                  if (def == null) {
this.plugin.platformScheduler().runGlobal( () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>NPC '<white>" + id + "<reset><#888888>' not found.<reset>"))
                        );
                  } else {
                     def.glow = glow;
                     def.glowColor = color;
                     this.repository.upsert(this.serverId, def, player.getName());
                     World w = def.location != null && def.location.world != null ? this.plugin.getServer().getWorld(def.location.world) : player.getWorld();
                     this.npcLibrary.applyAndNotify(w, w == null ? null : this.npcLibrary.resolveLocation(w, def), existing, () ->
                        player.sendMessage(ServerTextUtil.miniMessageComponent("<#FFD700>NPC <#FFFFFF>" + id + "<reset><#FFD700> glow updated.<reset>"))
                     );
                  }
               }
            );
      }
   }

   private void handleFacePlayer(Player player, String[] args) {
      if (args.length < 3) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /npc faceplayer <id> <true|false><reset>"));
      } else {
         String id = args[1].toLowerCase(Locale.ROOT);
         boolean facePlayer = Boolean.parseBoolean(args[2]);
         this.plugin.platformScheduler().runAsync(
               () -> {
                  List<NpcDefinition> existing = this.repository.loadAll(this.serverId);
                  NpcDefinition def = existing.stream().filter(d -> id.equals(d.id)).findFirst().orElse(null);
                  if (def == null) {
this.plugin.platformScheduler().runGlobal( () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>NPC '<white>" + id + "<reset><#888888>' not found.<reset>"))
                        );
                  } else {
                     def.facePlayer = facePlayer;
                     this.repository.upsert(this.serverId, def, player.getName());
                     World w = def.location != null && def.location.world != null
                        ? this.plugin.getServer().getWorld(def.location.world)
                        : player.getWorld();
                     this.npcLibrary.applyAndNotify(w, w == null ? null : this.npcLibrary.resolveLocation(w, def), existing, () ->
                        player.sendMessage(
                           ServerTextUtil.miniMessageComponent("<#FFD700>NPC <#FFFFFF>" + id + "<reset><#FFD700> facePlayer set to <white>" + facePlayer + "<reset>")
                        )
                     );
                  }
               }
            );
      }
   }

   private void handleNavigator(Player player, String[] args) {
      if (args.length < 3) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /npc navigator <id> <true|false><reset>"));
         return;
      }
      String id = args[1].toLowerCase(Locale.ROOT);
      boolean navigator = Boolean.parseBoolean(args[2]);
      this.plugin.platformScheduler().runAsync(() -> {
         List<NpcDefinition> existing = this.repository.loadAll(this.serverId);
         NpcDefinition def = existing.stream().filter(d -> id.equals(d.id)).findFirst().orElse(null);
         if (def == null) {
            this.plugin.platformScheduler().runGlobal(() -> player.sendMessage(
                    ServerTextUtil.miniMessageComponent("<#888888>NPC '<white>" + id + "<reset><#888888>' not found.<reset>")
            ));
            return;
         }
         def.navigator = navigator;
         this.repository.upsert(this.serverId, def, player.getName());
         World w = def.location != null && def.location.world != null
                 ? this.plugin.getServer().getWorld(def.location.world)
                 : player.getWorld();
         this.npcLibrary.applyAndNotify(w, w == null ? null : this.npcLibrary.resolveLocation(w, def), existing, () ->
                 player.sendMessage(ServerTextUtil.miniMessageComponent(
                         "<#FFD700>NPC <#FFFFFF>" + id + "<reset><#FFD700> navigator set to <white>" + navigator + "<reset>"
                 ))
         );
      });
   }

   private void handleScale(Player player, String[] args) {
      if (args.length < 3) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /npc scale <id> <value><reset>"));
      } else {
         String id = args[1].toLowerCase(Locale.ROOT);
         double scale;
         try {
            scale = Double.parseDouble(args[2]);
         } catch (NumberFormatException var6) {
            player.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>Invalid scale value.<reset>"));
            return;
         }

         this.plugin.platformScheduler().runAsync( () -> {
            List<NpcDefinition> existing = this.repository.loadAll(this.serverId);
            NpcDefinition def = existing.stream().filter((d) -> id.equals(d.id)).findFirst().orElse(null);
            if (def == null) {
               this.plugin.platformScheduler().runGlobal( () -> {
                  player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>NPC '<white>" + id + "<reset><#888888>' not found.<reset>"));
               });
            } else {
               def.scale = scale;
               this.repository.upsert(this.serverId, def, player.getName());
               World w = def.location != null && def.location.world != null ? this.plugin.getServer().getWorld(def.location.world) : player.getWorld();
               this.npcLibrary.applyAndNotify(w, w == null ? null : this.npcLibrary.resolveLocation(w, def), existing, () ->
                  player.sendMessage(ServerTextUtil.miniMessageComponent("<#FFD700>NPC <#FFFFFF>" + id + "<reset><#FFD700> scale set to <white>" + scale + "<reset>"))
               );
            }
         });
      }
   }

   private void handleReload(Player player) {
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#FFD700>Reloading NPCs...<reset>"));
      this.plugin.reloadNpcDecorations();
   }

   private void handleNpcDisplayBoolean(
         Player player,
         String[] args,
         String label,
         java.util.function.BiConsumer<NpcDefinition, Boolean> mutator
   ) {
      if (args.length < 3) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /npc " + label + " <id> <true|false><reset>"));
         return;
      }
      String id = args[1].toLowerCase(Locale.ROOT);
      boolean value = Boolean.parseBoolean(args[2]);
      this.mutateNpc(player, id, def -> mutator.accept(def, value), label + "=" + value);
   }

   private void handleNpcAlignment(Player player, String[] args) {
      if (args.length < 3) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /npc alignment <id> <left|center|right><reset>"));
         return;
      }
      String alignment = args[2].toUpperCase(Locale.ROOT);
      if (!alignment.equals("LEFT") && !alignment.equals("CENTER") && !alignment.equals("RIGHT")) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Alignment must be left, center, or right.<reset>"));
         return;
      }
      this.mutateNpc(player, args[1].toLowerCase(Locale.ROOT), def -> def.hologramAlignment = alignment, "alignment=" + alignment);
   }

   private void handleNpcViewRange(Player player, String[] args) {
      if (args.length < 3) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /npc viewrange <id> <0.1-5><reset>"));
         return;
      }
      float range;
      try {
         range = Float.parseFloat(args[2]);
      } catch (NumberFormatException ex) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Invalid view range.<reset>"));
         return;
      }
      if (range < 0.1F || range > 5.0F) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>View range must be between 0.1 and 5.0.<reset>"));
         return;
      }
      float finalRange = range;
      this.mutateNpc(player, args[1].toLowerCase(Locale.ROOT), def -> def.hologramViewRange = finalRange, "viewrange=" + finalRange);
   }

   private void handleNpcHoloBillboard(Player player, String[] args) {
      if (args.length < 3) {
         player.sendMessage(ServerTextUtil.miniMessageComponent(
               "<#888888>Usage: /npc holobillboard <id> <fixed|vertical|horizontal|center><reset>"));
         return;
      }
      String billboard = args[2].toUpperCase(Locale.ROOT);
      this.mutateNpc(player, args[1].toLowerCase(Locale.ROOT), def -> def.hologramBillboard = billboard, "holobillboard=" + billboard);
   }

   private void handleNpcHoloScale(Player player, String[] args) {
      if (args.length < 3) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /npc holoscale <id> <0.01-10><reset>"));
         return;
      }
      double scale;
      try {
         scale = Double.parseDouble(args[2]);
      } catch (NumberFormatException ex) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Invalid scale.<reset>"));
         return;
      }
      if (scale < 0.01D || scale > 10.0D) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Scale must be between 0.01 and 10.<reset>"));
         return;
      }
      double finalScale = scale;
      this.mutateNpc(player, args[1].toLowerCase(Locale.ROOT), def -> def.hologramScale = finalScale, "holoscale=" + finalScale);
   }

   private void mutateNpc(Player player, String id, java.util.function.Consumer<NpcDefinition> mutator, String summary) {
      this.plugin.platformScheduler().runAsync(() -> {
         List<NpcDefinition> existing = this.repository.loadAll(this.serverId);
         NpcDefinition def = existing.stream().filter(d -> id.equals(d.id)).findFirst().orElse(null);
         if (def == null) {
            this.plugin.platformScheduler().runGlobal(() -> player.sendMessage(
                  ServerTextUtil.miniMessageComponent("<#888888>NPC '<white>" + id + "<reset><#888888>' not found.<reset>")));
            return;
         }
         mutator.accept(def);
         this.repository.upsert(this.serverId, def, player.getName());
         World w = def.location != null && def.location.world != null
               ? this.plugin.getServer().getWorld(def.location.world)
               : player.getWorld();
         this.npcLibrary.applyAndNotify(w, w == null ? null : this.npcLibrary.resolveLocation(w, def), existing, () ->
               player.sendMessage(ServerTextUtil.miniMessageComponent(
                     "<#FFD700>NPC <#FFFFFF>" + id + "<reset><#FFD700> updated (" + summary + ")<reset>")));
      });
   }

   private void sendHelp(Player player) {
      player.sendMessage(
         ServerTextUtil.miniMessageComponent(
            "<#555555>-<reset>"
         )
      );
      player.sendMessage(ServerTextUtil.miniMessageComponent("<gradient:#FFB300:#FF6F00><bold>  NPC Commands</bold><reset>"));
      player.sendMessage(
         ServerTextUtil.miniMessageComponent(
            "<#555555>-<reset>"
         )
      );
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/npc create <id> [name]<reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/npc delete <id><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/npc list [scope]<reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/npc scope <id> <scope><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/npc action <id> <type> [data] — COMMAND/CONSOLE/PROXY types support PlaceholderAPI<reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/npc type <id> <entity|PLAYER|CHEST|BLOCK:CHEST><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/npc skin <id> <url><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/npc glow <id> <true|false> [color]<reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/npc faceplayer <id> <true|false><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/npc navigator <id> <true|false><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/npc scale <id> <value><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/npc movehere <id><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/npc reload<reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/npc name <id> <display name...><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/npc line <add|set|remove> <id> [text]<reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/npc background|seethrough|shadowed|freeze <id> <true|false><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/npc alignment <id> <left|center|right><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/npc viewrange <id> <0.1-5><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/npc holobillboard <id> <fixed|vertical|horizontal|center><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/npc holoscale <id> <0.01-10><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Lines support MiniMessage + <smallcaps>...</smallcaps><reset>"));
      player.sendMessage(
         ServerTextUtil.miniMessageComponent(
            "<#555555>-<reset>"
         )
      );
   }

   private String readNpcId(LivingEntity entity) {
      NamespacedKey key = new NamespacedKey(this.plugin, "layout_npc_id");
      return (String)entity.getPersistentDataContainer().get(key, PersistentDataType.STRING);
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (!(sender instanceof Player p) || !p.hasPermission("skypvp.staff")) {
         return List.of();
      }

      if (args.length == 1) {
         return List.of("create", "delete", "list", "scope", "action", "type", "skin", "glow", "faceplayer", "navigator", "scale",
               "background", "seethrough", "shadowed", "freeze", "alignment", "viewrange", "holobillboard", "holoscale",
               "movehere", "reload", "line", "name");
      } else if (args.length == 2 && "list".equalsIgnoreCase(args[0])) {
         return DecorationScopes.KNOWN.stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
      } else if (args.length == 3 && "scope".equalsIgnoreCase(args[0])) {
         return DecorationScopes.KNOWN.stream().filter(s -> s.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
      } else if (args.length == 3 && "action".equalsIgnoreCase(args[0])) {
         return InteractionActionTypes.NPC_TAB_TYPES;
      } else {
         return args.length == 2 && "line".equalsIgnoreCase(args[0]) ? List.of("add", "set", "remove") : List.of();
      }
   }
}
