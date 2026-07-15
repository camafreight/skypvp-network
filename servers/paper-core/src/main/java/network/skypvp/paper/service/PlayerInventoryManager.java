package network.skypvp.paper.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gui.GuiFeedback;
import network.skypvp.paper.gui.GuiButtonLibrary;
import network.skypvp.paper.gui.GuiClickContext;
import network.skypvp.paper.gui.GuiManager;
import network.skypvp.paper.gui.GuiMenu;
import network.skypvp.paper.gui.GuiMenuBuilder;
import network.skypvp.paper.inventory.loadout.LoadoutApplyResult;
import network.skypvp.paper.inventory.vault.VaultGuiService;
import network.skypvp.paper.inventory.vault.VaultLayout;
import network.skypvp.paper.inventory.vault.VaultSlotAccess;
import network.skypvp.paper.library.ItemStackCodec;
import network.skypvp.paper.library.NetworkSoundCue;
import network.skypvp.paper.repository.ExtractionInventoryRepository;
import network.skypvp.shared.NetworkServerRole;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

public final class PlayerInventoryManager {
   public static final String CONTAINER_VAULT = "VAULT";
   public static final String CONTAINER_RAID = "RAID";
   public static final int MAX_LOADOUTS = 10;
   public static final String LOADOUT_PERMISSION_PREFIX = "skypvp.loadout.";
   private static final String LOADOUTS_MENU_TITLE = "Loadouts";
   private static final int[] LOADOUT_MENU_SLOTS = {19, 20, 21, 22, 23, 24, 25, 30, 31, 32};

   private final PaperCorePlugin plugin;
   private final ExtractionInventoryRepository repository;
   private final CoreHotbarService hotbarService;
   private final GuiManager guiManager;
   private VaultGuiService vaultGuiService;
   private final Map<UUID, Long> pendingRevisions = new ConcurrentHashMap<>();
   private final Map<UUID, Long> pendingRaidRevisions = new ConcurrentHashMap<>();
   private final Map<UUID, Map<Integer, String>> dirtyVaultSlots = new ConcurrentHashMap<>();
   // Seeded from wall-clock millis so revisions stay strictly increasing across pod restarts.
   // A plain new AtomicLong() restarts at 0 and collides with revision rows persisted before the
   // restart (PK player_uuid,revision,container_type), which broke inventory/extract saves.
   private final AtomicLong saveSequence = new AtomicLong(System.currentTimeMillis());

   public PlayerInventoryManager(
      PaperCorePlugin plugin,
      ExtractionInventoryRepository repository,
      CoreHotbarService hotbarService,
      GuiManager guiManager
   ) {
      this.plugin = Objects.requireNonNull(plugin, "plugin");
      this.repository = Objects.requireNonNull(repository, "repository");
      this.hotbarService = Objects.requireNonNull(hotbarService, "hotbarService");
      this.guiManager = Objects.requireNonNull(guiManager, "guiManager");
   }

   public void bindVaultGui(VaultGuiService vaultGuiService) {
      this.vaultGuiService = Objects.requireNonNull(vaultGuiService, "vaultGuiService");
   }

   public static List<String> essentialContainerTypes() {
      List<String> types = new ArrayList<>(MAX_LOADOUTS + 1);
      types.add(CONTAINER_VAULT);
      for (int loadoutIndex = 1; loadoutIndex <= MAX_LOADOUTS; loadoutIndex++) {
         types.add("LOADOUT_" + loadoutIndex);
      }
      return List.copyOf(types);
   }

   public void preloadEssentialsOnJoin(UUID playerId) {
      if (playerId == null) {
         return;
      }
      this.repository.preloadContainers(playerId, this.preloadContainerTypes());
      // Warms the rows cache so the first vault open is fully synchronous (no DB hop).
      this.repository.loadVaultUnlockedRows(playerId);
   }

   public void prepareJoinedPlayerInventory(Player player) {
      if (player == null) {
         return;
      }
      UUID playerId = player.getUniqueId();
      // Warms the rows cache so the first vault open is fully synchronous (no DB hop).
      this.repository.loadVaultUnlockedRows(playerId);
      this.repository.preloadContainers(playerId, this.preloadContainerTypes()).thenAcceptAsync(ignored -> {
         if (this.plugin.serverRole() != NetworkServerRole.EXTRACTION) {
            return;
         }
         this.plugin.platformScheduler().runOnPlayer(player, () -> {
            if (player.isOnline()) {
               this.restoreCurrentInventory(player);
            }
         });
      }, runnable -> this.plugin.platformScheduler().runAsync(runnable));
   }

