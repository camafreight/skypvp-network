package network.skypvp.paper.library;

import network.skypvp.shared.ServerTextUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.library.packet.NpcFakeLightVisual;
import network.skypvp.paper.model.HologramDefinition;
import network.skypvp.paper.model.WorldPoint;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemDisplay.ItemDisplayTransform;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Display.Billboard;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import network.skypvp.paper.platform.PlatformTask;
import org.bukkit.util.NumberConversions;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

public final class HolographicLibrary implements Listener {
   private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
   private static final double LINE_SPACING = 0.28;
   private static final double HITBOX_VERTICAL_OFFSET = 0.0;
   private static final int TEXT_DISPLAY_LINE_WIDTH = 4096;
   private static final Display.Brightness FULL_BRIGHTNESS = new Display.Brightness(15, 15);
   private final PaperCorePlugin plugin;
   private final NamespacedKey hologramIdKey;
   private final NamespacedKey lineIndexKey;
   private final NamespacedKey hitboxKey;
   private final NamespacedKey actionTypeKey;
   private final NamespacedKey actionDataKey;

   private final Map<String, HolographicLibrary.ActiveHologramState> activeStates = new ConcurrentHashMap<>();
   private final Map<String, HologramDefinition> activeDefinitions = new ConcurrentHashMap<>();
   private final Map<String, NpcFakeLightVisual> fakeLights = new ConcurrentHashMap<>();
   private final Map<UUID, Map<String, Integer>> playerPages = new ConcurrentHashMap<>();
   private PlatformTask syncTask;
   private long globalTick = 0L;

   public HolographicLibrary(PaperCorePlugin plugin) {
      this.plugin = plugin;
      this.hologramIdKey = new NamespacedKey(plugin, "layout_hologram_id");
      this.lineIndexKey = new NamespacedKey(plugin, "layout_hologram_line");
      this.hitboxKey = new NamespacedKey(plugin, "layout_hologram_hitbox");
      this.actionTypeKey = new NamespacedKey(plugin, "layout_hologram_action_type");
      this.actionDataKey = new NamespacedKey(plugin, "layout_hologram_action_data");
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
   }

   @EventHandler
   public void onPlayerJoin(org.bukkit.event.player.PlayerJoinEvent event) {
      Player player = event.getPlayer();
      for (String id : this.activeDefinitions.keySet()) {
         this.showTo(player, id);
      }
   }

   @EventHandler
   public void onPlayerQuit(PlayerQuitEvent event) {
      this.playerPages.remove(event.getPlayer().getUniqueId());
   }

   @EventHandler
   public void onChunkLoad(ChunkLoadEvent event) {
      Chunk chunk = event.getChunk();
      World world = chunk.getWorld();
      String worldName = world.getName();
      int cx = chunk.getX();
      int cz = chunk.getZ();

      for (HologramDefinition def : this.activeDefinitions.values()) {
         if (def.anchor != null && worldName.equals(def.anchor.world)) {
            int defCx = NumberConversions.floor(def.anchor.x) >> 4;
            int defCz = NumberConversions.floor(def.anchor.z) >> 4;
            if (defCx == cx && defCz == cz) {
               this.applyDefinition(world, def, this.activeDefinitions);
            }
         }
      }
   }

   public Set<String> getActiveHologramIds() {
      return this.activeDefinitions.keySet();
   }

   public void hideFrom(Player player, String hologramId) {
      HolographicLibrary.ActiveHologramState state = this.activeStates.get(this.stateKey(player.getWorld(), this.normalizeId(hologramId)));
      if (state != null) {
         for (HolographicLibrary.LineState ls : state.lines()) {
            Entity entity = player.getWorld().getEntity(ls.entityId);
            if (entity != null) {
               player.hideEntity(this.plugin, entity);
            }
         }
      }
      NpcFakeLightVisual light = this.fakeLights.get(this.normalizeId(hologramId));
      if (light != null) {
         light.hideFrom(player);
      }
   }

   public void showTo(Player player, String hologramId) {
      String normId = this.normalizeId(hologramId);
      HolographicLibrary.ActiveHologramState state = this.activeStates.get(this.stateKey(player.getWorld(), normId));
      if (state != null) {
         HologramDefinition def = this.activeDefinitions.get(normId);
         String pageKey = normId;
         if (def != null && def.parentId != null && !def.parentId.isBlank()) {
            pageKey = def.parentId.toLowerCase(Locale.ROOT);
         }

         Map<String, Integer> pMap = this.playerPages.getOrDefault(player.getUniqueId(), Collections.emptyMap());
         int targetPage = pMap.getOrDefault(pageKey, 0);
         int pagesCount = this.getPages(def).size();

         for (HolographicLibrary.LineState ls : state.lines()) {
            if (ls.entityId != null) {
               Entity entity = player.getWorld().getEntity(ls.entityId);
               if (entity != null) {
                  if (ls.pageIndex == targetPage || (pagesCount == 1 && def.parentId != null)) {
                     player.showEntity(this.plugin, entity);
                  } else {
                     player.hideEntity(this.plugin, entity);
                  }
               }
            }
         }
      }
      NpcFakeLightVisual light = this.fakeLights.get(normId);
      if (light != null) {
         light.showTo(player);
      }
   }

   public void apply(World world, List<? extends HologramDefinition> definitions) {
      if (world != null) {
         Map<String, HologramDefinition> byId = new HashMap<>();

         for (HologramDefinition def : definitions) {
            if (def != null && def.id != null && !def.id.isBlank()) {
               String normId = def.id.toLowerCase(Locale.ROOT);
               if (def.lines != null && !def.lines.isEmpty()) {
                  this.activeDefinitions.put(normId, def);
               } else {
                  this.activeDefinitions.remove(normId);
               }

               byId.put(normId, def);
            }
         }

         boolean added;
         do {
            added = false;

            for (HologramDefinition activeDef : this.activeDefinitions.values()) {
               if (activeDef.parentId != null && !activeDef.parentId.isBlank()) {
                  String normParent = activeDef.parentId.toLowerCase(Locale.ROOT);
                  if (byId.containsKey(normParent) && !byId.containsKey(activeDef.id.toLowerCase(Locale.ROOT))) {
                     byId.put(activeDef.id.toLowerCase(Locale.ROOT), activeDef);
                     added = true;
                  }
               }
            }
         } while (added);

         this.cleanupTaggedHitboxes(world, byId);

         for (HologramDefinition defx : byId.values()) {
            this.applyDefinition(world, defx, this.activeDefinitions);
         }

         this.stopSyncTaskIfIdle();
      }
   }

