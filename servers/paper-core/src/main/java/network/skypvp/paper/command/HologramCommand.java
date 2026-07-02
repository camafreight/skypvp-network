package network.skypvp.paper.command;

import network.skypvp.shared.ServerTextUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import network.skypvp.paper.util.DecorationScopes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.library.HolographicLibrary;
import network.skypvp.paper.model.HologramDefinition;
import network.skypvp.paper.model.WorldPoint;
import network.skypvp.paper.repository.HologramRepository;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import network.skypvp.paper.PaperCorePlugin;

public final class HologramCommand implements CommandExecutor, TabCompleter {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   private final HologramRepository repository;
   private final HolographicLibrary holographicLibrary;
   private final String serverId;
   private final PaperCorePlugin plugin;

   public HologramCommand(HologramRepository repository, HolographicLibrary holographicLibrary, String serverId, PaperCorePlugin plugin) {
      this.repository = repository;
      this.holographicLibrary = holographicLibrary;
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
               case "confirmdelete":
                  this.handleConfirmDelete(player, args);
                  break;
               case "list":
                  this.handleList(player, args);
                  break;
               case "info":
                  this.handleInfo(player, args);
                  break;
               case "addline":
                  this.handleAddLine(player, args);
                  break;
               case "setline":
                  this.handleSetLine(player, args);
                  break;
               case "insertline":
                  this.handleInsertLine(player, args);
                  break;
               case "removeline":
                  this.handleRemoveLine(player, args);
                  break;
               case "action":
                  this.handleAction(player, args);
                  break;
               case "interactive":
                  this.handleInteractive(player, args);
                  break;
               case "hitboxsize":
                  this.handleHitboxSize(player, args);
                  break;
               case "movehere":
                  this.handleMoveHere(player, args);
                  break;
               case "offset":
                  this.handleOffset(player, args);
                  break;
               case "align":
                  this.handleAlign(player, args);
                  break;
               case "attach":
                  this.handleAttach(player, args);
                  break;
               case "detach":
                  this.handleDetach(player, args);
                  break;
               case "reload":
                  this.handleReload(player);
                  break;
               case "scope":
                  this.handleScope(player, args);
                  break;
               case "sample":
                  this.handleSample(player);
                  break;
               case "billboard":
                  this.handleBillboard(player, args);
                  break;
               case "scale":
                  this.handleScale(player, args);
                  break;
               case "center":
                  this.handleCenter(player, args);
                  break;
               case "sweep":
                  this.handleSweep(player);
                  break;
               default:
                  this.sendHelp(player);
            }