   public void restoreCurrentInventory(Player player) {
      if (player == null) {
         return;
      }
      Map<Integer, String> slots = this.repository.getCachedContainer(player.getUniqueId(), CONTAINER_RAID).orElse(Map.of());
      player.getInventory().clear();
      player.getInventory().setArmorContents(null);
      if (!slots.isEmpty()) {
         this.applyRaidToPlayer(player, slots);
      }
      this.hotbarService.ensureNetworkItems(player);
   }

   public void scheduleRaidInventorySave(Player player) {
      if (player == null) {
         return;
      }
      long revision = this.saveSequence.incrementAndGet();
      this.pendingRaidRevisions.put(player.getUniqueId(), revision);
      this.plugin.platformScheduler().runGlobalLater(() -> this.flushRaidInventorySave(player.getUniqueId(), revision), 20L);
   }

   public void flushRaidInventorySave(Player player) {
      if (player == null) {
         return;
      }
      this.pendingRaidRevisions.remove(player.getUniqueId());
      this.saveRaidInventory(player);
   }

   public void cancelPendingRaidSaves(UUID playerId) {
      if (playerId == null) {
         return;
      }
      this.pendingRaidRevisions.remove(playerId);
   }

   public void persistCurrentInventoryOnQuit(Player player) {
      if (player == null || this.plugin.serverRole() != NetworkServerRole.EXTRACTION) {
         return;
      }
      this.flushRaidInventorySave(player);
   }

   private List<String> preloadContainerTypes() {
      if (this.plugin.serverRole() == NetworkServerRole.EXTRACTION) {
         List<String> types = new ArrayList<>(MAX_LOADOUTS + 2);
         types.add(CONTAINER_VAULT);
         types.add(CONTAINER_RAID);
         for (int loadoutIndex = 1; loadoutIndex <= MAX_LOADOUTS; loadoutIndex++) {
            types.add("LOADOUT_" + loadoutIndex);
         }
         return List.copyOf(types);
      }
      return essentialContainerTypes();
   }

   public CompletableFuture<Void> loadVault(Player player) {
      return this.repository.loadContainer(player.getUniqueId(), CONTAINER_VAULT).thenAcceptAsync(slots -> {
         this.plugin.platformScheduler().runOnPlayer(player, () -> this.applyVaultToPlayer(player, slots));
      }, runnable -> this.plugin.platformScheduler().runAsync(runnable));
   }

   public void scheduleVaultSave(Player player) {
      if (player == null) {
         return;
      }
      Map<Integer, String> snapshot = this.captureVault(player);
      this.dirtyVaultSlots.put(player.getUniqueId(), snapshot);
      long revision = this.saveSequence.incrementAndGet();
      this.pendingRevisions.put(player.getUniqueId(), revision);
      this.plugin.platformScheduler().runGlobalLater(() -> this.flushVaultSave(player.getUniqueId(), revision), 20L);
   }

   public void openVaultMenu(Player player) {
      this.openVaultMenu(player, false);
   }

   public void openVaultMenu(Player player, boolean returnToNetworkMenu) {
      if (this.vaultGuiService == null) {
         player.sendMessage("§cVault services are currently unavailable.");
         return;
      }
      this.vaultGuiService.open(player, returnToNetworkMenu);
   }

   public void openLoadoutMenu(GuiClickContext context) {
      context.open(this.buildLoadoutMenu(context.viewer()));
   }

   public void openLoadoutMenu(Player player) {
      this.guiManager.open(player, this.buildLoadoutMenu(player));
   }

   public CompletableFuture<Void> saveLoadout(Player player, int loadoutIndex) {
      if (player == null || !this.isValidLoadoutIndex(loadoutIndex) || !this.hasLoadoutPermission(player, loadoutIndex)) {
         return CompletableFuture.completedFuture(null);
      }
      Map<Integer, String> snapshot = this.captureLoadoutInventory(player);
      long revision = this.saveSequence.incrementAndGet();
      String checksum = this.checksum(snapshot);
      return this.repository.saveContainerBulk(
         player.getUniqueId(),
         this.loadoutContainer(loadoutIndex),
         snapshot,
         revision,
         checksum
      );
   }

