package network.skypvp.paper.service;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gamemode.api.HudProvider;
import network.skypvp.shared.RankRecord;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

public final class HudProviderService {
   private final PaperCorePlugin plugin;

   public HudProviderService(PaperCorePlugin plugin) {
      this.plugin = plugin;
   }

   private Class<?> lastActiveProviderClass = null;

   public Optional<HudProvider> activeProvider() {
      Collection<RegisteredServiceProvider<HudProvider>> registrations = this.plugin.getServer().getServicesManager().getRegistrations(HudProvider.class);
      if (registrations != null && !registrations.isEmpty()) {
         String expected = this.plugin.gameModeBehaviorService().activeModeKey().toLowerCase(Locale.ROOT);

         for (RegisteredServiceProvider<HudProvider> registration : registrations) {
            HudProvider provider = (HudProvider)registration.getProvider();
            if (provider != null) {
               String modeKey = provider.modeKey();
               if (modeKey != null && modeKey.trim().equalsIgnoreCase(expected)) {
                  if (this.lastActiveProviderClass != provider.getClass()) {
                     this.lastActiveProviderClass = provider.getClass();
                     this.plugin.performanceMonitorService().incrementProviderChurn();
                  }
                  return Optional.of(provider);
               }
            }
         }

         if (this.lastActiveProviderClass != null) {
            this.lastActiveProviderClass = null;
            this.plugin.performanceMonitorService().incrementProviderChurn();
         }
         return Optional.empty();
      } else {
         if (this.lastActiveProviderClass != null) {
            this.lastActiveProviderClass = null;
            this.plugin.performanceMonitorService().incrementProviderChurn();
         }
         return Optional.empty();
      }
   }

   public HudProvider.Context createContext(Player player, RankRecord rank, Map<String, String> placeholders, long tickMillis) {
      String locale = this.plugin.playerLocaleService() != null
              ? this.plugin.playerLocaleService().locale(player.getUniqueId())
              : player.getLocale();
      return new HudProvider.Context(
         player,
         this.plugin.serverId(),
         this.plugin.serverRole().name(),
         this.plugin.getServer().getOnlinePlayers().size(),
         this.plugin.getServer().getMaxPlayers(),
         rank,
         placeholders,
         tickMillis,
         locale
      );
   }
}
