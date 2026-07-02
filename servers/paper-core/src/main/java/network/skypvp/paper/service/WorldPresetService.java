package network.skypvp.paper.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.model.WorldPoint;
import network.skypvp.paper.model.WorldPresetMeta;

public final class WorldPresetService {
   private static final String[] CHUNK_DATA_DIRS = new String[]{"region", "entities", "poi"};
   private static final String[] WORLD_ROOT_FILES = new String[]{"level.dat", "paper-world.yml", "uid.dat"};
   private static final String[] WORLD_ROOT_DIRS = new String[]{"data", "datapacks"};
   private static final String PRESET_ROOT_RELATIVE = "../../config/world-presets";
   private static final String PRESET_ROOT_RELATIVE_ALT = "../../config/world-templates";
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private final PaperCorePlugin plugin;
   private final Path presetRoot;

   public WorldPresetService(PaperCorePlugin plugin) {
      this.plugin = plugin;
      Path serverRoot = plugin.getServer().getWorldContainer().toPath();

      String configPath = plugin.getConfig().getString("world-state.preset-root");
      if (configPath != null && !configPath.trim().isEmpty()) {
         Path path = Paths.get(configPath.trim());
         if (!path.isAbsolute()) {
            path = serverRoot.resolve(path).normalize();
         }
         this.presetRoot = path;
         plugin.getLogger().info("[WorldPreset] Using configured preset root: " + this.presetRoot);
      } else {
         Path preferred = Paths.get(PRESET_ROOT_RELATIVE);
         if (!preferred.isAbsolute()) {
            preferred = serverRoot.resolve(preferred).normalize();
         }

         Path fallback = Paths.get(PRESET_ROOT_RELATIVE_ALT);
         if (!fallback.isAbsolute()) {
            fallback = serverRoot.resolve(fallback).normalize();
         }

         // Safely check directories without letting exceptions bubble up, defaulting to fallback if exists.
         boolean preferredExists = false;
         boolean fallbackExists = false;
         try {
            preferredExists = Files.isDirectory(preferred);
         } catch (Exception ignored) {}
         try {
            fallbackExists = Files.isDirectory(fallback);
         } catch (Exception ignored) {}

         if (fallbackExists) {
            this.presetRoot = fallback;
         } else if (preferredExists) {
            this.presetRoot = preferred;
         } else {
            this.presetRoot = preferred;
         }
         plugin.getLogger().info("[WorldPreset] Auto-detected preset root: " + this.presetRoot);
      }
   }

   public Path presetRoot() {
      return this.presetRoot;
   }

   public boolean hasPreset(String presetId) {
      return Files.isDirectory(this.presetRoot.resolve(presetId));
   }

   public List<String> listPresets() {
      List<String> result = new ArrayList<>();
      if (!Files.isDirectory(this.presetRoot)) {
         return result;
      } else {
         try (DirectoryStream<Path> ds = Files.newDirectoryStream(this.presetRoot)) {
            for (Path p : ds) {
               if (Files.isDirectory(p)) {
                  result.add(p.getFileName().toString());
               }
            }
         } catch (IOException var7) {
            this.plugin.getLogger().warning("[WorldPreset] Failed to list presets: " + var7.getMessage());
         }

         return result;
      }
   }