   public CompletableFuture<LoadoutApplyResult> loadLoadout(Player player, int loadoutIndex) {
      if (player == null || !this.isValidLoadoutIndex(loadoutIndex) || !this.hasLoadoutPermission(player, loadoutIndex)) {
         return CompletableFuture.completedFuture(LoadoutApplyResult.failure("You cannot use this loadout."));
      }
      UUID playerId = player.getUniqueId();
      String loadoutType = this.loadoutContainer(loadoutIndex);
      return this.repository.loadContainer(playerId, loadoutType).thenCompose(loadoutSlots ->
         this.repository.loadContainer(playerId, CONTAINER_VAULT).thenCompose(vaultSlots -> {
            CompletableFuture<LoadoutApplyResult> resultFuture = new CompletableFuture<>();
            this.plugin.platformScheduler().runOnPlayer(player, () -> {
               if (!player.isOnline()) {
                  resultFuture.complete(LoadoutApplyResult.failure("You went offline."));
                  return;
               }
               try {
                  Map<Integer, String> vault = vaultSlots == null ? Map.of() : new HashMap<>(vaultSlots);
                  LoadoutApplyResult.Transaction transaction = LoadoutApplyResult.plan(
                     loadoutSlots == null ? Map.of() : loadoutSlots,
                     vault,
                     this.collectLoadoutSources(player)
                  );
                  this.clearLoadoutInventory(player);
                  this.applyLoadoutItems(player, transaction.appliedItems());
                  this.hotbarService.ensureNetworkItems(player);

                  long revision = this.saveSequence.incrementAndGet();
                  String checksum = this.checksum(transaction.updatedVault());
                  this.repository.saveContainerBulk(playerId, CONTAINER_VAULT, transaction.updatedVault(), revision, checksum)
                     .thenCompose(ignored -> this.saveRaidInventory(player))
                     .whenComplete((ignored, error) -> {
                        if (error != null) {
                           resultFuture.complete(LoadoutApplyResult.failure("Could not save inventory changes."));
                        } else {
                           resultFuture.complete(LoadoutApplyResult.success(transaction.extraStacksStored()));
                        }
                     });
               } catch (LoadoutApplyResult.LoadoutApplyException exception) {
                  resultFuture.complete(exception.result());
               }
            });
            return resultFuture;
         })
      );
   }

   public boolean hasLoadoutPermission(Player player, int loadoutIndex) {
      return player != null && player.hasPermission(LOADOUT_PERMISSION_PREFIX + loadoutIndex);
   }

   private GuiMenu buildLoadoutMenu(Player player) {
      GuiMenuBuilder menu = GuiMenuBuilder.create(Component.text("Loadouts"), 54);
      menu.button(0, GuiButtonLibrary.close("Close loadouts"), GuiClickContext::close);
      menu.button(8, GuiButtonLibrary.back("Return to previous menu"), context -> context.back());
      for (int index = 0; index < MAX_LOADOUTS; index++) {
         int loadoutIndex = index + 1;
         int slot = LOADOUT_MENU_SLOTS[index];
         if (this.hasLoadoutPermission(player, loadoutIndex)) {
            menu.button(
               slot,
               GuiButtonLibrary.primaryAction(
                  Material.NETHERITE_HELMET,
                  "Loadout " + loadoutIndex,
                  lore -> lore.plain("Save or load this kit")
               ),
               context -> this.openLoadoutActionsMenu(context, player, loadoutIndex)
            );
         } else {
            menu.button(
               slot,
               GuiButtonLibrary.warningAction(
                  Material.IRON_DOOR,
                  "Loadout " + loadoutIndex,
                  lore -> lore.footerStrong("<red>", "LOCKED").plain("Requires " + LOADOUT_PERMISSION_PREFIX + loadoutIndex)
               ),
               context -> {
                  context.playSound(NetworkSoundCue.UI_BUTTON_FAILURE);
                  context.viewer().sendMessage("§cThis loadout is locked.");
               }
            );
         }
      }
      return menu.build();
   }

