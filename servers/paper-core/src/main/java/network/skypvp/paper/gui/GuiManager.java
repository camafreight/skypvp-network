package network.skypvp.paper.gui;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.paper.library.NetworkSoundCue;
import network.skypvp.paper.platform.PlatformTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.Event.Result;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.inventory.view.builder.LocationInventoryViewBuilder;
import org.bukkit.plugin.java.JavaPlugin;
import network.skypvp.paper.platform.ServerPlatform;

public final class GuiManager implements Listener {
   private final JavaPlugin plugin;
   private final ServerPlatform scheduler;
   private final GuiFeedbackService feedbackService;
   private final Map<UUID, GuiManager.ActiveGui> activeGuis = new ConcurrentHashMap<>();
   private final Map<UUID, GuiManager.ActiveAnvilPrompt> activeAnvilPrompts = new ConcurrentHashMap<>();
   private PlatformTask animationTask;

   public GuiManager(JavaPlugin plugin, ServerPlatform scheduler) {
      this.plugin = plugin;
      this.scheduler = scheduler;
      this.feedbackService = new GuiFeedbackService();
   }

   public GuiFeedbackService feedbackService() {
      return this.feedbackService;
   }

   public void presentSlotFeedback(Player player, String menuTitleEquals, int slot, GuiFeedback feedback, ItemStack restoreItem) {
      this.feedbackService.presentSlot(player, menuTitleEquals, slot, feedback, restoreItem);
      // Feedback only re-renders on inventory open/click otherwise, so the countdown text and the
      // eventual restore would never update while the menu sits open. Tick the sync once a second.
      if (player != null) {
         for (long delayTicks = 20L; delayTicks <= 120L; delayTicks += 20L) {
            this.scheduler.runOnPlayerLater(player, () -> this.feedbackService.sync(player), delayTicks);
         }
      }
   }

   public void open(Player viewer, GuiMenu menu) {
      this.open(viewer, menu, false);
   }

   public void openChild(Player viewer, GuiMenu menu) {
      this.open(viewer, menu, true);
   }

   public void openAnvilPrompt(Player viewer, GuiAnvilPrompt prompt) {
      this.openAnvilPrompt(viewer, prompt, false);
   }

   public void openChildAnvilPrompt(Player viewer, GuiAnvilPrompt prompt) {
      this.openAnvilPrompt(viewer, prompt, true);
   }

   public boolean back(Player viewer) {
      GuiManager.ActiveAnvilPrompt prompt = this.activeAnvilPrompts.remove(viewer.getUniqueId());
      if (prompt != null) {
         this.clearPromptInventory(prompt.inventory());
         return this.reopenPromptParent(viewer, prompt);
      } else {
         GuiManager.ActiveGui active = this.activeGuis.get(viewer.getUniqueId());
         if (active != null && !active.history().isEmpty()) {
            GuiMenu previous = active.history().pop();
            this.open(viewer, previous, active.history(), false);
            return true;
         } else {
            return false;
         }
      }
   }

   public void refresh(Player viewer) {
      GuiManager.ActiveGui active = this.activeGuis.get(viewer.getUniqueId());
      if (active != null) {
         if (active.menu() instanceof AnimatedGuiMenu animated && animated.backgroundAnimation() != null) {
            animated.renderAnimatedFrame(viewer, active.inventory(), active.animationFrame());
         } else {
            active.menu().render(viewer, active.inventory());
         }
         viewer.updateInventory();
      }
   }

   private void ensureAnimationTicker() {
      if (this.animationTask != null && !this.animationTask.isCancelled()) {
         return;
      }
      this.animationTask = this.scheduler.runGlobalTimer(this::tickAnimations, 1L, 1L);
   }

