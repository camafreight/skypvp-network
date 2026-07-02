package network.skypvp.lobby;

import network.skypvp.lobby.command.LobbyGameCommand;
import network.skypvp.lobby.command.LobbyLayoutCommand;
import network.skypvp.lobby.command.LobbyRuntimeCheckCommand;
import network.skypvp.lobby.command.LobbyStateCommand;
import network.skypvp.lobby.game.DuelsModule;
import network.skypvp.lobby.game.HideAndSeekModule;
import network.skypvp.lobby.game.LobbyGameManager;
import network.skypvp.lobby.game.TagModule;
import network.skypvp.lobby.game.parkour.ParkourManager;
import network.skypvp.lobby.game.parkour.ParkourRedisSync;
import network.skypvp.lobby.game.parkour.ParkourSetupCommand;
import network.skypvp.lobby.listener.LobbyPlayerProfileListener;
import network.skypvp.lobby.listener.LobbySelectorListener;
import network.skypvp.lobby.listener.LobbyWorldGuardListener;
import network.skypvp.lobby.service.LobbyLayoutService;
import network.skypvp.lobby.service.LobbyRuntimeMonitor;
import network.skypvp.lobby.task.LobbySpawnBalancerTask;
import network.skypvp.lobby.task.LobbyWorldMaintenanceTask;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gamemode.api.CoreBehaviorProfile;
import network.skypvp.paper.gamemode.api.HudProvider;
import network.skypvp.paper.library.HolographicLibrary;
import network.skypvp.paper.library.WorldGroundItemCleanup;
import network.skypvp.lobby.library.HotbarItemsLibrary;
import network.skypvp.paper.library.NpcLibrary;
import network.skypvp.lobby.state.LobbyRuntimeStateRegistry;
import network.skypvp.shared.NetworkServerRole;

import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import network.skypvp.paper.platform.PlatformTask;

import java.util.Optional;

public final class LobbyModePlugin extends JavaPlugin {
   private final LobbyMechanicCatalog mechanics = new LobbyMechanicCatalog();
   private final CoreBehaviorProfile behaviorProfile = new LobbyCoreBehaviorProfile();
   private final HudProvider hudProvider = new LobbyHudProvider();
   private LobbyLayoutService lobbyLayoutService;
   private PlatformTask lobbyMonitorTask;
   private PlatformTask lobbyMaintenanceTask;
   private PlatformTask lobbySpawnBalancerTask;
   private boolean lobbySystemsInitialized;
   private boolean lobbyBootstrapScheduled;

   public LobbyModePlugin() {
   }

   public void onEnable() {
      this.getServer().getServicesManager().register(CoreBehaviorProfile.class, this.behaviorProfile, this,
            ServicePriority.Normal);
      this.getServer().getServicesManager().register(HudProvider.class, this.hudProvider, this, ServicePriority.Normal);
      this.getLogger().info("Loaded mode: " + this.mechanics.modeKey() + " (" + this.mechanics.mechanics().size()
            + " classified mechanics)");
      if (this.getServer().getPluginManager().getPlugin("SkyPvPCore") instanceof PaperCorePlugin core) {
         this.bootstrapLobbySystems(core);
      } else {
         this.getLogger().warning("SkyPvPCore not found; lobby layout systems disabled.");
      }
   }