   private void openLoadoutActionsMenu(GuiClickContext context, Player player, int loadoutIndex) {
      context.playSound(NetworkSoundCue.UI_BUTTON_CLICK);
      String containerType = this.loadoutContainer(loadoutIndex);
      Optional<Map<Integer, String>> cached = this.repository.getCachedContainer(player.getUniqueId(), containerType);
      if (cached.isPresent()) {
         context.open(this.buildLoadoutActionsMenu(player, loadoutIndex, cached.get().size()));
         return;
      }
      player.sendActionBar(Component.text("Loading loadout...", NamedTextColor.GRAY));
      this.repository.loadContainer(player.getUniqueId(), containerType).thenAcceptAsync(slots -> {
         int itemCount = slots == null ? 0 : slots.size();
         this.plugin.platformScheduler().runOnPlayer(player, () -> {
            if (player.isOnline()) {
               player.sendActionBar(Component.empty());
               context.open(this.buildLoadoutActionsMenu(player, loadoutIndex, itemCount));
            }
         });
      }, runnable -> this.plugin.platformScheduler().runAsync(runnable)).exceptionally(error -> {
         this.plugin.platformScheduler().runOnPlayer(player, () -> {
            if (player.isOnline()) {
               player.sendActionBar(Component.empty());
               NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
               player.sendMessage("§cCould not open loadout " + loadoutIndex + ". Try again.");
            }
         });
         return null;
      });
   }

   private GuiMenu buildLoadoutActionsMenu(Player player, int loadoutIndex, int savedItemCount) {
      GuiMenuBuilder menu = GuiMenuBuilder.create(Component.text("Loadout " + loadoutIndex), 27);
      menu.button(0, GuiButtonLibrary.close("Close loadout menu"), GuiClickContext::close);
      menu.button(8, GuiButtonLibrary.back("Return to loadouts"), GuiClickContext::back);
      menu.button(
         4,
         GuiButtonLibrary.infoCard(
            Material.CHEST,
            "Saved gear",
            lore -> lore.fact("Stored items", String.valueOf(savedItemCount)).plain("Load replaces your current raid inventory")
         ),
         context -> {
         }
      );
      menu.button(
         11,
         GuiButtonLibrary.positiveAction(Material.LIME_WOOL, "Load", lore -> lore.plain("Apply this loadout to your inventory")),
         context -> {
            context.playSound(NetworkSoundCue.UI_BUTTON_CLICK);
            this.loadLoadout(context.viewer(), loadoutIndex).whenComplete((result, error) -> {
               this.plugin.platformScheduler().runOnPlayer(context.viewer(), () -> {
                  if (!context.viewer().isOnline()) {
                     return;
                  }
                  LoadoutApplyResult outcome = error != null
                     ? LoadoutApplyResult.failure("Could not load loadout " + loadoutIndex + ".")
                     : result;
                  this.presentLoadoutFeedback(context.viewer(), loadoutIndex, outcome);
                  if (outcome.success()) {
                     NetworkSoundCue.UI_BUTTON_SUCCESS.play(context.viewer());
                  } else {
                     NetworkSoundCue.UI_BUTTON_FAILURE.play(context.viewer());
                  }
               });
            });
         }
      );
      menu.button(
         15,
         GuiButtonLibrary.warningAction(Material.RED_WOOL, "Save", lore -> lore.plain("Overwrite this loadout with your current gear")),
         context -> {
            context.playSound(NetworkSoundCue.UI_BUTTON_CLICK);
            this.saveLoadout(context.viewer(), loadoutIndex).thenAcceptAsync(ignored -> {
               this.plugin.platformScheduler().runOnPlayer(context.viewer(), () -> {
                  if (context.viewer().isOnline()) {
                     NetworkSoundCue.UI_BUTTON_SUCCESS.play(context.viewer());
                     context.viewer().sendMessage("§aSaved loadout " + loadoutIndex + ".");
                  }
               });
            }, runnable -> this.plugin.platformScheduler().runAsync(runnable)).exceptionally(error -> {
               this.plugin.platformScheduler().runOnPlayer(context.viewer(), () -> {
                  if (context.viewer().isOnline()) {
                     NetworkSoundCue.UI_BUTTON_FAILURE.play(context.viewer());
                     context.viewer().sendMessage("§cCould not save loadout " + loadoutIndex + ".");
                  }
               });
               return null;
            });
         }
      );
      return menu.build();
   }

