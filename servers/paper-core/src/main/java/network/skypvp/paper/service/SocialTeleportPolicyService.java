package network.skypvp.paper.service;

import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gamemode.api.SocialTeleportPolicy;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

public final class SocialTeleportPolicyService {
   private final PaperCorePlugin plugin;
   private final GameModeBehaviorService gameModeBehaviorService;

   public SocialTeleportPolicyService(PaperCorePlugin plugin, GameModeBehaviorService gameModeBehaviorService) {
      this.plugin = plugin;
      this.gameModeBehaviorService = gameModeBehaviorService;
   }

   public SocialTeleportPolicy.TeleportDecision canRequestFriendTeleport(Player requester, Player target) {
      return this.activePolicy().map(policy -> policy.canRequestFriendTeleport(requester, target)).orElseGet(SocialTeleportPolicy.TeleportDecision::allow);
   }

   public SocialTeleportPolicy.TeleportDecision canExecuteFriendTeleport(Player requester, Player target) {
      return this.activePolicy().map(policy -> policy.canExecuteFriendTeleport(requester, target)).orElseGet(SocialTeleportPolicy.TeleportDecision::allow);
   }

   private Optional<SocialTeleportPolicy> activePolicy() {
      Collection<RegisteredServiceProvider<SocialTeleportPolicy>> registrations = this.plugin
         .getServer()
         .getServicesManager()
         .getRegistrations(SocialTeleportPolicy.class);
      if (registrations != null && !registrations.isEmpty()) {
         String expected = this.gameModeBehaviorService.activeModeKey().toLowerCase(Locale.ROOT);

         for (RegisteredServiceProvider<SocialTeleportPolicy> registration : registrations) {
            SocialTeleportPolicy provider = (SocialTeleportPolicy)registration.getProvider();
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
}
