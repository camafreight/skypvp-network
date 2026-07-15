package network.skypvp.lobby.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.library.HolographicLibrary;
import network.skypvp.paper.library.NpcLibrary;
import network.skypvp.lobby.model.LobbyHologramDefinition;
import network.skypvp.lobby.model.LobbyNpcDefinition;
import network.skypvp.lobby.model.LobbyTemplateLayout;
import network.skypvp.paper.model.WorldPoint;
import network.skypvp.paper.model.WorldPresetMeta;
import network.skypvp.paper.service.WorldPresetService;
import network.skypvp.paper.service.WorldStateService;
import org.bukkit.Location;
import org.bukkit.World;

public final class LobbyLayoutService {
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private final PaperCorePlugin plugin;
   private final WorldStateService worldStateService;
   private final WorldPresetService worldPresetService;
   private final NpcLibrary npcLibrary;
   private final HolographicLibrary holographicLibrary;
   private LobbyTemplateLayout currentLayout = new LobbyTemplateLayout();

   public LobbyLayoutService(PaperCorePlugin plugin, WorldStateService worldStateService, NpcLibrary npcLibrary, HolographicLibrary holographicLibrary) {
      this.plugin = plugin;
      this.worldStateService = worldStateService;
      this.worldPresetService = worldStateService.presetService();
      this.npcLibrary = npcLibrary;
      this.holographicLibrary = holographicLibrary;
   }

   public String activePresetId() {
      return this.worldStateService.resolvePresetId();
   }

   public synchronized LobbyTemplateLayout currentLayout() {
      return this.currentLayout;
   }

   public synchronized void reload() {
      String presetId = this.activePresetId();
      Path layoutFile = this.worldPresetService.presetRoot().resolve(presetId).resolve("lobby-layout.json");
      if (!Files.exists(layoutFile)) {
         this.currentLayout = new LobbyTemplateLayout();
         this.currentLayout.spawn = this.spawnPointFromPresetMeta();
      } else {
         try (Reader r = Files.newBufferedReader(layoutFile, StandardCharsets.UTF_8)) {
            LobbyTemplateLayout layout = GSON.fromJson(r, LobbyTemplateLayout.class);
            if (layout == null) {
               this.currentLayout = new LobbyTemplateLayout();
               return;
            }
            if (layout.spawn == null) {
               layout.spawn = this.spawnPointFromPresetMeta();
            }
            if (layout.navigationPoints == null) {
               layout.navigationPoints = new LinkedHashMap<>();
            }
            if (layout.npcs == null) {
               layout.npcs = new ArrayList<>();
            } else {
               for (LobbyNpcDefinition npc : layout.npcs) {
                  if (npc.scale <= 0.0) npc.scale = 1.0;
               }
            }
            if (layout.holograms == null) {
               layout.holograms = new ArrayList<>();
            } else {
               for (LobbyHologramDefinition holo : layout.holograms) {
                  if (holo.scale <= 0.0) holo.scale = 1.0;
               }
            }
            this.currentLayout = layout;
         } catch (Exception var8) {
            this.plugin.getLogger().warning("[LobbyLayout] Failed to read lobby layout for '" + presetId + "': " + var8.getMessage());
            this.currentLayout = new LobbyTemplateLayout();
         }
      }
   }

   public synchronized Location applyAndResolveSpawn(World fallbackWorld) {
      this.reload();
      if (!this.currentLayout.npcs.isEmpty()) {
         this.applyNpcLayout(fallbackWorld);
      }
      if (!this.currentLayout.holograms.isEmpty()) {
         this.applyHologramLayout(fallbackWorld);
      }
      Location spawn = this.worldStateService.presetSpawnLocation()
         .orElseGet(() -> this.toLocation(this.currentLayout.spawn, fallbackWorld));
      fallbackWorld.setSpawnLocation(spawn);
      return spawn;
   }