   private void bootstrapLobbySystems(PaperCorePlugin core) {
      if (!this.lobbySystemsInitialized) {
         if (core.serverRole() != NetworkServerRole.LOBBY) {
            this.getLogger().info("Server role is " + core.serverRole() + "; skipping lobby-only systems.");
         } else {
            boolean defaultLobbySystems = true;
            if (!core.gameModeBehaviorService().booleanValue("core.lobby.systems.enabled", defaultLobbySystems)) {
               this.getLogger().info("Lobby systems disabled by behavior profile override.");
            } else {
               LobbyRuntimeStateRegistry lobbyStateRegistry = new LobbyRuntimeStateRegistry();
               HotbarItemsLibrary hotbarItemsLibrary = new HotbarItemsLibrary(this);
               NpcLibrary npcLibrary = core.npcLibrary();
               HolographicLibrary holographicLibrary = core.holographicLibrary();
               if (npcLibrary != null && holographicLibrary != null) {
                  this.lobbyLayoutService = new LobbyLayoutService(core, core.worldStateService(), npcLibrary,
                        holographicLibrary);

                  Location lobbySpawn;
                  try {
                     lobbySpawn = this.resolveLobbySpawn(core);
                  } catch (IllegalStateException var24) {
                     if (!this.lobbyBootstrapScheduled) {
                        this.lobbyBootstrapScheduled = true;
                        this.getLogger().warning("Lobby world not ready yet; retrying lobby systems bootstrap in 1s.");
                        core.platform().runGlobalLater(() -> {
                           this.lobbyBootstrapScheduled = false;
                           this.bootstrapLobbySystems(core);
                        }, 20L);
                     }

                     return;
                  }

                  this.lobbySystemsInitialized = true;
                  lobbySpawn.getWorld().setGameRule(org.bukkit.GameRule.LOCATOR_BAR, false);
                  lobbySpawn = this.lobbyLayoutService.applyAndResolveSpawn(lobbySpawn.getWorld());
                  WorldGroundItemCleanup.clearGroundItems(lobbySpawn.getWorld(), this.getLogger(), "lobby bootstrap");
                  LobbyGameManager gameManager = new LobbyGameManager(this, hotbarItemsLibrary, core.platform());
                  DuelsModule duelsModule = new DuelsModule(this, gameManager, core.playerStatsRepository(), core.platform());
                  TagModule tagModule = new TagModule(this, gameManager);
                  HideAndSeekModule hnsModule = new HideAndSeekModule(this, gameManager, core.playerStatsRepository(), core.platform());
                  PluginCommand lobbyGameCmd = this.getCommand("lobbygame");
                  if (lobbyGameCmd != null) {
                     lobbyGameCmd.setExecutor(new LobbyGameCommand(gameManager, duelsModule, tagModule, hnsModule));
                  }

                  if (core.getConfig().getBoolean("lobby.protections-enabled", true)) {
                     int voidY = core.getConfig().getInt("lobby.void-safe-y", -20);
                     boolean hungerEnabled = core.getConfig().getBoolean("lobby.mechanics.hunger-enabled", false);
                     boolean damageEnabled = core.getConfig().getBoolean("lobby.mechanics.damage-enabled", false);
                     boolean inventoryLockEnabled = core.getConfig()
                           .getBoolean("lobby.mechanics.inventory-lock-enabled", true);
                     this.getServer()
                           .getPluginManager()
                           .registerEvents(
                                 new LobbyWorldGuardListener(lobbyStateRegistry, lobbySpawn, voidY, hungerEnabled,
                                       damageEnabled, inventoryLockEnabled, gameManager),
                                 this);
                  }

                  this.getServer()
                        .getPluginManager()
                        .registerEvents(
                              new LobbyPlayerProfileListener(core, lobbyStateRegistry, lobbySpawn, hotbarItemsLibrary),
                              this);
                  this.getServer().getPluginManager()
                        .registerEvents(new LobbySelectorListener(core, lobbyStateRegistry, core.guiManager()), this);
                  if (core.getConfig().getBoolean("lobby.parkour.enabled", true)) {
                     ParkourManager parkourManager = new ParkourManager(this, gameManager);
                     ParkourRedisSync redisSync = new ParkourRedisSync(this, core, parkourManager);
                     parkourManager.setRedisSync(redisSync);
                     PluginCommand parkourSetupCmd = this.getCommand("parkoursetup");
                     if (parkourSetupCmd != null) {
                        parkourSetupCmd.setExecutor(new ParkourSetupCommand(parkourManager));
                     }
                  }

                  PluginCommand lobbyLayout = this.getCommand("lobbylayout");
                  if (lobbyLayout != null && this.lobbyLayoutService != null) {
                     LobbyLayoutCommand command = new LobbyLayoutCommand(this.lobbyLayoutService);
                     lobbyLayout.setExecutor(command);
                     lobbyLayout.setTabCompleter(command);
                  }

                  PluginCommand lobbyStateCmd = this.getCommand("lobbystate");
                  if (lobbyStateCmd != null) {
                     LobbyStateCommand command = new LobbyStateCommand(lobbyStateRegistry);
                     lobbyStateCmd.setExecutor(command);
                     lobbyStateCmd.setTabCompleter(command);
                  }

                  PluginCommand lobbyCheckCmd = this.getCommand("lobbycheck");
                  if (lobbyCheckCmd != null) {
                     lobbyCheckCmd.setExecutor(new LobbyRuntimeCheckCommand(core, lobbyStateRegistry, lobbySpawn));
                  }

                  LobbyRuntimeMonitor monitor = new LobbyRuntimeMonitor(core, lobbyStateRegistry, lobbySpawn,
                        hotbarItemsLibrary, gameManager);
                  int monitorInterval = Math.max(40, core.getConfig().getInt("lobby.monitor-interval-ticks", 100));
                  this.lobbyMonitorTask = core.platform().runSyncTimer(monitor::tick, 40L, (long) monitorInterval);
                  if (core.getConfig().getBoolean("lobby.maintenance.enabled", true)) {
                     int maintenanceInterval = Math.max(100,
                           core.getConfig().getInt("lobby.maintenance.interval-ticks", 200));
                     int cleanupRadius = Math.max(32, core.getConfig().getInt("lobby.maintenance.cleanup-radius", 128));
                     long fixedTime = Math.max(0L, core.getConfig().getLong("lobby.maintenance.fixed-time", 6000L));
                     LobbyWorldMaintenanceTask maintenanceTask = new LobbyWorldMaintenanceTask(this, lobbySpawn,
                           cleanupRadius, fixedTime);
                     this.lobbyMaintenanceTask = core.platform().runSyncTimer(maintenanceTask, 80L, (long) maintenanceInterval);
                  }

                  if (core.getConfig().getBoolean("lobby.spawn-balance.enabled", true)) {
                     int interval = Math.max(20, core.getConfig().getInt("lobby.spawn-balance.interval-ticks", 60));
                     int crowdRadius = Math.max(4, core.getConfig().getInt("lobby.spawn-balance.crowd-radius", 8));
                     int threshold = Math.max(10, core.getConfig().getInt("lobby.spawn-balance.threshold", 30));
                     int spreadRadius = Math.max(crowdRadius + 4,
                           core.getConfig().getInt("lobby.spawn-balance.spread-radius", 22));
                     LobbySpawnBalancerTask balancer = new LobbySpawnBalancerTask(lobbySpawn, crowdRadius, threshold,
                           spreadRadius);
                     this.lobbySpawnBalancerTask = core.platform().runSyncTimer(balancer, 100L, (long) interval);
                  }
               } else {
                  this.getLogger().warning("Core NPC/hologram libraries unavailable; lobby layout systems disabled.");
               }
            }
         }
      }
   }

