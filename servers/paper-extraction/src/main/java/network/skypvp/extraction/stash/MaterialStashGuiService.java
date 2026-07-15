package network.skypvp.extraction.stash;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.extraction.crafting.CraftingMaterialService;
import network.skypvp.extraction.crafting.MaterialStashConstants;
import network.skypvp.extraction.crafting.MaterialStashHelper;
import network.skypvp.extraction.gui.HubEconomyService;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gui.GuiButtonLibrary;
import network.skypvp.paper.gui.GuiClickContext;
import network.skypvp.paper.gui.GuiClickInventory;
import network.skypvp.paper.gui.GuiManager;
import network.skypvp.paper.gui.GuiMenu;
import network.skypvp.paper.gui.GuiMenuBuilder;
import network.skypvp.shared.currency.CurrencyFormat;
import network.skypvp.paper.gui.GuiItems;
import network.skypvp.paper.item.api.CustomItemService;
import network.skypvp.paper.library.NetworkSoundCue;
import network.skypvp.paper.repository.ExtractionInventoryRepository;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Opens, upgrades, and persists the physical material stash GUI. */
public final class MaterialStashGuiService {

    private final PaperCorePlugin core;
    private final CraftingMaterialService materials;
    private final MaterialStashTierConfigService tiers;
    private final HubEconomyService economy;
    private final CustomItemService itemService;
    private final ExtractionInventoryRepository repository;
    private final GuiManager guiManager;
    private final Map<UUID, StashSession> pendingSessions = new ConcurrentHashMap<>();

    public MaterialStashGuiService(
            PaperCorePlugin core,
            CraftingMaterialService materials,
            MaterialStashTierConfigService tiers,
            HubEconomyService economy
    ) {
        this.core = Objects.requireNonNull(core, "core");
        this.materials = Objects.requireNonNull(materials, "materials");
        this.tiers = Objects.requireNonNull(tiers, "tiers");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.itemService = core.customItemService();
        this.repository = core.extractionInventoryRepository();
        this.guiManager = core.guiManager();
    }

    public static void open(
            PaperCorePlugin core,
            CraftingMaterialService materials,
            MaterialStashTierConfigService tiers,
            HubEconomyService economy,
            Player player,
            Runnable onBack
    ) {
        if (core == null || materials == null || tiers == null || economy == null || player == null) {
            return;
        }
        new MaterialStashGuiService(core, materials, tiers, economy).open(player, onBack);
    }

