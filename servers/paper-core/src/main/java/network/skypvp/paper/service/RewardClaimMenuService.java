package network.skypvp.paper.service;

import gg.skypvp.web.api.events.PlayerStorePurchaseEvent;
import gg.skypvp.web.api.events.PlayerVoteEvent;
import gg.skypvp.webauth.core.sync.RewardModels;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gui.GuiButtonLibrary;
import network.skypvp.paper.gui.GuiManager;
import network.skypvp.paper.gui.GuiMenuBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class RewardClaimMenuService implements Listener {
   private final PaperCorePlugin plugin;
   private final GuiManager guiManager;
   private final Map<UUID, List<RewardClaimMenuService.PendingRewardEntry>> pendingByPlayer = new ConcurrentHashMap<>();

   public RewardClaimMenuService(PaperCorePlugin plugin, GuiManager guiManager) {
      this.plugin = plugin;
      this.guiManager = guiManager;
   }

   public void openRewardsMenu(Player player) {
      List<RewardClaimMenuService.PendingRewardEntry> pending = this.pendingByPlayer.getOrDefault(player.getUniqueId(), List.of());
      GuiMenuBuilder menu = GuiMenuBuilder.create(Component.text("Unclaimed Rewards"), 27);
      if (pending.isEmpty()) {
         menu.button(13, GuiButtonLibrary.infoExclamation("No pending rewards", lore -> lore.plain("Store and vote rewards appear here.")), viewer -> {
         });
      } else {
         int slot = 10;
         for (RewardClaimMenuService.PendingRewardEntry entry : pending) {
            if (slot > 16) {
               break;
            }
            menu.button(
               slot++,
               GuiButtonLibrary.primaryAction(Material.GOLD_INGOT, entry.label(), lore -> {
                  lore.plain(entry.detail());
                  lore.plain("Click to claim");
               }),
               context -> this.claimEntry(context.viewer(), entry)
            );
         }
      }
      this.guiManager.open(player, menu.build());
   }

   public int pendingCount(UUID playerId) {
      return this.pendingByPlayer.getOrDefault(playerId, List.of()).size();
   }

   @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
   public void onStorePurchase(PlayerStorePurchaseEvent event) {
      Player player = event.getPlayer();
      if (player == null) {
         return;
      }
      this.pendingByPlayer.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayList<>())
         .add(new RewardClaimMenuService.PendingRewardEntry("store:" + event.getOrderId(), "Store purchase", event.getOrderId(), event));
   }

   @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
   public void onVote(PlayerVoteEvent event) {
      Player player = event.getPlayer();
      if (player == null) {
         return;
      }
      this.pendingByPlayer.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayList<>())
         .add(new RewardClaimMenuService.PendingRewardEntry("vote:" + event.getServiceName(), "Vote reward", event.getServiceName(), event));
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent event) {
      this.pendingByPlayer.putIfAbsent(event.getPlayer().getUniqueId(), new ArrayList<>());
   }

   private void claimEntry(Player player, RewardClaimMenuService.PendingRewardEntry entry) {
      if (entry.source() instanceof PlayerStorePurchaseEvent purchaseEvent) {
         purchaseEvent.setFulfillmentStatus(RewardModels.FulfillmentStatus.FULFILLED);
         player.sendMessage(Component.text("Store reward acknowledged for order " + purchaseEvent.getOrderId()));
      } else if (entry.source() instanceof PlayerVoteEvent voteEvent) {
         player.sendMessage(Component.text("Vote reward acknowledged from " + voteEvent.getServiceName()));
      }
      this.pendingByPlayer.computeIfPresent(player.getUniqueId(), (uuid, list) -> {
         list.removeIf(item -> item.id().equals(entry.id()));
         return list.isEmpty() ? null : list;
      });
      Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tellraw " + player.getName() + " {\"text\":\"Reward claimed.\",\"color\":\"green\"}");
      this.openRewardsMenu(player);
   }

   private record PendingRewardEntry(String id, String label, String detail, Object source) {
   }
}