   public void onDisable() {
      if (this.lobbyMonitorTask != null) {
         this.lobbyMonitorTask.cancel();
      }

      if (this.lobbyMaintenanceTask != null) {
         this.lobbyMaintenanceTask.cancel();
      }

      if (this.lobbySpawnBalancerTask != null) {
         this.lobbySpawnBalancerTask.cancel();
      }

      this.getServer().getServicesManager().unregister(CoreBehaviorProfile.class, this.behaviorProfile);
      this.getServer().getServicesManager().unregister(HudProvider.class, this.hudProvider);
   }

   private Location resolveLobbySpawn(PaperCorePlugin core) {
      Optional<Location> presetSpawn = core.worldStateService().presetSpawnLocation();
      if (presetSpawn.isPresent()) {
         return presetSpawn.get();
      }

      String worldName = core.getConfig().getString("lobby.spawn.world", "world");
      World world = core.getServer().getWorld(worldName);
      if (world == null) {
         world = core.getServer().getWorlds().isEmpty() ? null : (World) core.getServer().getWorlds().get(0);
      }

      if (world == null) {
         throw new IllegalStateException("No worlds loaded; cannot resolve lobby spawn.");
      } else {
         double x = core.getConfig().getDouble("lobby.spawn.x", world.getSpawnLocation().getX());
         double y = core.getConfig().getDouble("lobby.spawn.y", world.getSpawnLocation().getY());
         double z = core.getConfig().getDouble("lobby.spawn.z", world.getSpawnLocation().getZ());
         float yaw = (float) core.getConfig().getDouble("lobby.spawn.yaw", (double) world.getSpawnLocation().getYaw());
         float pitch = (float) core.getConfig().getDouble("lobby.spawn.pitch",
               (double) world.getSpawnLocation().getPitch());
         return new Location(world, x, y, z, yaw, pitch);
      }
   }
}