   private void tickAnimations() {
      if (this.activeGuis.isEmpty()) {
         if (this.animationTask != null) {
            this.animationTask.cancel();
            this.animationTask = null;
         }
         return;
      }

      boolean anyAnimated = false;
      for (Map.Entry<UUID, GuiManager.ActiveGui> entry : this.activeGuis.entrySet()) {
         Player player = Bukkit.getPlayer(entry.getKey());
         GuiManager.ActiveGui active = entry.getValue();
         if (player == null || !player.isOnline()) {
            continue;
         }
         if (!(active.menu() instanceof AnimatedGuiMenu animated) || animated.backgroundAnimation() == null) {
            continue;
         }
         anyAnimated = true;
         active.incrementAnimationTicks();
         GuiAnimation animation = animated.backgroundAnimation();
         if (active.animationTicks() < animation.periodTicks()) {
            continue;
         }
         active.resetAnimationTicks();
         active.advanceAnimationFrame(animation.frameCount());
         int frame = active.animationFrame();
         this.scheduler.runOnPlayer(player, () -> {
            if (!player.isOnline()) {
               return;
            }
            GuiManager.ActiveGui live = this.activeGuis.get(player.getUniqueId());
            if (live == null || live.menu() != animated || !live.inventory().equals(player.getOpenInventory().getTopInventory())) {
               return;
            }
            animated.renderAnimatedFrame(player, live.inventory(), frame);
            player.updateInventory();
         });
      }

      if (!anyAnimated && this.animationTask != null) {
         this.animationTask.cancel();
         this.animationTask = null;
      }
   }

   public void close(Player viewer) {
      GuiManager.ActiveGui active = this.activeGuis.remove(viewer.getUniqueId());
      if (active != null) {
         this.notifyClose(active, viewer, GuiCloseReason.PLUGIN);
         viewer.closeInventory();
      } else {
         GuiManager.ActiveAnvilPrompt prompt = this.activeAnvilPrompts.remove(viewer.getUniqueId());
         if (prompt != null) {
            this.clearPromptInventory(prompt.inventory());
            viewer.closeInventory();
         }
      }
   }

   private void open(Player viewer, GuiMenu menu, boolean pushHistory) {
      Deque<GuiMenu> history = new ArrayDeque<>();
      GuiManager.ActiveGui current = this.activeGuis.get(viewer.getUniqueId());
      if (current != null) {
         history.addAll(current.history());
         if (pushHistory) {
            history.push(current.menu());
         }
      }

      this.open(viewer, menu, history, current != null);
   }

   private void open(Player viewer, GuiMenu menu, Deque<GuiMenu> history, boolean replaceExisting) {
      GuiManager.ActiveAnvilPrompt prompt = this.activeAnvilPrompts.remove(viewer.getUniqueId());
      if (prompt != null) {
         this.clearPromptInventory(prompt.inventory());
      }

      if (replaceExisting) {
         GuiManager.ActiveGui existing = this.activeGuis.remove(viewer.getUniqueId());
         if (existing != null) {
            this.notifyClose(existing, viewer, GuiCloseReason.INVENTORY_REPLACE);
         }
      }

      Inventory inventory = this.plugin.getServer().createInventory(viewer, menu.size(), menu.title());
      menu.onPreOpen(viewer, inventory);
      menu.render(viewer, inventory);
      this.activeGuis.put(viewer.getUniqueId(), new GuiManager.ActiveGui(menu, inventory, history));
      viewer.openInventory(inventory);
      menu.onPostOpen(viewer, inventory);
      if (menu instanceof AnimatedGuiMenu animated && animated.backgroundAnimation() != null) {
         this.ensureAnimationTicker();
      }
   }