   public boolean presetHasWorldData(String presetId, String worldName) {
      Path regionDir = this.presetRoot.resolve(presetId).resolve(worldName).resolve("region");
      if (!Files.isDirectory(regionDir)) {
         return false;
      } else {
         try {
            boolean var5;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(regionDir, "*.mca")) {
               var5 = ds.iterator().hasNext();
            }

            return var5;
         } catch (IOException var9) {
            return false;
         }
      }
   }

   public WorldPresetMeta readMeta(String presetId) {
      Path metaFile = this.presetRoot.resolve(presetId).resolve("meta.json");
      if (!Files.exists(metaFile)) {
         return new WorldPresetMeta(presetId, "", 0, 64, 0, 0.0F, 0.0F);
      } else {
         try {
            WorldPresetMeta var5;
            try (Reader r = Files.newBufferedReader(metaFile, StandardCharsets.UTF_8)) {
               JsonObject obj = JsonParser.parseReader(r).getAsJsonObject();
               var5 = new WorldPresetMeta(
                  presetId,
                  obj.has("description") ? obj.get("description").getAsString() : "",
                  obj.has("spawnX") ? obj.get("spawnX").getAsInt() : 0,
                  obj.has("spawnY") ? obj.get("spawnY").getAsInt() : 64,
                  obj.has("spawnZ") ? obj.get("spawnZ").getAsInt() : 0,
                  obj.has("spawnYaw") ? obj.get("spawnYaw").getAsFloat() : 0.0F,
                  obj.has("spawnPitch") ? obj.get("spawnPitch").getAsFloat() : 0.0F
               );
            }

            return var5;
         } catch (Exception var8) {
            this.plugin.getLogger().warning("[WorldPreset] Failed to read meta for '" + presetId + "': " + var8.getMessage());
            return new WorldPresetMeta(presetId, "", 0, 64, 0, 0.0F, 0.0F);
         }
      }
   }

   public void writeMeta(String presetId, WorldPresetMeta meta) throws IOException {
      Path presetDir = this.presetRoot.resolve(presetId);
      Files.createDirectories(presetDir);
      JsonObject obj = new JsonObject();
      obj.addProperty("presetId", meta.presetId());
      obj.addProperty("description", meta.description());
      obj.addProperty("spawnX", meta.spawnX());
      obj.addProperty("spawnY", meta.spawnY());
      obj.addProperty("spawnZ", meta.spawnZ());
      obj.addProperty("spawnYaw", meta.spawnYaw());
      obj.addProperty("spawnPitch", meta.spawnPitch());

      try (Writer w = Files.newBufferedWriter(presetDir.resolve("meta.json"), StandardCharsets.UTF_8)) {
         GSON.toJson((JsonElement)obj, w);
      }
   }


   public void populateWorld(String presetId, String worldName, Path serverRoot) throws IOException {
      Path presetWorld = this.presetRoot.resolve(presetId).resolve(worldName);
      if (!Files.isDirectory(presetWorld)) {
         this.plugin.getLogger().info("[WorldPreset] Preset '" + presetId + "' has no data for world '" + worldName + "' — skipping.");
      } else {
         Path runtimeWorld = serverRoot.resolve(worldName);
         Files.createDirectories(runtimeWorld);

         for (String fileName : WORLD_ROOT_FILES) {
            Path src = presetWorld.resolve(fileName);
            if (Files.isRegularFile(src)) {
               Files.copy(src, runtimeWorld.resolve(fileName), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
         }

         for (String subdir : WORLD_ROOT_DIRS) {
            Path src = presetWorld.resolve(subdir);
            if (Files.isDirectory(src)) {
               Path dst = runtimeWorld.resolve(subdir);
               deleteDirectoryIfExists(dst);
               Files.createDirectories(dst);
               copyContents(src, dst);
            }
         }

         for (String subdir : CHUNK_DATA_DIRS) {
            Path src = presetWorld.resolve(subdir);
            if (Files.isDirectory(src)) {
               Path dst = runtimeWorld.resolve(subdir);
               Files.createDirectories(dst);
               copyContents(src, dst);
            }
         }

         this.plugin.getLogger().info("[WorldPreset] Populated world '" + worldName + "' from preset '" + presetId + "'.");
      }
   }

   public void clearWorldChunks(String worldName, Path serverRoot) throws IOException {
      Path runtimeWorld = serverRoot.resolve(worldName);

      for (String fileName : WORLD_ROOT_FILES) {
         Files.deleteIfExists(runtimeWorld.resolve(fileName));
      }

      for (String subdir : WORLD_ROOT_DIRS) {
         deleteDirectoryIfExists(runtimeWorld.resolve(subdir));
      }

      for (String subdir : CHUNK_DATA_DIRS) {
         deleteDirectoryIfExists(runtimeWorld.resolve(subdir));
      }

      this.plugin.getLogger().info("[WorldPreset] Cleared world data for '" + worldName + "'.");
   }

   public void captureWorld(String presetId, String worldName, Path serverRoot) throws IOException {
      Path runtimeWorld = serverRoot.resolve(worldName);
      if (!Files.isDirectory(runtimeWorld)) {
         this.plugin.getLogger().warning("[WorldPreset] Cannot capture '" + worldName + "' — directory not found.");
      } else {
         Path presetWorld = this.presetRoot.resolve(presetId).resolve(worldName);
         Files.createDirectories(presetWorld);

         for (String fileName : WORLD_ROOT_FILES) {
            Path src = runtimeWorld.resolve(fileName);
            if (Files.isRegularFile(src)) {
               Files.copy(src, presetWorld.resolve(fileName), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
         }

         for (String subdir : WORLD_ROOT_DIRS) {
            Path src = runtimeWorld.resolve(subdir);
            if (Files.isDirectory(src)) {
               Path dst = presetWorld.resolve(subdir);
               Files.createDirectories(dst);
               copyContents(src, dst);
            }
         }

         for (String subdir : CHUNK_DATA_DIRS) {
            Path src = runtimeWorld.resolve(subdir);
            if (Files.isDirectory(src)) {
               Path dst = presetWorld.resolve(subdir);
               Files.createDirectories(dst);
               copyContents(src, dst);
            }
         }

         this.plugin.getLogger().info("[WorldPreset] Captured world '" + worldName + "' → preset '" + presetId + "'.");
      }
   }

   private static void copyContents(Path src, Path dst) throws IOException {
      try (DirectoryStream<Path> ds = Files.newDirectoryStream(src)) {
         for (Path child : ds) {
            Path target = dst.resolve(child.getFileName().toString());
            if (Files.isDirectory(child)) {
               Files.createDirectories(target);
               copyContents(child, target);
            } else {
               Files.copy(child, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
         }
      }
   }

   private static void deleteDirectoryIfExists(Path dir) throws IOException {
      if (Files.isDirectory(dir)) {
         deleteDirectory(dir);
      }
   }

   private static void deleteDirectory(Path dir) throws IOException {
      Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
         public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
         }

         public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
            Files.delete(d);
            return FileVisitResult.CONTINUE;
         }
      });
   }
}
