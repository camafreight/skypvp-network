package network.skypvp.paper.service;

import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gamemode.api.CoreBehaviorProfile;
import network.skypvp.shared.NetworkServerRole;
import org.bukkit.plugin.RegisteredServiceProvider;

public final class GameModeBehaviorService {
   private static final String MODE_KEY_OVERRIDE = "";
   private final PaperCorePlugin plugin;
   private final String activeModeKey;

   public GameModeBehaviorService(PaperCorePlugin plugin, NetworkServerRole role) {
      this.plugin = plugin;
      this.activeModeKey = this.resolveModeKey(role);
   }

   public String activeModeKey() {
      return this.activeModeKey;
   }

   public boolean booleanValue(String key, boolean defaultValue) {
      Optional<Boolean> override = this.activeProfile().flatMap(profile -> profile.booleanOverride(key));
      return override.orElse(defaultValue);
   }

   public int intValue(String key, int defaultValue) {
      OptionalInt override = this.activeProfile().flatMap(profile -> {
         OptionalInt value = profile.intOverride(key);
         return value.isPresent() ? Optional.of(value) : Optional.empty();
      }).orElseGet(OptionalInt::empty);
      return override.isPresent() ? override.getAsInt() : defaultValue;
   }

   public String stringValue(String key, String defaultValue) {
      return this.activeProfile().flatMap(profile -> profile.stringOverride(key)).orElse(defaultValue);
   }

   private Optional<CoreBehaviorProfile> activeProfile() {
      Collection<RegisteredServiceProvider<CoreBehaviorProfile>> registrations = this.plugin
         .getServer()
         .getServicesManager()
         .getRegistrations(CoreBehaviorProfile.class);
      if (registrations != null && !registrations.isEmpty()) {
         String expected = this.activeModeKey.toLowerCase(Locale.ROOT);

         for (RegisteredServiceProvider<CoreBehaviorProfile> registration : registrations) {
            CoreBehaviorProfile provider = (CoreBehaviorProfile)registration.getProvider();
            if (provider != null) {
               String mode = provider.modeKey();
               if (mode != null && mode.trim().equalsIgnoreCase(expected)) {
                  return Optional.of(provider);
               }
            }
         }

         return Optional.empty();
      } else {
         return Optional.empty();
      }
   }

   private String resolveModeKey(NetworkServerRole role) {
      String configured = MODE_KEY_OVERRIDE.trim();
      if (!configured.isBlank()) {
         return configured.toLowerCase(Locale.ROOT);
      } else {
         return switch (role) {
            case LOBBY -> "lobby";
            case EXTRACTION -> "extraction";
            case SURVIVAL -> "survival";
            default -> role.name().toLowerCase(Locale.ROOT);
         };
      }
   }
}