   private void openAnvilPrompt(Player viewer, GuiAnvilPrompt prompt, boolean pushHistory) {
      Deque<GuiMenu> history = new ArrayDeque<>();
      GuiManager.ActiveGui current = this.activeGuis.remove(viewer.getUniqueId());
      if (current != null) {
         history.addAll(current.history());
         if (pushHistory) {
            history.push(current.menu());
         }

         this.notifyClose(current, viewer, GuiCloseReason.INVENTORY_REPLACE);
      }

      this.activeAnvilPrompts.remove(viewer.getUniqueId());
      AnvilView anvilView = (AnvilView)((LocationInventoryViewBuilder)MenuType.ANVIL.builder()).title(prompt.title()).checkReachable(false).build(viewer);
      AnvilInventory anvilInventory = anvilView.getTopInventory();
      anvilInventory.setFirstItem(prompt.buildInputItem());
      anvilInventory.setSecondItem(null);
      GuiManager.ActiveAnvilPrompt activePrompt = new GuiManager.ActiveAnvilPrompt(prompt, anvilInventory, history);
      this.activeAnvilPrompts.put(viewer.getUniqueId(), activePrompt);
      viewer.openInventory(anvilView);
      this.updatePromptResult(activePrompt, anvilView, null);
   }

   public boolean isManaged(InventoryView view) {
      if (view != null && view.getPlayer() instanceof Player player) {
         GuiManager.ActiveGui active = this.activeGuis.get(player.getUniqueId());
         if (active != null && active.inventory().equals(view.getTopInventory())) {
            return true;
         } else {
            GuiManager.ActiveAnvilPrompt prompt = this.activeAnvilPrompts.get(player.getUniqueId());
            return prompt != null && prompt.inventory().equals(view.getTopInventory());
         }
      } else {
         return false;
      }
   }

   @EventHandler(
      priority = EventPriority.HIGHEST
   )
   public void onClick(InventoryClickEvent event) {
      if (event.getWhoClicked() instanceof Player player) {
         GuiManager.ActiveAnvilPrompt prompt = this.activeAnvilPrompts.get(player.getUniqueId());
         if (prompt != null) {
            if (prompt.inventory().equals(event.getView().getTopInventory())) {
               this.handlePromptClick(player, prompt, event);
               return;
            }

            this.activeAnvilPrompts.remove(player.getUniqueId());
         }

         GuiManager.ActiveGui active = this.activeGuis.get(player.getUniqueId());
         if (active != null) {
            if (!active.inventory().equals(event.getView().getTopInventory())) {
               this.activeGuis.remove(player.getUniqueId());
            } else {
               event.setCancelled(true);
               event.setResult(Result.DENY);
               int rawSlot = event.getRawSlot();
               int topSize = event.getView().getTopInventory().getSize();
               if (rawSlot >= 0 && rawSlot < topSize) {
                  GuiClickContext context = new GuiClickContext(this, player, event);
                  if (!active.menu().lockSlotDuringClick(context) || active.tryAcquire(rawSlot, active.menu().clickDebounceMillis())) {
                     try {
                        if (active.menu().onPreClick(context)) {
                           active.menu().onClick(context);
                           active.menu().onPostClick(context);
                           return;
                        }
                     } catch (RuntimeException var12) {
                        this.plugin.getLogger().log(Level.SEVERE, "GUI click handler failed for " + player.getName(), (Throwable)var12);
                        NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
                        player.sendMessage(MiniMessage.miniMessage().deserialize("<red>That menu action failed. Please try again.</red>"));
                        return;
                     } finally {
                        active.release(rawSlot);
                     }
                  }
               } else {
                  player.updateInventory();
               }
            }
         }
      }
   }

   @EventHandler(
      priority = EventPriority.HIGHEST
   )
   public void onPrepareAnvil(PrepareAnvilEvent event) {
      if (event.getView().getPlayer() instanceof Player player) {
         GuiManager.ActiveAnvilPrompt prompt = this.activeAnvilPrompts.get(player.getUniqueId());
         if (prompt != null) {
            if (prompt.inventory().equals(event.getInventory())) {
               AnvilView var5 = event.getView();
               if (var5 instanceof AnvilView) {
                  this.updatePromptResult(prompt, var5, event);
               }
            }
         }
      }
   }

