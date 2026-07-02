package network.skypvp.proxy.config;

import java.util.List;
import network.skypvp.shared.RedisConnectionSettings;
import network.skypvp.shared.chat.ChatModerationSettings;

public final class ProxyBootstrapConfig {
   public String networkName = "SkyPvP Network";
   public String externalAddress = "play.SkyPvP.local";
   public String fallbackServer = "";
   public boolean requireHeartbeatForRouting = true;
   public boolean redisEnabled = false;
   public String sessionChannel = "skypvp:network:players";
   public String heartbeatChannel = "skypvp:network:heartbeats";
   public String rankChannel = "skypvp:network:ranks";
   public String queueChannel = "skypvp:network:queues";
   public int queueTransferRatePerSecond = 20;
   public int queueTransferBurstCapacity = 30;
   public int queueDrainMaxPerTick = 5;
   public long serverLifecycleStaleHeartbeatMillis = 45000L;
   public ProxyBootstrapConfig.LimboSettings limbo = new ProxyBootstrapConfig.LimboSettings();
   public RedisConnectionSettings redis = RedisConnectionSettings.localDefaults();
    public ProxyBootstrapConfig.PostgresProxySettings postgres = new ProxyBootstrapConfig.PostgresProxySettings();
    public ChatModerationSettings chatModeration = ChatModerationSettings.defaults();
    public ProxyBootstrapConfig.VersionCompatibilitySettings versionCompatibility = new ProxyBootstrapConfig.VersionCompatibilitySettings();
   public List<ProxyBootstrapConfig.TrackedBackendServer> backendServers = List.of(
      new ProxyBootstrapConfig.TrackedBackendServer("extraction-1", "EXTRACTION", "extraction", 250, false)
   );


   public ProxyBootstrapConfig() {
   }

   public static ProxyBootstrapConfig defaultConfig() {
      return new ProxyBootstrapConfig();
   }

   public void normalizeDefaults() {
      if (this.sessionChannel == null || this.sessionChannel.isBlank()) {
         this.sessionChannel = "skypvp:network:players";
      }

      if (this.heartbeatChannel == null || this.heartbeatChannel.isBlank()) {
         this.heartbeatChannel = "skypvp:network:heartbeats";
      }

      if (this.rankChannel == null || this.rankChannel.isBlank()) {
         this.rankChannel = "skypvp:network:ranks";
      }

      if (this.queueChannel == null || this.queueChannel.isBlank()) {
         this.queueChannel = "skypvp:network:queues";
      }

      if (this.redis == null) {
         this.redis = RedisConnectionSettings.localDefaults();
      }

      if (this.queueTransferRatePerSecond <= 0) {
         this.queueTransferRatePerSecond = 20;
      }

      if (this.queueTransferBurstCapacity <= 0) {
         this.queueTransferBurstCapacity = 30;
      }

      if (this.queueDrainMaxPerTick <= 0) {
         this.queueDrainMaxPerTick = 5;
      }

      if (this.serverLifecycleStaleHeartbeatMillis < 30000L) {
         this.serverLifecycleStaleHeartbeatMillis = 30000L;
      }

      if (this.postgres == null) {
         this.postgres = new ProxyBootstrapConfig.PostgresProxySettings();
      }

      if (this.chatModeration == null) {
         this.chatModeration = ChatModerationSettings.defaults();
      }

      if (this.limbo == null) {
         this.limbo = new ProxyBootstrapConfig.LimboSettings();
      }

      this.limbo.normalizeDefaults();
      if (this.versionCompatibility == null) {
         this.versionCompatibility = new ProxyBootstrapConfig.VersionCompatibilitySettings();
      }

      this.versionCompatibility.normalizeDefaults();
   }

   public static final class LimboSettings {
      public boolean enabled = true;
      public String initialQueueKey = "network-initial";
      public int statusRefreshMillis = 1000;
      public int readTimeoutMillis = 30000;
      public long worldTime = 6000L;
      public double spawnX = 0.5;
      public double spawnY = 66.0;
      public double spawnZ = 0.5;
      public float spawnYaw = 0.0F;
      public float spawnPitch = 0.0F;
      public int platformRadius = 5;
      public int platformY = 64;
      public int platformLightLevel = 15;
      public boolean holdPlayersDuringMaintenance = true;
      public boolean holdPlayersWhenNoInitialServer = true;
      public boolean evacuatePlayersDuringMaintenance = true;
      public boolean evacuatePlayersFromUnhealthyServers = true;

      public LimboSettings() {
      }

      public void normalizeDefaults() {
         if (this.initialQueueKey == null || this.initialQueueKey.isBlank()) {
            this.initialQueueKey = "network-initial";
         }

         if (this.statusRefreshMillis < 500) {
            this.statusRefreshMillis = 500;
         }

         if (this.readTimeoutMillis < 10000) {
            this.readTimeoutMillis = 10000;
         }

         if (this.worldTime < 0L) {
            this.worldTime = 6000L;
         }

         if (this.platformRadius < 2) {
            this.platformRadius = 2;
         }

         if (this.platformY < 1) {
            this.platformY = 64;
         }

         if (this.platformLightLevel < 0) {
            this.platformLightLevel = 0;
         } else if (this.platformLightLevel > 15) {
            this.platformLightLevel = 15;
         }
      }
   }

   public static final class PostgresProxySettings {
      public boolean enabled = false;
      public String host = "127.0.0.1";
      public int port = 5432;
      public String database = "skypvp_network";
      public String username = "skypvp";
      public String password = "change-me";

      public PostgresProxySettings() {
      }
   }

   public static final class TrackedBackendServer {
      public String serverId;
      public String role;
      public String cluster;
      public int softCapacity;
      public boolean fallbackEligible;

      public TrackedBackendServer(String serverId, String role, String cluster, int softCapacity, boolean fallbackEligible) {
         this.serverId = serverId;
         this.role = role;
         this.cluster = cluster;
         this.softCapacity = softCapacity;
         this.fallbackEligible = fallbackEligible;
      }
   }

   public static final class VersionCompatibilitySettings {
      public boolean enforceServerGates = true;
      public String latestJavaVersionLabel = "1.21.11";
      public int latestJavaProtocol = -1;
      public List<String> latestOnlyRoles = List.of("EXTRACTION");
      public List<String> latestOnlyClusters = List.of("extraction");
      public String latestOnlyDenyMessage = "This mode requires Minecraft Java {requiredVersion}. You can still play in the lobby on your current version.";

      public VersionCompatibilitySettings() {
      }

      public void normalizeDefaults() {
         if (this.latestJavaVersionLabel == null || this.latestJavaVersionLabel.isBlank()) {
            this.latestJavaVersionLabel = "latest";
         }

         if (this.latestOnlyRoles == null) {
            this.latestOnlyRoles = List.of();
         }

         if (this.latestOnlyClusters == null) {
            this.latestOnlyClusters = List.of();
         }

         if (this.latestOnlyDenyMessage == null || this.latestOnlyDenyMessage.isBlank()) {
            this.latestOnlyDenyMessage = "This mode requires Java {requiredVersion} ({requiredProtocol}).";
         }
      }
   }
}