   public boolean handleInteract(Player player, Entity entity) {
      if (player != null && entity != null) {
         PersistentDataContainer pdc = entity.getPersistentDataContainer();
         Byte hitboxValue = (Byte)pdc.get(this.hitboxKey, PersistentDataType.BYTE);
         String hologramId = (String)pdc.get(this.hologramIdKey, PersistentDataType.STRING);
         if (hitboxValue != null && hitboxValue != 0 && hologramId != null && !hologramId.isBlank()) {
            String actionType = (String)pdc.get(this.actionTypeKey, PersistentDataType.STRING);
            String actionData = (String)pdc.get(this.actionDataKey, PersistentDataType.STRING);
            if (actionType != null) {
               String normAction = actionType.trim().toUpperCase(Locale.ROOT);
               if (normAction.equals("NEXT_PAGE")
                  || normAction.equals("PREV_PAGE")
                  || normAction.equals("GOTO_PAGE")
                  || normAction.equals("CYCLE_PAGE")) {
                  this.handlePageAction(player, hologramId, normAction, actionData);
                  NetworkSoundCue.HOLOGRAM_INTERACT.play(player);
                  return true;
               }
            }

            InteractionActionExecutor.execute(this.plugin, player, actionType, actionData);
            NetworkSoundCue.HOLOGRAM_INTERACT.play(player);
            return true;
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   private void handlePageAction(Player player, String clickedId, String action, String actionData) {
      String normClicked = clickedId.toLowerCase(Locale.ROOT);
      HologramDefinition clickedDef = this.activeDefinitions.get(normClicked);
      if (clickedDef != null) {
         String targetId = normClicked;
         HologramDefinition targetDef = clickedDef;
         if (clickedDef.parentId != null && !clickedDef.parentId.isBlank()) {
            String normParent = clickedDef.parentId.toLowerCase(Locale.ROOT);
            HologramDefinition parentDef = this.activeDefinitions.get(normParent);
            if (parentDef != null) {
               targetId = normParent;
               targetDef = parentDef;
            }
         }

         int maxPages = this.getPages(targetDef).size();

         for (HologramDefinition def : this.activeDefinitions.values()) {
            if (def.parentId != null && def.parentId.equalsIgnoreCase(targetId)) {
               maxPages = Math.max(maxPages, this.getPages(def).size());
            }
         }

         if (maxPages > 1) {
            Map<String, Integer> pMap = this.playerPages.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>());
            int currentPage = pMap.getOrDefault(targetId, 0);
            int newPage = currentPage;
            switch (action) {
               case "NEXT_PAGE":
                  if (currentPage < maxPages - 1) {
                     newPage = currentPage + 1;
                  }
                  break;
               case "PREV_PAGE":
                  if (currentPage > 0) {
                     newPage = currentPage - 1;
                  }
                  break;
               case "CYCLE_PAGE":
                  newPage = (currentPage + 1) % maxPages;
                  break;
               case "GOTO_PAGE":
                  try {
                     int p = Integer.parseInt(actionData.trim());
                     if (p >= 0 && p < maxPages) {
                        newPage = p;
                     }
                  } catch (NumberFormatException e) {
                  }
            }

            if (newPage != currentPage) {
               pMap.put(targetId, newPage);
               this.showTo(player, targetId);

               for (HologramDefinition defx : this.activeDefinitions.values()) {
                  if (defx.parentId != null && defx.parentId.equalsIgnoreCase(targetId)) {
                     this.showTo(player, defx.id);
                  }
               }
            }
         }
      }
   }

   private List<List<String>> getPages(HologramDefinition def) {
      List<List<String>> pages = new ArrayList<>();
      if (def != null && def.lines != null) {
         List<String> currentPage = new ArrayList<>();
         List<String> pageNames = new ArrayList<>();
         String currentName = null;

         for (String line : def.lines) {
            if (line != null) {
               String u = line.trim().toUpperCase(Locale.ROOT);
               if (!u.equals("===") && !u.equals("---") && !u.startsWith("===PAGE")) {
                  currentPage.add(line);
               } else {
                  boolean isFirstTag = pages.isEmpty() && currentName == null && currentPage.stream().allMatch(String::isBlank);
                  if (isFirstTag) {
                     currentPage.clear();
                  } else {
                     pages.add(new ArrayList<>(currentPage));
                     pageNames.add(currentName);
                     currentPage.clear();
                  }

                  if (u.startsWith("===PAGE:")) {
                     currentName = line.substring(8, line.lastIndexOf("=")).trim();

                     while (currentName.endsWith("=")) {
                        currentName = currentName.substring(0, currentName.length() - 1);
                     }
                  } else {
                     currentName = null;
                  }
               }
            }
         }

         pages.add(new ArrayList<>(currentPage));
         pageNames.add(currentName);
         boolean hasNames = false;

         for (String n : pageNames) {
            if (n != null && !n.isEmpty()) {
               hasNames = true;
               break;
            }
         }

         if (pages.size() > 1 && hasNames) {
            for (int i = 0; i < pages.size(); i++) {
               StringBuilder tabRow = new StringBuilder();

               for (int j = 0; j < pages.size(); j++) {
                  String name = pageNames.get(j) != null ? pageNames.get(j) : String.valueOf(j + 1);
                  if (j == i) {
                     tabRow.append("<green>").append(name).append("</green>");
                  } else {
                     tabRow.append("<gray>").append(name).append("</gray>");
                  }

                  if (j < pages.size() - 1) {
                     tabRow.append(" <dark_gray>/</dark_gray> ");
                  }
               }

               pages.get(i).add(tabRow.toString());
               pages.get(i).add("<white>Click to toggle</white>");
            }
         }

         return pages;
      } else {
         return pages;
      }
   }

   private void applyDefinition(World world, HologramDefinition definition, Map<String, HologramDefinition> allDefs) {
      String normalizedId = this.normalizeId(definition.id);
      if (normalizedId != null) {
         List<List<String>> pages = this.getPages(definition);
         if (pages.isEmpty() || pages.size() == 1 && pages.get(0).isEmpty()) {
            this.despawnHitbox(world, normalizedId);
            this.removeHologramLight(normalizedId);
            HolographicLibrary.ActiveHologramState oldState = this.activeStates.remove(this.stateKey(world, normalizedId));
            if (oldState != null) {
               for (HolographicLibrary.LineState ls : oldState.lines()) {
                  if (ls.entityId == null) {
                     continue;
                  }
                  Entity entity = world.getEntity(ls.entityId);
                  if (entity != null) {
                     entity.remove();
                  }
               }
            } else {
               for (Entity stand : world.getEntitiesByClasses(new Class[]{ArmorStand.class, ItemDisplay.class, TextDisplay.class, BlockDisplay.class})) {
                  PersistentDataContainer pdc = stand.getPersistentDataContainer();
                  String id = (String)pdc.get(this.hologramIdKey, PersistentDataType.STRING);
                  if (normalizedId.equalsIgnoreCase(id)) {
                     stand.remove();
                  }
               }
            }
         } else {
            Map<Integer, Entity> existing = new HashMap<>();
            HolographicLibrary.ActiveHologramState oldState = this.activeStates.get(this.stateKey(world, normalizedId));
            if (oldState != null) {
               for (int i = 0; i < oldState.lines().size(); i++) {
                  HolographicLibrary.LineState lsx = oldState.lines().get(i);
                  if (lsx.entityId != null) {
                     Entity entity = world.getEntity(lsx.entityId);
                     if (entity != null && entity.isValid() && !entity.isDead()) {
                        existing.put(lsx.pageIndex * 1000 + lsx.lineIndex, entity);
                     }
                  }
               }
            } else {
               for (Entity standx : world.getEntitiesByClasses(new Class[]{ArmorStand.class, ItemDisplay.class, TextDisplay.class, BlockDisplay.class})) {
                  PersistentDataContainer pdc = standx.getPersistentDataContainer();
                  String id = (String)pdc.get(this.hologramIdKey, PersistentDataType.STRING);
                  if (id != null && id.equalsIgnoreCase(normalizedId)) {
                     Integer lineIndex = (Integer)pdc.get(this.lineIndexKey, PersistentDataType.INTEGER);
                     if (lineIndex != null) {
                        if (existing.containsKey(lineIndex)) {
                           standx.remove();
                        } else {
                           existing.put(lineIndex, standx);
                        }
                     } else {
                        standx.remove();
                     }
                  }
               }
            }

            Slime hitbox = this.reconcileHitbox(world, definition, normalizedId, allDefs);
            Location anchorLocation = hitbox == null
               ? this.resolveAnchorLocation(world, definition, allDefs, new HashSet<>())
               : this.textAnchorLocation(hitbox.getLocation());
            this.ensureHologramLight(normalizedId, anchorLocation, definition);
            Set<Integer> used = new HashSet<>();
            List<HolographicLibrary.LineState> lineStates = new ArrayList<>();

            for (int pIdx = 0; pIdx < pages.size(); pIdx++) {
               List<String> lines = pages.get(pIdx);
            Map<String, String> placeholders = new HashMap<>();
               placeholders.put("currentPage", String.valueOf(pIdx + 1));
               placeholders.put("pages", String.valueOf(Math.max(1, pages.size())));
               placeholders.put("hologram_id", definition.id);

               for (int index = 0; index < lines.size(); index++) {
                  int virtualIndex = pIdx * 1000 + index;
                  Location location = this.lineLocation(anchorLocation, lines.size(), index, definition.scale);
                  Entity standxx = existing.get(virtualIndex);
                  HolographicLibrary.ParsedLine parsedLine = new HolographicLibrary.ParsedLine(lines.get(index));
                  if (parsedLine.isEmpty) {
                     if (standxx != null) {
                        standxx.remove();
                        existing.remove(virtualIndex);
                     }

                     lineStates.add(new HolographicLibrary.LineState(null, parsedLine, placeholders, location, pIdx, index));
                     used.add(virtualIndex);
                  } else {
                     boolean shouldBeVisibleByDefault = pIdx == 0 && !definition.perPlayer;
                     if (parsedLine.itemMaterial != null) {
                        if (standxx instanceof ItemDisplay && standxx.isValid() && !standxx.isDead()) {
                           this.teleportEntity(standxx, location);
                           standxx.setVisibleByDefault(shouldBeVisibleByDefault);
                           this.applyItemLineFormat((ItemDisplay)standxx, normalizedId, virtualIndex, parsedLine, definition);
                        } else {
                           if (standxx != null) {
                              standxx.remove();
                           }

                           standxx = world.spawn(location, ItemDisplay.class, display -> {
                              display.setVisibleByDefault(shouldBeVisibleByDefault);
                              this.applyItemLineFormat(display, normalizedId, virtualIndex, parsedLine, definition);
                           });
                        }
                     } else if (parsedLine.blockMaterial != null) {
                        if (standxx instanceof BlockDisplay && standxx.isValid() && !standxx.isDead()) {
                           this.teleportEntity(standxx, location);
                           standxx.setVisibleByDefault(shouldBeVisibleByDefault);
                           this.applyBlockLineFormat((BlockDisplay)standxx, normalizedId, virtualIndex, parsedLine, definition);
                        } else {
                           if (standxx != null) {
                              standxx.remove();
                           }

                           standxx = world.spawn(location, BlockDisplay.class, display -> {
                              display.setVisibleByDefault(shouldBeVisibleByDefault);
                              this.applyBlockLineFormat(display, normalizedId, virtualIndex, parsedLine, definition);
                           });
                        }
                     } else if (standxx instanceof TextDisplay && standxx.isValid() && !standxx.isDead()) {
                        this.teleportEntity(standxx, location);
                        standxx.setVisibleByDefault(shouldBeVisibleByDefault);
                        this.applyLineFormat((TextDisplay)standxx, normalizedId, virtualIndex, parsedLine, placeholders, location, 0L, definition);
                     } else {
                        if (standxx != null) {
                           standxx.remove();
                        }

                        standxx = world.spawn(location, TextDisplay.class, stand -> {
                           stand.setVisibleByDefault(shouldBeVisibleByDefault);
                           this.applyLineFormat(stand, normalizedId, virtualIndex, parsedLine, placeholders, location, 0L, definition);
                        });
                     }

                     lineStates.add(new HolographicLibrary.LineState(standxx.getUniqueId(), parsedLine, placeholders, location, pIdx, index));
                     used.add(virtualIndex);
                  }
               }
            }

            for (Entry<Integer, Entity> entry : existing.entrySet()) {
               if (!used.contains(entry.getKey())) {
                  entry.getValue().remove();
               }
            }

            String stateKey = this.stateKey(world, normalizedId);
            boolean hasAnimations = lineStates.stream().anyMatch(lsx -> lsx.parsedLine.animation != HolographicLibrary.HologramAnimation.NONE);
            this.activeStates
               .put(
                  stateKey,
                  new HolographicLibrary.ActiveHologramState(
                     hitbox == null ? null : hitbox.getUniqueId(),
                     anchorLocation.clone(),
                     lineStates,
                     hasAnimations,
                     definition
                  )
               );
            if (hitbox == null && !hasAnimations) {
               // We still keep it in activeStates to have the definition for syncHolograms if it has billboard
            }
            this.ensureSyncTask();

            for (Player p : world.getPlayers()) {
               if (this.plugin.platform().isFolia()) {
                  this.plugin.platform().runOnPlayer(p, () -> this.showTo(p, normalizedId));
               } else {
                  this.showTo(p, normalizedId);
               }
            }
         }
      }
   }

   private void ensureHologramLight(String normalizedId, Location anchor, HologramDefinition definition) {
      if (definition != null && definition.parentId != null && !definition.parentId.isBlank()) {
         this.removeHologramLight(normalizedId);
         return;
      }
      if (anchor == null || anchor.getWorld() == null) {
         return;
      }
      NpcFakeLightVisual existing = this.fakeLights.get(normalizedId);
      if (existing != null && existing.matchesAnchor(anchor)) {
         return;
      }
      if (existing != null) {
         for (Player player : this.plugin.getServer().getOnlinePlayers()) {
            existing.hideFrom(player);
         }
      }
      this.fakeLights.put(normalizedId, new NpcFakeLightVisual(this.plugin, anchor));
   }

   private void removeHologramLight(String normalizedId) {
      NpcFakeLightVisual removed = this.fakeLights.remove(normalizedId);
      if (removed == null) {
         return;
      }
      for (Player player : this.plugin.getServer().getOnlinePlayers()) {
         removed.hideFrom(player);
      }
   }

   private void applyFullBrightness(Display display) {
      if (display != null) {
         display.setBrightness(FULL_BRIGHTNESS);
      }
   }

   private void applyItemLineFormat(ItemDisplay display, String hologramId, int lineIndex, HolographicLibrary.ParsedLine parsedLine, HologramDefinition definition) {
      display.setItemStack(new ItemStack(parsedLine.itemMaterial));
      display.setItemDisplayTransform(ItemDisplayTransform.FIXED);
      this.applyFullBrightness(display);
      if (parsedLine.billboard != null) {
         display.setBillboard(parsedLine.billboard);
      } else if (definition != null && definition.billboard != null) {
         try {
            display.setBillboard(Billboard.valueOf(definition.billboard.toUpperCase(Locale.ROOT)));
         } catch (Exception e) {
            display.setBillboard(Billboard.FIXED);
         }
      } else {
         display.setBillboard(Billboard.FIXED);
      }
      display.setPersistent(false);
      display.setTeleportDuration(0);
      float defScale = definition != null ? (float) definition.scale : 1.0f;
      if (defScale <= 0.0f) defScale = 1.0f;
      float s = parsedLine.scale * defScale;
      if (s <= 0.0f) s = 1.0f;
      display.setTransformation(
         new Transformation(
            new Vector3f(0.0F, 0.0F, 0.0F),
            new AxisAngle4f(0.0F, 0.0F, 1.0F, 0.0F),
            new Vector3f(s, s, s),
            new AxisAngle4f(0.0F, 0.0F, 1.0F, 0.0F)
         )
      );
      if (hologramId != null) {
         PersistentDataContainer pdc = display.getPersistentDataContainer();
         pdc.set(this.hologramIdKey, PersistentDataType.STRING, hologramId);
         pdc.set(this.lineIndexKey, PersistentDataType.INTEGER, lineIndex);
      }
   }

   private void teleportEntity(Entity entity, Location location) {
      if (entity == null || location == null) {
         return;
      }
      if (this.plugin.platform().isFolia()) {
         entity.teleportAsync(location);
      } else {
         entity.teleport(location);
      }
   }

   private void applyPhysicsAnimation(Entity entity, HolographicLibrary.ParsedLine parsedLine, Location baseLocation, long tick) {
      if (parsedLine.animation == HolographicLibrary.HologramAnimation.BOUNCE) {
         double offsetY = Math.sin((double)(tick % 40L) / 40.0 * Math.PI * 2.0) * 0.15;
         this.teleportEntity(entity, baseLocation.clone().add(0.0, offsetY, 0.0));
      } else if (parsedLine.animation == HolographicLibrary.HologramAnimation.RAIN) {
         double progress = (double)(tick % 40L) / 40.0;
         double offsetY = -(progress * 1.5);
         this.teleportEntity(entity, baseLocation.clone().add(0.0, offsetY, 0.0));
      } else if (parsedLine.animation == HolographicLibrary.HologramAnimation.SHOWER) {
         double t = (double)(tick % 50L) / 10.0;
         double offsetY = 1.2 * t - 0.25 * t * t;
         this.teleportEntity(entity, baseLocation.clone().add(0.0, offsetY, 0.0));
      }
   }

   private void applyLineFormat(
      ArmorStand stand,
      String hologramId,
      int lineIndex,
      HolographicLibrary.ParsedLine parsedLine,
      Map<String, String> placeholders,
      Location baseLocation,
      long tick
   ) {
      stand.setVisible(false);
      stand.setSmall(true);
      stand.setMarker(true);
      stand.setGravity(false);
      stand.setInvulnerable(true);
      stand.setSilent(true);
      stand.setSilent(true);
      stand.setPersistent(false);
      if (hologramId != null) {
         PersistentDataContainer pdc = stand.getPersistentDataContainer();
         pdc.set(this.hologramIdKey, PersistentDataType.STRING, hologramId);
         pdc.set(this.lineIndexKey, PersistentDataType.INTEGER, lineIndex);
      }

      String displayText = parsedLine.baseText;
      for (Map.Entry<String, String> entry : placeholders.entrySet()) {
         displayText = displayText.replace("%" + entry.getKey() + "%", entry.getValue());
         displayText = displayText.replace("{" + entry.getKey() + "}", entry.getValue());
      }
      if (parsedLine.animation == HolographicLibrary.HologramAnimation.SCROLL) {
         String leadingTags = extractLeadingTags(displayText);
         String plain = extractPlainTextStatic(displayText);
         if (!plain.isEmpty()) {
            int offset = (int)(tick / 5L % (long)Math.max(1, plain.length() + 10));
            if (offset < plain.length()) {
               displayText = leadingTags + plain.substring(offset) + "   " + plain.substring(0, offset);
            } else {
               displayText = leadingTags + plain;
            }
         }
      } else if (parsedLine.animation == HolographicLibrary.HologramAnimation.TYPEWRITER) {
         int plainLen = getVisibleLength(displayText);
         if (plainLen > 0) {
            int length = (int)(tick / 3L % (long)Math.max(1, plainLen + 20));
            if (length < plainLen) {
               displayText = substringPreservingTags(displayText, length) + "_";
            }
         }
      } else if (parsedLine.animation == HolographicLibrary.HologramAnimation.BLINK) {
         if (tick / 10L % 2L == 0L) {
            displayText = "";
         }
      } else if (parsedLine.animation == HolographicLibrary.HologramAnimation.RAINBOW) {
         displayText = "<rainbow:" + tick % 100L + ">" + extractPlainTextStatic(displayText) + "</rainbow>";
      }

      String plainResult = extractPlainTextStatic(displayText).trim();
      stand.setCustomNameVisible(!plainResult.isEmpty());

      try {
         String trimmed = displayText.trim();
         Component comp;
         if ((!trimmed.startsWith("{") || !trimmed.endsWith("}")) && (!trimmed.startsWith("[") || !trimmed.endsWith("]"))) {
            comp = MINI_MESSAGE.deserialize(displayText);
         } else {
            comp = GsonComponentSerializer.gson().deserialize(displayText);
         }

         stand.customName(comp);
      } catch (Exception e) {
         stand.customName(ServerTextUtil.component(plainResult));
      }
   }

   private void applyLineFormat(
      TextDisplay stand,
      String hologramId,
      int lineIndex,
      HolographicLibrary.ParsedLine parsedLine,
      Map<String, String> placeholders,
      Location baseLocation,
      long tick,
      HologramDefinition definition
   ) {
      if (parsedLine.billboard != null) {
         stand.setBillboard(parsedLine.billboard);
      } else if (definition != null && definition.billboard != null) {
         try {
            stand.setBillboard(Billboard.valueOf(definition.billboard.toUpperCase(Locale.ROOT)));
         } catch (Exception e) {
            stand.setBillboard(Billboard.CENTER);
         }
      } else {
         stand.setBillboard(Billboard.CENTER);
      }
      stand.setPersistent(false);
      stand.setDefaultBackground(false);
      stand.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
      stand.setLineWidth(TEXT_DISPLAY_LINE_WIDTH);
      stand.setTeleportDuration(0);
      this.applyFullBrightness(stand);
      float defScale = definition != null ? (float) definition.scale : 1.0f;
      if (defScale <= 0.0f) defScale = 1.0f;
      float s = defScale;
      stand.setTransformation(
         new Transformation(
            new Vector3f(0.0F, 0.0F, 0.0F),
            new AxisAngle4f(0.0F, 0.0F, 1.0F, 0.0F),
            new Vector3f(s, s, s),
            new AxisAngle4f(0.0F, 0.0F, 1.0F, 0.0F)
         )
      );
      if (hologramId != null) {
         PersistentDataContainer pdc = stand.getPersistentDataContainer();
         pdc.set(this.hologramIdKey, PersistentDataType.STRING, hologramId);
         pdc.set(this.lineIndexKey, PersistentDataType.INTEGER, lineIndex);
      }

      String displayText = parsedLine.baseText;
      for (Map.Entry<String, String> entry : placeholders.entrySet()) {
         displayText = displayText.replace("%" + entry.getKey() + "%", entry.getValue());
         displayText = displayText.replace("{" + entry.getKey() + "}", entry.getValue());
      }
      if (parsedLine.animation == HolographicLibrary.HologramAnimation.SCROLL) {
         String leadingTags = extractLeadingTags(displayText);
         String plain = extractPlainTextStatic(displayText);
         if (!plain.isEmpty()) {
            int offset = (int)(tick / 5L % (long)Math.max(1, plain.length() + 10));
            if (offset < plain.length()) {
               displayText = leadingTags + plain.substring(offset) + "   " + plain.substring(0, offset);
            } else {
               displayText = leadingTags + plain;
            }
         }
      } else if (parsedLine.animation == HolographicLibrary.HologramAnimation.TYPEWRITER) {
         int plainLen = getVisibleLength(displayText);
         if (plainLen > 0) {
            int length = (int)(tick / 3L % (long)Math.max(1, plainLen + 20));
            if (length < plainLen) {
               displayText = substringPreservingTags(displayText, length) + "_";
            }
         }
      } else if (parsedLine.animation == HolographicLibrary.HologramAnimation.BLINK) {
         if (tick / 10L % 2L == 0L) {
            displayText = "";
         }
      } else if (parsedLine.animation == HolographicLibrary.HologramAnimation.RAINBOW) {
         displayText = "<rainbow:" + tick % 100L + ">" + extractPlainTextStatic(displayText) + "</rainbow>";
      }

      String plainResult = extractPlainTextStatic(displayText).trim();

      try {
         String trimmed = displayText.trim();
         Component comp;
         if ((!trimmed.startsWith("{") || !trimmed.endsWith("}")) && (!trimmed.startsWith("[") || !trimmed.endsWith("]"))) {
            comp = MINI_MESSAGE.deserialize(displayText);
         } else {
            comp = GsonComponentSerializer.gson().deserialize(displayText);
         }

         stand.text(comp);
      } catch (Exception e) {
         stand.text(ServerTextUtil.component(plainResult));
      }
   }

   private void applyBlockLineFormat(BlockDisplay display, String hologramId, int lineIndex, HolographicLibrary.ParsedLine parsedLine, HologramDefinition definition) {
      display.setBlock(org.bukkit.Bukkit.createBlockData(parsedLine.blockMaterial));
      this.applyFullBrightness(display);
      if (parsedLine.billboard != null) {
         display.setBillboard(parsedLine.billboard);
      } else if (definition != null && definition.billboard != null) {
         try {
            display.setBillboard(Billboard.valueOf(definition.billboard.toUpperCase(Locale.ROOT)));
         } catch (Exception e) {
            display.setBillboard(Billboard.CENTER);
         }
      } else {
         display.setBillboard(Billboard.CENTER);
      }
      display.setPersistent(false);
      display.setTeleportDuration(0);
      float s = parsedLine.scale * (definition != null ? (float) definition.scale : 1.0f);
      display.setTransformation(
         new Transformation(
            new Vector3f(0.0F, 0.0F, 0.0F),
            new AxisAngle4f(0.0F, 0.0F, 1.0F, 0.0F),
            new Vector3f(s, s, s),
            new AxisAngle4f(0.0F, 0.0F, 1.0F, 0.0F)
         )
      );
      if (hologramId != null) {
         PersistentDataContainer pdc = display.getPersistentDataContainer();
         pdc.set(this.hologramIdKey, PersistentDataType.STRING, hologramId);
         pdc.set(this.lineIndexKey, PersistentDataType.INTEGER, lineIndex);
      }
   }

   private void cleanupTaggedArmorStands(World world, Map<String, HologramDefinition> byId) {
      for (ArmorStand stand : world.getEntitiesByClass(ArmorStand.class)) {
         PersistentDataContainer pdc = stand.getPersistentDataContainer();
         String id = (String)pdc.get(this.hologramIdKey, PersistentDataType.STRING);
         if (id != null && !id.isBlank()) {
            String normId = id.toLowerCase(Locale.ROOT);
            if (byId.containsKey(normId)) {
               Integer lineIndex = (Integer)pdc.get(this.lineIndexKey, PersistentDataType.INTEGER);
               HologramDefinition definition = byId.get(normId);
               if (definition != null && lineIndex != null && lineIndex >= 0) {
                  int maxLines = 0;
                  List<List<String>> pages = this.getPages(definition);
                  if (!pages.isEmpty()) {
                     for (List<String> page : pages) {
                        if (page.size() > maxLines) {
                           maxLines = page.size();
                        }
                     }
                  } else if (definition.lines != null) {
                     maxLines = definition.lines.size();
                  }

                  if (lineIndex >= maxLines) {
                     stand.remove();
                  }
               } else {
                  stand.remove();
               }
            }
         }
      }
   }

   private void cleanupTaggedHitboxes(World world, Map<String, HologramDefinition> byId) {
      for (Slime slime : world.getEntitiesByClass(Slime.class)) {
         PersistentDataContainer pdc = slime.getPersistentDataContainer();
         Byte hitboxValue = (Byte)pdc.get(this.hitboxKey, PersistentDataType.BYTE);
         if (hitboxValue != null && hitboxValue != 0) {
            String id = (String)pdc.get(this.hologramIdKey, PersistentDataType.STRING);
            if (id != null && !id.isBlank()) {
               String normId = id.toLowerCase(Locale.ROOT);
               if (byId.containsKey(normId)) {
                  HologramDefinition definition = byId.get(normId);
                  if (definition == null || definition.lines == null || definition.lines.isEmpty() || !this.isInteractive(definition)) {
                     slime.remove();
                  }
               }
            }
         }
      }
   }

   private Slime reconcileHitbox(World world, HologramDefinition definition, String normalizedId, Map<String, HologramDefinition> allDefs) {
      if (!this.isInteractive(definition)) {
         this.despawnHitbox(world, normalizedId);
         return null;
      } else {
         Location location = this.hitboxLocation(this.resolveAnchorLocation(world, definition, allDefs, new HashSet<>()));
         Slime hitbox = this.findHitbox(world, normalizedId);
         if (hitbox != null && hitbox.isValid() && !hitbox.isDead()) {
            this.teleportEntity(hitbox, location);
         } else {
            hitbox = (Slime)world.spawn(location, Slime.class);
         }

         this.applyHitbox(hitbox, normalizedId, definition);
         return hitbox;
      }
   }

   private void applyHitbox(Slime hitbox, String hologramId, HologramDefinition definition) {
      hitbox.setAI(false);
      hitbox.setInvisible(true);
      hitbox.setInvulnerable(true);
      hitbox.setSilent(true);
      hitbox.setCollidable(false);
      hitbox.setGravity(false);
      hitbox.setPersistent(false);
      hitbox.setRemoveWhenFarAway(false);
      hitbox.setCanPickupItems(false);
      hitbox.setSize(this.clampHitboxSize(definition.hitboxSize));
      hitbox.setVisibleByDefault(!definition.perPlayer);
      hitbox.customName(null);
      PersistentDataContainer pdc = hitbox.getPersistentDataContainer();
      pdc.set(this.hologramIdKey, PersistentDataType.STRING, hologramId);
      pdc.set(this.hitboxKey, PersistentDataType.BYTE, (byte)1);
      pdc.set(this.actionTypeKey, PersistentDataType.STRING, definition.actionType == null ? "NONE" : definition.actionType);
      pdc.set(this.actionDataKey, PersistentDataType.STRING, definition.actionData == null ? "" : definition.actionData);
   }

   private Slime findHitbox(World world, String hologramId) {
      for (Slime slime : world.getEntitiesByClass(Slime.class)) {
         PersistentDataContainer pdc = slime.getPersistentDataContainer();
         Byte hitboxValue = (Byte)pdc.get(this.hitboxKey, PersistentDataType.BYTE);
         String id = (String)pdc.get(this.hologramIdKey, PersistentDataType.STRING);
         if (hitboxValue != null && hitboxValue == 1 && id != null && id.equalsIgnoreCase(hologramId)) {
            return slime;
         }
      }

      return null;
   }

   private void despawnHitbox(World world, String hologramId) {
      if (hologramId != null && !hologramId.isBlank()) {
         Slime hitbox = this.findHitbox(world, hologramId);
         if (hitbox != null) {
            hitbox.remove();
         }
      }
   }

   private void ensureSyncTask() {
      if (this.syncTask == null) {
         this.syncTask = this.plugin.platformScheduler().runGlobalTimer(this::syncHolograms, 1L, 1L);
      }
   }

   private void syncHolograms() {
      if (this.activeStates.isEmpty()) {
         this.stopSyncTaskIfIdle();
         return;
      }

      this.globalTick++;
      for (Entry<String, HolographicLibrary.ActiveHologramState> entry : this.activeStates.entrySet()) {
         HolographicLibrary.ActiveHologramState state = entry.getValue();
         if (!state.hasAnimations()) {
            continue;
         }

         Location regionAnchor = state.regionAnchor();
         if (regionAnchor == null || regionAnchor.getWorld() == null) {
            continue;
         }

         Location syncAnchor = regionAnchor.clone();
         if (this.plugin.platform().isFolia()) {
            this.plugin.platform().runAtLocation(syncAnchor, () -> this.syncHologramEntry(entry, this.resolveLiveAnchor(state, syncAnchor)));
         } else {
            this.syncHologramEntry(entry, this.resolveLiveAnchor(state, syncAnchor));
         }
      }
   }

   private Location resolveLiveAnchor(HolographicLibrary.ActiveHologramState state, Location fallback) {
      if (state.hitboxEntityId() != null) {
         Entity hitbox = this.plugin.getServer().getEntity(state.hitboxEntityId());
         if (hitbox instanceof Slime slime && slime.isValid() && !slime.isDead()) {
            return this.textAnchorLocation(slime.getLocation());
         }
      }

      return fallback;
   }

   private void syncHologramEntry(Entry<String, HolographicLibrary.ActiveHologramState> entry, Location anchorLocation) {
      HolographicLibrary.ActiveHologramState state = entry.getValue();
      for (int index = 0; index < state.lines().size(); index++) {
         HolographicLibrary.LineState lineState = state.lines().get(index);
         if (lineState.entityId != null) {
            Entity lineEntity = this.plugin.getServer().getEntity(lineState.entityId);
            if (lineEntity != null && lineEntity.isValid() && !lineEntity.isDead()) {
               if (anchorLocation != null) {
                  int pageLineCount = 0;

                  for (HolographicLibrary.LineState ls : state.lines()) {
                     if (ls.pageIndex == lineState.pageIndex) {
                        pageLineCount++;
                     }
                  }

                  lineState.baseLocation = this.lineLocation(anchorLocation, pageLineCount, lineState.lineIndex, state.definition().scale);
               }

               if (state.hasAnimations() && lineState.parsedLine.animation != HolographicLibrary.HologramAnimation.NONE) {
                  this.applyPhysicsAnimation(lineEntity, lineState.parsedLine, lineState.baseLocation, this.globalTick);
                  if (lineEntity instanceof ArmorStand standEntity) {
                     this.applyLineFormat(
                        standEntity,
                        entry.getKey(),
                        lineState.pageIndex * 1000 + lineState.lineIndex,
                        lineState.parsedLine,
                        lineState.placeholders,
                        lineState.baseLocation,
                        this.globalTick
                     );
                  } else if (lineEntity instanceof ItemDisplay displayEntity) {
                     this.applyItemLineFormat(displayEntity, entry.getKey(), lineState.pageIndex * 1000 + lineState.lineIndex, lineState.parsedLine, state.definition());
                  } else if (lineEntity instanceof BlockDisplay blockEntity) {
                     this.applyBlockLineFormat(blockEntity, entry.getKey(), lineState.pageIndex * 1000 + lineState.lineIndex, lineState.parsedLine, state.definition());
                  } else if (lineEntity instanceof TextDisplay textEntity) {
                     this.applyLineFormat(
                        textEntity,
                        entry.getKey(),
                        lineState.pageIndex * 1000 + lineState.lineIndex,
                        lineState.parsedLine,
                        lineState.placeholders,
                        lineState.baseLocation,
                        this.globalTick,
                        state.definition()
                     );
                  }
               }
            }
         }
      }
   }

   private void stopSyncTaskIfIdle() {
      if (this.activeStates.isEmpty() && this.syncTask != null) {
         this.syncTask.cancel();
         this.syncTask = null;
      }
   }

   private boolean isInteractive(HologramDefinition definition) {
      return definition == null
         ? false
         : definition.interactive || definition.actionType != null && !definition.actionType.isBlank() && !definition.actionType.equalsIgnoreCase("NONE");
   }

   private int clampHitboxSize(int requestedSize) {
      return Math.max(1, Math.min(4, requestedSize));
   }

   private String normalizeId(String hologramId) {
      return hologramId != null && !hologramId.isBlank() ? hologramId.toLowerCase(Locale.ROOT) : null;
   }

   private String stateKey(World world, String hologramId) {
      return world.getUID() + ":" + this.normalizeId(hologramId);
   }

   private Location resolveAnchorLocation(World fallbackWorld, HologramDefinition definition, Map<String, HologramDefinition> allDefs, Set<String> visited) {
      if (!visited.add(definition.id)) {
         return this.getBaseAnchorLocation(fallbackWorld, definition.anchor);
      } else {
         if (definition.parentId != null && !definition.parentId.isBlank()) {
            HologramDefinition parentDef = allDefs.get(definition.parentId.toLowerCase(Locale.ROOT));
            if (parentDef != null) {
               Location parentLoc = this.resolveAnchorLocation(fallbackWorld, parentDef, allDefs, visited);
               return parentLoc.clone().add(definition.offsetX, definition.offsetY, definition.offsetZ);
            }
         }

         Location baseLoc = this.getBaseAnchorLocation(fallbackWorld, definition.anchor);
         return baseLoc.clone().add(definition.offsetX, definition.offsetY, definition.offsetZ);
      }
   }

   private Location getBaseAnchorLocation(World fallbackWorld, WorldPoint anchor) {
      World world = fallbackWorld;
      if (anchor != null && anchor.world != null && !anchor.world.isBlank()) {
         World resolved = this.plugin.getServer().getWorld(anchor.world);
         if (resolved != null) {
            world = resolved;
         }
      }

      double baseX = anchor == null ? fallbackWorld.getSpawnLocation().getX() : anchor.x;
      double baseY = anchor == null ? fallbackWorld.getSpawnLocation().getY() : anchor.y;
      double baseZ = anchor == null ? fallbackWorld.getSpawnLocation().getZ() : anchor.z;
      float yaw = anchor == null ? 0.0F : anchor.yaw;
      float pitch = anchor == null ? 0.0F : anchor.pitch;
      return new Location(world, baseX, baseY, baseZ, yaw, pitch);
   }

   private Location hitboxLocation(Location anchorLocation) {
      return anchorLocation.clone().add(0.0, 0.0, 0.0);
   }

   private Location textAnchorLocation(Location hitboxLocation) {
      return hitboxLocation.clone().add(0.0, -0.0, 0.0);
   }

   private Location lineLocation(Location anchorLocation, int lineCount, int lineIndex, double scale) {
      if (scale <= 0.0) scale = 1.0;
      double y = anchorLocation.getY() + (double)(lineCount - 1 - lineIndex) * 0.28 * scale;
      return new Location(anchorLocation.getWorld(), anchorLocation.getX(), y, anchorLocation.getZ(), anchorLocation.getYaw(), anchorLocation.getPitch());
   }

   private static String extractPlainTextStatic(String text) {
      if (text != null && !text.isBlank()) {
         String result = text.replaceAll("\\{.*?\"text\"\\s*:\\s*\"([^\"]+)\".*?}", "$1")
            .replaceAll("<[^>]+>", "")
            .replaceAll("[{}\\[\\]\"':]", "")
            .replaceAll("\\btext\\b|\\bcolor\\b|\\bextra\\b|\\bclickEvent\\b|\\bhoverEvent\\b", "")
            .trim();
         return result.isEmpty() ? text : result;
      } else {
         return "";
      }
   }

   private static String extractLeadingTags(String text) {
      StringBuilder tags = new StringBuilder();
      boolean inTag = false;

      for (int i = 0; i < text.length(); i++) {
         char c = text.charAt(i);
         if (c == '<') {
            inTag = true;
            tags.append(c);
         } else if (c == '>') {
            inTag = false;
            tags.append(c);
         } else {
            if (!inTag) {
               break;
            }

            tags.append(c);
         }
      }

      return tags.toString();
   }

   private static int getVisibleLength(String text) {
      int count = 0;
      boolean inTag = false;

      for (int i = 0; i < text.length(); i++) {
         char c = text.charAt(i);
         if (c == '<') {
            inTag = true;
         } else if (c == '>') {
            inTag = false;
         } else if (!inTag) {
            count++;
         }
      }

      return count;
   }

   private static String substringPreservingTags(String text, int length) {
      StringBuilder result = new StringBuilder();
      int visibleCount = 0;
      boolean inTag = false;

      for (int i = 0; i < text.length(); i++) {
         char c = text.charAt(i);
         if (c == '<') {
            inTag = true;
         }

         result.append(c);
         if (c == '>') {
            inTag = false;
         } else if (!inTag) {
            if (++visibleCount >= length) {
               break;
            }
         }
      }

      return result.toString();
   }

   private static record ActiveHologramState(
      UUID hitboxEntityId,
      Location regionAnchor,
      List<HolographicLibrary.LineState> lines,
      boolean hasAnimations,
      HologramDefinition definition
   ) {
   }

   private static enum HologramAnimation {
      NONE,
      SCROLL,
      TYPEWRITER,
      BLINK,
      RAINBOW,
      BOUNCE,
      RAIN,
      SHOWER;

      private HologramAnimation() {
      }
   }

   private static class LineState {
      final UUID entityId;
      final HolographicLibrary.ParsedLine parsedLine;
      final Map<String, String> placeholders;
      Location baseLocation;
      final int pageIndex;
      final int lineIndex;

      LineState(UUID entityId, HolographicLibrary.ParsedLine parsedLine, Map<String, String> placeholders, Location baseLocation, int pageIndex, int lineIndex) {
         this.entityId = entityId;
         this.parsedLine = parsedLine;
         this.placeholders = placeholders;
         this.baseLocation = baseLocation;
         this.pageIndex = pageIndex;
         this.lineIndex = lineIndex;
      }
   }

   private static class ParsedLine {
      final HolographicLibrary.HologramAnimation animation;
      final String baseText;
      final Material itemMaterial;
      final Material blockMaterial;
      final boolean isEmpty;
      float scale = 0.5f;
      Billboard billboard = null;

      ParsedLine(String rawText) {
         HolographicLibrary.HologramAnimation anim = HolographicLibrary.HologramAnimation.NONE;
         String text = rawText == null ? "" : rawText;
         if (text.contains("<anim:scroll>")) {
            anim = HolographicLibrary.HologramAnimation.SCROLL;
            text = text.replace("<anim:scroll>", "");
         } else if (text.contains("<anim:typewriter>")) {
            anim = HolographicLibrary.HologramAnimation.TYPEWRITER;
            text = text.replace("<anim:typewriter>", "");
         } else if (text.contains("<anim:blink>")) {
            anim = HolographicLibrary.HologramAnimation.BLINK;
            text = text.replace("<anim:blink>", "");
         } else if (text.contains("<anim:rainbow>")) {
            anim = HolographicLibrary.HologramAnimation.RAINBOW;
            text = text.replace("<anim:rainbow>", "");
         } else if (text.contains("<anim:bounce>")) {
            anim = HolographicLibrary.HologramAnimation.BOUNCE;
            text = text.replace("<anim:bounce>", "");
         } else if (text.contains("<anim:rain>")) {
            anim = HolographicLibrary.HologramAnimation.RAIN;
            text = text.replace("<anim:rain>", "");
         } else if (text.contains("<anim:shower>")) {
            anim = HolographicLibrary.HologramAnimation.SHOWER;
            text = text.replace("<anim:shower>", "");
         }

         this.animation = anim;
         this.baseText = text;
         Material parsedItem = null;
         Material parsedBlock = null;
         if (this.baseText.startsWith("<item:") && this.baseText.contains(">")) {
            int end = this.baseText.indexOf(62);
            String content = this.baseText.substring(6, end);
            String[] parts = content.split(":");
            String matStr = parts[0].toUpperCase(Locale.ROOT);

            try {
               parsedItem = Material.valueOf(matStr);
            } catch (Exception e) {
            }
         } else if (this.baseText.startsWith("<block:") && this.baseText.contains(">")) {
            int end = this.baseText.indexOf(62);
            String content = this.baseText.substring(7, end);
            String[] parts = content.split(":");
            String matStr = parts[0].toUpperCase(Locale.ROOT);

            try {
               parsedBlock = Material.valueOf(matStr);
            } catch (Exception e) {
            }
         }

         this.itemMaterial = parsedItem;
         this.blockMaterial = parsedBlock;

         if (this.itemMaterial != null || this.blockMaterial != null) {
            String tag = this.baseText.startsWith("<item:") ? "<item:" : "<block:";
            int end = this.baseText.indexOf(62);
            String content = this.baseText.substring(tag.length(), end);
            String[] parts = content.split(":");
            // parts[0] is material
            if (parts.length > 1) {
               try {
                  this.scale = Float.parseFloat(parts[1]);
               } catch (Exception ignored) {}
            }
            if (parts.length > 2) {
               try {
                  this.billboard = Billboard.valueOf(parts[2].toUpperCase(Locale.ROOT));
               } catch (Exception ignored) {}
            }
         }

         String plain = HolographicLibrary.extractPlainTextStatic(this.baseText).trim();
         this.isEmpty = this.itemMaterial == null && this.blockMaterial == null && (plain.isEmpty() || this.baseText.equalsIgnoreCase("<empty>") || plain.equals("|"));
      }
   }
}