   @EventHandler(
      priority = EventPriority.HIGHEST
   )
   public void onDrag(InventoryDragEvent event) {
      if (event.getWhoClicked() instanceof Player player) {
         GuiManager.ActiveAnvilPrompt prompt = this.activeAnvilPrompts.get(player.getUniqueId());
         if (prompt != null) {
            if (prompt.inventory().equals(event.getView().getTopInventory())) {
               event.setCancelled(true);
               event.setResult(Result.DENY);
               player.updateInventory();
               return;
            }

            this.activeAnvilPrompts.remove(player.getUniqueId());
         }

         GuiManager.ActiveGui active = this.activeGuis.get(player.getUniqueId());
         if (active != null) {
            if (!active.inventory().equals(event.getView().getTopInventory())) {
               this.activeGuis.remove(player.getUniqueId());
            } else {
               event.setCancelled(true);
               event.setResult(Result.DENY);
               player.updateInventory();
            }
         }
      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR
   )
   public void onClose(InventoryCloseEvent event) {
      if (event.getPlayer() instanceof Player player) {
         this.feedbackService.onInventoryClosed(player, event.getView().title());
         GuiManager.ActiveAnvilPrompt prompt = this.activeAnvilPrompts.get(player.getUniqueId());
         if (prompt != null) {
            if (prompt.inventory().equals(event.getView().getTopInventory())) {
               this.activeAnvilPrompts.remove(player.getUniqueId());
               this.clearPromptInventory(prompt.inventory());
               this.reopenPromptParentNextTick(player, prompt);
            }
         } else {
            GuiManager.ActiveGui active = this.activeGuis.get(player.getUniqueId());
            if (active != null) {
               if (active.inventory().equals(event.getView().getTopInventory())) {
                  this.activeGuis.remove(player.getUniqueId());
                  this.notifyClose(active, player, GuiCloseReason.PLAYER);
               }
            }
         }
      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR
   )
   public void onQuit(PlayerQuitEvent event) {
      this.feedbackService.clear(event.getPlayer());
      GuiManager.ActiveAnvilPrompt prompt = this.activeAnvilPrompts.remove(event.getPlayer().getUniqueId());
      if (prompt != null) {
         this.clearPromptInventory(prompt.inventory());
      }

      GuiManager.ActiveGui active = this.activeGuis.remove(event.getPlayer().getUniqueId());
      if (active != null) {
         this.notifyClose(active, event.getPlayer(), GuiCloseReason.PLAYER_QUIT);
      }
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onInventoryOpenMonitor(InventoryOpenEvent event) {
      if (event.getPlayer() instanceof Player player) {
         this.feedbackService.sync(player);
      }
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onInventoryClickMonitor(InventoryClickEvent event) {
      if (event.getWhoClicked() instanceof Player player) {
         this.feedbackService.sync(player);
      }
   }

   private void handlePromptClick(Player viewer, GuiManager.ActiveAnvilPrompt prompt, InventoryClickEvent event) {
      event.setCancelled(true);
      event.setResult(Result.DENY);
      int rawSlot = event.getRawSlot();
      int topSize = event.getView().getTopInventory().getSize();
      if (rawSlot >= 0 && rawSlot < topSize) {
         if (rawSlot == 2 && event.getView() instanceof AnvilView anvilView) {
            String var10 = this.currentPromptText(anvilView);
            this.activeAnvilPrompts.remove(viewer.getUniqueId());
            this.clearPromptInventory(prompt.inventory());
            viewer.closeInventory();

            try {
               prompt.prompt().submit(viewer, var10);
            } catch (RuntimeException var9) {
               this.plugin.getLogger().log(Level.SEVERE, "GUI anvil prompt submit failed for " + viewer.getName(), (Throwable)var9);
               NetworkSoundCue.UI_BUTTON_FAILURE.play(viewer);
               viewer.sendMessage(MiniMessage.miniMessage().deserialize("<red>That search failed. Please try again.</red>"));
            }
         } else {
            viewer.updateInventory();
         }
      } else {
         viewer.updateInventory();
      }
   }

   private void updatePromptResult(GuiManager.ActiveAnvilPrompt prompt, AnvilView anvilView, PrepareAnvilEvent event) {
      ItemStack result = prompt.prompt().buildResultItem(this.currentPromptText(anvilView));
      if (event == null) {
         anvilView.getTopInventory().setResult(result);
      } else {
         event.setResult(result);
      }

      anvilView.setRepairCost(0);
      anvilView.setRepairItemCountCost(0);
      anvilView.setMaximumRepairCost(0);
   }

   private String currentPromptText(AnvilView anvilView) {
      String text = anvilView.getRenameText();
      return text == null ? "" : text;
   }

   private void clearPromptInventory(AnvilInventory inventory) {
      if (inventory != null) {
         inventory.setFirstItem(null);
         inventory.setSecondItem(null);
         inventory.setResult(null);
      }
   }

   private boolean reopenPromptParent(Player viewer, GuiManager.ActiveAnvilPrompt prompt) {
      if (prompt.history().isEmpty()) {
         return false;
      } else {
         GuiMenu previous = prompt.history().pop();
         this.open(viewer, previous, prompt.history(), false);
         return true;
      }
   }

   private boolean reopenPromptParentNextTick(Player viewer, GuiManager.ActiveAnvilPrompt prompt) {
      if (prompt.history().isEmpty()) {
         return false;
      } else {
         Deque<GuiMenu> history = new ArrayDeque<>(prompt.history());
         GuiMenu previous = history.pop();
         this.scheduler.runOnPlayer(viewer, () -> {
            if (viewer.isOnline()) {
               this.open(viewer, previous, history, false);
            }
         });
         return true;
      }
   }

   private void notifyClose(GuiManager.ActiveGui active, Player viewer, GuiCloseReason reason) {
      GuiCloseContext context = new GuiCloseContext(this, viewer, reason);
      active.menu().onPreClose(context);
      active.menu().onClose(viewer);
      active.menu().onPostClose(context);
   }

   private static record ActiveAnvilPrompt(GuiAnvilPrompt prompt, AnvilInventory inventory, Deque<GuiMenu> history) {
      private ActiveAnvilPrompt(GuiAnvilPrompt prompt, AnvilInventory inventory, Deque<GuiMenu> history) {
         Deque<GuiMenu> var4 = new ArrayDeque<>(history);
         this.prompt = prompt;
         this.inventory = inventory;
         this.history = var4;
      }
   }

   private static final class ActiveGui {
      private final GuiMenu menu;
      private final Inventory inventory;
      private final Deque<GuiMenu> history;
      private final Set<Integer> lockedSlots = ConcurrentHashMap.newKeySet();
      private volatile long lastInteractionMillis;
      private int animationFrame;
      private long animationTicks;

      private ActiveGui(GuiMenu menu, Inventory inventory, Deque<GuiMenu> history) {
         this.menu = menu;
         this.inventory = inventory;
         this.history = new ArrayDeque<>(history);
      }

      private GuiMenu menu() {
         return this.menu;
      }

      private Inventory inventory() {
         return this.inventory;
      }

      private Deque<GuiMenu> history() {
         return this.history;
      }

      private int animationFrame() {
         return this.animationFrame;
      }

      private long animationTicks() {
         return this.animationTicks;
      }

      private void resetAnimationTicks() {
         this.animationTicks = 0L;
      }

      private void incrementAnimationTicks() {
         this.animationTicks++;
      }

      private void advanceAnimationFrame(int frameCount) {
         this.animationFrame = Math.floorMod(this.animationFrame + 1, Math.max(1, frameCount));
      }

      private boolean tryAcquire(int slot, long debounceMillis) {
         long now = System.currentTimeMillis();
         if (now - this.lastInteractionMillis < debounceMillis) {
            return false;
         } else if (!this.lockedSlots.add(slot)) {
            return false;
         } else {
            this.lastInteractionMillis = now;
            return true;
         }
      }

      private void release(int slot) {
         this.lockedSlots.remove(slot);
      }
   }
}
