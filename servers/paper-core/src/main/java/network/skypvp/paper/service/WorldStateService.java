package network.skypvp.paper.service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gamemode.api.CoreBehaviorKeys;
import network.skypvp.paper.integration.ProxyRouteMessenger;
import network.skypvp.paper.library.WorldGroundItemCleanup;
import network.skypvp.paper.model.WorldPresetMeta;
import network.skypvp.shared.NetworkServerRole;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import network.skypvp.paper.platform.ServerPlatform;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class WorldStateService {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
   private static final boolean WORLD_STATE_ENABLED = true;
   private static final List<String> MANAGED_WORLDS = List.of("world");
   private static final String DEFAULT_LOBBY_POOL = "lobby";
   private static final int KICK_DELAY_TICKS = 60;
   private static final int RESET_DELAY_TICKS = 80;
   private final PaperCorePlugin plugin;
   private final WorldPresetService presetService;
   private final AtomicBoolean resetInProgress = new AtomicBoolean(false);
   private final AtomicBoolean serverLoadComplete = new AtomicBoolean(false);
   private final AtomicBoolean startupSyncInProgress = new AtomicBoolean(false);
   private final AtomicBoolean startupReady = new AtomicBoolean(false);
   private final AtomicBoolean spawnChunksReady = new AtomicBoolean(false);
   /**
    * Final routing gate. Flips true only after a warmup grace period that begins once the spawn chunks finish
    * loading. The window right after spawn-chunk load is CPU-heavy (decoration/NPC + hologram reload, ground-item
    * cleanup, Folia region warmup); routing players in during it leaves them stuck in "Joining World" until they
    * time out. {@link #isJoinableForRouting()} keys off this flag, not {@link #spawnChunksReady}, so the periodic
    * heartbeat keeps advertising not-joinable until the server can actually accept joins.
    */
   private final AtomicBoolean routingReady = new AtomicBoolean(false);
   private static final long ROUTING_GRACE_TICKS = 20L * 30L;
   private final AtomicReference<String> startupStatus = new AtomicReference<>("PENDING");
   private final AtomicReference<String> pendingSpawnPreset = new AtomicReference<>(null);

   public WorldStateService(PaperCorePlugin plugin) {
      this.plugin = plugin;
      this.presetService = new WorldPresetService(plugin);
   }

   public WorldPresetService presetService() {
      return this.presetService;
   }

   public boolean isResetInProgress() {
      return this.resetInProgress.get();
   }

   public boolean isStartupSyncInProgress() {
      return this.startupSyncInProgress.get();
   }

   public boolean isStartupReady() {
      return this.startupReady.get();
   }

   public String startupStatus() {
      return this.startupStatus.get();
   }

   public void markServerLoadComplete() {
      this.serverLoadComplete.set(true);
      String pendingPreset = this.pendingSpawnPreset.getAndSet(null);
      if (pendingPreset != null) {
         this.applyPresetSpawn(pendingPreset);
      }

      if (!this.requiresPresetGate()) {
         this.startupStatus.set("READY (persistent role fully loaded)");
      }

      this.verifySpawnChunksReady();
   }

   private void verifySpawnChunksReady() {
      World resolved = null;

      for (String worldName : this.managedWorlds()) {
         World candidate = Bukkit.getWorld(worldName);
         if (candidate != null) {
            resolved = candidate;
            break;
         }
      }

      if (resolved == null && !Bukkit.getWorlds().isEmpty()) {
         resolved = (World)Bukkit.getWorlds().get(0);
      }

      if (resolved == null) {
         this.spawnChunksReady.set(true);
         this.routingReady.set(true);
         this.plugin.publishJoinableHeartbeatNow();
      } else {
         final org.bukkit.World finalResolved = resolved;
         Location spawn = finalResolved.getSpawnLocation();
         final int chunkX = spawn.getBlockX() >> 4;
         final int chunkZ = spawn.getBlockZ() >> 4;
         ServerPlatform scheduler = this.plugin.platformScheduler();
         AtomicBoolean spawnReadyHandled = new AtomicBoolean(false);
         scheduler.runGlobalTimer(() -> {
            if (spawnReadyHandled.get()) {
               return;
            }
            if (finalResolved.isChunkLoaded(chunkX, chunkZ)) {
               if (!spawnReadyHandled.compareAndSet(false, true)) {
                  return;
               }
               WorldStateService.this.spawnChunksReady.set(true);
               WorldStateService.this.startupStatus.set("READY (spawn chunks loaded; routing grace)");
               WorldStateService.this.clearGroundItemsInManagedWorlds("spawn ready");
               WorldStateService.this.plugin
                  .getLogger()
                  .info("[WorldState] Spawn chunks loaded for '" + finalResolved.getName() + "' — warming up; joinable for routing in 30 seconds.");
               WorldStateService.this.plugin.reloadDecorationsWhenReady();
               // Only open the routing gate after the warmup grace so the periodic heartbeat keeps reporting
               // not-joinable until the post-load CPU spike settles; otherwise players are routed in too early
               // and hang in "Joining World" until they time out.
               scheduler.runGlobalLater(() -> {
                  WorldStateService.this.routingReady.set(true);
                  WorldStateService.this.startupStatus.set("READY (routing open)");
                  WorldStateService.this.plugin
                     .getLogger()
                     .info("[WorldState] Routing grace elapsed for '" + finalResolved.getName() + "' — server is now joinable for routing.");
                  WorldStateService.this.plugin.publishJoinableHeartbeatNow();
               }, ROUTING_GRACE_TICKS);
               return;
            }
            scheduler.runAtChunk(finalResolved, chunkX, chunkZ, () -> finalResolved.loadChunk(chunkX, chunkZ, true));
         }, 1L, 10L);
      }
   }

   public boolean isJoinableForRouting() {
      return this.startupReady.get()
         && this.serverLoadComplete.get()
         && this.routingReady.get()
         && !this.resetInProgress.get()
         && !this.startupSyncInProgress.get();
   }

   public String resolvePresetId() {
      String fromEnv = System.getenv("SPVP_PRESET_ID");
      if (fromEnv != null && !fromEnv.isBlank()) {
         return fromEnv.trim();
      }

      return switch (this.plugin.serverRole()) {
         case LOBBY -> "lobby-template";
         case EXTRACTION -> "extraction-template";
         default -> this.plugin.serverId();
      };
   }

   /**
    * Spawn from {@code world-templates/<presetId>/meta.json} on the managed hub world.
    */
   public Optional<Location> presetSpawnLocation() {
      return this.presetSpawnLocation(this.resolvePresetId());
   }

   public Optional<Location> presetSpawnLocation(String presetId) {
      if (presetId == null || presetId.isBlank()) {
         return Optional.empty();
      }
      List<String> worlds = this.managedWorlds();
      if (worlds.isEmpty()) {
         return Optional.empty();
      }
      WorldPresetMeta meta = this.presetService.readMeta(presetId.trim());
      for (String worldName : worlds) {
         World world = Bukkit.getWorld(worldName);
         if (world == null) {
            continue;
         }
         Location spawn = new Location(
                 world,
                 meta.spawnX(),
                 meta.spawnY(),
                 meta.spawnZ(),
                 meta.spawnYaw(),
                 meta.spawnPitch()
         );
         // Do not load hub chunks here — callers may run on a breach region thread (Folia).
         // teleportAsync() loads the destination chunk on the correct region when needed.
         return Optional.of(spawn);
      }
      return Optional.empty();
   }

   public List<String> managedWorlds() {
      List<String> configured = MANAGED_WORLDS;
      if (configured != null && !configured.isEmpty()) {
         List<String> normalized = new ArrayList<>();

         for (String world : configured) {
            if (world != null && !world.isBlank()) {
               normalized.add(world.trim());
            }
         }

         return normalized.isEmpty() ? List.of("world") : normalized;
      } else {
         return List.of("world");
      }
   }

   public void initializeStartupReadiness() {
      if (!this.requiresPresetGate()) {
         this.startupReady.set(true);
         this.startupStatus.set("WAITING_FOR_SERVER_LOAD (persistent role)");
      } else if (!WORLD_STATE_ENABLED) {
         this.startupReady.set(false);
         this.startupStatus.set("BLOCKED (world-state disabled for non-persistent role)");
         this.plugin.getLogger().warning("[WorldState] gate blocked: world-state.enabled=false on role " + this.plugin.serverRole());
      } else {
         this.startupReady.set(false);
         this.startupStatus.set("CHECKING");
         String presetId = this.resolvePresetId();
         List<String> worlds = this.managedWorlds();
         Path serverRoot = this.plugin.getServer().getWorldContainer().toPath();
         this.plugin.getLogger().info("[WorldState] Startup check — preset='" + presetId + "' worlds=" + worlds + " server=" + this.plugin.serverId());
         boolean allPresent = worlds.stream().allMatch(wx -> this.hasRegionFiles(wx, serverRoot));
         if (allPresent) {
            this.startupReady.set(true);
            this.startupStatus.set("READY (pre-populated)");
            this.plugin.getLogger().info("[WorldState] All worlds pre-populated. Server is ready.");
         } else {
            // Run preset existence check asynchronously to allow transient NFS mounts to settle
            this.plugin.platformScheduler().runAsync(() -> {
               int retries = 15;
               while (retries > 0 && !this.presetService.hasPreset(presetId)) {
                  this.plugin.getLogger().info("[WorldState] Preset '" + presetId + "' not found yet, retrying in 2 seconds... (Retries left: " + retries + ")");
                  try {
                     Thread.sleep(2000L);
                  } catch (InterruptedException e) {
                     Thread.currentThread().interrupt();
                     break;
                  }
                  retries--;
               }

               // Dispatch the actual recovery/block logic back to the main thread
               this.plugin.platformScheduler().runWorldGlobal(() -> {
                  if (!this.presetService.hasPreset(presetId)) {
                     this.startupStatus.set("BLOCKED (preset not found: " + presetId + ")");
                     this.plugin.getLogger().warning(
                        "[WorldState] Blocked: preset '"
                           + presetId
                           + "' not found at "
                           + this.presetService.presetRoot()
                           + ". Run /worldstate capture <presetId> or create a preset folder."
                     );
                  } else {
                     this.plugin.getLogger().info("[WorldState] Chunk data missing — recovering from preset '" + presetId + "'.");
                     this.startupSyncInProgress.set(true);
                     this.startupStatus.set("POPULATING");

                     for (String worldName : worlds) {
                        World w = Bukkit.getWorld(worldName);
                        if (w != null) {
                           Bukkit.unloadWorld(w, false);
                        }
                     }

                     try {
                        for (String worldNamex : worlds) {
                           this.presetService.clearWorldChunks(worldNamex, serverRoot);
                           this.presetService.populateWorld(presetId, worldNamex, serverRoot);
                        }
                     } catch (IOException var8) {
                        this.startupSyncInProgress.set(false);
                        this.startupStatus.set("BLOCKED (populate failed: " + var8.getMessage() + ")");
                        this.plugin.getLogger().severe("[WorldState] Populate failed: " + var8.getMessage());
                        return;
                     }

                     boolean worldsAlreadyLoaded = worlds.stream().anyMatch(wx -> Bukkit.getWorld(wx) != null);
                     if (worldsAlreadyLoaded) {
                        for (String worldName : worlds) {
                           World loaded = Bukkit.getWorld(worldName);
                           if (loaded != null) {
                              Bukkit.unloadWorld(loaded, false);
                           }
                        }
                     }

                     this.reloadWorlds(worlds);
                     this.applyPresetSpawn(presetId);
                     this.pendingSpawnPreset.set(null);

                     this.startupSyncInProgress.set(false);
                     this.startupReady.set(true);
                     this.startupStatus.set("READY (recovered from preset: " + presetId + ")");
                     this.plugin.getLogger().info("[WorldState] Recovery complete. Server is ready.");
                  }
               });
            });
         }
      }
   }

   public void resetFromPreset() {
      this.resetFromPreset(this.resolvePresetId(), null);
   }

   public void resetFromPreset(CommandSender sender) {
      this.resetFromPreset(this.resolvePresetId(), sender);
   }

   public void resetFromPreset(String presetId, CommandSender sender) {
      if (this.plugin.serverRole() != NetworkServerRole.EXTRACTION) {
         this.feedback(sender, "<#888888>World reset is only available on EXTRACTION servers.<reset>");
      } else if (!WORLD_STATE_ENABLED) {
         this.feedback(sender, "<#888888>World-state resets are disabled in config.<reset>");
      } else if (!this.presetService.hasPreset(presetId)) {
         this.feedback(sender, "<#FF5555>[!] <reset><#888888>Preset not found: <white>" + presetId + "<reset>");
      } else if (!this.resetInProgress.compareAndSet(false, true)) {
         this.feedback(sender, "<#888888>A reset is already in progress.<reset>");
      } else {
         this.startupReady.set(false);
         this.spawnChunksReady.set(false);
         this.routingReady.set(false);
         this.startupStatus.set("RESET_IN_PROGRESS");
         this.feedback(sender, "<#FFD700>World reset starting (preset: <white>" + presetId + "<#FFD700>)...<reset>");
         this.plugin.getLogger().info("[WorldState] Reset requested — preset='" + presetId + "' by=" + (sender != null ? sender.getName() : "system"));
         String lobbyServer = DEFAULT_LOBBY_POOL;

         for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(MINI_MESSAGE.deserialize("<#888888>Arena reset in progress. Returning to lobby...<reset>"));
            this.connect(player, lobbyServer);
         }

         int kickDelay = Math.max(40, KICK_DELAY_TICKS);
         int resetDelay = Math.max(kickDelay + 10, RESET_DELAY_TICKS);
         this.plugin.platformScheduler().runGlobalLater(() -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
               p.kick(MINI_MESSAGE.deserialize("<#888888>Server reset in progress. Rejoin shortly.<reset>"));
            }
         }, (long)kickDelay);
         this.plugin.platformScheduler().runGlobalLater(() -> this.beginReset(presetId, sender), (long)resetDelay);
      }
   }

   public void captureToPreset(String presetId, CommandSender sender) {
      if (!WORLD_STATE_ENABLED) {
         this.feedback(sender, "<#888888>World-state is disabled in config.<reset>");
      } else {
         this.feedback(sender, "<#FFD700>Capturing worlds → preset '<white>" + presetId + "<#FFD700>'...<reset>");
         this.plugin.getLogger().info("[WorldState] Capture started — preset='" + presetId + "'");
         List<String> worlds = this.managedWorlds();
         Path serverRoot = this.plugin.getServer().getWorldContainer().toPath();
         World primaryWorld = Bukkit.getWorld(worlds.get(0));
         Location spawn = primaryWorld != null ? primaryWorld.getSpawnLocation() : null;
         WorldPresetMeta meta = new WorldPresetMeta(
            presetId,
            "Captured from " + this.plugin.serverId(),
            spawn != null ? spawn.getBlockX() : 0,
            spawn != null ? spawn.getBlockY() : 64,
            spawn != null ? spawn.getBlockZ() : 0,
            spawn != null ? spawn.getYaw() : 0.0F,
            spawn != null ? spawn.getPitch() : 0.0F
         );
         this.plugin.platformScheduler().runAsync(() -> {
                  try {
                     this.presetService.writeMeta(presetId, meta);

                     for (String worldName : worlds) {
                        this.presetService.captureWorld(presetId, worldName, serverRoot);
                     }
                  } catch (IOException var8) {
                     this.plugin.platformScheduler().runGlobal(() -> this.feedback(sender, "<#FF5555>[!] <reset><#888888>Capture failed: " + var8.getMessage() + "<reset>"));
                     this.plugin.getLogger().severe("[WorldState] Capture failed: " + var8.getMessage());
                     return;
                  }

                  this.plugin.platformScheduler().runWorldGlobal(() -> {
                     this.feedback(sender, "<#FFB300><bold>Capture complete</bold><reset><#888888> — preset '<white>" + presetId + "<#888888>' saved.<reset>");
                     this.plugin.getLogger().info("[WorldState] Capture complete — preset='" + presetId + "'.");
                  });
         });
      }
   }

   private boolean requiresPresetGate() {
      boolean defaultRequired = this.plugin.serverRole() == NetworkServerRole.LOBBY || this.plugin.serverRole() == NetworkServerRole.EXTRACTION;
      return this.plugin.gameModeBehaviorService().booleanValue("core.world.preset-gate.required", defaultRequired);
   }

   private boolean hasRegionFiles(String worldName, Path serverRoot) {
      Path worldDir = serverRoot.resolve(worldName);
      // Legacy layout: <world>/region. Folia/Paper 26.1.2+ "dimensions" layout moves the
      // overworld to <world>/dimensions/minecraft/overworld/region. Treat either as present so
      // the startup gate does not fall into the (Folia-incompatible) preset recovery path.
      return regionDirHasMca(worldDir.resolve("region"))
         || regionDirHasMca(worldDir.resolve(Paths.get("dimensions", "minecraft", "overworld", "region")));
   }

   private boolean regionDirHasMca(Path regionDir) {
      if (!Files.isDirectory(regionDir)) {
         return false;
      } else {
         try {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(regionDir, "*.mca")) {
               return ds.iterator().hasNext();
            }
         } catch (IOException ex) {
            return false;
         }
      }
   }

   private void beginReset(String presetId, CommandSender sender) {
      List<String> worlds = this.managedWorlds();
      Path serverRoot = this.plugin.getServer().getWorldContainer().toPath();

      for (String worldName : worlds) {
         World loaded = Bukkit.getWorld(worldName);
         if (loaded != null && !Bukkit.unloadWorld(loaded, false)) {
            this.resetInProgress.set(false);
            this.startupStatus.set("BLOCKED (failed to unload: " + worldName + ")");
            this.feedback(sender, "<#FF5555>[!] <reset><#888888>Failed to unload world: <white>" + worldName + "<reset>");
            this.plugin.getLogger().warning("[WorldState] Failed to unload world " + worldName);
            return;
         }
      }

      this.plugin.platformScheduler().runAsync(() -> {
         try {
            for (String worldNamex : worlds) {
               this.presetService.clearWorldChunks(worldNamex, serverRoot);
               this.presetService.populateWorld(presetId, worldNamex, serverRoot);
            }
         } catch (IOException var7x) {
            this.plugin.platformScheduler().runWorldGlobal(() -> {
               this.resetInProgress.set(false);
               this.startupStatus.set("BLOCKED (reset failed: " + var7x.getMessage() + ")");
               this.feedback(sender, "<#FF5555>[!] <reset><#888888>Reset failed: " + var7x.getMessage() + "<reset>");
               this.plugin.getLogger().severe("[WorldState] Reset failed: " + var7x.getMessage());
            });
            return;
         }

         this.plugin.platformScheduler().runWorldGlobal(() -> {
            this.reloadWorlds(worlds);
            this.applyPresetSpawn(presetId);
            this.resetInProgress.set(false);
            this.startupReady.set(true);
            this.verifySpawnChunksReady();
            this.startupStatus.set("READY (reset to preset: " + presetId + ")");
            this.feedback(sender, "<#FFB300><bold>World reset complete.</bold><reset><#888888> Preset: <white>" + presetId + "<reset>");
            this.plugin.getLogger().info("[WorldState] Reset complete — preset='" + presetId + "'.");
         });
      });
   }

   private void reloadWorlds(List<String> worldNames) {
      for (String worldName : worldNames) {
         if (Bukkit.getWorld(worldName) == null) {
            WorldCreator creator = this.plugin.configureWorldCreator(new WorldCreator(worldName));
            Bukkit.createWorld(creator);
         }
         World loaded = Bukkit.getWorld(worldName);
         if (loaded != null) {
            WorldGroundItemCleanup.clearGroundItems(loaded, this.plugin.getLogger(), "world reload");
         }
      }
   }

   private void clearGroundItemsInManagedWorlds(String reason) {
      if (!this.plugin.gameModeBehaviorService().booleanValue(CoreBehaviorKeys.CLEAR_GROUND_ITEMS_ON_LOAD_ENABLED, false)) {
         return;
      }
      for (String worldName : this.managedWorlds()) {
         World world = Bukkit.getWorld(worldName);
         if (world == null) {
            continue;
         }
         if (this.plugin.platform().isFolia()) {
            for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
               this.plugin.platform().runAtChunk(world, chunk.getX(), chunk.getZ(), () -> {
                  int removed = WorldGroundItemCleanup.clearGroundItemsInChunk(chunk);
                  if (removed > 0) {
                     this.plugin.getLogger().info("[WorldCleanup] Removed " + removed + " ground item(s) in '" + world.getName() + "' chunk (" + chunk.getX() + "," + chunk.getZ() + ") (" + reason + ").");
                  }
               });
            }
         } else {
            WorldGroundItemCleanup.clearGroundItems(world, this.plugin.getLogger(), reason);
         }
      }
   }

   private void applyPresetSpawn(String presetId) {
      this.presetSpawnLocation(presetId).ifPresent(location -> {
         World world = location.getWorld();
         if (world != null) {
            world.setSpawnLocation(location);
         }
      });
   }

   private void feedback(CommandSender sender, String miniMessage) {
      if (sender != null) {
         sender.sendMessage(MINI_MESSAGE.deserialize(miniMessage));
      }
   }

   private void connect(Player player, String server) {
      if (server != null && !server.isBlank()) {
         ProxyRouteMessenger.routePlayer(this.plugin, player, server.trim().toLowerCase(Locale.ROOT));
      }
   }
}