   public synchronized void applyOnly(World fallbackWorld) {
      if (!this.currentLayout.npcs.isEmpty()) {
         this.applyNpcLayout(fallbackWorld);
      }
      if (!this.currentLayout.holograms.isEmpty()) {
         this.applyHologramLayout(fallbackWorld);
      }
   }

   private void applyNpcLayout(World world) {
      Location anchor = this.toLocation(this.currentLayout.npcs.get(0).location, world);
      this.plugin.platform().runRegionOwned(anchor, () -> this.npcLibrary.apply(world, this.currentLayout.npcs));
   }

   private void applyHologramLayout(World world) {
      Location anchor = this.toLocation(this.currentLayout.holograms.get(0).anchor, world);
      this.plugin.platform().runRegionOwned(anchor, () -> this.holographicLibrary.apply(world, this.currentLayout.holograms));
   }

   public synchronized boolean setSpawn(Location location) {
      this.currentLayout.spawn = WorldPoint.fromLocation(location);
      this.persistLayoutAndMeta();
      return true;
   }

   public synchronized boolean setNavigationPoint(String id, Location location) {
      String normalized = this.normalizeId(id);
      if (normalized == null) {
         return false;
      } else {
         this.currentLayout.navigationPoints.put(normalized, WorldPoint.fromLocation(location));
         this.persistLayout();
         return true;
      }
   }

   public synchronized Optional<Location> navigationPoint(String id, World fallbackWorld) {
      String normalized = this.normalizeId(id);
      if (normalized == null) {
         return Optional.empty();
      } else {
         WorldPoint point = (WorldPoint)this.currentLayout.navigationPoints.get(normalized);
         return point == null ? Optional.empty() : Optional.of(this.toLocation(point, fallbackWorld));
      }
   }

   public synchronized boolean upsertNpc(String id, String displayName, String actionType, String actionData, String entityType, Location location, World world) {
      String normalized = this.normalizeId(id);
      if (normalized == null) {
         return false;
      } else {
         LobbyNpcDefinition existing = null;

         for (LobbyNpcDefinition npc : this.currentLayout.npcs) {
            if (npc.id != null && npc.id.equalsIgnoreCase(normalized)) {
               existing = npc;
               break;
            }
         }

         if (existing == null) {
            existing = new LobbyNpcDefinition();
            this.currentLayout.npcs.add(existing);
         }

         existing.id = normalized;
         existing.displayName = displayName;
         existing.actionType = actionType == null ? "NONE" : actionType.toUpperCase(Locale.ROOT);
         existing.actionData = actionData == null ? "" : actionData;
         existing.entityType = entityType != null && !entityType.isBlank() ? entityType.toUpperCase(Locale.ROOT) : "VILLAGER";
         existing.location = WorldPoint.fromLocation(location);
         this.persistLayout();
         this.npcLibrary.apply(world, this.currentLayout.npcs);
         return true;
      }
   }

   public synchronized boolean removeNpc(String id, World world) {
      String normalized = this.normalizeId(id);
      if (normalized == null) {
         return false;
      } else {
         boolean removed = this.currentLayout.npcs.removeIf(npc -> npc.id != null && npc.id.equalsIgnoreCase(normalized));
         if (!removed) {
            return false;
         } else {
            this.persistLayout();
            this.npcLibrary.apply(world, this.currentLayout.npcs);
            return true;
         }
      }
   }

   public synchronized boolean upsertHologram(String id, List<String> lines, Location anchor, World world) {
      String normalized = this.normalizeId(id);
      if (normalized != null && lines != null && !lines.isEmpty()) {
         LobbyHologramDefinition existing = null;

         for (LobbyHologramDefinition hologram : this.currentLayout.holograms) {
            if (hologram.id != null && hologram.id.equalsIgnoreCase(normalized)) {
               existing = hologram;
               break;
            }
         }

         if (existing == null) {
            existing = new LobbyHologramDefinition();
            this.currentLayout.holograms.add(existing);
         }

         existing.id = normalized;
         existing.anchor = WorldPoint.fromLocation(anchor);
         existing.lines = new ArrayList<>(lines);
         this.persistLayout();
         this.holographicLibrary.apply(world, this.currentLayout.holograms);
         return true;
      } else {
         return false;
      }
   }