   private Map<Integer, String> captureLoadoutInventory(Player player) {
      return this.captureRaidInventory(player);
   }

   private List<LoadoutApplyResult.InventoryStackRef> collectLoadoutSources(Player player) {
      List<LoadoutApplyResult.InventoryStackRef> sources = new ArrayList<>();
      if (player == null) {
         return sources;
      }
      PlayerInventory inventory = player.getInventory();
      int virtualSlot = 0;
      for (int slot = 0; slot < inventory.getSize(); slot++) {
         if (this.hotbarService.isReservedHotbarSlot(player, slot)) {
            continue;
         }
         ItemStack item = inventory.getItem(slot);
         if (item == null || item.getType().isAir() || this.hotbarService.isServerItem(item)) {
            continue;
         }
         sources.add(new LoadoutApplyResult.InventoryStackRef(virtualSlot++, item.clone()));
      }
      ItemStack[] armor = inventory.getArmorContents();
      for (ItemStack item : armor) {
         if (item == null || item.getType().isAir()) {
            continue;
         }
         sources.add(new LoadoutApplyResult.InventoryStackRef(virtualSlot++, item.clone()));
      }
      ItemStack offhand = inventory.getItemInOffHand();
      if (offhand != null && !offhand.getType().isAir() && !this.hotbarService.isServerItem(offhand)) {
         sources.add(new LoadoutApplyResult.InventoryStackRef(virtualSlot, offhand.clone()));
      }
      return sources;
   }

   private void applyLoadoutItems(Player player, Map<Integer, ItemStack> items) {
      if (player == null || items == null || items.isEmpty()) {
         return;
      }
      ItemStack[] armor = new ItemStack[4];
      for (Map.Entry<Integer, ItemStack> entry : items.entrySet()) {
         ItemStack item = entry.getValue();
         if (item == null || item.getType().isAir()) {
            continue;
         }
         int slot = entry.getKey();
         if (slot >= 100 && slot <= 103) {
            armor[slot - 100] = item.clone();
         } else if (slot == 104) {
            player.getInventory().setItemInOffHand(item.clone());
         } else if (slot >= 0 && slot < player.getInventory().getSize() && !this.hotbarService.isReservedHotbarSlot(player, slot)) {
            player.getInventory().setItem(slot, item.clone());
         }
      }
      player.getInventory().setArmorContents(armor);
   }

   private void presentLoadoutFeedback(Player player, int loadoutIndex, LoadoutApplyResult result) {
      if (player == null || result == null) {
         return;
      }

      String prefix = result.success() ? "§a" : "§c";
      for (String line : result.detailLines()) {
         player.sendMessage(prefix + line);
      }

      ItemStack restoreItem = GuiButtonLibrary.primaryAction(
         Material.NETHERITE_HELMET,
         "Loadout " + loadoutIndex,
         lore -> lore.plain("Save or load this kit")
      );
      GuiFeedback feedback = GuiFeedback.builder()
         .success(result.success())
         .title("Loadout " + loadoutIndex)
         .detailLines(result.detailLines())
         .footerLine("Tap again to open this loadout")
         .build();
      this.guiManager.presentSlotFeedback(
         player,
         LOADOUTS_MENU_TITLE,
         LOADOUT_MENU_SLOTS[loadoutIndex - 1],
         feedback,
         restoreItem
      );

      if (this.isLoadoutGuiOpen(player)) {
         if (this.isLoadoutActionsMenuOpen(player)) {
            this.guiManager.back(player);
         }
         this.guiManager.feedbackService().sync(player);
      }
   }

   private boolean isLoadoutsMenuOpen(Player player) {
      return LOADOUTS_MENU_TITLE.equals(this.readOpenInventoryTitle(player));
   }

   private boolean isLoadoutGuiOpen(Player player) {
      if (player == null || !player.isOnline()) {
         return false;
      }
      return this.readOpenInventoryTitle(player).contains("Loadout");
   }

   private boolean isLoadoutActionsMenuOpen(Player player) {
      String title = this.readOpenInventoryTitle(player);
      return title.startsWith("Loadout ") && !title.equals("Loadouts");
   }

   private String readOpenInventoryTitle(Player player) {
      Component title = player.getOpenInventory().title();
      return PlainTextComponentSerializer.plainText().serialize(title);
   }

