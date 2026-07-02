package network.skypvp.paper.platform;

import java.util.Objects;
import network.skypvp.paper.PaperCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Resolves the shared {@link ServerPlatform} from SkyPvPCore or a standalone fallback.
 */
public final class Platforms {

   private Platforms() {
   }

   /** Returns the platform from SkyPvPCore, or throws if core is not loaded. */
   public static ServerPlatform require(Plugin plugin) {
      ServerPlatform platform = Platforms.fromCore(plugin);
      if (platform != null) {
         return platform;
      }
      throw new IllegalStateException("SkyPvPCore must be loaded to access ServerPlatform");
   }

   /** Returns the platform from SkyPvPCore when available, otherwise a standalone Paper/Folia adapter. */
   public static ServerPlatform get(Plugin plugin) {
      Objects.requireNonNull(plugin, "plugin");
      ServerPlatform fromCore = Platforms.fromCore(plugin);
      return fromCore != null ? fromCore : PlatformScheduler.create(plugin);
   }

   private static ServerPlatform fromCore(Plugin plugin) {
      if (plugin instanceof PaperCorePlugin core) {
         return core.platform();
      }
      Plugin corePlugin = Bukkit.getPluginManager().getPlugin("SkyPvPCore");
      if (corePlugin instanceof PaperCorePlugin core) {
         return core.platform();
      }
      return null;
   }
}