   public synchronized boolean removeHologram(String id, World world) {
      String normalized = this.normalizeId(id);
      if (normalized == null) {
         return false;
      } else {
         boolean removed = this.currentLayout.holograms.removeIf(holo -> holo.id != null && holo.id.equalsIgnoreCase(normalized));
         if (!removed) {
            return false;
         } else {
            this.persistLayout();
            this.holographicLibrary.apply(world, this.currentLayout.holograms);
            return true;
         }
      }
   }

   public synchronized List<String> npcSummaries() {
      return this.currentLayout
         .npcs
         .stream()
         .sorted(Comparator.comparing(npc -> npc.id == null ? "" : npc.id))
         .map(npc -> npc.id + " (" + npc.entityType + ", " + npc.actionType + ")")
         .toList();
   }

   public synchronized List<String> hologramSummaries() {
      return this.currentLayout
         .holograms
         .stream()
         .sorted(Comparator.comparing(holo -> holo.id == null ? "" : holo.id))
         .map(holo -> holo.id + " (" + (holo.lines == null ? 0 : holo.lines.size()) + " lines)")
         .toList();
   }

   public synchronized int navigationCount() {
      return this.currentLayout.navigationPoints.size();
   }

   public synchronized Map<String, WorldPoint> navigationPoints() {
      return Map.copyOf(this.currentLayout.navigationPoints);
   }

   private WorldPoint spawnPointFromPresetMeta() {
      String presetId = this.activePresetId();
      WorldPresetMeta meta = this.worldPresetService.readMeta(presetId);
      String worldName = this.worldStateService.managedWorlds().stream().findFirst().orElse("world");
      return new WorldPoint(worldName, meta.spawnX(), meta.spawnY(), meta.spawnZ(), meta.spawnYaw(), meta.spawnPitch());
   }

   private void persistLayoutAndMeta() {
      this.persistLayout();
      String presetId = this.activePresetId();
      WorldPresetMeta meta = this.worldPresetService.readMeta(presetId);
      WorldPoint spawn = this.currentLayout.spawn == null ? new WorldPoint() : this.currentLayout.spawn;
      WorldPresetMeta updated = new WorldPresetMeta(
         presetId, meta.description(), spawn.x, spawn.y, spawn.z, spawn.yaw, spawn.pitch
      );

      try {
         this.worldPresetService.writeMeta(presetId, updated);
      } catch (IOException var6) {
         this.plugin.getLogger().warning("[LobbyLayout] Failed writing preset meta: " + var6.getMessage());
      }
   }

   private void persistLayout() {
      String presetId = this.activePresetId();
      Path presetDir = this.worldPresetService.presetRoot().resolve(presetId);
      try {
         Files.createDirectories(presetDir);
         try (Writer w = Files.newBufferedWriter(presetDir.resolve("lobby-layout.json"), StandardCharsets.UTF_8)) {
            GSON.toJson(this.currentLayout, w);
         }
      } catch (IOException var2) {
         this.plugin.getLogger().warning("[LobbyLayout] Failed writing layout: " + var2.getMessage());
      }
   }

   private String normalizeId(String value) {
      if (value != null && !value.isBlank()) {
         String normalized = value.trim().toLowerCase(Locale.ROOT);
         return !normalized.matches("[a-z0-9_\\-]+") ? null : normalized;
      } else {
         return null;
      }
   }

   private Location toLocation(WorldPoint point, World fallbackWorld) {
      if (point == null) {
         return fallbackWorld.getSpawnLocation();
      } else {
         World world = this.plugin.getServer().getWorld(point.world);
         if (world == null) {
            world = fallbackWorld;
         }

         return new Location(world, point.x, point.y, point.z, point.yaw, point.pitch);
      }
   }
}