            return true;
         }
      } else {
         sender.sendMessage(ServerTextUtil.component("Only players can use /hologram."));
         return true;
      }
   }

   private void handleSweep(Player player) {
      int count = 0;

      for (ArmorStand stand : player.getWorld().getEntitiesByClass(ArmorStand.class)) {
         Component name = stand.customName();
         if (name != null) {
            String plain = PlainTextComponentSerializer.plainText().serialize(name).trim();
            if (plain.equals("|") || plain.equalsIgnoreCase("<empty>")) {
               stand.remove();
               count++;
            }
         }
      }

      player.sendMessage(ServerTextUtil.miniMessageComponent("<#FFD700>Swept " + count + " orphaned empty line armor stands in this world.<reset>"));
   }

   private void handleCreate(Player player, String[] args) {
      if (args.length < 2) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /holo create <id> [line1...]<reset>"));
      } else {
         String id = args[1].toLowerCase(Locale.ROOT);
         String line1 = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "<gold><bold>" + args[1] + "</bold></gold>";
         HologramDefinition def = new HologramDefinition();
         def.id = id;
         def.lines = new ArrayList<>();
         def.lines.add(line1);
         def.anchor = WorldPoint.fromLocation(player.getLocation());
         def.actionType = "NONE";
         def.actionData = "";
         def.interactive = false;
         def.hitboxSize = 1;
         this.plugin.platformScheduler().runAsync( () -> {
            this.repository.upsert(this.serverId, def, player.getName());
            this.plugin.platformScheduler().runGlobal( () -> {
               this.holographicLibrary.apply(player.getWorld(), List.of(def));
               player.sendMessage(ServerTextUtil.miniMessageComponent("<#FFD700>Hologram <reset><#FFFFFF>" + id + "<reset><#FFD700> created at your location.<reset>"));
            });
         });
      }
   }

   private void handleDelete(Player player, String[] args) {
      if (args.length < 2) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /holo delete <id><reset>"));
      } else {
         String id = args[1].toLowerCase(Locale.ROOT);
         this.plugin.platformScheduler().runAsync(
               () -> {
                  boolean removed = false;

                  try {
                     removed = this.repository.delete(this.serverId, id);
                  } catch (Exception var5) {
                     this.plugin.platformScheduler().runGlobal(
                           () -> player.sendMessage(
                                 ServerTextUtil.miniMessageComponent(
                                    "<#888888>Hologram '<white>"
                                       + id
                                       + "<reset><#888888>' has attached children.<reset> <click:run_command:'/holo confirmdelete "
                                       + id
                                       + "'><hover:show_text:'Click to confirm delete'><#FFD700>[Click here to delete all children and the parent holo]</hover></click>"
                                 )
                              )
                        );
                     return;
                  }

                  // Effectively-final copy so the main-thread callback below can capture the result.
                  final boolean wasRemoved = removed;
                  this.plugin.platformScheduler().runGlobal( () -> {
                     HologramDefinition emptyDef = new HologramDefinition();
                     emptyDef.id = id;
                     emptyDef.lines = new ArrayList<>();
                     this.holographicLibrary.apply(player.getWorld(), List.of(emptyDef));
                     if (wasRemoved) {
                        player.sendMessage(ServerTextUtil.miniMessageComponent("<#FFD700>Hologram <reset><#FFFFFF>" + id + "<reset><#FFD700> deleted.<reset>"));
                     } else {
                        player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>No Hologram with id '<white>" + id + "<reset><#888888>' found.<reset>"));
                     }
                  });
               }
            );
      }
   }

   private void handleConfirmDelete(Player player, String[] args) {
      if (args.length < 2) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /holo confirmdelete <id><reset>"));
      } else {
         String id = args[1].toLowerCase(Locale.ROOT);
         this.plugin.platformScheduler().runAsync(
               () -> {
                  List<HologramDefinition> list = this.repository.loadAll(this.serverId);
                  HologramDefinition target = list.stream().filter(d -> id.equals(d.id)).findFirst().orElse(null);
                  if (target == null) {
                     this.plugin.platformScheduler().runGlobal(
                           () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Hologram '<white>" + id + "<reset><#888888>' not found.<reset>"))
                        );
                  } else {
                     List<String> toDelete = new ArrayList<>();
                     this.findChildrenRecursive(id, list, toDelete);

                     for (String childId : toDelete) {
                        try {
                           this.repository.delete(this.serverId, childId);
                        } catch (Exception var10) {
                        }
                     }

                     try {
                        this.repository.delete(this.serverId, id);
                     } catch (Exception var9) {
                     }

                     this.plugin.platformScheduler().runGlobal(
                           () -> {
                              for (String childIdx : toDelete) {
                                 HologramDefinition emptyDef = new HologramDefinition();
                                 emptyDef.id = childIdx;
                                 emptyDef.lines = new ArrayList<>();
                                 this.holographicLibrary.apply(player.getWorld(), List.of(emptyDef));
                              }

                              HologramDefinition emptyDef = new HologramDefinition();
                              emptyDef.id = id;
                              emptyDef.lines = new ArrayList<>();
                              this.holographicLibrary.apply(player.getWorld(), List.of(emptyDef));
                              player.sendMessage(
                                 ServerTextUtil.miniMessageComponent(
                                    "<#FFD700>Deleted Hologram <reset><#FFFFFF>"
                                       + id
                                       + "<reset><#FFD700> and its <white>"
                                       + toDelete.size()
                                       + "<reset><#FFD700> children.<reset>"
                                 )
                              );
                           }
                        );
                  }
               }
            );
      }
   }

   private void findChildrenRecursive(String parentId, List<HologramDefinition> all, List<String> toDelete) {
      for (HologramDefinition def : all) {
         if (parentId.equalsIgnoreCase(def.parentId)) {
            toDelete.add(def.id);
            this.findChildrenRecursive(def.id, all, toDelete);
         }
      }
   }

   private void handleList(Player player, String[] args) {
      String filterScope = args.length >= 2 ? DecorationScopes.normalize(args[1]) : null;
      this.plugin.platformScheduler().runAsync(
            () -> {
               List<HologramDefinition> list;
               try {
                  list = filterScope == null
                     ? this.repository.loadAllScopedAsync().get()
                     : this.repository.loadAllAsync(filterScope).get();
               } catch (Exception exception) {
                  this.plugin.platformScheduler().runGlobal(
                     () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>Failed to load hologram list.<reset>"))
                  );
                  return;
               }

               this.plugin.platformScheduler().runGlobal(
                     () -> {
                        player.sendMessage(ServerTextUtil.miniMessageComponent("<#555555>----------------------------<reset>"));
                        String title = filterScope == null
                           ? "All Holograms (" + list.size() + ")"
                           : "Holograms in " + filterScope + " (" + list.size() + ")";
                        player.sendMessage(
                           ServerTextUtil.miniMessageComponent("<gradient:#FFB300:#FF6F00><bold>  " + title + "</bold><reset>")
                        );
                        player.sendMessage(ServerTextUtil.miniMessageComponent("<#555555>----------------------------<reset>"));
                        if (list.isEmpty()) {
                           player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>None registered.<reset>"));
                        } else if (filterScope == null) {
                           Map<String, List<HologramDefinition>> byScope = list.stream()
                              .collect(Collectors.groupingBy(d -> d.scope != null ? d.scope : "?"));

                           for (Map.Entry<String, List<HologramDefinition>> scopeEntry : byScope.entrySet()) {
                              player.sendMessage(
                                 ServerTextUtil.miniMessageComponent("<#FFD700>[" + scopeEntry.getKey() + "]<reset>")
                              );
                              this.printHologramScopeGroup(player, scopeEntry.getValue());
                           }
                        } else {
                           this.printHologramScopeGroup(player, list);
                        }

                        player.sendMessage(ServerTextUtil.miniMessageComponent("<#555555>----------------------------<reset>"));
                     }
                  );
            }
         );
   }

   private void printHologramScopeGroup(Player player, List<HologramDefinition> list) {
      List<HologramDefinition> roots = list.stream().filter(d -> d.parentId == null || d.parentId.isBlank()).toList();

      for (HologramDefinition root : roots) {
         this.printHologramNode(player, root, list, 0);
      }

      List<HologramDefinition> orphans = list.stream()
         .filter(
            d -> d.parentId != null && !d.parentId.isBlank() && roots.stream().noneMatch(r -> r.id.equalsIgnoreCase(d.parentId))
         )
         .toList();
      if (!orphans.isEmpty()) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>[Orphaned Children]<reset>"));

         for (HologramDefinition orphan : orphans) {
            this.printHologramNode(player, orphan, list, 0);
         }
      }
   }

   private void handleScope(Player player, String[] args) {
      if (args.length < 3) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /holo scope <id> <scope><reset>"));
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
            List<HologramDefinition> matches;
            try {
               matches = this.repository.findAllByIdAsync(id).get();
            } catch (Exception exception) {
               this.plugin.platformScheduler().runGlobal(
                  () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>Failed to look up hologram.<reset>"))
               );
               return;
            }

            HologramDefinition source = matches.stream().filter(d -> this.serverId.equals(d.scope)).findFirst().orElse(null);
            if (source == null && matches.size() == 1) {
               source = matches.get(0);
            }
            if (source == null) {
               this.plugin.platformScheduler().runGlobal(
                  () -> {
                     if (matches.isEmpty()) {
                        player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Hologram '<white>" + id + "<reset><#888888>' not found.<reset>"));
                     } else {
                        player.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>Hologram '<white>" + id + "<reset><#FF5555>' exists in multiple scopes. Use /holo list to see them.<reset>"));
                     }
                  }
               );
               return;
            }

            if (newScope.equals(source.scope)) {
               this.plugin.platformScheduler().runGlobal(
                  () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Hologram '<white>" + id + "<reset><#888888>' is already in scope '<white>" + newScope + "<reset><#888888>'.<reset>"))
               );
               return;
            }

            String fromScope = source.scope;
            boolean changed = this.repository.changeScope(id, fromScope, newScope);
            this.plugin.platformScheduler().runGlobal(
               () -> {
                  if (!changed) {
                     player.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>Could not move hologram '<white>" + id + "<reset><#FF5555>' to scope '<white>" + newScope + "<reset><#FF5555>' (target scope may already have that id).<reset>"));
                     return;
                  }

                  player.sendMessage(
                     ServerTextUtil.miniMessageComponent(
                        "<#FFD700>Moved hologram <#FFFFFF>" + id + "<reset><#FFD700> (and children) from <white>" + fromScope + "<reset><#FFD700> to <white>" + newScope + "<reset><#FFD700>.<reset>"
                     )
                  );
                  if (this.serverId.equals(fromScope) || this.serverId.equals(newScope)) {
                     this.plugin.reloadHologramDecorations();
                  }
               }
            );
         }
      );
   }

   private void handleInfo(Player player, String[] args) {
      if (args.length < 2) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /holo info <id><reset>"));
      } else {
         String id = args[1].toLowerCase(Locale.ROOT);
         this.plugin.platformScheduler().runAsync(
               () -> {
                  List<HologramDefinition> existing = this.repository.loadAll(this.serverId);
                  HologramDefinition def = existing.stream().filter(d -> id.equals(d.id)).findFirst().orElse(null);
                  if (def == null) {
                     this.plugin.platformScheduler().runGlobal(
                           () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Hologram '<white>" + id + "<reset><#888888>' not found.<reset>"))
                        );
                  } else {
                     this.plugin.platformScheduler().runGlobal( () -> {
                        player.sendMessage(ServerTextUtil.miniMessageComponent("<gradient:#FFB300:#FF6F00><bold>  Hologram Info: " + def.id + "</bold><reset>"));
                        player.sendMessage(ServerTextUtil.miniMessageComponent("<#555555>----------------------------<reset>"));
                        if (def.lines != null && !def.lines.isEmpty()) {
                           for (int i = 0; i < def.lines.size(); i++) {
                              player.sendMessage(ServerTextUtil.miniMessageComponent("  <#888888>" + i + ": <reset>" + def.lines.get(i)));
                           }
                        } else {
                           player.sendMessage(ServerTextUtil.miniMessageComponent("  <#888888>No lines.<reset>"));
                        }

                        player.sendMessage(ServerTextUtil.miniMessageComponent("<#555555>----------------------------<reset>"));
                     });
                  }
               }
            );
      }
   }

   private void handleInsertLine(Player player, String[] args) {
      if (args.length < 4) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /holo insertline <id> <index> <text...><reset>"));
      } else {
         String id = args[1].toLowerCase(Locale.ROOT);

         int index;
         try {
            index = Integer.parseInt(args[2]);
         } catch (NumberFormatException var6) {
            player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Invalid index.<reset>"));
            return;
         }

         String text = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
         this.plugin.platformScheduler().runAsync(
               () -> {
                  List<HologramDefinition> existing = this.repository.loadAll(this.serverId);
                  HologramDefinition def = existing.stream().filter(d -> id.equals(d.id)).findFirst().orElse(null);
                  if (def == null) {
                     this.plugin.platformScheduler().runGlobal(
                           () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Hologram '<white>" + id + "<reset><#888888>' not found.<reset>"))
                        );
                  } else {
                     if (def.lines == null) {
                        def.lines = new ArrayList<>();
                     }

                     int finalIndex = index;
                     if (index < 0) {
                        finalIndex = 0;
                     }

                     if (finalIndex > def.lines.size()) {
                        finalIndex = def.lines.size();
                     }

                     def.lines.add(finalIndex, text);
                     this.repository.upsert(this.serverId, def, player.getName());
                     this.plugin.platformScheduler().runGlobal( () -> {
                        World world = def.anchor != null && def.anchor.world != null ? this.plugin.getServer().getWorld(def.anchor.world) : player.getWorld();
                        if (world != null) {
                           this.holographicLibrary.apply(world, List.of(def));
                        }

                        if (!text.equals("===") && !text.equalsIgnoreCase("===PAGE===") && !text.equals("---")) {
                           player.sendMessage(ServerTextUtil.miniMessageComponent("<#FFD700>Inserted line to Hologram <reset><#FFFFFF>" + id + "<reset>"));
                        } else {
                           player.sendMessage(ServerTextUtil.miniMessageComponent("<#FFD700>Inserted a new page separator in Hologram <reset><#FFFFFF>" + id + "<reset>"));
                        }
                     });
                  }
               }
            );
      }
   }

   private void printHologramNode(Player player, HologramDefinition def, List<HologramDefinition> all, int depth) {
      String indent = "  ".repeat(depth);
      String prefix = depth > 0 ? "<dark_gray>|_ </dark_gray>" : "";
      WorldPoint pt = def.anchor != null ? def.anchor : new WorldPoint();
      String locationStr;
      if (depth > 0) {
         locationStr = String.format(Locale.ROOT, "offset: %.1f %.1f %.1f", def.offsetX, def.offsetY, def.offsetZ);
      } else {
         locationStr = String.format(Locale.ROOT, "at: %.1f %.1f %.1f", pt.x, pt.y, pt.z);
      }

      player.sendMessage(
         ServerTextUtil.miniMessageComponent(
            indent
               + prefix
               + "<#FFFFFF>"
               + def.id
               + "<reset> <#888888>lines=<reset><white>"
               + (def.lines != null ? def.lines.size() : 0)
               + "<reset> <#888888>action=<reset><white>"
               + def.actionType
               + "<reset> <#888888>"
               + locationStr
               + "<reset>"
         )
      );

      for (HologramDefinition child : all.stream().filter(d -> def.id.equalsIgnoreCase(d.parentId)).toList()) {
         this.printHologramNode(player, child, all, depth + 1);
      }
   }

   private void handleAddLine(Player player, String[] args) {
      if (args.length < 3) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /holo addline <id> <text...><reset>"));
      } else {
         String id = args[1].toLowerCase(Locale.ROOT);
         String text = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
         this.plugin.platformScheduler().runAsync(
               () -> {
                  List<HologramDefinition> existing = this.repository.loadAll(this.serverId);
                  HologramDefinition def = existing.stream().filter(d -> id.equals(d.id)).findFirst().orElse(null);
                  if (def == null) {
                     this.plugin.platformScheduler().runGlobal(
                           () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Hologram '<white>" + id + "<reset><#888888>' not found.<reset>"))
                        );
                  } else {
                     if (def.lines == null) {
                        def.lines = new ArrayList<>();
                     }

                     def.lines.add(text);
                     this.repository.upsert(this.serverId, def, player.getName());
                     this.plugin.platformScheduler().runGlobal( () -> {
                        World world = def.anchor != null && def.anchor.world != null ? this.plugin.getServer().getWorld(def.anchor.world) : player.getWorld();
                        if (world != null) {
                           this.holographicLibrary.apply(world, List.of(def));
                        }

                        if (!text.equals("===") && !text.equalsIgnoreCase("===PAGE===") && !text.equals("---")) {
                           player.sendMessage(ServerTextUtil.miniMessageComponent("<#FFD700>Added line to Hologram <reset><#FFFFFF>" + id + "<reset>"));
                        } else {
                           player.sendMessage(ServerTextUtil.miniMessageComponent("<#FFD700>Created a new page separator for Hologram <reset><#FFFFFF>" + id + "<reset>"));
                        }
                     });
                  }
               }
            );
      }
   }

   private void handleSetLine(Player player, String[] args) {
      if (args.length < 4) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /holo setline <id> <index> <text...><reset>"));
      } else {
         String id = args[1].toLowerCase(Locale.ROOT);

         int index;
         try {
            index = Integer.parseInt(args[2]);
         } catch (NumberFormatException var6) {
            player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Invalid index.<reset>"));
            return;
         }

         String text = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
         this.plugin.platformScheduler().runAsync(
               () -> {
                  List<HologramDefinition> existing = this.repository.loadAll(this.serverId);
                  HologramDefinition def = existing.stream().filter(d -> id.equals(d.id)).findFirst().orElse(null);
                  if (def == null) {
                     this.plugin.platformScheduler().runGlobal(
                           () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Hologram '<white>" + id + "<reset><#888888>' not found.<reset>"))
                        );
                  } else if (def.lines != null && index >= 0 && index < def.lines.size()) {
                     def.lines.set(index, text);
                     this.repository.upsert(this.serverId, def, player.getName());
                     this.plugin.platformScheduler().runGlobal( () -> {
                        World world = def.anchor != null && def.anchor.world != null ? this.plugin.getServer().getWorld(def.anchor.world) : player.getWorld();
                        if (world != null) {
                           this.holographicLibrary.apply(world, List.of(def));
                        }

                        player.sendMessage(ServerTextUtil.miniMessageComponent("<#FFD700>Updated line in Hologram <reset><#FFFFFF>" + id + "<reset>"));
                     });
                  } else {
                     this.plugin.platformScheduler().runGlobal( () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Index out of bounds.<reset>")));
                  }
               }
            );
      }
   }

   private void handleRemoveLine(Player player, String[] args) {
      if (args.length < 3) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /holo removeline <id> <index><reset>"));
      } else {
         String id = args[1].toLowerCase(Locale.ROOT);

         int index;
         try {
            index = Integer.parseInt(args[2]);
         } catch (NumberFormatException var6) {
            player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Invalid index.<reset>"));
            return;
         }

         this.plugin.platformScheduler().runAsync(
               () -> {
                  List<HologramDefinition> existing = this.repository.loadAll(this.serverId);
                  HologramDefinition def = existing.stream().filter(d -> id.equals(d.id)).findFirst().orElse(null);
                  if (def == null) {
                     this.plugin.platformScheduler().runGlobal(
                           () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Hologram '<white>" + id + "<reset><#888888>' not found.<reset>"))
                        );
                  } else if (def.lines != null && index >= 0 && index < def.lines.size()) {
                     def.lines.remove(index);
                     this.repository.upsert(this.serverId, def, player.getName());
                     this.plugin.platformScheduler().runGlobal( () -> {
                        World world = def.anchor != null && def.anchor.world != null ? this.plugin.getServer().getWorld(def.anchor.world) : player.getWorld();
                        if (world != null) {
                           this.holographicLibrary.apply(world, List.of(def));
                        }

                        player.sendMessage(ServerTextUtil.miniMessageComponent("<#FFD700>Removed line from Hologram <reset><#FFFFFF>" + id + "<reset>"));
                     });
                  } else {
                     this.plugin.platformScheduler().runGlobal( () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Index out of bounds.<reset>")));
                  }
               }
            );
      }
   }

   private void handleAction(Player player, String[] args) {
      if (args.length < 3) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /holo action <id> <NONE|CONNECT|COMMAND|MESSAGE> [data...]<reset>"));
      } else {
         String id = args[1].toLowerCase(Locale.ROOT);
         String actionType = args[2].toUpperCase(Locale.ROOT);
         String actionData = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : "";
         this.plugin.platformScheduler().runAsync(
               () -> {
                  List<HologramDefinition> existing = this.repository.loadAll(this.serverId);
                  HologramDefinition def = existing.stream().filter(d -> id.equals(d.id)).findFirst().orElse(null);
                  if (def == null) {
                     this.plugin.platformScheduler().runGlobal(
                           () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Hologram '<white>" + id + "<reset><#888888>' not found.<reset>"))
                        );
                  } else {
                     def.actionType = actionType;
                     def.actionData = actionData;
                     this.repository.upsert(this.serverId, def, player.getName());
                     this.plugin.platformScheduler().runGlobal(
                           () -> {
                              World world = def.anchor != null && def.anchor.world != null
                                 ? this.plugin.getServer().getWorld(def.anchor.world)
                                 : player.getWorld();
                              if (world != null) {
                                 this.holographicLibrary.apply(world, List.of(def));
                              }

                              player.sendMessage(
                                 ServerTextUtil.miniMessageComponent(
                                    "<#FFD700>Hologram <reset><#FFFFFF>"
                                       + id
                                       + "<reset><#FFD700> action set to <reset><white>"
                                       + actionType
                                       + (actionData.isBlank() ? "" : " " + actionData)
                                       + "<reset>"
                                 )
                              );
                           }
                        );
                  }
               }
            );
      }
   }

   private void handleInteractive(Player player, String[] args) {
      if (args.length < 3) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /holo interactive <id> <true|false><reset>"));
      } else {
         String id = args[1].toLowerCase(Locale.ROOT);
         boolean interactive = Boolean.parseBoolean(args[2]);
         this.plugin.platformScheduler().runAsync(
               () -> {
                  List<HologramDefinition> existing = this.repository.loadAll(this.serverId);
                  HologramDefinition def = existing.stream().filter(d -> id.equals(d.id)).findFirst().orElse(null);
                  if (def == null) {
                     this.plugin.platformScheduler().runGlobal(
                           () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Hologram '<white>" + id + "<reset><#888888>' not found.<reset>"))
                        );
                  } else {
                     def.interactive = interactive;
                     this.repository.upsert(this.serverId, def, player.getName());
                     this.plugin.platformScheduler().runGlobal(
                           () -> {
                              World world = def.anchor != null && def.anchor.world != null
                                 ? this.plugin.getServer().getWorld(def.anchor.world)
                                 : player.getWorld();
                              if (world != null) {
                                 this.holographicLibrary.apply(world, List.of(def));
                              }

                              player.sendMessage(
                                 ServerTextUtil.miniMessageComponent(
                                    "<#FFD700>Hologram <reset><#FFFFFF>" + id + "<reset><#FFD700> interactivity set to " + interactive + ".<reset>"
                                 )
                              );
                           }
                        );
                  }
               }
            );
      }
   }

   private void handleHitboxSize(Player player, String[] args) {
      if (args.length < 3) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /holo hitboxsize <id> <size><reset>"));
      } else {
         String id = args[1].toLowerCase(Locale.ROOT);

         int size;
         try {
            size = Integer.parseInt(args[2]);
         } catch (NumberFormatException var6) {
            player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Invalid size.<reset>"));
            return;
         }

         this.plugin.platformScheduler().runAsync(
               () -> {
                  List<HologramDefinition> existing = this.repository.loadAll(this.serverId);
                  HologramDefinition def = existing.stream().filter(d -> id.equals(d.id)).findFirst().orElse(null);
                  if (def == null) {
                     this.plugin.platformScheduler().runGlobal(
                           () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Hologram '<white>" + id + "<reset><#888888>' not found.<reset>"))
                        );
                  } else {
                     def.hitboxSize = size;
                     this.repository.upsert(this.serverId, def, player.getName());
                     this.plugin.platformScheduler().runGlobal(
                           () -> {
                              World world = def.anchor != null && def.anchor.world != null
                                 ? this.plugin.getServer().getWorld(def.anchor.world)
                                 : player.getWorld();
                              if (world != null) {
                                 this.holographicLibrary.apply(world, List.of(def));
                              }

                              player.sendMessage(
                                 ServerTextUtil.miniMessageComponent("<#FFD700>Hologram <reset><#FFFFFF>" + id + "<reset><#FFD700> hitbox size set to " + size + ".<reset>")
                              );
                           }
                        );
                  }
               }
            );
      }
   }

   private void handleMoveHere(Player player, String[] args) {
      if (args.length < 2) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /holo movehere <id><reset>"));
      } else {
         String id = args[1].toLowerCase(Locale.ROOT);
         this.plugin.platformScheduler().runAsync(
               () -> {
                  List<HologramDefinition> existing = this.repository.loadAll(this.serverId);
                  HologramDefinition def = existing.stream().filter(d -> id.equals(d.id)).findFirst().orElse(null);
                  if (def == null) {
                     this.plugin.platformScheduler().runGlobal(
                           () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Hologram '<white>" + id + "<reset><#888888>' not found.<reset>"))
                        );
                  } else {
                     def.anchor = WorldPoint.fromLocation(player.getLocation());
                     this.repository.upsert(this.serverId, def, player.getName());
                     this.plugin.platformScheduler().runGlobal( () -> {
                        this.holographicLibrary.apply(player.getWorld(), List.of(def));
                        player.sendMessage(ServerTextUtil.miniMessageComponent("<#FFD700>Moved Hologram <reset><#FFFFFF>" + id + "<reset><#FFD700> to your location.<reset>"));
                     });
                  }
               }
            );
      }
   }

   private void handleOffset(Player player, String[] args) {
      if (args.length < 5) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /holo offset <id> <dx> <dy> <dz><reset>"));
      } else {
         String id = args[1].toLowerCase(Locale.ROOT);

         double dx;
         double dy;
         double dz;
         try {
            dx = Double.parseDouble(args[2]);
            dy = Double.parseDouble(args[3]);
            dz = Double.parseDouble(args[4]);
         } catch (NumberFormatException var11) {
            player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Invalid offset values. Use numbers.<reset>"));
            return;
         }

         this.plugin.platformScheduler().runAsync(
               () -> {
                  List<HologramDefinition> existing = this.repository.loadAll(this.serverId);
                  HologramDefinition def = existing.stream().filter(d -> id.equals(d.id)).findFirst().orElse(null);
                  if (def == null) {
                     this.plugin.platformScheduler().runGlobal(
                           () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Hologram '<white>" + id + "<reset><#888888>' not found.<reset>"))
                        );
                  } else {
                     if (def.parentId != null && !def.parentId.isBlank()) {
                        def.offsetX += dx;
                        def.offsetY += dy;
                        def.offsetZ += dz;
                     } else {
                        if (def.anchor == null) {
                           def.anchor = new WorldPoint();
                        }

                        def.anchor.x += dx;
                        def.anchor.y += dy;
                        def.anchor.z += dz;
                     }

                     this.repository.upsert(this.serverId, def, player.getName());
                     this.plugin.platformScheduler().runGlobal(
                           () -> {
                              World world = def.anchor != null && def.anchor.world != null
                                 ? this.plugin.getServer().getWorld(def.anchor.world)
                                 : player.getWorld();
                              if (world != null) {
                                 this.holographicLibrary.apply(world, List.of(def));
                              }

                              player.sendMessage(
                                 ServerTextUtil.miniMessageComponent(
                                    "<#FFD700>Offset Hologram <reset><#FFFFFF>" + id + "<reset><#FFD700> by " + dx + ", " + dy + ", " + dz + ".<reset>"
                                 )
                              );
                           }
                        );
                  }
               }
            );
      }
   }

   private void handleAlign(Player player, String[] args) {
      if (args.length < 3) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /holo align <id> <target_id> [x|y|z|xz|xyz|rot|all]<reset>"));
      } else {
         String id = args[1].toLowerCase(Locale.ROOT);
         String targetId = args[2].toLowerCase(Locale.ROOT);
         String mode = args.length > 3 ? args[3].toLowerCase(Locale.ROOT) : "all";
         this.plugin.platformScheduler().runAsync(
               () -> {
                  List<HologramDefinition> existing = this.repository.loadAll(this.serverId);
                  HologramDefinition def = existing.stream().filter(d -> id.equals(d.id)).findFirst().orElse(null);
                  HologramDefinition targetDef = existing.stream().filter(d -> targetId.equals(d.id)).findFirst().orElse(null);
                  if (def != null && targetDef != null) {
                     if (def.anchor == null) {
                        def.anchor = new WorldPoint();
                     }

                     if (targetDef.anchor == null) {
                        targetDef.anchor = new WorldPoint();
                     }

                     boolean alignX = false, alignY = false, alignZ = false, alignRot = false, alignWorld = false;
                     switch (mode) {
                        case "x":
                           alignX = true;
                           break;
                        case "y":
                           alignY = true;
                           break;
                        case "z":
                           alignZ = true;
                           break;
                        case "xz":
                           alignX = true;
                           alignZ = true;
                           break;
                        case "xyz":
                           alignX = true;
                           alignY = true;
                           alignZ = true;
                           break;
                        case "rot":
                           alignRot = true;
                           break;
                        case "all":
                        default:
                           alignX = true;
                           alignY = true;
                           alignZ = true;
                           alignRot = true;
                           alignWorld = true;
                           break;
                     }

                     if (alignWorld) def.anchor.world = targetDef.anchor.world;
                     if (alignX) def.anchor.x = targetDef.anchor.x;
                     if (alignY) def.anchor.y = targetDef.anchor.y;
                     if (alignZ) def.anchor.z = targetDef.anchor.z;
                     if (alignRot) {
                        def.anchor.yaw = targetDef.anchor.yaw;
                        def.anchor.pitch = targetDef.anchor.pitch;
                     }

                     this.repository.upsert(this.serverId, def, player.getName());
                     this.plugin.platformScheduler().runGlobal(
                           () -> {
                              World world = def.anchor != null && def.anchor.world != null
                                 ? this.plugin.getServer().getWorld(def.anchor.world)
                                 : player.getWorld();
                              if (world != null) {
                                 this.holographicLibrary.apply(world, List.of(def));
                              }

                              player.sendMessage(
                                 ServerTextUtil.miniMessageComponent(
                                    "<#FFD700>Aligned Hologram <reset><#FFFFFF>" + id + "<reset><#FFD700> to <reset><#FFFFFF>" + targetId + "<reset> (mode: " + mode + ")."
                                 )
                              );
                           }
                        );
                  } else {
                     this.plugin.platformScheduler().runGlobal( () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>One or both Holograms not found.<reset>")));
                  }
               }
            );
      }
   }

   private void handleAttach(Player player, String[] args) {
      if (args.length < 3) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /holo attach <child_id> <parent_id> [dx] [dy] [dz]<reset>"));
      } else {
         String id = args[1].toLowerCase(Locale.ROOT);
         String parentId = args[2].toLowerCase(Locale.ROOT);
         double dx = 0.0;
         double dy = 0.0;
         double dz = 0.0;
         if (args.length >= 6) {
            try {
               dx = Double.parseDouble(args[3]);
               dy = Double.parseDouble(args[4]);
               dz = Double.parseDouble(args[5]);
            } catch (NumberFormatException var17) {
               player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Invalid offset values. Use numbers.<reset>"));
               return;
            }
         }

         double finalDx = dx;
         double finalDy = dy;
         double finalDz = dz;
         this.plugin.platformScheduler().runAsync(
               () -> {
                  List<HologramDefinition> existing = this.repository.loadAll(this.serverId);
                  HologramDefinition def = existing.stream().filter(d -> id.equals(d.id)).findFirst().orElse(null);
                  HologramDefinition parentDef = existing.stream().filter(d -> parentId.equals(d.id)).findFirst().orElse(null);
                  if (def != null && parentDef != null) {
                     def.parentId = parentId;
                     def.offsetX = finalDx;
                     def.offsetY = finalDy;
                     def.offsetZ = finalDz;
                     this.repository.upsert(this.serverId, def, player.getName());
                     this.plugin.platformScheduler().runGlobal(
                           () -> {
                              this.holographicLibrary.apply(player.getWorld(), List.of(def));
                              player.sendMessage(
                                 ServerTextUtil.miniMessageComponent(
                                    "<#FFD700>Attached Hologram <reset><#FFFFFF>" + id + "<reset><#FFD700> to <reset><#FFFFFF>" + parentId + "<reset>."
                                 )
                              );
                           }
                        );
                  } else {
                     this.plugin.platformScheduler().runGlobal( () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>One or both Holograms not found.<reset>")));
                  }
               }
            );
      }
   }

   private void handleDetach(Player player, String[] args) {
      if (args.length < 2) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /holo detach <child_id><reset>"));
      } else {
         String id = args[1].toLowerCase(Locale.ROOT);
         this.plugin.platformScheduler().runAsync(
               () -> {
                  List<HologramDefinition> existing = this.repository.loadAll(this.serverId);
                  HologramDefinition def = existing.stream().filter(d -> id.equals(d.id)).findFirst().orElse(null);
                  if (def == null) {
                     this.plugin.platformScheduler().runGlobal(
                           () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Hologram '<white>" + id + "<reset><#888888>' not found.<reset>"))
                        );
                  } else if (def.parentId != null && !def.parentId.isBlank()) {
                     def.parentId = null;
                     def.offsetX = 0.0;
                     def.offsetY = 0.0;
                     def.offsetZ = 0.0;
                     if (def.anchor == null) {
                        def.anchor = WorldPoint.fromLocation(player.getLocation());
                     }

                     this.repository.upsert(this.serverId, def, player.getName());
                     this.plugin.platformScheduler().runGlobal( () -> {
                        this.holographicLibrary.apply(player.getWorld(), List.of(def));
                        player.sendMessage(ServerTextUtil.miniMessageComponent("<#FFD700>Detached Hologram <reset><#FFFFFF>" + id + "<reset>."));
                     });
                  } else {
                     this.plugin.platformScheduler().runGlobal( () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Hologram is not attached to a parent.<reset>")));
                  }
               }
            );
      }
   }

   private void handleBillboard(Player player, String[] args) {
      if (args.length < 3) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /holo billboard <id> <fixed|vertical|horizontal|center><reset>"));
      } else {
         String id = args[1].toLowerCase(Locale.ROOT);
         String billboard = args[2].toUpperCase(Locale.ROOT);
         this.plugin.platformScheduler().runAsync( () -> {
            List<HologramDefinition> existing = this.repository.loadAll(this.serverId);
            HologramDefinition def = existing.stream().filter(d -> id.equals(d.id)).findFirst().orElse(null);
            if (def == null) {
               this.plugin.platformScheduler().runGlobal( () -> {
                  player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Hologram '<white>" + id + "<reset><#888888>' not found.<reset>"));
               });
            } else {
               def.billboard = billboard;
               this.repository.upsert(this.serverId, def, player.getName());
               this.plugin.platformScheduler().runGlobal( () -> {
                  this.holographicLibrary.apply(player.getWorld(), List.of(def));
                  player.sendMessage(ServerTextUtil.miniMessageComponent("<#FFD700>Hologram <#FFFFFF>" + id + "<reset><#FFD700> billboard set to <white>" + billboard + "<reset>"));
               });
            }
         });
      }
   }

   private void handleScale(Player player, String[] args) {
      if (args.length < 3) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /holo scale <id> <value><reset>"));
      } else {
         String id = args[1].toLowerCase(Locale.ROOT);
         double scale;
         try {
            scale = Double.parseDouble(args[2]);
         } catch (NumberFormatException e) {
            player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Invalid scale value. Use a number.<reset>"));
            return;
         }

         if (scale < 0.01 || scale > 10.0) {
            player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Scale must be between 0.01 and 10.0.<reset>"));
            return;
         }

         final double finalScale = scale;
         this.plugin.platformScheduler().runAsync( () -> {
            List<HologramDefinition> existing = this.repository.loadAll(this.serverId);
            HologramDefinition def = existing.stream().filter(d -> id.equals(d.id)).findFirst().orElse(null);
            if (def == null) {
               this.plugin.platformScheduler().runGlobal( () -> {
                  player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Hologram '<white>" + id + "<reset><#888888>' not found.<reset>"));
               });
            } else {
               def.scale = finalScale;
               this.repository.upsert(this.serverId, def, player.getName());
               this.plugin.platformScheduler().runGlobal( () -> {
                  this.holographicLibrary.apply(player.getWorld(), List.of(def));
                  player.sendMessage(ServerTextUtil.miniMessageComponent("<#FFD700>Hologram <#FFFFFF>" + id + "<reset><#FFD700> scale set to <white>" + finalScale + "<reset>"));
               });
            }
         });
      }
   }

   private void handleCenter(Player player, String[] args) {
      if (args.length < 2) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /holo center <id><reset>"));
      } else {
         String id = args[1].toLowerCase(Locale.ROOT);
         this.plugin.platformScheduler().runAsync( () -> {
            List<HologramDefinition> existing = this.repository.loadAll(this.serverId);
            HologramDefinition def = existing.stream().filter(d -> id.equals(d.id)).findFirst().orElse(null);
            if (def == null) {
               this.plugin.platformScheduler().runGlobal( () -> {
                  player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Hologram '<white>" + id + "<reset><#888888>' not found.<reset>"));
               });
            } else {
               if (def.anchor == null) {
                  def.anchor = new WorldPoint();
               }
               def.anchor.x = Math.floor(def.anchor.x) + 0.5;
               def.anchor.z = Math.floor(def.anchor.z) + 0.5;
               
               float yaw = def.anchor.yaw;
               float normalizedYaw = ((yaw % 360) + 360) % 360;
               float snappedYaw;
               if (normalizedYaw < 45 || normalizedYaw >= 315) {
                  snappedYaw = 0.0f; // South
               } else if (normalizedYaw >= 45 && normalizedYaw < 135) {
                  snappedYaw = 90.0f; // West
               } else if (normalizedYaw >= 135 && normalizedYaw < 225) {
                  snappedYaw = 180.0f; // North
               } else {
                  snappedYaw = 270.0f; // East
               }
               def.anchor.yaw = snappedYaw;
               def.anchor.pitch = 0.0f;

               this.repository.upsert(this.serverId, def, player.getName());
               this.plugin.platformScheduler().runGlobal( () -> {
                  this.holographicLibrary.apply(player.getWorld(), List.of(def));
                  player.sendMessage(ServerTextUtil.miniMessageComponent("<#FFD700>Centered Hologram <#FFFFFF>" + id + "<reset><#FFD700> to block center (Yaw: " + snappedYaw + ").<reset>"));
               });
            }
         });
      }
   }

   private void handleReload(Player player) {
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#FFD700>Reloading holograms...<reset>"));
      this.plugin.reloadHologramDecorations();
   }

   private void handleSample(Player player) {
      String id = "sample_leaderboard";
      HologramDefinition def = new HologramDefinition();
      def.id = id;
      def.lines = new ArrayList<>();
      def.anchor = WorldPoint.fromLocation(player.getLocation().add(0.0, 1.5, 0.0));
      def.actionType = "NONE";
      def.interactive = false;
      def.lines.add("<gradient:blue:aqua><bold>SkyPvP NETWORK</bold></gradient>");
      def.lines.add("===");
      def.lines.add("<anim:rainbow>Welcome to the Hologram Library Demo!");
      def.lines.add("");
      def.lines.add("<anim:scroll><yellow>We support incredible features like scrolling text, typewriters, and physics animations!</yellow>");
      def.lines.add("");
      def.lines.add("<anim:typewriter><green>Check out this cool typewriter effect!</green>");
      def.lines.add("");
      def.lines.add("<gray>Page %currentPage% of %pages%</gray>");
      def.lines.add("===");
      def.lines.add("<gradient:gold:yellow><bold>TOP PLAYERS - KILLS</bold></gradient>");
      def.lines.add("");
      def.lines.add("<anim:bounce><gold>1. Notch - 9,999</gold>");
      def.lines.add("<anim:blink><gray>2. jeb_ - 5,432</gray>");
      def.lines.add("<color:#cd7f32>3. Dinnerbone - 1,234</color>");
      def.lines.add("");
      def.lines.add("<gray>Page %currentPage% of %pages%</gray>");
      def.lines.add("===");
      def.lines.add("<gradient:red:gold><bold>PHYSICS SHOWCASE</bold></gradient>");
      def.lines.add("");
      def.lines.add("<anim:rain><aqua>This line is raining down!</aqua>");
      def.lines.add("");
      def.lines.add("<anim:shower><light_purple>This line is a fountain shower!</light_purple>");
      def.lines.add("");
      def.lines.add("<gray>Page %currentPage% of %pages%</gray>");
      HologramDefinition prevDef = new HologramDefinition();
      prevDef.id = "sample_prev";
      prevDef.parentId = id;
      prevDef.offsetX = -1.5;
      prevDef.offsetY = -1.5;
      prevDef.lines = List.of("<red>\u25C0 Previous</red>");
      prevDef.interactive = true;
      prevDef.hitboxSize = 1;
      prevDef.actionType = "PREV_PAGE";
      HologramDefinition nextDef = new HologramDefinition();
      nextDef.id = "sample_next";
      nextDef.parentId = id;
      nextDef.offsetX = 1.5;
      nextDef.offsetY = -1.5;
      nextDef.lines = List.of("<green>Next \u25B6</green>");
      nextDef.interactive = true;
      nextDef.hitboxSize = 1;
      nextDef.actionType = "NEXT_PAGE";
      this.plugin.platformScheduler().runAsync(
            () -> {
               this.repository.upsert(this.serverId, def, player.getName());
               this.repository.upsert(this.serverId, prevDef, player.getName());
               this.repository.upsert(this.serverId, nextDef, player.getName());
                        this.plugin.platformScheduler().runGlobal(
                     () -> {
                        this.holographicLibrary.apply(player.getWorld(), List.of(def, prevDef, nextDef));
                        player.sendMessage(
                           ServerTextUtil.miniMessageComponent(
                              "<green>Spawned the sample leaderboard showcase at your location! IDs: <white>sample_leaderboard, sample_prev, sample_next<reset>"
                           )
                        );
                     }
                  );
            }
         );
   }

   private void sendHelp(Player player) {
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#555555>────────────────────────────<reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<gradient:#FFB300:#FF6F00><bold>  Hologram Commands</bold><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#555555>────────────────────────────<reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/holo create <id> [line1]<reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/holo delete <id><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/holo list [scope]<reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/holo scope <id> <scope><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/holo info <id><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/holo addline <id> <text><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/holo setline <id> <index> <text><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/holo insertline <id> <index> <text><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/holo removeline <id> <index><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/holo action <id> <type> [data]<reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/holo interactive <id> <true|false><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/holo hitboxsize <id> <size><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/holo movehere <id><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/holo offset <id> <dx> <dy> <dz><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/holo align <id> <target_id> [x|y|z|xz|xyz|rot|all]<reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/holo center <id><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/holo scale <id> <value><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/holo attach <child_id> <parent_id> [dx] [dy] [dz]<reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/holo detach <child_id><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/holo sweep<reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/holo reload<reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/holo sample<reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>/holo billboard <id> <type><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#555555>────────────────────────────<reset>"));
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (!(sender instanceof Player p) || !p.hasPermission("skypvp.staff")) {
         return List.of();
      }

      if (args.length == 1) {
         String prefix = args[0].toLowerCase(Locale.ROOT);
         List<String> options = List.of(
            "create",
            "delete",
            "confirmdelete",
            "list",
            "scope",
            "info",
            "addline",
            "setline",
            "insertline",
            "removeline",
            "action",
            "interactive",
            "hitboxsize",
            "movehere",
            "offset",
            "align",
            "center",
            "scale",
            "attach",
            "detach",
            "reload",
            "sample",
            "billboard"
         );
         List<String> matches = new ArrayList<>();

         for (String option : options) {
            if (option.startsWith(prefix)) {
               matches.add(option);
            }
         }

         return matches;
      } else if (args.length == 2 && "list".equalsIgnoreCase(args[0])) {
         return DecorationScopes.KNOWN.stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
      } else if (args.length == 3 && "scope".equalsIgnoreCase(args[0])) {
         return DecorationScopes.KNOWN.stream().filter(s -> s.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
      } else if (args.length == 2
         && (
            "delete".equalsIgnoreCase(args[0])
               || "addline".equalsIgnoreCase(args[0])
               || "setline".equalsIgnoreCase(args[0])
               || "insertline".equalsIgnoreCase(args[0])
               || "info".equalsIgnoreCase(args[0])
               || "removeline".equalsIgnoreCase(args[0])
               || "action".equalsIgnoreCase(args[0])
               || "interactive".equalsIgnoreCase(args[0])
               || "hitboxsize".equalsIgnoreCase(args[0])
               || "movehere".equalsIgnoreCase(args[0])
               || "offset".equalsIgnoreCase(args[0])
               || "align".equalsIgnoreCase(args[0])
               || "center".equalsIgnoreCase(args[0])
               || "scale".equalsIgnoreCase(args[0])
               || "attach".equalsIgnoreCase(args[0])
               || "detach".equalsIgnoreCase(args[0])
               || "billboard".equalsIgnoreCase(args[0])
               || "scope".equalsIgnoreCase(args[0])
         )) {
         String prefix = args[1].toLowerCase(Locale.ROOT);
         Set<String> ids = this.holographicLibrary.getActiveHologramIds();
         List<String> matches = new ArrayList<>();

         for (String id : ids) {
            if (id.startsWith(prefix)) {
               matches.add(id);
            }
         }

         return matches;
      } else if (args.length == 3 && "action".equalsIgnoreCase(args[0])) {
         return List.of("NONE", "CONNECT", "COMMAND", "MESSAGE", "NEXT_PAGE", "PREV_PAGE", "GOTO_PAGE", "TOGGLE_PAGE");
      } else if (args.length == 3 && "interactive".equalsIgnoreCase(args[0])) {
         return List.of("true", "false");
      } else if (args.length == 3 && "billboard".equalsIgnoreCase(args[0])) {
         return List.of("FIXED", "VERTICAL", "HORIZONTAL", "CENTER");
      } else if (args.length == 3 && "align".equalsIgnoreCase(args[0])) {
         String prefix = args[2].toLowerCase(Locale.ROOT);
         Set<String> ids = this.holographicLibrary.getActiveHologramIds();
         List<String> matches = new ArrayList<>();
         for (String id : ids) {
            if (id.startsWith(prefix)) {
               matches.add(id);
            }
         }
         return matches;
      } else if (args.length == 4 && "align".equalsIgnoreCase(args[0])) {
         String prefix = args[3].toLowerCase(Locale.ROOT);
         List<String> options = List.of("x", "y", "z", "xz", "xyz", "rot", "all");
         List<String> matches = new ArrayList<>();
         for (String opt : options) {
            if (opt.startsWith(prefix)) {
               matches.add(opt);
            }
         }
         return matches;
      } else {
         return List.of();
      }
   }
}