    public void open(Player player, Runnable onBack) {
        UUID playerId = player.getUniqueId();
        player.sendActionBar(Component.text("Opening material stash...", NamedTextColor.GRAY));
        CompletableFuture<Map<Integer, ItemStack>> slotsFuture = materials.loadSlotsAsync(playerId);
        CompletableFuture<Integer> tierFuture = repository == null
                ? CompletableFuture.completedFuture(tiers.defaultTier())
                : repository.loadMaterialStashTier(playerId);
        CompletableFuture.allOf(slotsFuture, tierFuture).thenAcceptAsync(ignored ->
                core.platformScheduler().runOnPlayer(player, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    player.sendActionBar(Component.empty());
                    MaterialStashHolder holder = new MaterialStashHolder(playerId, onBack);
                    holder.applyTier(tiers, tierFuture.join());
                    holder.replaceAll(slotsFuture.join());
                    MaterialStashMenu menu = new MaterialStashMenu(
                            holder,
                            this,
                            itemService,
                            core.coreHotbarService()
                    );
                    guiManager.open(player, menu);
                    NetworkSoundCue.UI_BUTTON_CLICK.play(player);
                }), runnable -> core.platformScheduler().runAsync(runnable)).exceptionally(error -> {
            core.platformScheduler().runOnPlayer(player, () -> {
                if (player.isOnline()) {
                    player.sendActionBar(Component.empty());
                    NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
                    player.sendMessage(Component.text("Could not open your material stash.", NamedTextColor.RED));
                }
            });
            return null;
        });
    }

    public void handleClose(Inventory inventory) {
        // Legacy no-op — GuiManager routes close through MaterialStashMenu.onClose().
    }

    public void handleBack(Player player, MaterialStashHolder holder) {
        if (player == null || holder == null) {
            return;
        }
        persistHolder(holder);
        player.closeInventory();
        if (holder.onBack() != null) {
            core.platformScheduler().runOnPlayerLater(player, () -> {
                if (player.isOnline()) {
                    holder.onBack().run();
                }
            }, 1L);
        }
    }

    public void promptUpgrade(Player player, MaterialStashHolder holder) {
        if (player == null || holder == null || holder.getInventory() == null) {
            return;
        }
        MaterialStashTierDefinition next = holder.nextTier();
        if (next == null) {
            player.sendMessage(MiniMessage.miniMessage().deserialize("<red>Your material stash is fully upgraded.</red>"));
            return;
        }
        persistHolder(holder);
        pendingSessions.put(player.getUniqueId(), new StashSession(holder.copyForSession()));
        player.closeInventory();
        core.platformScheduler().runOnPlayerLater(player, () -> {
            if (!player.isOnline() || !pendingSessions.containsKey(player.getUniqueId()) || guiManager == null) {
                return;
            }
            guiManager.open(player, wrapUpgradeConfirmMenu(buildUpgradeConfirmMenu(holder, next)));
            NetworkSoundCue.UI_BUTTON_CLICK.play(player);
        }, 1L);
    }

    public int depositShiftClickedStack(Player player, MaterialStashHolder holder, ItemStack stack) {
        if (player == null || holder == null || stack == null || stack.getType().isAir()) {
            return 0;
        }
        if (!MaterialStashHelper.isCraftingMaterial(itemService, stack)) {
            player.sendMessage(Component.text("Only crafting materials can be stored here.", NamedTextColor.RED));
            return 0;
        }
        int allowed = MaterialStashAccess.depositableAmount(holder.usedCapacity(), holder.maxCapacity(), stack.getAmount());
        if (allowed <= 0) {
            player.sendMessage(Component.text("Your material stash is at capacity.", NamedTextColor.RED));
            return 0;
        }
        int mergeIndex = holder.findMergeIndex(itemService, stack);
        int deposited;
        if (mergeIndex >= 0) {
            ItemStack existing = holder.get(mergeIndex);
            if (existing == null || existing.getType().isAir()) {
                mergeIndex = -1;
            }
        }
        if (mergeIndex >= 0) {
            ItemStack existing = holder.get(mergeIndex);
            int current = MaterialStashStackAmount.read(existing);
            int spaceInStack = Math.max(0, MaterialStashConstants.MAX_STACK_SIZE - current);
            deposited = Math.min(allowed, spaceInStack);
            if (deposited <= 0) {
                player.sendMessage(Component.text("That material stack is full.", NamedTextColor.YELLOW));
                return 0;
            }
            holder.put(mergeIndex, MaterialStashStackAmount.withAmount(existing, current + deposited));
        } else {
            int empty = holder.findEmptyIndex();
            if (empty < 0) {
                player.sendMessage(Component.text("Your material stash has no open slots.", NamedTextColor.RED));
                return 0;
            }
            deposited = Math.min(allowed, stack.getAmount());
            holder.put(empty, MaterialStashStackAmount.withAmount(stack, deposited));
        }
        if (deposited < stack.getAmount()) {
            player.sendMessage(Component.text("Stash capacity limited deposit to " + deposited + " items.", NamedTextColor.YELLOW));
        }
        persistHolder(holder);
        return deposited;
    }

    public int depositToContentIndex(MaterialStashHolder holder, int contentIndex, ItemStack stack) {
        if (holder == null || stack == null || stack.getType().isAir() || contentIndex < 0) {
            return 0;
        }
        if (!holder.isSlotUnlocked(contentIndex)) {
            return 0;
        }
        if (!MaterialStashHelper.isCraftingMaterial(itemService, stack)) {
            return 0;
        }
        int deposited = MaterialStashWithdrawHelper.depositToSlot(
                itemService, holder, contentIndex, stack, holder.maxCapacity());
        if (deposited > 0) {
            persistHolder(holder);
        }
        return deposited;
    }

    /**
     * Deposits every remaining stack of the same crafting material from the player inventory.
     * Used after vanilla shift-double-click bursts (multiple quick shift transfers in one tick).
     */
    public int depositAllMatchingFromInventory(Player player, MaterialStashHolder holder, ItemStack reference) {
        if (player == null || holder == null || reference == null || reference.getType().isAir()) {
            return 0;
        }
        if (!MaterialStashHelper.isCraftingMaterial(itemService, reference)) {
            return 0;
        }
        int total = 0;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            if (core.coreHotbarService() != null && core.coreHotbarService().isServerItem(stack)) {
                continue;
            }
            if (!MaterialStashHelper.isCraftingMaterial(itemService, stack)) {
                continue;
            }
            if (!MaterialStashHelper.sameMaterial(itemService, stack, reference)) {
                continue;
            }
            int deposited = depositShiftClickedStack(player, holder, stack);
            if (deposited <= 0) {
                break;
            }
            int removed = GuiClickInventory.consumePlayerSlot(player, slot, deposited);
            if (removed < deposited) {
                revertShiftDeposit(holder, stack, deposited - removed);
            }
            total += removed;
            if (MaterialStashAccess.depositableAmount(holder.usedCapacity(), holder.maxCapacity(), 1) <= 0) {
                break;
            }
        }
        return total;
    }

    /** Removes items credited to the stash when the player inventory could not be debited (anti-dupe). */
    public void revertShiftDeposit(MaterialStashHolder holder, ItemStack referenceStack, int amount) {
        if (holder == null || referenceStack == null || amount <= 0) {
            return;
        }
        int remaining = amount;
        for (int index = 0; index < MaterialStashConstants.MAX_SLOTS && remaining > 0; index++) {
            ItemStack stored = holder.get(index);
            if (stored == null || !MaterialStashHelper.sameMaterial(itemService, stored, referenceStack)) {
                continue;
            }
            int take = Math.min(remaining, MaterialStashStackAmount.read(stored));
            remaining -= take;
            int left = MaterialStashStackAmount.read(stored) - take;
            if (left <= 0) {
                holder.remove(index);
            } else {
                holder.put(index, MaterialStashStackAmount.withAmount(stored, left));
            }
        }
        persistHolder(holder);
    }

    public void scheduleInventoryResync(Player player) {
        if (player == null) {
            return;
        }
        core.platformScheduler().runOnPlayerLater(player, player::updateInventory, 1L);
    }

    /** Writes holder state to the material stash cache and database. */
    public void persistHolder(MaterialStashHolder holder) {
        if (holder == null || pendingSessions.containsKey(holder.playerId())) {
            return;
        }
        materials.saveSlots(holder.playerId(), holder.snapshot());
    }

    public boolean acceptsDeposit(ItemStack stack) {
        return MaterialStashHelper.isCraftingMaterial(itemService, stack);
    }

    public boolean isLockedContentSlot(MaterialStashHolder holder, int rawSlot) {
        int index = MaterialStashLayout.contentIndex(rawSlot);
        return index >= 0 && !holder.isSlotUnlocked(index);
    }

    private GuiMenu wrapUpgradeConfirmMenu(GuiMenu inner) {
        return new GuiMenu() {
            @Override
            public Component title() {
                return inner.title();
            }

            @Override
            public int size() {
                return inner.size();
            }

            @Override
            public void render(Player viewer, Inventory inventory) {
                inner.render(viewer, inventory);
            }

            @Override
            public void onClick(GuiClickContext context) {
                inner.onClick(context);
            }

            @Override
            public void onClose(Player viewer) {
                if (pendingSessions.containsKey(viewer.getUniqueId())) {
                    cancelUpgrade(viewer);
                }
                inner.onClose(viewer);
            }
        };
    }

    private GuiMenu buildUpgradeConfirmMenu(MaterialStashHolder holder, MaterialStashTierDefinition next) {
        Component title = MiniMessage.miniMessage().deserialize("<gold>Upgrade Material Stash?</gold>");
        return GuiMenuBuilder.create(title, 27)
                .fill(GuiItems.named(Material.GRAY_STAINED_GLASS_PANE, " ", java.util.List.of()))
                .button(11, GuiButtonLibrary.positiveAction(Material.LIME_WOOL, "Confirm Upgrade", lore -> lore
                        .fact("Tier", next.name())
                        .fact("Capacity", CurrencyFormat.formatCompact(next.maxCapacity()))
                        .fact("Slots", String.valueOf(next.maxSlots()))
                        .fact("Cost", MaterialStashLayout.priceLine(next.upgradeCoins(), next.upgradeGold()))
                        .footerStrong("<#55FF55>", "Click to confirm")), ctx -> confirmUpgrade(ctx.viewer()))
                .button(13, GuiItems.named(Material.BUNDLE, "<gold>" + next.name(), java.util.List.of(
                        "<gray>+" + (next.maxSlots() - holder.unlockedSlots()) + " stash slots",
                        "<gray>" + CurrencyFormat.formatCompact(next.maxCapacity()) + " total capacity",
                        "",
                        MaterialStashLayout.priceLine(next.upgradeCoins(), next.upgradeGold()),
                        "<dark_gray>Gold can be purchased in the web store."
                )), ctx -> { })
                .button(15, GuiButtonLibrary.warningAction(Material.RED_WOOL, "Cancel", lore -> lore
                        .plain("Return to your stash without upgrading")
                        .footer("<#FF5555>", "Click to cancel")), ctx -> cancelUpgrade(ctx.viewer()))
                .build();
    }

    private void confirmUpgrade(Player player) {
        if (player == null) {
            return;
        }
        StashSession session = pendingSessions.remove(player.getUniqueId());
        if (session == null) {
            if (guiManager != null) {
                guiManager.close(player);
            }
            return;
        }
        MaterialStashHolder holder = session.holder();
        MaterialStashTierDefinition next = holder.nextTier();
        if (next == null || repository == null) {
            reopenStash(player, session);
            return;
        }
        int expectedTier = holder.tier();
        long coinCost = next.upgradeCoins();
        long goldCost = next.upgradeGold();
        if (guiManager != null) {
            guiManager.close(player);
        }
        player.sendActionBar(Component.text("Processing upgrade...", NamedTextColor.GRAY));
        economy.trySpendCoins(player, coinCost)
                .thenCompose(coinsSpent -> {
                    if (!coinsSpent) {
                        return CompletableFuture.completedFuture(false);
                    }
                    return economy.trySpendGold(player, goldCost);
                })
                .thenCompose(goldSpent -> {
                    if (!goldSpent) {
                        economy.refundCoins(player.getUniqueId(), coinCost);
                        return CompletableFuture.completedFuture(false);
                    }
                    return repository.incrementMaterialStashTier(player.getUniqueId(), expectedTier, tiers.maxTier());
                })
                .thenAcceptAsync(success -> core.platformScheduler().runOnPlayer(player, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    player.sendActionBar(Component.empty());
                    if (!success) {
                        economy.refundCoins(player.getUniqueId(), coinCost);
                        economy.refundGold(player.getUniqueId(), goldCost);
                        NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
                        player.sendMessage(MiniMessage.miniMessage().deserialize(
                                "<red>Could not upgrade your stash. Check your "
                                        + MaterialStashLayout.priceLine(coinCost, goldCost)
                                        + " <red>balance and try again.</red>"
                        ));
                        reopenStash(player, session);
                        return;
                    }
                    materials.evictPlayer(player.getUniqueId());
                    NetworkSoundCue.UI_BUTTON_SUCCESS.play(player);
                    player.sendMessage(MiniMessage.miniMessage().deserialize(
                            "<green>Upgraded material stash to <white>" + next.name() + "<green>!</green>"
                    ));
                    open(player, holder.onBack());
                }), runnable -> core.platformScheduler().runAsync(runnable));
    }

    private void cancelUpgrade(Player player) {
        if (player == null) {
            return;
        }
        StashSession session = pendingSessions.remove(player.getUniqueId());
        if (guiManager != null) {
            guiManager.close(player);
        }
        if (session != null) {
            reopenStash(player, session);
        }
    }

    private void reopenStash(Player player, StashSession session) {
        core.platformScheduler().runOnPlayerLater(player, () -> open(player, session.holder().onBack()), 1L);
    }

    private record StashSession(MaterialStashHolder holder) {
    }
}