   private void clearLoadoutInventory(Player player) {
      PlayerInventory inventory = player.getInventory();
      for (int slot = 0; slot < inventory.getSize(); slot++) {
         if (this.hotbarService.isReservedHotbarSlot(player, slot)) {
            continue;
         }
         inventory.setItem(slot, null);
      }
      inventory.setArmorContents(null);
      ItemStack offhand = inventory.getItemInOffHand();
      if (!this.hotbarService.isServerItem(offhand)) {
         inventory.setItemInOffHand(null);
      }
   }

   private boolean isValidLoadoutIndex(int loadoutIndex) {
      return loadoutIndex >= 1 && loadoutIndex <= MAX_LOADOUTS;
   }

   private String loadoutContainer(int loadoutIndex) {
      return "LOADOUT_" + loadoutIndex;
   }

   public Map<Integer, String> captureRaidInventory(Player player) {
      Map<Integer, String> captured = new HashMap<>();
      PlayerInventory inventory = player.getInventory();
      for (int slot = 0; slot < inventory.getSize(); slot++) {
         ItemStack item = inventory.getItem(slot);
         if (item == null || item.getType().isAir() || this.hotbarService.isServerItem(item)) {
            continue;
         }
         captured.put(slot, ItemStackCodec.encode(item));
      }
      ItemStack[] armor = inventory.getArmorContents();
      for (int i = 0; i < armor.length; i++) {
         ItemStack item = armor[i];
         if (item != null && !item.getType().isAir()) {
            captured.put(100 + i, ItemStackCodec.encode(item));
         }
      }
      ItemStack offhand = inventory.getItemInOffHand();
      if (offhand != null && !offhand.getType().isAir() && !this.hotbarService.isServerItem(offhand)) {
         captured.put(104, ItemStackCodec.encode(offhand));
      }
      return captured;
   }

   public CompletableFuture<Void> saveRaidInventory(Player player) {
      Map<Integer, String> raid = this.captureRaidInventory(player);
      long revision = this.saveSequence.incrementAndGet();
      String checksum = this.checksum(raid);
      return this.repository.saveContainerBulk(player.getUniqueId(), CONTAINER_RAID, raid, revision, checksum);
   }

   public CompletableFuture<Void> prepareForRaid(Player player) {
      return this.prepareForRaid(player, () -> true);
   }

   public CompletableFuture<Void> prepareForRaid(Player player, java.util.function.BooleanSupplier stillValid) {
      if (player == null) {
         return CompletableFuture.completedFuture(null);
      }
      java.util.function.BooleanSupplier guard = stillValid == null ? () -> true : stillValid;
      return this.repository.loadContainer(player.getUniqueId(), CONTAINER_RAID).thenAcceptAsync(slots -> {
         this.plugin.platformScheduler().runOnPlayer(player, () -> {
            if (!guard.getAsBoolean()) {
               return;
            }
            player.getInventory().clear();
            player.getInventory().setArmorContents(null);
            this.applyRaidToPlayer(player, slots);
            this.hotbarService.ensureNetworkItems(player);
         });
      }, runnable -> this.plugin.platformScheduler().runAsync(runnable));
   }

   public CompletableFuture<Void> commitExtract(Player player) {
      if (player == null) {
         return CompletableFuture.completedFuture(null);
      }
      Map<Integer, String> raid = this.captureRaidInventory(player);
      return this.repository.loadContainer(player.getUniqueId(), CONTAINER_VAULT).thenComposeAsync(vault -> {
         Map<Integer, String> merged = this.mergeRaidIntoVault(vault, raid);
         long revision = this.saveSequence.incrementAndGet();
         String checksum = this.checksum(merged);
         return this.repository.saveContainerBulk(player.getUniqueId(), CONTAINER_VAULT, merged, revision, checksum)
            .thenCompose(ignored -> this.clearRaidContainer(player.getUniqueId()));
      }, runnable -> this.plugin.platformScheduler().runAsync(runnable));
   }

   public void clearRaid(Player player) {
      if (player == null) {
         return;
      }
      this.clearRaidContainer(player.getUniqueId());
   }

   /** Offline variant: wipes an escrowed RAID container by id (used when an AFK stand-in's owner is eliminated). */
   public void clearRaid(UUID playerId) {
      if (playerId == null) {
         return;
      }
      this.clearRaidContainer(playerId);
   }

