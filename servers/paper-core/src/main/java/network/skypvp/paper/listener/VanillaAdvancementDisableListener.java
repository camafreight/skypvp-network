package network.skypvp.paper.listener;

import com.destroystokyo.paper.event.player.PlayerAdvancementCriterionGrantEvent;
import java.util.Iterator;
import java.util.Objects;
import network.skypvp.paper.PaperCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.ServerLoadEvent;

public final class VanillaAdvancementDisableListener implements Listener {

   private static final String VANILLA_NAMESPACE = "minecraft";

   private final PaperCorePlugin plugin;

   public VanillaAdvancementDisableListener(PaperCorePlugin plugin) {
      this.plugin = Objects.requireNonNull(plugin, "plugin");
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onAdvancementCriterionGrant(PlayerAdvancementCriterionGrantEvent event) {
      if (isVanillaAdvancement(event.getAdvancement().getKey())) {
         event.setCancelled(true);
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onAdvancementDone(PlayerAdvancementDoneEvent event) {
      if (isVanillaAdvancement(event.getAdvancement().getKey())) {
         event.message(null);
      }
   }

   @EventHandler
   public void onServerLoad(ServerLoadEvent event) {
      if (event.getType() != ServerLoadEvent.LoadType.STARTUP) {
         return;
      }
      this.plugin.platformScheduler().runForEachPlayer(VanillaAdvancementDisableListener::revokeVanillaAdvancements);
   }

   @EventHandler
   public void onPlayerJoin(PlayerJoinEvent event) {
      Player player = event.getPlayer();
      this.plugin.platformScheduler().runOnPlayerLater(player, () -> revokeVanillaAdvancements(player), 1L);
   }

   static void revokeVanillaAdvancements(Player player) {
      Iterator<Advancement> iterator = Bukkit.advancementIterator();
      while (iterator.hasNext()) {
         Advancement advancement = iterator.next();
         if (!isVanillaAdvancement(advancement.getKey())) {
            continue;
         }
         AdvancementProgress progress = player.getAdvancementProgress(advancement);
         for (String criterion : progress.getAwardedCriteria()) {
            progress.revokeCriteria(criterion);
         }
      }
   }

   private static boolean isVanillaAdvancement(NamespacedKey key) {
      return key != null && VANILLA_NAMESPACE.equals(key.getNamespace());
   }
}