   private CompletableFuture<Void> clearRaidContainer(UUID playerId) {
      long revision = this.saveSequence.incrementAndGet();
      return this.repository.saveContainerBulk(playerId, CONTAINER_RAID, Map.of(), revision, this.checksum(Map.of()));
   }

   private void applyRaidToPlayer(Player player, Map<Integer, String> slots) {
      ItemStack[] armor = new ItemStack[4];
      for (Map.Entry<Integer, String> entry : slots.entrySet()) {
         try {
            ItemStack item = ItemStackCodec.decode(entry.getValue());
            int slot = entry.getKey();
            if (slot >= 100 && slot <= 103) {
               armor[slot - 100] = item;
            } else if (slot == 104) {
               player.getInventory().setItemInOffHand(item);
            } else if (slot >= 0 && slot < player.getInventory().getSize() && !this.hotbarService.isServerItem(item)) {
               player.getInventory().setItem(slot, item);
            }
         } catch (RuntimeException ignored) {
         }
      }
      player.getInventory().setArmorContents(armor);
   }

   private Map<Integer, String> mergeRaidIntoVault(Map<Integer, String> vault, Map<Integer, String> raid) {
      Map<Integer, String> merged = new HashMap<>(vault == null ? Map.of() : vault);
      Set<Integer> reservedHotbar = this.hotbarService.reservedHotbarSlots();
      for (Map.Entry<Integer, String> entry : raid.entrySet()) {
         int raidSlot = entry.getKey();
         if (raidSlot >= 0 && raidSlot < 36 && reservedHotbar.contains(raidSlot)) {
            continue;
         }
         int targetSlot = this.findFirstFreeVaultSlot(merged);
         if (targetSlot < 0) {
            break;
         }
         merged.put(targetSlot, entry.getValue());
      }
      return merged;
   }

   private int findFirstFreeVaultSlot(Map<Integer, String> vault) {
      int highest = vault.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
      int limit = VaultSlotAccess.depositableLimit(highest);
      for (int slot = 0; slot < limit; slot++) {
         if (!vault.containsKey(slot)) {
            return slot;
         }
      }
      if (limit < VaultLayout.MAX_VAULT_SLOTS) {
         return limit;
      }
      return -1;
   }

   private void flushVaultSave(UUID playerId, long revision) {
      Long pending = this.pendingRevisions.get(playerId);
      if (pending == null || pending != revision) {
         return;
      }
      Map<Integer, String> slots = this.dirtyVaultSlots.remove(playerId);
      if (slots == null) {
         return;
      }
      String checksum = this.checksum(slots);
      this.repository.saveContainerBulk(playerId, CONTAINER_VAULT, slots, revision, checksum);
   }

   private void flushRaidInventorySave(UUID playerId, long revision) {
      Long pending = this.pendingRaidRevisions.get(playerId);
      if (pending == null || pending != revision) {
         return;
      }
      this.pendingRaidRevisions.remove(playerId);
      org.bukkit.entity.Player player = this.plugin.getServer().getPlayer(playerId);
      if (player != null && player.isOnline()) {
         this.saveRaidInventory(player);
      }
   }

   private Map<Integer, String> captureVault(Player player) {
      Map<Integer, String> captured = new HashMap<>();
      PlayerInventory inventory = player.getInventory();
      for (int slot = 0; slot < inventory.getSize(); slot++) {
         ItemStack item = inventory.getItem(slot);
         if (item == null || item.getType().isAir() || this.hotbarService.isServerItem(item)) {
            continue;
         }
         captured.put(slot, ItemStackCodec.encode(item));
      }
      return captured;
   }

   private void applyVaultToPlayer(Player player, Map<Integer, String> slots) {
      for (Map.Entry<Integer, String> entry : slots.entrySet()) {
         try {
            player.getInventory().setItem(entry.getKey(), ItemStackCodec.decode(entry.getValue()));
         } catch (RuntimeException ignored) {
         }
      }
   }

   private String checksum(Map<Integer, String> slots) {
      try {
         MessageDigest digest = MessageDigest.getInstance("SHA-256");
         slots.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            digest.update((entry.getKey() + ":" + entry.getValue()).getBytes(StandardCharsets.UTF_8));
         });
         return HexFormat.of().formatHex(digest.digest());
      } catch (Exception ex) {
         return "";
      }
   }
}
